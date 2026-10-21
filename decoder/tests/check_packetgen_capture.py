"""Check real RTL captures against retained retirement PCs.

The baseline capture covers an anchored prefix; lifecycle captures complete
stopped segments, and loss captures contain only the retained instructions.
"""
import json
from pathlib import Path
import sys
from decoder.__main__ import load_config


def main(directory, stem='packetgen-execution'):
    if stem == 'packetgen-support':
        for mode in ('unchanged', 'full-address', 'instruction-off', 'encoder-off'):
            main(directory, f'packetgen-support-{mode}')
        return
    directory = Path(directory)
    print('[encoder->decoder][1/3] 读取 RTL 采集与对应 raw 指令镜像', file=sys.stderr)
    decoder = load_config(directory / f'{stem}.json')
    capture = (directory / f'{stem}.bin').read_bytes()
    events = []
    # Deliberately cut packets and multibyte signed fields across input chunks.
    for offset in range(0, len(capture), 3):
        events.extend(decoder.feed(capture[offset:offset + 3]))
    decoder.finish()
    print('[encoder->decoder][2/3] 按 srcID 解码采集流，比较指令、停止、丢失及异常事件', file=sys.stderr)
    instructions = [event for event in events if event['kind'] == 'instruction']
    actual = [event['pc'] for event in instructions]
    expected = json.loads((directory / f'{stem}-expected.json').read_text())
    if actual != expected:
        raise SystemExit(f'PacketGen/decoder PC mismatch: {actual!r} != {expected!r}')
    if any(event['source_id'] != 3 or event['hart_id'] != 7 for event in instructions):
        raise SystemExit('PacketGen/decoder source mapping mismatch')
    if stem == 'packetgen-lifecycle':
        ends = [e for e in events if e['kind'] == 'support' and e['qual_status'] == 1 and not e['ienable']]
        if len(ends) != 2 or not decoder.decoders[3].start_of_trace:
            raise SystemExit('lifecycle capture did not finish two disabled trace segments')
    if stem == 'packetgen-loss':
        lost = [e for e in events if e['kind'] == 'support' and e['qual_status'] == 2]
        sync = [e for e in events if e['kind'] == 'instruction' and e['pc'] == 0x3000]
        if len(lost) != 1 or len(sync) != 1 or not decoder.decoders[3].start_of_trace:
            raise SystemExit('loss capture did not report one gap and finish the recovered segment')
    if stem == 'packetgen-traps':
        epcs = [e['pc'] for e in events if e['kind'] == 'exception_pc']
        causes = [e['ecause'] for e in events if e['kind'] == 'trap']
        if epcs != [0x100c, 0x2008] or causes != [2, 12]:
            raise SystemExit(f'trap capture EPC/cause mismatch: {epcs!r}, {causes!r}')
    if stem == 'packetgen-context':
        actual_contexts = [e['context'] for e in instructions]
        contexts = [e for e in events if e['kind'] == 'context']
        if actual_contexts != [0, 0, 1, 1, 1, 2, 2] or len(contexts) != 1 or contexts[0]['time'] != 0x1234:
            raise SystemExit(f'context capture metadata mismatch: {actual_contexts!r}, {contexts!r}')
    if stem.startswith('packetgen-support-'):
        mode = stem.removeprefix('packetgen-support-')
        expected_support = [(0, 1, 0), (2, 1, 0)]
        if mode == 'full-address':
            expected_support += [(0, 1, 32)]
        elif mode in ('instruction-off', 'encoder-off'):
            expected_support += [(0, 0, 0), (0, 1, 0)]
        elif mode != 'unchanged':
            raise SystemExit(f'unknown support capture mode: {mode}')
        expected_support += [(1, 1, 32 if mode == 'full-address' else 0)]
        support = [(e['qual_status'], e['ienable'], e['options']) for e in events if e['kind'] == 'support']
        if support != expected_support or not decoder.decoders[3].start_of_trace:
            raise SystemExit(f'support capture ordering mismatch: {support!r} != {expected_support!r}')
    if stem == 'packetgen-jump-cache':
        supports = [(e['qual_status'], e['options']) for e in events if e['kind'] == 'support']
        if supports != [(0, 16), (1, 16)] or not decoder.decoders[3].start_of_trace:
            raise SystemExit(f'jump-cache capture configuration/end mismatch: {supports!r}')
    if stem == 'packetgen-branch-pred':
        supports = [(e['qual_status'], e['options']) for e in events if e['kind'] == 'support']
        if supports != [(0, 8), (1, 8)] or not decoder.decoders[3].start_of_trace:
            raise SystemExit(f'branch prediction capture configuration/end mismatch: {supports!r}')
    extent = ('保留下来的全部退休指令' if stem == 'packetgen-loss' or stem.startswith('packetgen-support-') else '全部退休指令'
              if stem in ('packetgen-lifecycle', 'packetgen-traps', 'packetgen-context', 'packetgen-jump-cache', 'packetgen-branch-pred') else '至最后 trace 锚点的退休前缀')
    print(f'[encoder->decoder][3/3] {len(actual)} 条指令与{extent}逐条一致：' +
          ', '.join(f'0x{pc:x}' for pc in actual), file=sys.stderr)
    print(f'{stem} capture decoded successfully: {len(actual)} instructions')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation',
         sys.argv[2] if len(sys.argv) > 2 else 'packetgen-execution')
