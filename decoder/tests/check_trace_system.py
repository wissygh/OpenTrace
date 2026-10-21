"""Check bytes captured after the actual Trace top's control and ATB crossings."""
import sys
from pathlib import Path

from decoder.__main__ import load_config


def main(directory='build/validation', stem='trace-system'):
    directory = Path(directory)
    print('[top->decoder][1/3] 读取真实TL配置/四时钟Trace顶层的ATB采集', file=sys.stderr)
    decoder = load_config(directory / f'{stem}.json')
    capture = (directory / f'{stem}.bin').read_bytes()
    events = []
    for offset in range(0, len(capture), 5):
        events.extend(decoder.feed(capture[offset:offset + 5]))
    decoder.finish()
    for source, base in ((0, 0x1000), (1, 0x8000)):
        expected = [base + 4 * i for i in range(4)]
        if stem == 'trace-system-reset' and source == 0:
            expected = [0x1000, 0x2000, 0x2004, 0x2008, 0x200c]
        if stem == 'trace-system-mask' and source == 0:
            expected = [0x2000 + 4 * i for i in range(4)]
        if stem == 'trace-system-funnel-reset':
            # The live encoders retain their last accepted ingress group and
            # report its PC explicitly in the requested new Format30 anchor.
            expected = [base, base + 12] + [base + 4 * i for i in range(4, 8)]
        instructions = [e for e in events if e['kind'] == 'instruction' and e['source_id'] == source]
        actual = [e['pc'] for e in instructions]
        if actual != expected or any(e['hart_id'] != source for e in instructions):
            raise SystemExit(f'actual Trace source {source} PC mismatch: {actual!r} != {expected!r}')
        ends = [e for e in events if e['kind'] == 'support' and e['source_id'] == source and e['qual_status'] == 1]
        if len(ends) != 1 or ends[0]['ienable'] or not decoder.decoders[source].start_of_trace:
            raise SystemExit(f'actual Trace source {source} did not stop cleanly')
        extent = '已保留并可确定的退休PC' if stem != 'trace-system' else '全部退休PC'
        print(f'[top->decoder][2/3] hart={source} 经控制/数据CDC后的{extent}：' +
              ', '.join(hex(pc) for pc in actual), file=sys.stderr)
    if stem == 'trace-system-reset':
        losses = [e for e in events if e['kind'] == 'support' and e['qual_status'] == 2]
        if len(losses) != 1 or losses[0]['source_id'] != 0:
            raise SystemExit(f'source reset did not report exactly one source0 loss: {losses!r}')
    if stem == 'trace-system-funnel-reset':
        losses = [e for e in events if e['kind'] == 'support' and e['qual_status'] == 2]
        if sorted(e['source_id'] for e in losses) != [0, 1]:
            raise SystemExit(f'Funnel reset did not report one loss per source: {losses!r}')
    if stem == 'trace-system-mask' and any(e['kind'] == 'support' and e['qual_status'] == 2 for e in events):
        raise SystemExit('intentional input masking/restart must not fabricate reset loss')
    count = sum(e['kind'] == 'instruction' for e in events)
    print(f'[top->decoder][3/3] 真实Empty/AF结束后{count}条可确定退休指令与停止/丢失状态通过', file=sys.stderr)
    print(f'{stem} capture decoded successfully: {len(capture)} bytes, ' +
          str(sum(e['kind'] == 'instruction' for e in events)) + ' instructions')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation',
         sys.argv[2] if len(sys.argv) > 2 else 'trace-system')
