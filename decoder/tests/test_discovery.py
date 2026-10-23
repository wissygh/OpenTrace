"""Discovery must determine real wire widths and survive capture relocation."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

from decoder.__main__ import load_config
from decoder.artifacts import DecodeArtifacts, write_json
from decoder.discovery import ATTRIBUTES, MAGIC, main, read_snapshot
from decoder.encapsulation import DecodeError
from decoder.performance import measure
from decoder.tests.test_decoder import address, encaps, support, sync

TMP = Path(__file__).resolve().parents[2] / 'delp/tmp'


def snapshot(path, attributes=None, source_bits=8, timestamp_bytes=0, mode_width=1):
    values = dict(zip(ATTRIBUTES, (0, 8, 8, 0, 0, 0, 7, 1, 0, 31, 1, 1, 1, 0, 0)))
    values.update(attributes or {})
    words = [MAGIC, 1, 20, 15, source_bits | timestamp_bytes << 8 | mode_width << 16]
    path.write_bytes(struct.pack('<20I', *words, *(values[k] for k in ATTRIBUTES)))


class EncoderDiscoveryTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=TMP)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def fixture(self, source_bits=8, timestamp_bytes=0):
        root = self.root
        snapshot(root / 'discovery.bin', source_bits=source_bits, timestamp_bytes=timestamp_bytes)
        (root / 'code.bin').write_bytes(bytes.fromhex('010013000000'))
        config = {'discovery': 'discovery.bin', 'sources': {
            '0': {'hart_id': 4, 'raw': 'code.bin', 'base': '0x1000', 'xlen': 32}}}
        write_json(root / 'config.json', config)
        bodies = [support(), sync(0x1000), address(2, notify=True), support(1)]
        data = b''.join(bytes([(128 if timestamp_bytes else 0) | len(body)]) +
            bytes(source_bits // 8) + bytes(timestamp_bytes) + body for body in bodies)
        (root / 'trace.bin').write_bytes(data)
        return config, data

    def test_all_attributes_use_specification_encodings_and_flags(self):
        p = self.root / 'discovery.bin'
        snapshot(p, {'context_width': 31, 'time_width': 63, 'nocontext': 0, 'notime': 0,
                     'iaddress_width': 49, 'privilege_width': 2})
        d = read_snapshot(p)
        self.assertEqual(tuple(d['attributes']), ATTRIBUTES)
        self.assertEqual(d['profile'], {'address_width': 50, 'address_lsb': 1,
            'privilege_width': 3, 'context_width': 32, 'time_width': 64, 'ecause_width': 8,
            'f0s_width': 1, 'cache_size': 8, 'bpred_size': 8, 'encoder_mode_width': 1, 'return_stack_size': 0})
        snapshot(p, {'context_width': 0, 'time_width': 0, 'nocontext': 0, 'notime': 0,
                     'iaddress_lsb': 1, 'cache_size': 0, 'bpred_size': 0, 'f0s_width': 0})
        d = read_snapshot(p)
        self.assertEqual((d['profile']['context_width'], d['profile']['time_width'],
                          d['profile']['address_lsb']), (1, 1, 2))
        snapshot(p)
        self.assertEqual((read_snapshot(p)['profile']['context_width'], read_snapshot(p)['profile']['time_width']), (0, 0))
        with redirect_stdout(io.StringIO()) as output:
            main([str(p)])
        self.assertEqual(json.loads(output.getvalue()), read_snapshot(p))

    def test_bad_header_width_flags_and_unsupported_modes_fail_explicitly(self):
        p = self.root / 'discovery.bin'
        snapshot(p); good = p.read_bytes()
        for size in (0, 76, 81):
            p.write_bytes((good + b'x')[:size])
            with self.assertRaises(DecodeError): read_snapshot(p)
        for index in range(4):
            words = list(struct.unpack('<20I', good)); words[index] += 1
            p.write_bytes(struct.pack('<20I', *words))
            with self.assertRaises(DecodeError): read_snapshot(p)
        for attributes in ({'arch': 1}, {'nocontext': 2}, {'notime': 2}, {'iaddress_width': 64},
                           {'iaddress_lsb': 31}, {'privilege_width': 3}, {'f0s_width': 3},
                           {'cache_size': 17}, {'bpred_size': 17}, {'sijump': 1},
                           {'return_stack_size': 17}, {'call_counter_size': 1}):
            with self.subTest(attributes=attributes):
                snapshot(p, attributes)
                with self.assertRaises(DecodeError): read_snapshot(p)
        for kwargs in ({'source_bits': 7}, {'timestamp_bytes': 9}, {'mode_width': 0}):
            snapshot(p, **kwargs)
            with self.assertRaises(DecodeError): read_snapshot(p)
        words = list(struct.unpack('<20I', good)); words[4] |= 1 << 24
        p.write_bytes(struct.pack('<20I', *words))
        with self.assertRaises(DecodeError): read_snapshot(p)

    def test_discovered_geometry_decodes_stream_and_rejects_configuration_conflicts(self):
        config, data = self.fixture(source_bits=16, timestamp_bytes=2)
        decoder = load_config(self.root / 'config.json')
        events = []
        for byte in data: events.extend(decoder.feed(bytes([byte])))
        decoder.finish()
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], [0x1000, 0x1002])
        for field, value in (('source_bits', 8), ('header_timestamp_bytes', 0), ('profile', {'address_width': 50})):
            bad = {**config, field: value}; write_json(self.root / 'bad.json', bad)
            with self.assertRaisesRegex(DecodeError, 'disagrees with discovery'): load_config(self.root / 'bad.json')
        config['profile'] = {'max_steps': 1000, 'address_width': 32}
        config['sources']['0']['profile'] = {'time_width': 64}
        write_json(self.root / 'bad.json', config)
        with self.assertRaisesRegex(DecodeError, 'time_width disagrees'): load_config(self.root / 'bad.json')

    def test_sources_can_have_independent_profiles_but_must_share_encapsulation(self):
        config, _ = self.fixture()
        snapshot(self.root / 'other.bin', {'iaddress_width': 49})
        config['sources']['1'] = {**config['sources']['0'], 'hart_id': 5, 'discovery': 'other.bin'}
        write_json(self.root / 'config.json', config)
        decoder = load_config(self.root / 'config.json')
        self.assertEqual([d.profile.address_width for d in decoder.decoders.values()], [32, 50])
        snapshot(self.root / 'other.bin', source_bits=16)
        with self.assertRaisesRegex(DecodeError, 'sources disagree'): load_config(self.root / 'config.json')
        (self.root / 'other.bin').unlink()
        with self.assertRaises(FileNotFoundError): load_config(self.root / 'config.json')

    def test_capture_snapshots_are_deduplicated_replayable_and_measured_from_discovery(self):
        config, data = self.fixture(source_bits=16, timestamp_bytes=2)
        config['sources']['0']['discovery'] = 'discovery.bin'
        write_json(self.root / 'config.json', config)
        out = self.root / 'out'
        observer = DecodeArtifacts(out)
        observer.snapshot(self.root / 'trace.bin', self.root / 'config.json')
        # Re-snapshot in place without copying a discovered input onto itself.
        observer.snapshot(out / 'dut/trace.bin', out / 'dut/trace.json')
        d = load_config(out / 'dut/trace.json', observer=observer)
        d.feed(data); d.finish(); observer.finish()
        stored = json.loads((out / 'dut/trace.json').read_text())
        self.assertEqual(stored['discovery'], stored['sources']['0']['discovery'])
        self.assertEqual(len(list((out / 'dut').glob('discovery-*.bin'))), 1)
        replay = load_config(out / 'dut/trace.json')
        replay.feed(data); replay.finish()
        write_json(out / 'diff/comparison.json', {'status': 'passed', 'bytes': len(data),
            'decoded_instructions': 2, 'decoded_pcs': [0x1000, 0x1002]})
        result = measure(out)
        self.assertEqual(result['status'], 'passed', result)
        self.assertEqual(result['storage_breakdown']['source_id_bytes'], 8)
        self.assertEqual(result['storage_breakdown']['header_timestamp_bytes'], 8)
        discovery = (out / 'dut' / stored['discovery']).resolve()
        self.assertIn(str(discovery), result['input_sha256'])
