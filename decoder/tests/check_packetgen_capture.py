"""Compare a real PacketGen capture with the retirement prefix through its last anchor.

The final RTL input at 0xff4 supplies lookahead; it is beyond that anchor and is
not included in the expected decoded prefix. This is not a trace-end test.
"""
import json
from pathlib import Path
import sys
from decoder.__main__ import load_config


def main(directory):
    directory = Path(directory)
    print('[encoder->decoder][1/3] 读取 RTL 采集与对应 raw 指令镜像', file=sys.stderr)
    decoder = load_config(directory / 'packetgen-execution.json')
    capture = (directory / 'packetgen-execution.bin').read_bytes()
    events = []
    # Deliberately cut packets and multibyte signed fields across input chunks.
    for offset in range(0, len(capture), 3):
        events.extend(decoder.feed(capture[offset:offset + 3]))
    decoder.finish()
    print('[encoder->decoder][2/3] 解析循环、taken/non-taken、间接调用、压缩指令和返回', file=sys.stderr)
    instructions = [event for event in events if event['kind'] == 'instruction']
    actual = [event['pc'] for event in instructions]
    expected = json.loads((directory / 'packetgen-execution-expected.json').read_text())
    if actual != expected:
        raise SystemExit(f'PacketGen/decoder PC mismatch: {actual!r} != {expected!r}')
    if any(event['source_id'] != 3 or event['hart_id'] != 7 for event in instructions):
        raise SystemExit('PacketGen/decoder source mapping mismatch')
    print(f'[encoder->decoder][3/3] 至最后 trace 锚点的 {len(actual)} 条指令与退休序列前缀逐条一致：' +
          ', '.join(f'0x{pc:x}' for pc in actual), file=sys.stderr)
    print(f'PacketGen execution capture decoded successfully: {len(actual)} instructions')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation')
