"""Validate decoder configuration from a real encoder discovery CSR readback."""
import sys
from pathlib import Path
from decoder.__main__ import load_config
from decoder.artifacts import write_json
from decoder.discovery import read_snapshot


def main(directory):
    root = Path(directory)
    print('[Table42->decoder][1/2] 读取真实Encoder的80byte CSR快照和对应trace/镜像', file=sys.stderr)
    dut = root / 'dut'
    discovered = read_snapshot(dut / 'discovery.bin')
    assert list(discovered['attributes'].values()) == [0, 8, 8, 0, 31, 63, 7, 1, 0, 49, 0, 0, 2, 0, 0]
    decoder = load_config(dut / 'trace.json')
    events = []
    for byte in (dut / 'trace.bin').read_bytes(): events.extend(decoder.feed(bytes([byte])))
    decoder.finish()
    pcs = [e['pc'] for e in events if e['kind'] == 'instruction']
    assert pcs == [0x80001000, 0x80001004], pcs
    assert [(e['qual_status'], e['ienable']) for e in events if e['kind'] == 'support'] == [(0, 1), (1, 0)]
    assert decoder.decoders[0].start_of_trace
    write_json(root / 'diff/comparison.json', {'status': 'passed', 'decoded_instructions': len(pcs),
        'decoded_pcs': pcs, 'discovery': discovered, 'reference_kind': 'actual encoder CSR and independent expected PCs'})
    print('[Table42->decoder][2/2] 由发现参数恢复全部2条退休指令，起止状态正确', file=sys.stderr)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation/encoder-discovery')
