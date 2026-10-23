"""Validate real JTAG/SBA capture and save each stage, including failures."""
import json
from pathlib import Path
import re
import sys
from decoder.__main__ import load_config, number
from decoder.artifacts import DECODE_REPORT_FILES, DecodeArtifacts, write_json, write_jsonl
from decoder.encapsulation import DecodeError
from decoder.capture_layout import CaptureLayout
from .xiangshan_sba_scenarios import reference


COMMIT = re.compile(r'\[opentrace-commit\]\s+hart=\s*(\d+)\s+cycle=\s*(\d+)\s+'
                    r'slot=\s*(\d+)\s+pc=0x([0-9a-fA-F]+)(?=\r?\n)')


def match_samples(decoded, samples):
    """All ROB samples must match in order; never discard repeated samples."""
    position = 0
    matched = []
    for index, pc in enumerate(samples):
        while position < len(decoded) and decoded[position] != pc:
            position += 1
        if position == len(decoded):
            raise DecodeError(f'ROB sample {index} pc=0x{pc:x} has no ordered decoder match')
        matched.append(position)
        position += 1
    if not matched:
        raise DecodeError('no independent ROB samples in the workload')
    return matched


def check(directory):
    directory = Path(directory)
    layout = CaptureLayout(directory).create()
    # Regenerate only derived reports; never touch the SRAM bytes, images or
    # RTL logs. A failing recheck must not leave an old successful report.
    for name in ('expected-pcs.json', 'pc-matches.tsv'):
        (layout.diff / name).unlink(missing_ok=True)
    for name in ('commit-pc.jsonl', 'commit-pc-workload.jsonl', 'commit-pc.tsv'):
        (layout.ref / name).unlink(missing_ok=True)
    for name in (*DECODE_REPORT_FILES, 'sram-raw.hex', 'sram-words.jsonl'):
        (layout.dut / name).unlink(missing_ok=True)
    result = {'status': 'failed', 'stage': 'inputs', 'error': None}
    try:
        return check_stages(directory, result)
    except (DecodeError, OSError, KeyError, ValueError, TypeError) as exc:
        result['error'] = str(exc)
        raise
    finally:
        write_json(layout.diff / 'comparison.json', result)


