"""Validate the capture produced by trace.MultiHartTraceSpec with the real decoder."""
from pathlib import Path
import sys
from . import ExecutionDecoder, MultiHartDecoder, ProgramImage


def main(path):
    source = {}
    for sid, base in [(0, 0x1000), (1, 0x8000)]:
        image = ProgramImage.from_raw(b'\x13\x00\x00\x00' * 4, base)
        source[sid] = ExecutionDecoder(image, source_id=sid, hart_id=sid)
    decoder = MultiHartDecoder(source)
    events = decoder.feed(Path(path).read_bytes())
    decoder.finish()
    actual = {sid: [e['pc'] for e in events if e['source_id'] == sid and e['kind'] == 'instruction'] for sid in source}
    expected = {0: [0x1000], 1: [0x8000]}
    if actual != expected:
        raise SystemExit(f'RTL/decoder mismatch: {actual!r} != {expected!r}')
    print(f'RTL capture decoded successfully: {actual}')


if __name__ == '__main__':
    main(sys.argv[1])
