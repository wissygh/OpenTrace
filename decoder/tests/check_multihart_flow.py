"""Check complete dual-hart execution captured from the real shared SRAM path."""
import sys
from pathlib import Path

from decoder.__main__ import load_config


def main(directory='build/validation', stem='two-hart-execution'):
    directory = Path(directory)
    label = 'SMEM' if stem == 'two-hart-smem' else 'SRAM'
    print(f'[{label}->decoder][1/4] 读取双核 {label} 采集、两份 raw 镜像与 srcID 配置', file=sys.stderr)
    decoder = load_config(directory / f'{stem}.json')
    data = (directory / f'{stem}.bin').read_bytes()
    events = []
    for offset in range(0, len(data), 7):
        events.extend(decoder.feed(data[offset:offset + 7]))
    decoder.finish()
    expected = {
        0: [0x1000, 0x1004, 0x1000, 0x1004, 0x1008, 0x100c, 0x2000, 0x2004, 0x2006, 0xff0, 0xff4],
        1: [0x8000, 0x9000, 0x9004, 0xa000, 0xa004, 0xa008, 0xb000, 0xb004],
    }
    print(f'[{label}->decoder][2/4] 分发两路完整片段：循环/调用/压缩返回及 trap/interrupt/context', file=sys.stderr)
    for source, pcs in expected.items():
        instructions = [e for e in events if e['kind'] == 'instruction' and e['source_id'] == source]
        actual = [e['pc'] for e in instructions]
        if actual != pcs or any(e['hart_id'] != source for e in instructions):
            raise SystemExit(f'hart {source} execution/source mismatch: {actual!r} != {pcs!r}')
        print(f'[{label}->decoder][3/4] hart={source} 全部 {len(pcs)} 条退休 PC 一致：' +
              ', '.join(hex(pc) for pc in pcs), file=sys.stderr)
    epcs = [e['pc'] for e in events if e['kind'] == 'exception_pc' and e['source_id'] == 1]
    traps = [e['ecause'] for e in events if e['kind'] == 'trap' and e['source_id'] == 1]
    contexts = [e['context'] for e in events if e['kind'] == 'instruction' and e['source_id'] == 1]
    if epcs != [0x8004] or traps != [2, 7] or contexts != [0, 0, 0, 1, 1, 1, 2, 2]:
        raise SystemExit(f'trap/context mismatch: EPC={epcs!r}, causes={traps!r}, contexts={contexts!r}')
    for source, count in ((0, 1), (1, 2)):
        ends = [e for e in events if e['kind'] == 'support' and e['source_id'] == source and e['qual_status'] == 1]
        if len(ends) != count or not decoder.decoders[source].start_of_trace:
            raise SystemExit(f'hart {source} did not finish all trace segments')
    print(f'[{label}->decoder][4/4] EPC 未记为退休指令；两个 cause、异步 context 边界与结束状态一致', file=sys.stderr)
    print(f'{stem} execution decoded successfully: {len(data)} bytes, 19 retired instructions')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation',
         sys.argv[2] if len(sys.argv) > 2 else 'two-hart-execution')
