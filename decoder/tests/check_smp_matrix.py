"""Read-only final audit of four same-binary, shared SMP boot captures."""
import argparse
import hashlib
import json
from pathlib import Path

from decoder.__main__ import number
from decoder.artifacts import write_json
from decoder.performance import measure
from decoder.tests.check_coremark_sba import check_storage, check_optional_packets
from decoder.tests.check_dual_hart_sba import tuple_hash
from decoder.tests.check_smp_sba import audit_rpc, canonical_pc, ordered_reference, check_boot_privileges
from decoder.tests.check_xiangshan_sba import COMMIT
from decoder.tests.run_smp_sba import BASE, BOUNDARIES, image_elf


def read(path):
    return json.loads(path.read_text())


def jsonl(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def audit(directory):
    directory = Path(directory)
    comparison = read(directory / 'diff/comparison.json')
    metadata = read(directory / 'dut/metadata.json')
    run = read(directory / 'dut/run.json')
    manifest = read(directory / 'workload/manifest.json')
    if comparison['status'] != 'passed' or metadata['status'] != 'captured':
        raise ValueError('capture has not passed complete SMP execution validation')
    raw = (directory / 'workload/workload.bin').read_bytes()
    if hashlib.sha256(raw).hexdigest() != manifest['image_sha256']:
        raise ValueError('original shared SMP image fingerprint changed')
    if (directory / 'workload/workload.elf').read_bytes() != image_elf(raw):
        raise ValueError('decoder address aliases differ from original binary bytes')
    data = (directory / 'dut/trace.bin').read_bytes()
    check_storage(data, jsonl(directory / 'dut/sram-writes.jsonl'), metadata['storage'])
    audit_rpc(directory, metadata)
    if not metadata['same_core_clock'] or any(metadata[k] & 11 != 9 for k in ('funnel', 'sram')):
        raise ValueError('same-clock or shared-path drain contract differs')
    from decoder.__main__ import load_config
    decoded = load_config(directory / 'dut/trace.json')
    rows = jsonl(directory / 'dut/instructions.jsonl')
    packets = jsonl(directory / 'dut/packets.jsonl')
    rob_raw = (directory / 'ref/rob.log').read_bytes()[metadata['rob_byte_start']:].decode(errors='replace')
    samples = {0: [], 1: []}
    for match in COMMIT.finditer(rob_raw):
        hart, cycle, slot = map(int, match.group(1, 2, 3))
        if hart not in samples:
            raise ValueError('unexpected physical hart in ROB')
        pc = canonical_pc(int(match.group(4), 16), decoded.decoders[hart].profile.address_width)
        if not 0x38020000 <= pc < 0x38021000:
            samples[hart].append({'cycle': cycle, 'slot': slot, 'pc': pc})
    for hart in (0, 1):
        status = metadata['harts'][str(hart)]
        instructions = [r for r in rows if r['source_id'] == hart]
        check_boot_privileges(instructions, hart)
        if (status['actual_mhartid'], status['a0'], number(status['pc']), status['dcsr'] >> 6 & 7) != (
                hart, hart, BOUNDARIES[hart], 2):
            raise ValueError('actual hart identity or final hardware stop differs')
        if status['encoder'] & 11 != 9 or status['dropped'] or status['loss_markers']:
            raise ValueError('encoder did not drain without loss')
        selected = comparison['sources'][str(hart)]
        ordered_reference(samples[hart], [number(r['pc']) for r in instructions])
        if len(samples[hart]) != selected['rob_samples'] or len(instructions) != selected['retired_instructions']:
            raise ValueError('saved instruction/ROB counts changed')
        if tuple_hash(instructions, BASE) != selected['relative_retirement_sha256']:
            raise ValueError('saved per-hart retirement tuple hash changed')
        features = status['features_requested']
        if status['features_readback'] & 0xffff != features or status['features_readback'] >> 16 & 0xfff != hart:
            raise ValueError('actual feature/source readback differs')
        check_optional_packets([p for p in packets if p['source_id'] == hart], features)
    features = {h['features_requested'] for h in metadata['harts'].values()}
    if len(features) != 1 or next(iter(features)) != run['features']:
        raise ValueError('matrix requires the same feature mode on both actual encoders')
    measurement = measure(directory)
    if measurement['status'] != 'passed':
        raise ValueError('fresh replay is not eligible for measurement: ' + str(measurement['reason']))
    saved = read(directory / 'diff/storage-performance.json')
    for field in ('stored_bytes', 'retired_instructions', 'bits_per_instruction', 'input_sha256'):
        if measurement[field] != saved[field]:
            raise ValueError('measurement changed after capture: ' + field)
    proof = run['recorder_provenance']
    if (proof['status'] != 'validated' or proof['emu_sha256'] != run['emu_sha256'] or
            proof['full_vs_filtered']['status'] != 'passed' or proof['full_vs_filtered']['signals'] != 5 or
            not proof['full_vs_filtered']['end_times_equal'] or
            proof['physical_vs_sba']['status'] != 'passed'):
        raise ValueError('this binary lacks both observer proofs')
    if hashlib.sha256(Path(proof['emu']).read_bytes()).hexdigest() != run['emu_sha256']:
        raise ValueError('immutable emulator archive differs from actual capture identity')
    names = ['dut/trace.bin', 'dut/trace.fst', 'dut/run.json', 'dut/metadata.json',
             'dut/logs/rpc.jsonl', 'dut/sram-writes.jsonl', 'dut/trace.json', 'dut/instructions.jsonl',
             'dut/packets.jsonl', 'ref/rob.log', 'workload/workload.bin', 'workload/workload.elf',
             'diff/comparison.json', 'diff/storage-performance.json']
    names += [f'dut/hart{h}-discovery.bin' for h in (0, 1)]
    return {'status': 'passed', 'directory': str(directory.resolve()), 'features': next(iter(features)),
            'emu_sha256': run['emu_sha256'], 'image_sha256': manifest['image_sha256'],
            'measurement': measurement, 'comparison': comparison,
            'input_sha256': {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in names}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', type=Path, nargs=4)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() in {p.resolve() for p in args.directories}:
        parser.error('output must be separate from input captures')
    args.output.mkdir(parents=True, exist_ok=True)
    result = {'status': 'failed', 'error': None, 'modes': [],
              'scope': 'same original shared BBL/Linux boot handshake and AMO window',
              'limits': ['full Linux SMP online and whole-program speedup are not claimed',
                         'independent ROB reference has compressed retirement granularity']}
    try:
        for directory in args.directories:
            print('[SMP-matrix][audit] ' + str(directory), flush=True)
            result['modes'].append(audit(directory))
        rows = result['modes']
        if {r['features'] for r in rows} != {0, 8, 24, 56}:
            raise ValueError('matrix must contain exactly the four representative modes')
        if len({r['emu_sha256'] for r in rows}) != 1 or len({r['image_sha256'] for r in rows}) != 1:
            raise ValueError('matrix mixes binary or original shared image versions')
        baseline = next(r['measurement']['bits_per_instruction'] for r in rows if r['features'] == 0)
        for row in rows:
            row['storage_density_reduction_percent'] = 100 * (1 - row['measurement']['bits_per_instruction'] / baseline)
        result['status'] = 'passed'
        write_json(args.output / 'summary.json', result)
        print('[SMP-matrix] all four modes passed final read-only audit', flush=True)
    except Exception as exc:
        result['error'] = str(exc)
        raise
    finally:
        write_json(args.output / 'final-audit.json', result)


if __name__ == '__main__':
    main()
