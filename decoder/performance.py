"""Measure SRAM storage per dynamic retired instruction from checked captures.

The numerator includes every stored byte, including encapsulation and nulls.
Replay the raw stream rather than dividing by a partial or compressed ROB count.
This command never rewrites the input capture or its decoder evidence.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sys

from .__main__ import load_config, number
from .artifacts import write_json
from .capture_layout import CaptureLayout
from .encapsulation import PacketParser


METHOD = {
    'numerator': '8 * len(dut/trace.bin); all stored payload, encapsulation, sync and null bytes',
    'denominator': 'dynamic instruction events from complete replay, checked against saved retirement evidence',
    'counting': 'count repeated PCs each time; exclude exception_pc, context and support events',
    'loss_policy': 'lossy captures only report cost per retained instruction; no lossless bits_per_instruction',
    'invalid_policy': 'failed, incomplete, stale or unverified captures have no rate',
    'scope': 'captured trace-covered instructions, not filtered-out or untraced workload instructions',
    'aggregation': 'report each workload separately; no average across unrelated synthetic workloads',
}


def _json(path):
    return json.loads(Path(path).read_text())


def _digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _signature(event):
    return (number(event['source_id']), number(event['hart_id']), number(event['pc']),
            number(event['instruction']), number(event['size']))


def _check_storage_metadata(metadata, data):
    if not metadata:
        return
    storage = metadata.get('storage', metadata)
    if metadata.get('capture_method') == 'all physical SRAM writes plus SBA tail':
        # A complete observed ring write stream can be much longer than its
        # final modulo pointer. The physical-port checker verifies every write;
        # also tie the measurement to its actual pointer and SBA tail snapshot.
        if not data or len(data) % 4:
            raise ValueError('circular SRAM stream is empty or not word aligned')
        words = len(data) // 4
        wp = number(storage['wp_register'])
        if (wp & ~3) != (words % 256) * 4 or bool(wp & 1) != (words >= 256):
            raise ValueError('circular SRAM WP/Wrap disagrees with full stored bytes')
        if storage['sample_start_word'] != (words - 16) % 256 or storage['rp_after_sample'] != wp & ~3:
            raise ValueError('circular SRAM SBA sample pointers disagree')
        expected = [int.from_bytes(data[i:i + 4], 'little') for i in range(len(data) - 64, len(data), 4)]
        if len(data) < 64 or storage['sample_words'] != expected:
            raise ValueError('circular SRAM bytes differ from the actual SBA tail')
    elif number(storage['wp']) != len(data) or number(storage['rp']) != len(data):
        raise ValueError('SRAM WP/RP do not match the complete captured byte count')
    for hart in metadata.get('harts', {}).values():
        if hart.get('dropped') or hart.get('loss_markers'):
            raise ValueError('a source reported loss in the shared SRAM capture')


class _Frames:
    def __init__(self, source_bytes, timestamp_bytes):
        self.source_bytes = source_bytes
        self.timestamp_bytes = timestamp_bytes
        self.bytes = Counter()
        self.formats = defaultdict(Counter)
        self.sources = Counter()

    def frame(self, offset, raw, packet):
        if packet is None:
            self.bytes['null_bytes'] += len(raw)
            return
        self.bytes['header_bytes'] += 1
        self.bytes['source_id_bytes'] += self.source_bytes
        self.bytes['header_timestamp_bytes'] += self.timestamp_bytes if packet.timestamp is not None else 0
        self.bytes['payload_bytes'] += len(packet.payload)
        fmt = packet.payload[0] & 3
        label = f'Format3{(packet.payload[0] >> 2) & 3}' if fmt == 3 else f'Format{fmt}'
        self.formats[label]['packets'] += 1
        self.formats[label]['stored_bytes'] += len(raw)
        self.formats[label]['payload_bytes'] += len(packet.payload)
        self.sources[packet.source_id] += len(raw)


def measure(directory):
    """Return an eligible measurement, a clearly labelled lossy cost, or exclusion."""
    layout = CaptureLayout(directory)
    result = {'directory': str(layout.root.resolve()), 'status': 'excluded', 'reason': None,
              'stored_bytes': None, 'stored_bits': None, 'retired_instructions': None,
              'bits_per_instruction': None, 'bits_per_retained_instruction': None, 'input_sha256': {}}
    try:
        capture = layout.input('dut', 'trace.bin')
        config_path = layout.input('dut', 'trace.json')
        comparison_path = layout.input('diff', 'comparison.json')
        decode_path = layout.input('dut', 'decode-result.json')
        instruction_path = layout.input('dut', 'instructions.jsonl')
        data = capture.read_bytes()
        result.update(stored_bytes=len(data), stored_bits=len(data) * 8)
        for path in (capture, config_path, comparison_path, decode_path, instruction_path):
            result['input_sha256'][str(path.resolve())] = _digest(path)
        comparison, decoded = _json(comparison_path), _json(decode_path)
        result['validation'] = {
            'comparison_status': comparison['status'], 'decode_status': decoded['status'],
            'reference_kind': comparison.get('reference_kind', 'independent ROB plus fixed workload path'
                if comparison.get('rob_samples') else 'capture checker'),
            'rob_samples': comparison.get('rob_samples'),
        }
        if comparison['status'] != 'passed' or decoded['status'] != 'passed':
            raise ValueError(comparison.get('error') or decoded.get('error') or 'capture validation did not pass')
        config = _json(config_path)
        decoder = load_config(config_path)
        framing = decoder.parser
        frames = _Frames(framing.source_bytes, framing.timestamp_bytes)
        parser = PacketParser(framing.source_bytes * 8, framing.timestamp_bytes, observer=frames)
        for offset in range(0, len(data), 4096):
            parser.feed(data[offset:offset + 4096])
        parser.finish()
        if sum(frames.bytes.values()) != len(data):
            raise ValueError('framing byte accounting does not cover the entire SRAM capture')
        result['storage_breakdown'] = dict(frames.bytes)
        result['packet_formats'] = dict(sorted(frames.formats.items()))

        # Reconstruct all instructions from the actual bytes and program images.
        events = []
        for offset in range(0, len(data), 4096):
            events.extend(decoder.feed(data[offset:offset + 4096]))
        decoder.finish()
        if any(not d.start_of_trace for d in decoder.decoders.values()):
            raise ValueError('execution segment is not terminated; its retirement tail is unknown')
        instructions = [e for e in events if e['kind'] == 'instruction']
        saved = [json.loads(line) for line in instruction_path.read_text().splitlines() if line.strip()]
        if [_signature(e) for e in instructions] != [_signature(e) for e in saved]:
            raise ValueError('saved retirement evidence differs from current capture replay')
        count = len(instructions)
        if not count or decoded['instructions'] != count:
            raise ValueError('zero instructions or stale decoder instruction count')
        for field in ('decoded_instructions', 'retired_instructions'):
            if field in comparison and comparison[field] != count:
                raise ValueError(f'stale comparison {field}')
        if 'decoded_pcs' in comparison and [number(pc) for pc in comparison['decoded_pcs']] != [e['pc'] for e in instructions]:
            raise ValueError('comparison PCs differ from current capture replay')
        if 'bytes' in comparison and comparison['bytes'] != len(data):
            raise ValueError('stale comparison byte count')
        metadata_path = layout.input('dut', 'metadata.json')
        metadata = _json(metadata_path) if metadata_path.exists() else {}
        if metadata:
            result['input_sha256'][str(metadata_path.resolve())] = _digest(metadata_path)
            _check_storage_metadata(metadata, data)

        # Fingerprint the images that define 16/32-bit dynamic retirement counts.
        def images(description):
            for field in ('raw', 'elf'):
                if field in description:
                    image = (config_path.parent / description[field]).resolve()
                    result['input_sha256'][str(image)] = _digest(image)
            for context in description.get('contexts', {}).values():
                images(context)
        for description in config['sources'].values():
            images(description)
        for description in (config, *config['sources'].values()):
            if 'discovery' in description:
                path = (config_path.parent / description['discovery']).resolve()
                result['input_sha256'][str(path)] = _digest(path)
        losses = sum(e['kind'] == 'support' and e['qual_status'] == 2 for e in events)
        lossy = bool(losses or metadata.get('dropped') or metadata.get('loss_markers'))
        rate = len(data) * 8 / count
        result.update(status='lossy' if lossy else 'passed', retired_instructions=count,
                      bits_per_instruction=None if lossy else rate, bits_per_retained_instruction=rate,
                      loss_events=losses, dynamic_instruction_bytes=sum(e['size'] for e in instructions),
                      instruction_sizes=dict(Counter(e['size'] for e in instructions)))
        if lossy:
            result['reason'] = 'trace loss present; rate only describes retained/reconstructed instructions'
        result['sources'] = []
        for source, d in sorted(decoder.decoders.items()):
            selected = [e for e in instructions if e['source_id'] == source]
            result['sources'].append({'source_id': source, 'hart_id': d.hart_id,
                'retired_instructions': len(selected), 'packet_bytes': frames.sources[source]})
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result['reason'] = str(exc)
    return result


def render(results):
    lines = ['平均SRAM存储开销：全部存储字节 × 8 / trace覆盖的动态退休指令数',
             '包含封装、同步、support、timestamp与null；循环重复执行逐次计数，异常EPC不计退休。',
             '', '状态\tSRAM bytes\t退休指令\tbit/指令\t仅保留流bit/指令\t采集目录']
    for r in results:
        rate = r['bits_per_instruction']
        retained = r['bits_per_retained_instruction']
        lines.append('\t'.join((r['status'], str(r['stored_bytes']), str(r['retired_instructions']),
            f'{rate:.6f}' if rate is not None else '-', f'{retained:.6f}' if retained is not None else '-', r['directory'])))
        if r['reason']:
            lines.append('  原因: ' + r['reason'])
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description='Measure stored SRAM bits per dynamic retired instruction')
    parser.add_argument('directories', nargs='+', type=Path, help='checked capture roots with workload/dut/ref/diff')
    parser.add_argument('--output', type=Path, help='separate evaluation directory for measurements and readable summary')
    args = parser.parse_args(argv)
    if args.output and (args.output.resolve() in {p.resolve() for p in args.directories}
                        or (args.output / 'dut/trace.bin').exists()):
        parser.error('--output must be a separate evaluation directory')
    results = []
    for i, directory in enumerate(args.directories, 1):
        print(f'[trace-performance][{i}/{len(args.directories)}] 重放与核对 {directory}', file=sys.stderr, flush=True)
        results.append(measure(directory))
    report = render(results)
    print(report, end='')
    if args.output:
        layout = CaptureLayout(args.output).create()
        write_json(layout.workload / 'inputs.json', {'capture_directories': [str(p.resolve()) for p in args.directories]})
        write_json(layout.ref / 'method.json', METHOD)
        write_json(layout.dut / 'measurements.json', {'schema_version': 1, 'measurements': results})
        write_json(layout.diff / 'summary.json', {'eligible': sum(r['status'] == 'passed' for r in results),
            'lossy': sum(r['status'] == 'lossy' for r in results), 'excluded': sum(r['status'] == 'excluded' for r in results)})
        (layout.diff / 'summary.txt').write_text(report)
    return 0 if any(r['status'] == 'passed' for r in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
