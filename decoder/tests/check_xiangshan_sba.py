"""Validate a real JTAG/SBA capture against its ELF and compressed ROB samples."""
import json
from pathlib import Path
import re
import sys
from decoder.__main__ import load_config
from decoder.encapsulation import DecodeError


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
    metadata = json.loads((directory / 'metadata.json').read_text())
    symbols = json.loads((directory / 'symbols.json').read_text())
    start, done = symbols['trace_begin'], symbols['trace_done']
    if metadata['memory_access'] != 'sysbus':
        raise DecodeError('trace registers were not accessed exclusively through SBA')
    if metadata['dropped'] or metadata['loss_markers']:
        raise DecodeError('encoder reported dropped input or a loss marker')
    if any(metadata[name] & 0xb != 9 for name in ('encoder', 'funnel', 'sram')):
        raise DecodeError('components were not disabled and empty before capture')
    if metadata['pc'] != done or metadata['a0'] != 18:
        raise DecodeError('hart did not finish the workload at its hardware breakpoint with a0=18')
    capture = (directory / 'trace.bin').read_bytes()
    wp = metadata['wp']
    if not 0 < wp < 0x400 or wp & 3 or metadata['rp'] != wp or len(capture) != wp:
        raise DecodeError('SRAM Wrap/alignment/length/read-pointer mismatch')
    print(f'[XS-SBA][decoder] 读取{wp}bytes与真实ELF；组件Empty、无loss/Wrap、RP推进已检查', flush=True)
    decoder = load_config(directory / 'trace.json')
    events = []
    for offset in range(0, len(capture), 3):
        events.extend(decoder.feed(capture[offset:offset + 3]))
    decoder.finish()
    (directory / 'events.jsonl').write_text(''.join(json.dumps(e, sort_keys=True) + '\n' for e in events))
    instructions = [e for e in events if e['kind'] == 'instruction']
    if any(e['source_id'] != 0 or e['hart_id'] != 0 for e in instructions):
        raise DecodeError('unexpected source/hart in single-hart capture')
    if any(e['kind'] == 'support' and e['qual_status'] == 2 for e in events):
        raise DecodeError('trace_lost support in capture')
    pcs = [e['pc'] for e in instructions]
    # The tiny workload has four loop iterations, then an indirect call and return.
    expected = [start, start + 2] + [start + x for _ in range(4) for x in (4, 6, 8)]
    expected += [start + x for x in (12, 16, 20, 28, 30, 24, 26)]
    if pcs != expected:
        raise DecodeError(f'workload retirement mismatch: {list(map(hex, pcs))} != {list(map(hex, expected))}')
    if not decoder.decoders[0].start_of_trace:
        raise DecodeError('capture did not terminate its execution segment')
    samples = []
    previous = None
    for match in COMMIT.finditer((directory / 'rob.log').read_text(errors='replace')):
        hart, cycle, slot = map(int, match.group(1, 2, 3))
        if hart != 0:
            continue
        key = cycle, slot
        if previous is not None and key <= previous:
            raise DecodeError('ROB samples are not ordered by cycle and slot')
        previous = key
        pc = int(match.group(4), 16)
        if start <= pc < done:
            samples.append(pc)
    matched = match_samples(pcs, samples)
    result = {'decoded_instructions': len(pcs), 'rob_samples': len(samples),
              'rob_decoder_indices': matched, 'decoded_pcs': pcs, 'rob_pcs': samples}
    (directory / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'[XS-SBA][PC对拍] {len(pcs)}条完整workload指令、{len(samples)}条ROB压缩采样有序一致；保留重复次数', flush=True)
    return result


if __name__ == '__main__':
    try:
        check(sys.argv[1])
    except (DecodeError, OSError, KeyError, ValueError) as exc:
        raise SystemExit(f'XS SBA capture failed: {exc}')
