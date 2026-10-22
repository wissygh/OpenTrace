"""Move legacy run evidence into workload/dut/ref/diff without deleting files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from decoder.capture_layout import CaptureLayout


def organize(directory):
    root = Path(directory).resolve()
    if not root.is_dir():
        raise ValueError(f'not an artifact directory: {root}')
    # Organize separate runs first; suite-level logs belong to the outer run.
    for name in ('real-sba', 'prediction-report'):
        if (root / name).is_dir():
            organize(root / name)
    layout = CaptureLayout(root).create()
    if (root / 'workload/sba-workload.S').is_file():
        source = root / 'workload/source'; source.mkdir(exist_ok=True)
        for item in list(layout.workload.iterdir()):
            if item.is_file() and item.suffix in ('.S', '.ld', '.cfg'):
                move(item, source / item.name)
    known = {
        'workload': ('workload.bin', 'workload.elf', 'workload.disasm', 'workload.symbols.txt', 'symbols.json', 'inputs.json'),
        'dut': ('trace.bin', 'trace.json', 'metadata.json', 'run.json', 'sram-raw.hex', 'sram-words.jsonl',
                'framing.jsonl', 'packets.jsonl', 'events.jsonl', 'instructions.jsonl', 'instructions.tsv', 'decode-result.json'),
        'ref': ('rob.log', 'commit-pc.jsonl', 'commit-pc-workload.jsonl', 'commit-pc.tsv'),
        'diff': ('comparison.json', 'expected-pcs.json', 'pc-matches.tsv'),
    }
    # Keep duplicate historical snapshots explicitly instead of silently deleting them.
    if (root / 'decode').exists():
        for item in list((root / 'decode').iterdir()):
            if item.name == 'workload':
                for image in item.iterdir():
                    move(image, layout.workload / 'images' / image.name)
                item.rmdir()
            elif item.name == 'capture-config.json':
                move(item, layout.dut / 'legacy-config.json')
            elif item.name == 'inputs.json':
                move(item, layout.workload / 'inputs.json')
            elif item.name == 'capture.bin':
                move(item, layout.dut / 'legacy-capture.bin')
            else:
                move(item, layout.dut / item.name)
        (root / 'decode').rmdir()
    for category, names in known.items():
        for name in names:
            source = root / name
            if source.exists():
                target = getattr(layout, category) / name
                if target.exists():
                    target = target.with_name('legacy-' + name)
                move(source, target)
    if (root / 'capture.bin').exists():
        move(root / 'capture.bin', layout.dut / 'trace.bin')
    if (root / 'capture-config.json').exists():
        move(root / 'capture-config.json', layout.dut / 'trace.json')
    for item in list(root.iterdir()):
        if not item.is_file():
            continue
        if item.name == 'prediction-events.jsonl':
            move(item, layout.dut / 'legacy/prediction-events.jsonl')
        elif item.suffix == '.log' or item.name == 'rpc.jsonl':
            if item.name.startswith(('real-sba-run', 'scala-')) or item.name in ('uart.log', 'openocd.log', 'rpc.jsonl'):
                target = layout.dut / 'logs' / item.name
            else:
                target = layout.diff / 'logs' / item.name
            move(item, target)
        else:
            raise ValueError(f'unknown artifact needs an explicit category: {item}')
    # Rebase active configs onto the moved images. Historical logs/run commands
    # retain original values; they describe the past execution, not a new run.
    for name in ('trace.json', 'legacy-config.json'):
        config = layout.dut / name
        if not config.exists():
            continue
        data = json.loads(config.read_text())

        def rebase(description):
            for field in ('elf', 'raw'):
                if field not in description:
                    continue
                old = Path(description[field])
                candidates = [config.parent / old, layout.workload / old.name, layout.workload / 'images' / old.name]
                image = next((path.resolve() for path in candidates if path.is_file()), None)
                if image is None:
                    raise ValueError(f'cannot locate moved image: {old}')
                description[field] = Path(os.path.relpath(image, config.parent)).as_posix()
            for context in description.get('contexts', {}).values():
                rebase(context)

        for description in data['sources'].values():
            rebase(description)
        config.write_text(json.dumps(data, indent=2) + '\n')


def move(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise ValueError(f'refusing to overwrite existing evidence: {target}')
    before = hashlib.sha256(source.read_bytes()).digest()
    source.rename(target)
    if hashlib.sha256(target.read_bytes()).digest() != before:
        raise ValueError(f'artifact changed while moving: {source}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    organize(args.directory)
    print(f'Organized {args.directory}: workload / dut / ref / diff')
