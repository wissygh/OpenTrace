"""python3 -m decoder CAPTURE --config CONFIG [--output JSONL] [--acquire-sync]"""
import argparse
import json
from pathlib import Path
import sys
from .encapsulation import DecodeError, PacketParser, SyncAcquirer
from .execution import ExecutionDecoder, Profile
from .image import ProgramImage
from .multihart import MultiHartDecoder
from .artifacts import DecodeArtifacts, readable


def number(value):
    return int(value, 0) if isinstance(value, str) else int(value)


def load_config(path, acquire=False, observer=None):
    path = Path(path)
    config = json.loads(path.read_text())
    decoders = {}
    harts = set()
    source_bits = config.get('source_bits', 8)

    def image(description):
        if 'elf' in description:
            return ProgramImage.from_elf(path.parent / description['elf'], number(description.get('load_bias', 0)))
        if 'raw' in description and 'base' in description:
            return ProgramImage.from_raw((path.parent / description['raw']).read_bytes(),
                                         number(description['base']), description.get('xlen', 64))
        raise DecodeError('each source/context needs elf, or raw plus base')

    for source, description in config['sources'].items():
        source = number(source)
        hart = number(description['hart_id'])
        if source in decoders or hart in harts or source < 0 or source >= (1 << source_bits if source_bits else 1):
            raise DecodeError('duplicate or out-of-range srcID/hart mapping')
        harts.add(hart)
        profile = dict(config.get('profile', {}))
        profile.update(description.get('profile', {}))
        contexts = {number(k): image(v) for k, v in description.get('contexts', {}).items()}
        vectors = {number(k): number(v) for k, v in description.get('trap_vectors', {}).items()}
        decoders[source] = ExecutionDecoder(image(description), Profile(**profile), source, hart, contexts, vectors)
    parser = PacketParser(source_bits, config.get('header_timestamp_bytes', 0), observer=observer)
    return MultiHartDecoder(decoders, SyncAcquirer(parser) if acquire else parser, observer)


def main(argv=None):
    parser = argparse.ArgumentParser(description='Decode packet-atomic multi-hart RISC-V E-Trace captures')
    parser.add_argument('capture', type=Path)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--acquire-sync', action='store_true', help='scan for encapsulation synchronization first')
    parser.add_argument('--artifacts', type=Path, help='save raw capture, image snapshots, packet fields and instruction flow')
    args = parser.parse_args(argv)
    output = None
    artifacts = None
    failure = None
    try:
        if args.artifacts:
            artifacts = DecodeArtifacts(args.artifacts)
            artifacts.snapshot(args.capture, args.config)
        decoder = load_config(args.config, args.acquire_sync, artifacts)
        output = args.output.open('w') if args.output else sys.stdout
        with args.capture.open('rb') as capture:
            while data := capture.read(4096):
                for event in decoder.feed(data):
                    output.write(json.dumps(readable(event), sort_keys=True) + '\n')
        decoder.finish()
        return 0
    except (DecodeError, OSError, KeyError, TypeError, ValueError) as exc:
        failure = exc
        print(f'opentrace decoder: {exc}', file=sys.stderr)
        return 1
    finally:
        if artifacts is not None:
            artifacts.finish(failure)
        if output is not None and output is not sys.stdout:
            output.close()


if __name__ == '__main__':
    raise SystemExit(main())
