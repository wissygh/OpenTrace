"""Verify unmodified SMP boot captures, complete packets and per-hart ROB."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json, write_jsonl
from decoder.image import ProgramImage
from decoder.performance import measure
from decoder.tests.check_coremark_sba import check_storage, check_optional_packets, observe_returns
from decoder.tests.check_dual_hart_sba import tuple_hash
from decoder.tests.check_xiangshan_sba import COMMIT, match_samples
from decoder.tests.run_smp_sba import BASE, PAYLOAD, BOUNDARIES, image_elf, validate_initial_hart


def canonical_pc(pc, width):
    """ROB prints a physical-width field, including truncated high VAs."""
    if not 1 < width <= 64 or not 0 <= pc < 1 << width:
        raise ValueError('ROB address does not fit the discovered address width')
    return (pc - (1 << width) if pc & (1 << (width - 1)) else pc) & ((1 << 64) - 1)


def ordered_reference(samples, pcs):
    if not samples:
        raise ValueError('no independent ROB samples in this trace interval')
    if any((b['cycle'], b['slot']) <= (a['cycle'], a['slot']) for a, b in zip(samples, samples[1:])):
        raise ValueError('per-hart ROB samples are duplicated or reordered')
    return match_samples(pcs, [sample['pc'] for sample in samples])


def check_boot_privileges(instructions, hart):
    for event in instructions:
        pc = number(event['pc'])
        expected = 3 if BASE <= pc < BASE + PAYLOAD else 1
        if event['hart_id'] != hart or event['source_id'] != hart or event['privilege'] != expected:
            raise ValueError('original BBL/kernel instruction privilege or source identity differs')


def audit_rpc(directory, metadata):
    rows = [json.loads(line) for line in (directory / 'dut/logs/rpc.jsonl').read_text().splitlines()]
    if not rows or any(row['return_code'] != '0' for row in rows):
        raise ValueError('capture contains failed external RPC commands')
    if any(row['command'].startswith('reg pc ') or row['command'].startswith('resume ') for row in rows):
        raise ValueError('SMP validation must continue original PC without debugger redirection')
    sample = [int(row['result'], 0) for row in rows if row['command'] == 'read_memory 0x20050040 32 1 phys']
    if sample != metadata['storage']['sample_words']:
        raise ValueError('saved SRAM tail differs from original physical SBA RPC responses')
    if any(row['command'].startswith('riscv set_mem_access ') and row['command'] != 'riscv set_mem_access sysbus'
           for row in rows):
        raise ValueError('trace control memory access was not restricted to external sysbus')
    return {'status': 'passed', 'commands': len(rows), 'sba_tail_words': len(sample),
            'sha256': hashlib.sha256((directory / 'dut/logs/rpc.jsonl').read_bytes()).hexdigest()}


def check(directory):
    directory = Path(directory)
    result = {'status': 'failed', 'stage': 'inputs', 'error': None,
              'reference_kind': 'independent per-hart ordered ROB samples plus unmodified shared SMP image',
              'scope': 'BBL wakeup, shared entry-point and kernel AMO lottery; not full Linux boot performance',
              'limits': ['ROB is compressed sampling, not an independent complete ISA retirement oracle',
                         'timing-dependent polling counts may differ across feature modes']}
    observer = None
    failure = None
    try:
        metadata = json.loads((directory / 'dut/metadata.json').read_text())
        manifest = json.loads((directory / 'workload/manifest.json').read_text())
        raw = (directory / 'workload/workload.bin').read_bytes()
        data = (directory / 'dut/trace.bin').read_bytes()
        if metadata['status'] != 'captured' or not metadata['same_core_clock']:
            raise ValueError('SMP capture was not completed under the same-clock contract')
        if not manifest['unmodified_image'] or hashlib.sha256(raw).hexdigest() != manifest['image_sha256']:
            raise ValueError('original SMP image fingerprint differs')
        if (directory / 'workload/workload.elf').read_bytes() != image_elf(raw):
            raise ValueError('decoder image differs from the original binary bytes/address aliases')
        if any(metadata[name] & 11 != 9 for name in ('funnel', 'sram')):
            raise ValueError('shared Funnel/SRAM were not disabled and fully drained')
        writes = [json.loads(line) for line in (directory / 'dut/sram-writes.jsonl').read_text().splitlines()]
        check_storage(data, writes, metadata['storage'])
        result['rpc_audit'] = audit_rpc(directory, metadata)
        observer = DecodeArtifacts(directory, overwrite=True)
        observer.snapshot(directory / 'dut/trace.bin', directory / 'dut/trace.json')
        decoder = load_config(directory / 'dut/trace.json', observer=observer)
        returns = {hart: observe_returns(decoder.decoders[hart]) for hart in (0, 1)}
        result['stage'] = 'decode'
        events = []
        for offset in range(0, len(data), 4096):
            events.extend(decoder.feed(data[offset:offset + 4096]))
        decoder.finish()
        if any(event['kind'] == 'unsynchronized' for event in events):
            raise ValueError('complete SRAM write stream contains unsynchronized source data')
        if any(event['kind'] == 'support' and event['qual_status'] == 2 for event in events):
            raise ValueError('SMP packet stream contains trace loss')
        if any(not d.start_of_trace for d in decoder.decoders.values()):
            raise ValueError('a source has no complete trace-end boundary')
        packets = [json.loads(line) for line in (directory / 'dut/packets.jsonl').read_text().splitlines()]
        sources = [packet['source_id'] for packet in packets]
        switches = sum(a != b for a, b in zip(sources, sources[1:]))
        if set(sources) != {0, 1} or switches < 2:
            raise ValueError('both sources must actually interleave in complete parsed packets')
        result['packet_layout'] = {
            'packets': len(packets),
            'packets_crossing_sram_words': sum(p['offset'] // 4 != (p['offset'] + p['byte_length'] - 1) // 4
                                               for p in packets),
            'source_switches_sharing_sram_word': sum(a['source_id'] != b['source_id'] and
                (a['offset'] + a['byte_length'] - 1) // 4 == b['offset'] // 4
                for a, b in zip(packets, packets[1:])),
        }
        all_samples = {0: [], 1: []}
        # rob_byte_start was recorded while both harts were halted, immediately
        # before natural resume. Keep every non-Debug sample after that actual
        # capture boundary, including repetitions and legitimate boot traps.
        # It is independent of architectural cycle CSR counter configuration.
        rob_data = (directory / 'ref/rob.log').read_bytes()
        rob_start = metadata['rob_byte_start']
        if not isinstance(rob_start, int) or not 0 <= rob_start < len(rob_data):
            raise ValueError('invalid independently recorded ROB capture boundary')
        for match in COMMIT.finditer(rob_data[rob_start:].decode(errors='replace')):
            hart, cycle, slot = map(int, match.group(1, 2, 3))
            if hart not in all_samples:
                raise ValueError('unexpected physical hart in ROB reference')
            pc = canonical_pc(int(match.group(4), 16), decoder.decoders[hart].profile.address_width)
            if not 0x38020000 <= pc < 0x38021000:
                all_samples[hart].append({'hart_id': hart, 'cycle': cycle, 'slot': slot, 'pc': pc})
        image = ProgramImage.from_elf(directory / 'workload/workload.elf')
        result.update(stage='reference', bytes=len(data), decoded_instructions=0, rob_samples=0,
                      packet_source_switches=switches, sources={})
        intervals = []
        for hart in (0, 1):
            status = metadata['harts'][str(hart)]
            validate_initial_hart(hart, status['actual_mhartid'], status['initial_pc'])
            if status['encoder'] & 11 != 9 or status['dropped'] or status['loss_markers']:
                raise ValueError('source was not drained without loss')
            if number(status['pc']) != BOUNDARIES[hart] or number(status['dcsr']) >> 6 & 7 != 2:
                raise ValueError('source did not stop at its original hardware breakpoint')
            if status['a0'] != hart:
                raise ValueError('actual SMP entry argument disagrees with physical hart identity')
            features = status['features_requested']
            if status['features_readback'] & 0xffff != features or status['features_readback'] >> 16 & 0xfff != hart:
                raise ValueError('source ID or feature readback differs')
            instructions = [e for e in events if e['kind'] == 'instruction' and e['source_id'] == hart]
            check_boot_privileges(instructions, hart)
            pcs = [e['pc'] for e in instructions]
            if not pcs or any(e['hart_id'] != hart or e['privilege'] == 4 for e in instructions):
                raise ValueError('source mapping or Debug qualification differs')
            for event in instructions:
                expected = image.instruction(event['pc'])
                if (expected.bits, expected.size) != (event['instruction'], event['size']):
                    raise ValueError('decoded instruction differs from unmodified SMP image')
            samples = all_samples[hart]
            indices = ordered_reference(samples, pcs)
            write_jsonl(directory / f'ref/hart{hart}-commit-pc.jsonl', samples)
            write_json(directory / f'diff/hart{hart}-rob-matches.json', {'status': 'passed', 'indices': indices})
            if pcs[0] != number(status['initial_pc']) or BOUNDARIES[hart] in pcs:
                raise ValueError('original continuation PC or non-retired final breakpoint differs')
            lottery = BASE + PAYLOAD + 0x18
            if pcs.count(lottery) != 1 or not any(sample['pc'] == lottery for sample in samples):
                raise ValueError('both actual harts must retire the original shared AMO exactly once')
            if hart == 1 and not any(BASE + 0x2e4 <= pc <= BASE + 0x2f2 for pc in pcs):
                raise ValueError('secondary hart did not follow original shared entry-point polling')
            # The secondary overwrites a3 at payload+0xbc..0xc8 before its
            # breakpoint. Its register is now the stack-array offset, not the
            # old AMO value. Derive the lottery outcome from the actual branch
            # path, and check the still-live value only for the boot hart.
            secondary_path = BASE + PAYLOAD + 0xb6 in pcs
            if secondary_path != bool(hart) or (hart == 0 and status['a3'] != 0):
                raise ValueError('actual shared AMO branch path disagrees with boot/secondary role')
            ended = sum(e['kind'] == 'support' and e['qual_status'] == 1 and e['source_id'] == hart for e in events)
            if not ended:
                raise ValueError('source trace-end was not observed')
            intervals.append((samples[0]['cycle'], samples[-1]['cycle']))
            result['sources'][str(hart)] = {'status': 'passed', 'retired_instructions': len(pcs),
                'rob_samples': len(samples), 'relative_retirement_sha256': tuple_hash(instructions, BASE),
                'features': features, 'original_shared_amo_count': pcs.count(lottery),
                'shared_amo_role': 'secondary' if secondary_path else 'boot',
                'a3_at_final_breakpoint': status['a3'], 'ended_reported': ended,
                'optional_coverage': check_optional_packets([p for p in packets if p['source_id'] == hart], features),
                'return_coverage': returns[hart], 'stall_cycles': status['stall_cycles'],
                'packet_bytes': sum(p['byte_length'] for p in packets if p['source_id'] == hart),
                'traps': dict(Counter(str(e['ecause']) for e in events if e['kind'] == 'trap' and e['source_id'] == hart))}
            result['decoded_instructions'] += len(pcs); result['rob_samples'] += len(samples)
        if max(row[0] for row in intervals) >= min(row[1] for row in intervals):
            raise ValueError('actual dual-hart retirement windows never overlapped')
        result.update(status='passed', stage='complete', retirement_intervals=intervals)
        return result
    except Exception as exc:
        failure = exc; result['error'] = str(exc); raise
    finally:
        if observer is not None:
            observer.finish(failure)
        write_json(directory / 'diff/comparison.json', result)
        if result['status'] == 'passed':
            performance = measure(directory)
            write_json(directory / 'diff/storage-performance.json', performance)
            print(f'[SMP][对拍] {result["decoded_instructions"]}条解码、{result["rob_samples"]}条独立ROB、'
                  f'{result["packet_source_switches"]}次源切换；计量状态{performance["status"]}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    check(parser.parse_args().directory)
