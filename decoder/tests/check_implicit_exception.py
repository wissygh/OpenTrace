"""Validate real RTL trap fields, handler inference and all retired PCs."""
import argparse
import json
from pathlib import Path

from decoder.artifacts import write_json
from decoder.tests.check_implicit_return import check as check_retirement


def check(root):
    root = Path(root)
    result = {'status': 'failed', 'error': 'validation incomplete'}
    try:
        result = check_retirement(root)
        result['status'] = 'failed'
        mode = json.loads((root / 'ref/mode.json').read_text())['features']
        expected = json.loads((root / 'ref/traps.json').read_text())
        packets = [json.loads(line)['fields'] for line in (root / 'dut/packets.jsonl').read_text().splitlines()]
        traps = [p for p in packets if (p['format'], p.get('subformat')) == (3, 1)]
        if len(traps) != len(expected):
            raise ValueError('trap packet count differs')
        omitted = 0
        for actual, reference in zip(traps, expected):
            for field in ('ecause', 'interrupt', 'thaddr'):
                if actual[field] != reference[field]:
                    raise ValueError('trap field differs: ' + field)
            if actual['interrupt']:
                if 'tval' in actual:
                    raise ValueError('interrupt must omit tval')
            elif actual['tval'] != int(reference['tval'], 0):
                raise ValueError('tval changed or retained the omitted address gap')
            should_omit = bool(mode & 2 and actual['thaddr'])
            if (actual['address'] is None) != should_omit:
                raise ValueError('only enabled thaddr=1 may omit the address')
            omitted += should_omit
        options = ((mode & 1) << 5) | ((mode & 2) << 1) | ((mode & 8) >> 2) | ((mode & 0x30) >> 1)
        if any(p['options'] != options for p in packets if (p['format'], p.get('subformat')) == (3, 3)):
            raise ValueError('support options differ from the captured mode')
        result.update(status='passed', mode=mode, trap_packets=len(traps), omitted_handler_addresses=omitted)
        print(f'[implicit-exception->decoder] {root}: mode={mode}, {len(traps)} traps, '
              f'{omitted} omitted handlers; full retirement and tval verified', flush=True)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result.update(status='failed', error=str(exc))
        raise
    finally:
        write_json(root / 'diff/comparison.json', result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', type=Path, default=Path('build/validation/implicit-exception'))
    args = parser.parse_args(argv)
    captures = sorted({p.parent.parent for p in args.directory.rglob('dut/trace.json')})
    if not captures:
        raise ValueError('no real RTL captures found')
    write_json(args.directory / 'summary.json', {'status': 'failed', 'error': 'validation incomplete'})
    results = [check(root) for root in captures]
    write_json(args.directory / 'summary.json', {'status': 'passed', 'captures': len(results),
        'retired_instructions': sum(r['decoded_instructions'] for r in results),
        'trap_packets': sum(r['trap_packets'] for r in results),
        'omitted_handler_addresses': sum(r['omitted_handler_addresses'] for r in results)})


if __name__ == '__main__':
    main()
