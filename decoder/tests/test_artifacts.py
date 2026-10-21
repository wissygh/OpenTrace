import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from decoder.__main__ import load_config
from decoder.artifacts import DecodeArtifacts
from decoder.encapsulation import DecodeError
from decoder.tests.check_xiangshan_sba import check
from decoder.tests.test_decoder import PROFILE, address, encaps, payload, support, sync
from decoder.tests.test_optional_formats import cached, predicted


def records(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines()]


class DecodeArtifactTests(unittest.TestCase):
    def config(self, base, sources=None):
        (base / 'code.bin').write_bytes((0x13).to_bytes(4, 'little') * 2)
        config = {'profile': {'address_width': 32, 'privilege_width': 2, 'context_width': 0, 'time_width': 0},
                  'sources': sources or {'0': {'hart_id': 4, 'raw': 'code.bin', 'base': '0x1000'}}}
        (base / 'config.json').write_text(json.dumps(config))
        return base / 'config.json'

    def test_snapshot_fields_hex_pc_and_null_offsets_survive_single_byte_chunks(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            config = self.config(base)
            capture = bytes(2) + encaps(0, sync(0x1000)) + encaps(0, address(4, notify=True)) + encaps(0, support(1))
            (base / 'trace.bin').write_bytes(capture)
            output = base / 'artifacts'
            observer = DecodeArtifacts(output)
            observer.snapshot(base / 'trace.bin', config)
            d = load_config(config, observer=observer)
            for byte in capture:
                d.feed(bytes([byte]))
            d.finish(); observer.finish()
            framing = records(output / 'framing.jsonl')
            packets = records(output / 'packets.jsonl')
            self.assertEqual([row['offset'] for row in framing[:3]], [0, 1, 2])
            self.assertEqual(packets[0]['offset'], 2)
            self.assertEqual(packets[0]['reported_pc'], '0x1000')
            fields = {field['name']: field for field in packets[0]['wire_fields']}
            self.assertEqual((fields['address']['bit_offset'], fields['address']['width']), (7, 31))
            self.assertEqual(fields['branch']['value'], 1)
            self.assertEqual([row['pc'] for row in records(output / 'instructions.jsonl')], ['0x1000', '0x1004'])
            self.assertEqual((output / 'capture.bin').read_bytes(), capture)
            replay = load_config(output / 'capture-config.json')
            self.assertEqual(len([e for e in replay.feed(capture) if e['kind'] == 'instruction']), 2)
            self.assertEqual(json.loads((output / 'decode-result.json').read_text())['status'], 'passed')

    def test_two_source_options_are_observed_before_each_packet_is_executed(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            config = self.config(base, {'0': {'hart_id': 4, 'raw': 'code.bin', 'base': '0x1000'},
                                        '1': {'hart_id': 5, 'raw': 'code.bin', 'base': '0x1000'}})
            observer = DecodeArtifacts(base / 'out')
            d = load_config(config, observer=observer)
            data = (encaps(0, support(options=16)) + encaps(1, support()) +
                    encaps(0, sync(0x1000)) + encaps(1, sync(0x1000)))
            d.feed(data); d.finish(); observer.finish()
            rows = records(base / 'out/packets.jsonl')
            self.assertEqual([r['options_before'] for r in rows], [0, 0, 16, 0])
            self.assertEqual([r['source_id'] for r in rows], [0, 1, 0, 1])

    def test_wire_fields_distinguish_compressed_count_and_xor_flags(self):
        fields = []
        from decoder.execution import unpack
        count = unpack(predicted(31), PROFILE, 8, fields)
        self.assertEqual(count['branch_count'], 31)
        self.assertEqual(next(f for f in fields if f['name'] == 'branch_count')['value'], 0)
        fields.clear()
        result = unpack(address(-4, notify=True), PROFILE, 0, fields)
        self.assertTrue(result['notify'])
        self.assertEqual(next(f for f in fields if f['name'] == 'notify')['value'], 0)
        fields.clear()
        unpack(cached(0x80, [True, False]), PROFILE, 16, fields)
        self.assertEqual(next(f for f in fields if f['name'] == 'branch_map')['width'], 3)

    def test_sync_acquisition_preserves_absolute_capture_offsets(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            config = self.config(base)
            observer = DecodeArtifacts(base / 'out')
            d = load_config(config, acquire=True, observer=observer)
            d.feed(b'garbage' + bytes(20))
            d.feed(bytes(13) + encaps(0, sync(0x1000)))
            d.finish(); observer.finish()
            self.assertEqual(records(base / 'out/packets.jsonl')[0]['offset'], 40)

    def test_cli_retains_artifacts_and_failure_report_for_truncation(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            config = self.config(base)
            (base / 'trace.bin').write_bytes(encaps(0, sync(0x1000)) + b'\x05\x00\x73')
            command = [sys.executable, '-m', 'decoder', str(base / 'trace.bin'), '--config', str(config),
                       '--artifacts', str(base / 'out')]
            process = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(process.returncode, 1)
            self.assertEqual(json.loads(process.stdout)['pc'], '0x1000')
            self.assertEqual(records(base / 'out/instructions.jsonl')[0]['pc'], '0x1000')
            result = json.loads((base / 'out/decode-result.json').read_text())
            self.assertEqual(result['status'], 'failed')
            self.assertIn('truncated', result['error'])


class SBACaptureArtifacts(unittest.TestCase):
    def fixture(self, base):
        # A stable real-capture fixture from the externally controlled 21-instruction workload.
        (base / 'trace.bin').write_bytes(bytes.fromhex(
            '01001f1100730000000000000000000000000400004003001184030100fa01000613007700000000000000000000000000500000000101005f01000f'))
        (base / 'workload.bin').write_bytes(bytes.fromhex(
            '7310403001a00100014591420d05fd12e39e02fe1703000013030301e7000300150519a00505828001a00100'))
        (base / 'trace.json').write_text(json.dumps({'sources': {'0': {'hart_id': 0, 'raw': 'workload.bin', 'base': '0x80000000'}}}))
        (base / 'symbols.json').write_text(json.dumps({'trace_begin': '0x80000008', 'trace_done': '0x80000028'}))
        (base / 'metadata.json').write_text(json.dumps({'pc': '0x80000028', 'a0': 18, 'memory_access': 'sysbus',
            'encoder': 9, 'funnel': 9, 'sram': 9, 'wp': 60, 'rp': 60, 'dropped': 0, 'loss_markers': 0}))
        pcs = [0x80000008, 0x80000010, 0x8000000c, 0x80000010, 0x8000000c, 0x80000010,
               0x8000000c, 0x80000010, 0x80000014, 0x80000018, 0x8000001c, 0x80000024,
               0x80000026, 0x80000020, 0x80000022]
        (base / 'rob.log').write_text(''.join(f'[opentrace-commit] hart=0 cycle={i} slot=0 pc=0x{pc:x}\n'
                                               for i, pc in enumerate(pcs)))

    def test_success_outputs_packets_instructions_commits_and_match_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            self.fixture(base)
            result = check(base)
            self.assertEqual((result['status'], result['decoded_instructions'], result['rob_samples']), ('passed', 21, 15))
            self.assertEqual(records(base / 'decode/instructions.jsonl')[0]['pc'], '0x80000008')
            self.assertEqual(records(base / 'commit-pc.jsonl')[0]['pc'], '0x80000008')
            self.assertEqual(len((base / 'pc-matches.tsv').read_text().splitlines()), 16)
            self.assertEqual(json.loads((base / 'comparison.json').read_text())['decoded_pcs'][0], '0x80000008')

    def test_mismatch_and_truncation_leave_explicit_failed_comparison(self):
        for fault in ('rob', 'capture'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                base = Path(tmp)
                self.fixture(base)
                if fault == 'rob':
                    (base / 'rob.log').write_text('[opentrace-commit] hart=0 cycle=1 slot=0 pc=0x80000008\n'
                        '[opentrace-commit] hart=0 cycle=2 slot=0 pc=0x80000008\n')
                else:
                    (base / 'trace.bin').write_bytes((base / 'trace.bin').read_bytes()[:-1])
                with self.assertRaises(DecodeError):
                    check(base)
                result = json.loads((base / 'comparison.json').read_text())
                self.assertEqual(result['status'], 'failed')
                self.assertTrue(result['error'])
                self.assertTrue((base / 'commit-pc.jsonl').exists())
                if fault == 'rob':
                    self.assertEqual(result['first_mismatch']['sample_index'], 1)
                    self.assertTrue((base / 'decode/packets.jsonl').exists())

    def test_failed_recheck_cannot_reuse_stale_success_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            self.fixture(base)
            original = (base / 'trace.bin').read_bytes()
            check(base)
            metadata = json.loads((base / 'metadata.json').read_text())
            metadata['dropped'] = 1
            (base / 'metadata.json').write_text(json.dumps(metadata))
            with self.assertRaises(DecodeError):
                check(base)
            self.assertFalse((base / 'pc-matches.tsv').exists())
            self.assertFalse((base / 'decode/decode-result.json').exists())
            self.assertEqual((base / 'trace.bin').read_bytes(), original)
            self.assertEqual(json.loads((base / 'comparison.json').read_text())['stage'], 'capture-metadata')


if __name__ == '__main__':
    unittest.main()
