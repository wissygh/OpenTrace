"""Check the actual single-hart Trace captured through SRAM control reads."""
import sys
from pathlib import Path

from decoder.__main__ import load_config


def main(directory='build/validation'):
    directory = Path(directory)
    print('[single-SRAM->decoder][1/3] 读取真实单核top SRAM捕获及对应raw指令镜像', file=sys.stderr)
    decoder = load_config(directory / 'single-hart-sram.json')
    data = (directory / 'single-hart-sram.bin').read_bytes()
    events = []
    for offset in range(0, len(data), 3):
        events.extend(decoder.feed(data[offset:offset + 3]))
    decoder.finish()
    expected = [0x1000, 0x1004, 0x1000, 0x1004, 0x1008, 0x100c, 0x2000, 0x2004, 0x1010, 0x1014]
    instructions = [event for event in events if event['kind'] == 'instruction']
    if [event['pc'] for event in instructions] != expected:
        raise SystemExit(f'single-hart SRAM retirement mismatch: {instructions!r}')
    if any(event['source_id'] != 0 or event['hart_id'] != 0 for event in events):
        raise SystemExit('single-hart SRAM capture contains another source')
    support = [event for event in events if event['kind'] == 'support']
    if [event['qual_status'] for event in support] != [0, 1] or support[-1]['ienable']:
        raise SystemExit(f'single-hart SRAM session did not end cleanly: {support!r}')
    if not decoder.decoders[0].start_of_trace:
        raise SystemExit('decoder retained an open execution segment')
    print('[single-SRAM->decoder][2/3] 10条退休PC逐条一致：' + ', '.join(map(hex, expected)), file=sys.stderr)
    print('[single-SRAM->decoder][3/3] 源号/分支/间接调用/压缩返回/停止匹配；香山实核emu仍待编译运行', file=sys.stderr)
    print(f'single-hart SRAM decoded successfully: {len(data)} bytes, {len(instructions)} instructions')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation')
