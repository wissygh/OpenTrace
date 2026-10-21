"""Check bytes captured after the actual Trace top's control and ATB crossings."""
import sys
from pathlib import Path

from decoder.__main__ import load_config


def main(directory='build/validation'):
    directory = Path(directory)
    print('[top->decoder][1/3] 读取真实TL配置/四时钟Trace顶层的ATB采集', file=sys.stderr)
    decoder = load_config(directory / 'trace-system.json')
    capture = (directory / 'trace-system.bin').read_bytes()
    events = []
    for offset in range(0, len(capture), 5):
        events.extend(decoder.feed(capture[offset:offset + 5]))
    decoder.finish()
    for source, base in ((0, 0x1000), (1, 0x8000)):
        expected = [base + 4 * i for i in range(4)]
        instructions = [e for e in events if e['kind'] == 'instruction' and e['source_id'] == source]
        actual = [e['pc'] for e in instructions]
        if actual != expected or any(e['hart_id'] != source for e in instructions):
            raise SystemExit(f'actual Trace source {source} PC mismatch: {actual!r} != {expected!r}')
        ends = [e for e in events if e['kind'] == 'support' and e['source_id'] == source and e['qual_status'] == 1]
        if len(ends) != 1 or ends[0]['ienable'] or not decoder.decoders[source].start_of_trace:
            raise SystemExit(f'actual Trace source {source} did not stop cleanly')
        print(f'[top->decoder][2/3] hart={source} 经控制/数据CDC后的全部退休PC：' +
              ', '.join(hex(pc) for pc in actual), file=sys.stderr)
    print('[top->decoder][3/3] 真实Empty/AF结束后采集的8条完整退休指令与停止状态通过', file=sys.stderr)
    print(f'actual Trace top capture decoded successfully: {len(capture)} bytes, 8 instructions')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation')
