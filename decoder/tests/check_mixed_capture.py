"""Cross-format RTL/SRAM evidence; reference PCs come from module stimulus."""
import json
from pathlib import Path
import sys
from contextlib import redirect_stdout
from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json
from decoder.tests.run_xiangshan_sba import Tee


REQUIRED = {'format00-no-address', 'format00-address', 'format00-address-fail',
            'format01-with-map', 'format01-without-map', 'format1-with-map', 'format1-map-only',
            'format2', 'format30', 'format31', 'format32', 'format33'}


def check(directory):
    root = Path(directory)
    dut, ref, diff = root / 'dut', root / 'ref', root / 'diff'
    diff.mkdir(exist_ok=True)
    result = {'status': 'failed', 'reference_kind': 'module stimulus, not independent ROB', 'error': None}
    observer = DecodeArtifacts(root, overwrite=True)
    failure = None
    try:
        observer.snapshot(dut / 'trace.bin', dut / 'trace.json')
        decoder = load_config(dut / 'trace.json', observer=observer)
        data = (dut / 'trace.bin').read_bytes()
        events = []
        for offset in range(0, len(data), 3):
            events.extend(decoder.feed(data[offset:offset + 3]))
        decoder.finish()
        expected = [number(pc) for pc in json.loads((ref / 'retired-pcs.json').read_text())]
        actual = [event['pc'] for event in events if event['kind'] == 'instruction']
        image_base = number(json.loads((dut / 'trace.json').read_text())['sources']['3']['base'])
        result['instruction_address_range'] = {'minimum_pc': hex(min(actual)), 'maximum_pc': hex(max(actual)),
                                               'strictly_above': '0x80000000'}
        if any(pc <= 0x80000000 for pc in (*actual, *expected)):
            raise ValueError('mixed-format instruction PCs must be strictly above 0x80000000')
        result.update(bytes=len(data), retired_instructions=len(actual), decoded_pcs=actual, expected_pcs=expected)
        with (diff / 'pc-matches.tsv').open('w') as output:
            output.write('index\tstimulus_pc\tdecoded_pc\tstatus\n')
            for index in range(max(len(actual), len(expected))):
                a = actual[index] if index < len(actual) else None
                e = expected[index] if index < len(expected) else None
                output.write(f'{index}\t{hex(e) if e is not None else ""}\t{hex(a) if a is not None else ""}\t'
                             f'{"matched" if a == e else "failed"}\n')
                if a != e:
                    result.setdefault('first_mismatch', {'index': index, 'expected_pc': e, 'actual_pc': a})
        if actual != expected:
            raise ValueError(f'mixed-format retirement mismatch: {result["first_mismatch"]}')
        if any(event['source_id'] != 3 or event['hart_id'] != 7 for event in events):
            raise ValueError('unexpected source mapping')
        traps = [event for event in events if event['kind'] == 'trap']
        if [(e['ecause'], e['interrupt'], e['handler']) for e in traps] != [(7, True, True), (11, False, False)]:
            raise ValueError(f'mixed trap event mismatch: {traps}')
        epcs = [e['pc'] for e in events if e['kind'] == 'exception_pc']
        exception_pc = image_base + 0x5000
        if epcs != [exception_pc] or exception_pc in actual:
            raise ValueError(f'non-retired exception PC mismatch: {epcs}')
        contexts = [e for e in events if e['kind'] == 'context']
        if len(contexts) != 1 or contexts[0]['context'] != 2 or contexts[0]['time'] != 0x1234:
            raise ValueError('imprecise context/time event missing')
        supports = [e['qual_status'] for e in events if e['kind'] == 'support']
        if supports != [0, 1, 1, 2, 1] or not decoder.decoders[3].start_of_trace:
            raise ValueError(f'trace lifecycle mismatch: {supports}')
        coverage = {}
        packets = [json.loads(line) for line in (dut / 'packets.jsonl').read_text().splitlines()]
        golden = json.loads((ref / 'packet-golden.json').read_text())
        if [packet['raw_hex'] for packet in packets] != golden:
            raise ValueError('SRAM packet bytes differ from independent wire golden')
        for packet in packets:
            fields = packet['fields']
            fmt = fields['format']
            if fmt == 0 and fields['subformat'] == 0:
                bf = next(f['value'] for f in packet['wire_fields'] if f['name'] == 'branch_fmt')
                name = {0: 'format00-no-address', 2: 'format00-address', 3: 'format00-address-fail'}[bf]
            elif fmt == 0:
                name = 'format01-with-map' if fields['branches'] else 'format01-without-map'
            elif fmt == 1:
                name = 'format1-with-map' if fields['branches'] else 'format1-map-only'
            else:
                name = f'format3{fields["subformat"]}' if fmt == 3 else 'format2'
            coverage.setdefault(name, []).append(packet['packet_index'])
        result['packet_coverage'] = coverage
        missing = REQUIRED - coverage.keys()
        if missing:
            raise ValueError(f'actual capture lacks packet variants: {sorted(missing)}')
        result.update(status='passed', packets=sum(len(rows) for rows in coverage.values()))
        print(f'[mixed-packets->decoder] {len(data)}bytes/{result["packets"]}packets/'
              f'{len(actual)}retired PCs matched at {min(actual):#x}..{max(actual):#x}; all {len(REQUIRED)} packet variants observed', flush=True)
        return result
    except (ValueError, OSError, KeyError) as exc:
        failure = exc
        result['error'] = str(exc)
        raise
    finally:
        observer.finish(failure)
        write_json(diff / 'comparison.json', result)


def main(directory):
    root = Path(directory)
    logs = root / 'diff/logs'; logs.mkdir(parents=True, exist_ok=True)
    with (logs / 'check.log').open('w') as output, redirect_stdout(Tee(sys.stdout, output)):
        if (root / 'dut/trace.bin').exists():
            return check(root)
        cases = sorted(root.glob('seed-*'))
        result = {'status': 'failed', 'cases': {}, 'reference_kind': 'module stimulus, not independent ROB'}
        try:
            if not cases:
                raise ValueError('no mixed-format capture cases found')
            for case in cases:
                result['cases'][case.name] = check(case)
            captures = [(case / 'dut/trace.bin').read_bytes() for case in cases]
            if any(capture != captures[0] for capture in captures):
                raise ValueError('backpressure changed the generated packet stream')
            result.update(status='passed', backpressure_invariant=True)
        except (ValueError, OSError, KeyError) as exc:
            result['error'] = str(exc)
            raise
        finally:
            write_json(root / 'diff/comparison.json', result)
        print(f'[mixed-packets] {len(cases)} seeded stalls produce byte-identical SRAM captures')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/validation/mixed-packets')
