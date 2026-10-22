"""Single-hart real component capture against independent wire and retired PCs."""
import json
from pathlib import Path
import sys
from contextlib import redirect_stdout
from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json
from decoder.tests.run_xiangshan_sba import Tee


def check(directory):
    root = Path(directory)
    logs = root / 'diff/logs'
    logs.mkdir(parents=True, exist_ok=True)
    has_golden = (root / 'ref/packet-golden.json').exists()
    result = {'status': 'failed', 'reference_kind': ('module stimulus and independent wire model, not ROB'
        if has_golden else 'offered PCs minus independently tested ingress-drop counter, not ROB')}
    observer = DecodeArtifacts(root, overwrite=True)
    failure = None
    try:
        observer.snapshot(root / 'dut/trace.bin', root / 'dut/trace.json')
        decoder = load_config(root / 'dut/trace.json', observer=observer)
        data = (root / 'dut/trace.bin').read_bytes()
        events = []
        for offset in range(0, len(data), 3):
            events.extend(decoder.feed(data[offset:offset + 3]))
        decoder.finish()
        actual = [e['pc'] for e in events if e['kind'] == 'instruction']
        expected = [number(pc) for pc in json.loads((root / 'ref/retired-pcs.json').read_text())]
        result.update(bytes=len(data), decoded_pcs=actual, expected_pcs=expected,
                      decoded_instructions=len(actual))
        with (root / 'diff/pc-matches.tsv').open('w') as output:
            output.write('index\texpected_pc\tactual_pc\tstatus\n')
            for index in range(max(len(actual), len(expected))):
                a = actual[index] if index < len(actual) else None
                e = expected[index] if index < len(expected) else None
                output.write(f'{index}\t{hex(e) if e is not None else ""}\t{hex(a) if a is not None else ""}\t'
                             f'{"matched" if a == e else "failed"}\n')
                if a != e:
                    result.setdefault('first_mismatch', {'index': index, 'expected_pc': e, 'actual_pc': a})
        if actual != expected:
            raise ValueError(f'acceptance retirement mismatch: {result["first_mismatch"]}')
        if any(pc <= 0x80000000 for pc in actual):
            raise ValueError('acceptance retired PCs must be strictly above 0x80000000')
        packets = [json.loads(line) for line in (root / 'dut/packets.jsonl').read_text().splitlines()]
        golden_path = root / 'ref/packet-golden.json'
        if golden_path.exists() and [p['raw_hex'] for p in packets] != json.loads(golden_path.read_text()):
            raise ValueError('actual SRAM packets differ from the independent wire model')
        result['packet_formats'] = sorted({f'{p["fields"]["format"]}' +
            (str(p['fields']['subformat']) if p['fields']['format'] == 3 else '') for p in packets})
        result['support_statuses'] = [e['qual_status'] for e in events if e['kind'] == 'support']
        contract = {'controls': [0, 1, 1, 1, 0, 1, 1], 'stall': [0, 1], 'overflow': [0, 2, 1]}
        if root.name in contract and result['support_statuses'] != contract[root.name]:
            raise ValueError(f'acceptance support sequence differs: {result["support_statuses"]}')
        if any(e['source_id'] != 0 or e['hart_id'] != 0 for e in events):
            raise ValueError('acceptance source mapping differs')
        if root.name == 'controls':
            if [e['pc'] for e in events if e['kind'] == 'exception_pc'] != [0x8000200c]:
                raise ValueError('acceptance exception EPC differs')
            traps = [(e['ecause'], e['interrupt'], e['handler']) for e in events if e['kind'] == 'trap']
            if traps != [(7, True, True), (11, False, True)]:
                raise ValueError(f'acceptance trap semantics differ: {traps}')
        if not decoder.decoders[0].start_of_trace:
            raise ValueError('acceptance segment was not terminated')
        result.update(status='passed', packets=len(packets))
        basis = '独立wire/指令参考' if has_golden else '输入PC与拒收计数交叉核对'
        print(f'[single-acceptance->decoder] {len(data)}bytes/{len(packets)}packets/{len(actual)}条退休PC与{basis}一致')
        return result
    except (ValueError, OSError, KeyError, TypeError) as exc:
        failure = exc
        result['error'] = str(exc)
        raise
    finally:
        observer.finish(failure)
        write_json(root / 'diff/comparison.json', result)


def main(directory):
    root = Path(directory)
    logs = root / 'diff/logs'
    logs.mkdir(parents=True, exist_ok=True)
    with (logs / 'check.log').open('w') as output, redirect_stdout(Tee(sys.stdout, output)):
        check(root)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation/single-hart-acceptance/controls')
