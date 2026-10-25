"""Validate real dual-hart shared-SRAM captures without imposing cross-hart PC order."""
import hashlib
import json
from pathlib import Path

from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json, write_jsonl
from decoder.image import ProgramImage
from decoder.modes import mode_name
from decoder.performance import measure
from decoder.tests.check_coremark_sba import check_storage, check_optional_packets, observe_returns
from decoder.tests.check_xiangshan_sba import COMMIT, match_samples
from decoder.tests.xiangshan_sba_scenarios import reference
from decoder.tests.build_dual_hart_workload import CRC


def tuple_hash(rows, base):
    digest = hashlib.sha256()
    for row in rows:
        digest.update(f"{number(row['pc'])-base:x}:{number(row['instruction']):x}:{number(row['size'])}\n".encode())
    return digest.hexdigest()


def check(directory, baseline=None):
    directory = Path(directory)
    result = {'status': 'failed', 'stage': 'inputs', 'error': None,
              'reference_kind': 'per-hart fixed assembly or validated benchmark baseline plus independent ROB',
              'control': 'external JTAG -> Debug SBA physical sysbus'}
    observer = None
    failure = None
    try:
        metadata = json.loads((directory / 'dut/metadata.json').read_text())
        manifest = json.loads((directory / 'workload/manifest.json').read_text())
        data = (directory / 'dut/trace.bin').read_bytes()
        if metadata['status'] != 'captured' or not metadata['same_core_clock']:
            raise ValueError('dual-hart capture incomplete or clock contract changed')
        if any(metadata[name] & 11 != 9 for name in ('funnel', 'sram')):
            raise ValueError('shared path was not disabled and drained')
        if metadata['capture_method'] == 'all physical SRAM writes plus SBA tail':
            words = [json.loads(line) for line in (directory / 'dut/sram-writes.jsonl').read_text().splitlines()]
            check_storage(data, words, metadata['storage'])
        else:
            if metadata['storage']['wp'] != len(data) or metadata['storage']['rp'] != len(data):
                raise ValueError('short SRAM capture pointers/length differ')
        if hashlib.sha256((directory / 'workload/workload.bin').read_bytes()).hexdigest() != manifest['image_sha256']:
            raise ValueError('dual workload image fingerprint changed')
        observer = DecodeArtifacts(directory, overwrite=True)
        observer.snapshot(directory / 'dut/trace.bin', directory / 'dut/trace.json')
        decoder = load_config(directory / 'dut/trace.json', observer=observer)
        returns = {hart: observe_returns(decoder.decoders[hart]) for hart in (0, 1)}
        events = []
        for offset in range(0, len(data), 4096):
            events.extend(decoder.feed(data[offset:offset + 4096]))
        decoder.finish()
        if any(e['kind'] == 'support' and e['qual_status'] == 2 for e in events):
            raise ValueError('shared capture contains trace loss')
        if any(not d.start_of_trace for d in decoder.decoders.values()):
            raise ValueError('a hart trace segment did not terminate')
        if any(e['kind'] in ('trap', 'exception_pc') for e in events):
            raise ValueError('unexpected architectural trap in dual-hart workload')
        packets = [json.loads(line) for line in (directory / 'dut/packets.jsonl').read_text().splitlines()]
        sources = [row['source_id'] for row in packets]
        if set(sources) != {0, 1}:
            raise ValueError('both actual in-band source IDs must appear')
        raw = (directory / 'ref/rob.log').read_bytes()[metadata['rob_byte_start']:].decode(errors='replace')
        all_samples = {hart: [] for hart in (0, 1)}
        for match in COMMIT.finditer(raw):
            hart, cycle, slot = map(int, match.group(1, 2, 3))
            if hart not in all_samples:
                raise ValueError('unexpected hart in actual dual-core ROB reference')
            all_samples[hart].append({'hart_id': hart, 'cycle': cycle, 'slot': slot, 'pc': int(match.group(4), 16)})
        result.update(bytes=len(data), decoded_instructions=0, rob_samples=0, sources={})
        ranges = []
        for description in manifest['harts']:
            hart = description['hart_id']
            status = metadata['harts'][str(hart)]
            base = number(description['base'])
            symbols = {k: number(v) for k, v in description['symbols'].items()}
            if status['encoder'] & 11 != 9 or status['dropped'] or status['loss_markers']:
                raise ValueError(f'hart {hart} did not drain without loss')
            if number(status['pc']) != symbols['trace_done'] or (number(status['dcsr']) >> 6) & 7 != 2:
                raise ValueError(f'hart {hart} did not reach the real hardware breakpoint')
            features = status['features_requested']
            mode_name(features)
            if status['features_readback'] & 0xffff != features:
                raise ValueError('per-hart feature readback differs')
            instructions = [e for e in events if e['kind'] == 'instruction' and e['source_id'] == hart]
            if not instructions or any(e['hart_id'] != hart or e['privilege'] == 4 for e in instructions):
                raise ValueError('source mapping or debug instruction qualification differs')
            pcs = [e['pc'] for e in instructions]
            image = ProgramImage.from_elf(directory / 'workload' / description['elf'])
            for event in instructions:
                instruction = image.instruction(event['pc'])
                if (instruction.bits, instruction.size) != (event['instruction'], event['size']):
                    raise ValueError('decoded instruction differs from that hart ELF')
            if manifest['kind'] != 'coremark':
                expected_a0, expected = reference(symbols, manifest['kind'])
                if pcs != expected or status['a0'] != expected_a0:
                    raise ValueError(f'hart {hart} full fixed workload retirement differs')
            else:
                record = status['benchmark_result']
                if record['magic'] != 0x434d4f54 or record['hart'] != hart or record['iterations'] != 2 or record['errors']:
                    raise ValueError('actual benchmark result record invalid')
                if any(record[name] != value for name, value in CRC.items()):
                    raise ValueError(f'hart {hart} actual CoreMark CRC differs')
                if pcs[0] != symbols['trace_begin'] or symbols['trace_done'] in pcs:
                    raise ValueError('benchmark trace entry/exit retirement differs')
            samples = [s for s in all_samples[hart] if symbols['trace_begin'] <= s['pc'] < symbols['trace_done'] or
                       (manifest['kind'] == 'coremark' and base <= s['pc'] < base + 0x80000 and s['pc'] != symbols['trace_done'])]
            if any((b['cycle'], b['slot']) <= (a['cycle'], a['slot']) for a, b in zip(samples, samples[1:])):
                raise ValueError('per-hart ROB samples reordered')
            indices = match_samples(pcs, [s['pc'] for s in samples])
            write_jsonl(directory / f'ref/hart{hart}-commit-pc.jsonl', samples)
            write_json(directory / f'diff/hart{hart}-rob-matches.json', {'status': 'passed', 'indices': indices})
            signature = tuple_hash(instructions, base)
            if baseline is not None:
                prior = json.loads((Path(baseline) / 'diff/comparison.json').read_text())
                expected = prior['sources'][str(hart)]
                if (len(instructions), signature) != (expected['retired_instructions'], expected['relative_retirement_sha256']):
                    raise ValueError('all per-hart retirement tuples differ from validated dual baseline')
            coverage = check_optional_packets([p for p in packets if p['source_id'] == hart], features)
            if features & 8 and not returns[hart]['implicit_returns']:
                raise ValueError('implicit-return was enabled without actual inferred returns')
            result['sources'][str(hart)] = {'status': 'passed', 'retired_instructions': len(instructions),
                'relative_retirement_sha256': signature, 'rob_samples': len(samples),
                'features': features, 'mode': mode_name(features), 'optional_coverage': coverage,
                'return_coverage': returns[hart], 'stall_cycles': status['stall_cycles']}
            result['decoded_instructions'] += len(instructions)
            result['rob_samples'] += len(samples)
            ranges.append((samples[0]['cycle'], samples[-1]['cycle']))
        if manifest['kind'] in ('pressure', 'coremark') and max(r[0] for r in ranges) >= min(r[1] for r in ranges):
            raise ValueError('dual-hart retirement intervals never overlapped')
        if metadata['pressure'] and any(not metadata['harts'][str(h)]['pressure_stalls'] for h in (0, 1)):
            raise ValueError('both harts must exhibit actual backpressure before sink release')
        result.update(status='passed', stage='complete', packet_source_switches=sum(a != b for a, b in zip(sources, sources[1:])),
                      retirement_intervals=ranges)
        return result
    except Exception as exc:
        result['error'] = str(exc); failure = exc; raise
    finally:
        if observer is not None:
            observer.finish(failure)
        write_json(directory / 'diff/comparison.json', result)


def check_and_measure(directory, baseline=None):
    result = check(directory, baseline)
    performance = measure(directory)
    if performance['status'] != 'passed':
        raise ValueError('dual-hart measurement prerequisites failed: ' + str(performance['reason']))
    write_json(Path(directory) / 'diff/storage-performance.json', performance)
    return result, performance
