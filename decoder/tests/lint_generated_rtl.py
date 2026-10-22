"""Lint every generated profile and reject missing pins or truncated widths."""
import argparse
import json
from pathlib import Path
import subprocess
from decoder.artifacts import write_json
from decoder.capture_layout import CaptureLayout


def main(rtl, manifest, output):
    layout = CaptureLayout(output).create()
    logs = layout.dut / 'logs'
    logs.mkdir(exist_ok=True)
    profiles = json.loads(manifest.read_text())
    write_json(layout.workload / 'profiles.json', profiles)
    result = {'status': 'failed', 'profiles': {}}
    try:
        for profile in profiles:
            name = profile['configuration']
            directory = rtl / name
            command = ['verilator', '--lint-only', '--timing', '--top-module', 'Trace',
                       '-Wno-fatal', '-Werror-PINMISSING', '-Werror-PINCONNECTEMPTY',
                       '-Werror-WIDTHTRUNC', '--error-limit', '20', '-f', 'filelist.f']
            with (logs / f'{name}.log').open('w') as log:
                process = subprocess.run(command, cwd=directory, stdout=log, stderr=subprocess.STDOUT)
            result['profiles'][name] = {'status': 'passed' if process.returncode == 0 else 'failed',
                                        'return_code': process.returncode, 'command': command}
            print(f'[rtl-lint] {name}: {result["profiles"][name]["status"]}', flush=True)
        if any(p['status'] != 'passed' for p in result['profiles'].values()):
            raise ValueError('RTL lint failed; inspect per-profile logs')
        result['status'] = 'passed'
    except (OSError, ValueError) as exc:
        result['error'] = str(exc)
        raise
    finally:
        write_json(layout.diff / 'comparison.json', result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rtl', type=Path, default=Path('build/rtl'))
    parser.add_argument('--manifest', type=Path, default=Path('build/validation/rtl-profile-manifest.json'))
    parser.add_argument('--output', type=Path, default=Path('build/validation/rtl-lint'))
    args = parser.parse_args()
    main(args.rtl.resolve(), args.manifest.resolve(), args.output)
