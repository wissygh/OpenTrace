"""Readable capture evidence without changing integer decoder state."""
from dataclasses import asdict
import json
from pathlib import Path
import shutil
from .execution import unpack
from .image import signed


PC_FIELDS = {'pc', 'decoded_pc', 'rob_pc', 'expected_pc', 'actual_pc', 'epc', 'reference_pc', 'reported_pc',
             'decoded_pcs', 'rob_pcs', 'expected_pcs', 'trace_begin', 'trace_done'}
DECODE_REPORT_FILES = ('framing.jsonl', 'packets.jsonl', 'events.jsonl', 'instructions.jsonl',
                       'instructions.tsv', 'decode-result.json')


def readable(value, key=None):
    if isinstance(value, dict):
        return {name: readable(item, name) for name, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [readable(item, key) for item in value]
    if key in PC_FIELDS and isinstance(value, int) and not isinstance(value, bool):
        return hex(value)
    return value


def write_json(path, value):
    Path(path).write_text(json.dumps(readable(value), ensure_ascii=False, indent=2) + '\n')


def write_jsonl(path, rows):
    with Path(path).open('w') as output:
        for row in rows:
            output.write(json.dumps(readable(row), ensure_ascii=False, sort_keys=True) + '\n')


class DecodeArtifacts:
    """Attach to framing and execution; flush each record for failure diagnosis."""
    def __init__(self, directory, overwrite=False):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        names = DECODE_REPORT_FILES
        if not overwrite and any((self.directory / name).exists() for name in names):
            raise ValueError('decoder artifact files already exist; use a new output directory')
        self.files = {name: (self.directory / name).open('w') for name in names[:-1]}
        self.files['instructions.tsv'].write('index\tpacket_index\tsource_id\thart_id\tpc\tinstruction\tsize\n')
        self.frames = {}
        self.packet_index = 0
        self.instruction_index = 0
        self.nulls = 0
        self.packet_count = 0

    def _line(self, name, row):
        output = self.files[name]
        output.write(json.dumps(readable(row), ensure_ascii=False, sort_keys=True) + '\n')
        output.flush()

    def snapshot(self, capture, config):
        """Copy capture/config/images, retaining a self-contained decode config."""
        capture, config = Path(capture).resolve(), Path(config).resolve()
        if capture != (self.directory / 'capture.bin').resolve():
            shutil.copyfile(capture, self.directory / 'capture.bin')
        data = json.loads(config.read_text())
        snapshots = self.directory / 'workload'
        snapshots.mkdir(exist_ok=True)
        copied = {}

        def image(description):
            for field in ('raw', 'elf'):
                if field not in description:
                    continue
                original = (config.parent / description[field]).resolve()
                if original not in copied:
                    target = snapshots / f'{len(copied):02d}-{original.name}'
                    shutil.copyfile(original, target)
                    copied[original] = target.relative_to(self.directory).as_posix()
                description[field] = copied[original]
            for context in description.get('contexts', {}).values():
                image(context)

        for source in data['sources'].values():
            image(source)
        write_json(self.directory / 'capture-config.json', data)
        write_json(self.directory / 'inputs.json', {'capture': str(capture), 'config': str(config),
                   'workload': [{'original': str(path), 'snapshot': target} for path, target in copied.items()]})

    def frame(self, offset, raw, packet):
        header = raw[0]
        row = {'offset': offset, 'offset_hex': hex(offset), 'byte_length': len(raw),
               'raw_hex': raw.hex(' '), 'header': {'raw': hex(header), 'length': header & 31,
               'flow': (header >> 5) & 3, 'extend': bool(header & 128)}, 'null': packet is None}
        if packet is None:
            self.nulls += 1
        else:
            row.update(source_id=packet.source_id, timestamp=packet.timestamp)
            self.frames[id(packet)] = row
        self._line('framing.jsonl', row)

    def packet(self, packet, decoder):
        self.packet_index = self.packet_count
        self.packet_count += 1
        row = dict(self.frames.pop(id(packet), {}), packet_index=self.packet_index,
                   source_id=packet.source_id, hart_id=decoder.hart_id, payload_hex=packet.payload.hex(' '),
                   options_before=decoder.options, profile=asdict(decoder.profile),
                   reference_pc=decoder.address, pc=decoder.pc, wire_fields=[])
        try:
            row['fields'] = unpack(packet.payload, decoder.profile, decoder.options, row['wire_fields'])
            address = row['fields'].get('address')
            if address is not None:
                aligned = signed(address, decoder.profile.address_width - decoder.profile.address_lsb) << decoder.profile.address_lsb
                if row['fields']['format'] == 3 or decoder.options & 32:
                    row['reported_pc'] = aligned & decoder.mask
                else:
                    row['address_delta_hex'] = hex(aligned)
                    row['reported_pc'] = (decoder.address + aligned) & decoder.mask
            elif row['fields']['format'] == 0 and row['fields'].get('subformat') == 1:
                row['reported_pc'] = decoder.target_cache.get(row['fields']['index'])
            for field in row['wire_fields']:
                field['value_hex'] = hex(field['value'])
            row['wire_note'] = 'MSB fields may be reconstructed by sign extension of the stored payload.'
        except ValueError as exc:
            row['error'] = str(exc)
            raise
        finally:
            self._line('packets.jsonl', row)

    def events(self, events):
        for event in events:
            row = dict(event, packet_index=self.packet_index)
            self._line('events.jsonl', row)
            if event['kind'] == 'instruction':
                row['index'] = self.instruction_index
                self._line('instructions.jsonl', row)
                output = self.files['instructions.tsv']
                output.write(f"{self.instruction_index}\t{self.packet_index}\t{event['source_id']}\t"
                             f"{event['hart_id']}\t{event['pc']:#x}\t{event['instruction']:#x}\t{event['size']}\n")
                output.flush()
                self.instruction_index += 1

    def finish(self, error=None):
        for output in self.files.values():
            output.close()
        write_json(self.directory / 'decode-result.json', {'status': 'failed' if error else 'passed',
                   'error': str(error) if error else None, 'packets': self.packet_count,
                   'null_packets': self.nulls, 'instructions': self.instruction_index})
