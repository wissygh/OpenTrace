"""Decode real implicit-return RTL captures, retaining every recursive PC repeat."""
import argparse
from contextlib import redirect_stdout
import json
from pathlib import Path
import sys

from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json
from decoder.tests.run_xiangshan_sba import Tee


def check(root):
    root = Path(root)
    result = {'status': 'failed', 'error': None}
    observer = None
    failure = None
    try:
        expected = [number(v) for v in json.loads((root / 'ref/expected.json').read_text())]
        data = (root / 'dut/trace.bin').read_bytes()
        observer = DecodeArtifacts(root, overwrite=True)
        observer.snapshot(root / 'dut/trace.bin', root / 'dut/trace.json')
        decoder = load_config(root / 'dut/trace.json', observer=observer)
        events = []
        for offset in range(0, len(data), 3):
            events.extend(decoder.feed(data[offset:offset+3]))
        decoder.finish()
        actual = [e['pc'] for e in events if e['kind'] == 'instruction']
        for i, (a, b) in enumerate(zip(actual, expected)):
            if a != b:
                raise ValueError(f'first retirement mismatch #{i}: actual={a:#x}, expected={b:#x}')
        if actual != expected:
            raise ValueError(f'retirement count mismatch {len(actual)} != {len(expected)}')
        if not all(d.start_of_trace for d in decoder.decoders.values()):
            raise ValueError('trace segment did not end')
        metadata = root / 'dut/metadata.json'
        if metadata.exists():
            m = json.loads(metadata.read_text())
            if m['features_readback'] & 0xc0:
                raise ValueError('E-Trace ImRetMode must be hardcoded to zero')
            requested = m['features_requested']
            if m['features_readback'] & 0x3b != requested:
                raise ValueError('encoder feature readback differs from the requested mode')
            expected_options = ((requested & 1) << 5) | ((requested & 2) << 1) | ((requested & 8) >> 2) | ((requested & 0x30) >> 1)
            if any(e['options'] != expected_options for e in events if e['kind'] == 'support'):
                raise ValueError('support options disagree with the actual encoder features')
            if m['dropped'] or m['loss_markers'] or m['wp'] != m['rp'] or m['wp'] != len(data):
                raise ValueError('SRAM loss or final pointer/byte count mismatch')
        result.update(status='passed', stored_bytes=len(data), decoded_instructions=len(actual), decoded_pcs=actual)
        print(f'[implicit-return->decoder] {root}: {len(actual)}条全部退休逐条一致，停止完整', flush=True)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        failure = exc; result['error'] = str(exc)
        raise
    finally:
        if observer is not None: observer.finish(failure)
        write_json(root / 'diff/comparison.json', result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', type=Path, default=Path('build/validation/implicit-return'))
    args = parser.parse_args(argv)
    captures = sorted({p.parent.parent for p in args.directory.rglob('dut/trace.json')})
    if not captures: raise RuntimeError('no actual RTL captures found')
    write_json(args.directory / 'summary.json', {'status': 'failed', 'error': 'validation incomplete'})
    results = []
    for root in captures:
        (root / 'diff/logs').mkdir(exist_ok=True, parents=True)
        with (root / 'diff/logs/check.log').open('w') as output, redirect_stdout(Tee(sys.stdout, output)):
            results.append(check(root))
    write_json(args.directory / 'summary.json', {'status': 'passed', 'captures': len(results),
        'decoded_instructions': sum(r['decoded_instructions'] for r in results)})


if __name__ == '__main__':
    main()