def check_stages(directory, result):
    layout = CaptureLayout(directory)
    metadata = json.loads(layout.input('dut', 'metadata.json').read_text())
    symbols = json.loads(layout.input('workload', 'symbols.json').read_text())
    start, done = number(symbols['trace_begin']), number(symbols['trace_done'])
    result['instruction_region'] = {'trace_begin': start, 'trace_done': done,
                                    'strictly_above': '0x80000000'}
    if not 0x80000000 < start < done:
        raise DecodeError('workload instruction range must be strictly above 0x80000000')
    capture = layout.input('dut', 'trace.bin').read_bytes()
    with (layout.dut / 'sram-raw.hex').open('w') as output:
        for offset in range(0, len(capture), 16):
            output.write(f'{offset:#010x}  {capture[offset:offset + 16].hex(" ")}\n')
    write_jsonl(layout.dut / 'sram-words.jsonl',
                ({'offset': offset, 'word_hex': '0x' + capture[offset:offset + 4][::-1].hex(),
                  'bytes_hex': capture[offset:offset + 4].hex(' ')} for offset in range(0, len(capture), 4)))
    # Export the independent reference before decoding, so malformed packets do
    # not hide the actual commits that led to the failing capture.
    commits = []
    previous = {}
    for match in COMMIT.finditer(layout.input('ref', 'rob.log').read_text(errors='replace')):
        hart, cycle, slot = map(int, match.group(1, 2, 3))
        key = cycle, slot
        if hart in previous and key <= previous[hart]:
            raise DecodeError('ROB samples are not ordered by cycle and slot')
        previous[hart] = key
        pc = int(match.group(4), 16)
        commits.append({'index': len(commits), 'hart_id': hart, 'cycle': cycle, 'slot': slot, 'pc': pc,
                        'in_workload': hart == 0 and start <= pc < done})
    write_jsonl(layout.ref / 'commit-pc.jsonl', commits)
    selected = [row for row in commits if row['in_workload']]
    write_jsonl(layout.ref / 'commit-pc-workload.jsonl', selected)
    with (layout.ref / 'commit-pc.tsv').open('w') as output:
        output.write('index\thart_id\tcycle\tslot\tpc\tin_workload\n')
        for row in commits:
            output.write(f"{row['index']}\t{row['hart_id']}\t{row['cycle']}\t{row['slot']}\t{row['pc']:#x}\t{row['in_workload']}\n")
    result.update(rob_samples=len(selected), rob_pcs=[row['pc'] for row in selected])
    scenario_path = layout.workload / 'scenario.json'
    scenario = json.loads(scenario_path.read_text())['scenario'] if scenario_path.exists() else 'basic'
    expected_a0, expected = reference(symbols, scenario)
    result['scenario'] = scenario
    write_json(layout.diff / 'expected-pcs.json', {'expected_pcs': expected,
               'basis': 'Fixed assembly workload; separate from the independent ROB reference.'})

    result['stage'] = 'capture-metadata'
    if metadata['memory_access'] != 'sysbus':
        raise DecodeError('trace registers were not accessed exclusively through SBA')
    if metadata['dropped'] or metadata['loss_markers']:
        raise DecodeError('encoder reported dropped input or a loss marker')
    if any(metadata[name] & 0xb != 9 for name in ('encoder', 'funnel', 'sram')):
        raise DecodeError('components were not disabled and empty before capture')
    if number(metadata['pc']) != done or metadata['a0'] != expected_a0:
        raise DecodeError('hart did not finish the workload at its hardware breakpoint with the expected a0')
    if scenario == 'pressure' and not (metadata['pressure_stalls_before_release'] > 0 and metadata['stall_cycles'] > 0):
        raise DecodeError('pressure run lacks actual core-stall evidence before sink release')
    wp = metadata['wp']
    if not 0 < wp < 0x400 or wp & 3 or metadata['rp'] != wp or len(capture) != wp:
        raise DecodeError('SRAM Wrap/alignment/length/read-pointer mismatch')
    print(f'[XS-SBA][decoder] 读取{wp}bytes与真实ELF；组件Empty、无loss/Wrap、RP推进已检查', flush=True)
    result['stage'] = 'decode'
    observer = DecodeArtifacts(directory, overwrite=True)
    events = []
    failure = None
    try:
        observer.snapshot(layout.input('dut', 'trace.bin'), layout.input('dut', 'trace.json'))
        decoder = load_config(layout.dut / 'trace.json', observer=observer)
        for offset in range(0, len(capture), 3):
            events.extend(decoder.feed(capture[offset:offset + 3]))
        decoder.finish()
    except (DecodeError, OSError, KeyError, ValueError, TypeError) as exc:
        failure = exc
        raise
    finally:
        observer.finish(failure)
    instructions = [event for event in events if event['kind'] == 'instruction']
    if any(event['source_id'] != 0 or event['hart_id'] != 0 for event in instructions):
        raise DecodeError('unexpected source/hart in single-hart capture')
    if any(event['kind'] == 'support' and event['qual_status'] == 2 for event in events):
        raise DecodeError('trace_lost support in capture')
    pcs = [event['pc'] for event in instructions]
    result.update(decoded_instructions=len(pcs), decoded_pcs=pcs)
    result['stage'] = 'expected-workload'
    if pcs != expected:
        index = next((i for i, pair in enumerate(zip(pcs, expected)) if pair[0] != pair[1]), min(len(pcs), len(expected)))
        result['first_mismatch'] = {'index': index, 'actual_pc': pcs[index] if index < len(pcs) else None,
                                    'expected_pc': expected[index] if index < len(expected) else None}
        raise DecodeError(f'workload retirement mismatch at instruction {index}')
    if not decoder.decoders[0].start_of_trace:
        raise DecodeError('capture did not terminate its execution segment')
    if scenario == 'exception':
        traps = [event for event in events if event['kind'] == 'trap']
        epcs = [event['pc'] for event in events if event['kind'] == 'exception_pc']
        if len(traps) < 2 or [(e['ecause'], e['interrupt']) for e in traps[:2]] != [(11, False), (2, False)]:
            raise DecodeError('ordinary ecall/illegal causes differ from the actual workload')
        if epcs[:2] != [start + 16, start + 24]:
            raise DecodeError('faulting EPCs were lost or confused with retired handler instructions')
        if number(metadata['trap_vector_readback']) != number(symbols['trace_handler']):
            raise DecodeError('actual mtvec differs from the decoded handler configuration')
        result['ordinary_traps'] = {'causes': [11, 2], 'exception_pcs': epcs[:2],
                                    'mtvec': hex(number(metadata['trap_vector_readback']))}
    result['stage'] = 'rob-comparison'
    samples = [row['pc'] for row in selected]
    matches = []
    position = 0
    with (layout.diff / 'pc-matches.tsv').open('w') as output:
        output.write('sample_index\tcycle\tslot\trob_pc\tdecoder_index\tdecoded_pc\tstatus\n')
        for index, row in enumerate(selected):
            while position < len(pcs) and pcs[position] != row['pc']:
                position += 1
            if position == len(pcs):
                result['first_mismatch'] = {'sample_index': index, 'rob_pc': row['pc'], 'decoded_pc': None,
                                            'cycle': row['cycle'], 'slot': row['slot']}
                output.write(f"{index}\t{row['cycle']}\t{row['slot']}\t{row['pc']:#x}\t\t\tfailed\n")
                raise DecodeError(f'ROB sample {index} pc={row["pc"]:#x} has no ordered decoder match')
            matches.append(position)
            output.write(f"{index}\t{row['cycle']}\t{row['slot']}\t{row['pc']:#x}\t{position}\t{pcs[position]:#x}\tmatched\n")
            position += 1
    if not matches:
        raise DecodeError('no independent ROB samples in the workload')
    result.update(status='passed', stage='complete', rob_decoder_indices=matches)
    print(f'[XS-SBA][PC对拍] {len(pcs)}条完整workload指令、{len(samples)}条ROB压缩采样有序一致；保留重复次数', flush=True)
    return result


if __name__ == '__main__':
    try:
        check(sys.argv[1])
    except (DecodeError, OSError, KeyError, ValueError, TypeError) as exc:
        raise SystemExit(f'XS SBA capture failed: {exc}')
