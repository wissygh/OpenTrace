"""Validate the capture produced by trace.MultiHartTraceSpec with the real decoder."""
from pathlib import Path
import sys
from . import ExecutionDecoder, MultiHartDecoder, ProgramImage


def log(message):
    print(f"[RTL->decoder] {message}", file=sys.stderr)


def main(path):
    capture = Path(path)
    log(f"1/5 读取 RTL 生成的采集文件：{capture}")
    data = capture.read_bytes()
    log(f"1/5 采集文件大小：{len(data)} bytes")
    source = {}
    for sid, base in [(0, 0x1000), (1, 0x8000)]:
        log(f"2/5 为 srcID={sid} 建立 raw 指令镜像，基地址=0x{base:x}")
        image = ProgramImage.from_raw(b'\x13\x00\x00\x00' * 4, base)
        source[sid] = ExecutionDecoder(image, source_id=sid, hart_id=sid)
    log("3/5 创建 MultiHartDecoder：一个封装解析器，两个独立的 hart 执行流状态机")
    decoder = MultiHartDecoder(source)
    log("4/5 交付封装字节流：解析 packet 边界，提取 srcID，并分发到对应 decoder")
    events = decoder.feed(data)
    decoder.finish()
    log(f"4/5 decoder 完成：输出 {len(events)} 个执行事件")
    actual = {sid: [e['pc'] for e in events if e['source_id'] == sid and e['kind'] == 'instruction'] for sid in source}
    expected = {0: [0x1000], 1: [0x8000]}
    for sid in source:
        actual_pcs = ', '.join(f'0x{pc:x}' for pc in actual[sid])
        expected_pcs = ', '.join(f'0x{pc:x}' for pc in expected[sid])
        log(f"5/5 srcID={sid} hart={sid}：实际 PC=[{actual_pcs}]，预期 PC=[{expected_pcs}]")
    if actual != expected:
        raise SystemExit(f'RTL/decoder mismatch: {actual!r} != {expected!r}')
    print(f'RTL capture decoded successfully: {actual}')


if __name__ == '__main__':
    main(sys.argv[1])
