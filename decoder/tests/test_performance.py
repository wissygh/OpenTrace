"""Storage rates require complete, correct retirement and every SRAM byte."""
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from decoder.__main__ import load_config
from decoder.artifacts import DecodeArtifacts, write_json
from decoder.encapsulation import PacketParser
from decoder.performance import main, measure
from decoder.tests.check_xiangshan_sba import check
from decoder.tests import test_artifacts
from decoder.tests.test_decoder import address, encaps, support, sync


class TracePerformanceTests(unittest.TestCase):
    def real_basic(self, root):
        test_artifacts.SBACaptureArtifacts().fixture(root)
        with redirect_stdout(io.StringIO()):
            check(root)

    def simple(self, root, end=1):
        root.mkdir(parents=True, exist_ok=True)
        (root / 'code.bin').write_bytes(bytes.fromhex('010013000000'))
        config = {'profile': {'address_width': 32, 'privilege_width': 2, 'context_width': 0, 'time_width': 0},
                  'sources': {'0': {'hart_id': 4, 'raw': 'code.bin', 'base': '0x1000', 'xlen': 32}}}
        write_json(root / 'config.json', config)
        data = encaps(0, support()) + encaps(0, sync(0x1000)) + encaps(0, address(2, notify=True))
        if end is not None:
            data += encaps(0, support(end))
        (root / 'capture.bin').write_bytes(data)
        observer = DecodeArtifacts(root)
        observer.snapshot(root / 'capture.bin', root / 'config.json')
        d = load_config(root / 'dut/trace.json', observer=observer)
        events = d.feed(data)
        d.finish(); observer.finish()
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], [0x1000, 0x1002])
        write_json(root / 'diff/comparison.json', {'status': 'passed', 'bytes': len(data),
            'decoded_instructions': 2, 'decoded_pcs': [0x1000, 0x1002], 'reference_kind': 'synthetic test reference'})

    def test_real_sram_capture_uses_21_dynamic_instructions_instead_of_15_rob_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.real_basic(root)
            before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}
            result = measure(root)
            self.assertEqual(result['status'], 'passed', result)
            self.assertEqual((result['stored_bytes'], result['stored_bits'], result['retired_instructions']), (60, 480, 21))
            self.assertAlmostEqual(result['bits_per_instruction'], 480 / 21)
            self.assertEqual(result['validation']['rob_samples'], 15)
            self.assertEqual(sum(result['storage_breakdown'].values()), 60)
            self.assertEqual(before, {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in before})

    def test_two_harts_timestamp_source_width_and_shared_nulls_all_count_in_storage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            original = root / 'original'; original.mkdir()
            self.real_basic(original)
            parser = PacketParser()
            packets = parser.feed((original / 'trace.bin').read_bytes()); parser.finish()
            # Same validated workload on both harts: dynamic repeats and srcID
            # multiplexing must not deduplicate retirement or ignore shared nulls.
            data = b''.join(bytes([128 | len(p.payload)]) + source.to_bytes(2, 'little') + b'\x34\x12' + p.payload
                            for p in packets for source in (0, 1025)) + bytes(4)
            capture = root / 'capture.bin'; capture.write_bytes(data)
            config = {'source_bits': 16, 'header_timestamp_bytes': 2, 'sources': {
                '0': {'hart_id': 0, 'raw': 'original/workload.bin', 'base': '0x80000000'},
                '1025': {'hart_id': 7, 'raw': 'original/workload.bin', 'base': '0x80000000'}}}
            write_json(root / 'config.json', config)
            out = root / 'two-hart'
            observer = DecodeArtifacts(out); observer.snapshot(capture, root / 'config.json')
            decoder = load_config(out / 'dut/trace.json', observer=observer)
            decoder.feed(data); decoder.finish(); observer.finish()
            write_json(out / 'diff/comparison.json', {'status': 'passed', 'decoded_instructions': 42})
            result = measure(out)
            self.assertEqual(result['status'], 'passed', result)
            self.assertEqual((result['stored_bytes'], result['retired_instructions']), (172, 42))
            self.assertEqual(result['storage_breakdown'], {'payload_bytes': 88, 'header_bytes': 16,
                'source_id_bytes': 32, 'header_timestamp_bytes': 32, 'null_bytes': 4})
            self.assertEqual([(s['source_id'], s['hart_id'], s['retired_instructions'], s['packet_bytes'])
                              for s in result['sources']], [(0, 0, 21, 84), (1025, 7, 21, 84)])
            self.assertAlmostEqual(result['bits_per_instruction'], 172 * 8 / 42)

    def test_compressed_instruction_is_one_retirement_and_loss_has_only_retained_rate(self):
        for end in (1, 2):
            with self.subTest(end=end), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); self.simple(root, end)
                result = measure(root)
                self.assertEqual(result['retired_instructions'], 2)
                self.assertEqual(result['dynamic_instruction_bytes'], 6)
                self.assertEqual(result['instruction_sizes'], {2: 1, 4: 1})
                if end == 2:
                    self.assertEqual(result['status'], 'lossy')
                    self.assertIsNone(result['bits_per_instruction'])
                    self.assertEqual(result['loss_events'], 1)
                else:
                    self.assertEqual(result['status'], 'passed', result)
                self.assertAlmostEqual(result['bits_per_retained_instruction'], result['stored_bits'] / 2)

    def test_failed_unterminated_truncated_stale_and_unverified_captures_have_no_rate(self):
        for fault in ('failed', 'unterminated', 'truncated', 'pc', 'count', 'metadata', 'unverified', 'zero'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); self.simple(root, end=None if fault == 'unterminated' else 1)
                comparison = root / 'diff/comparison.json'
                if fault == 'failed':
                    write_json(comparison, {'status': 'failed', 'error': 'wrong FTQ PC'})
                elif fault == 'truncated':
                    path = root / 'dut/trace.bin'; path.write_bytes(path.read_bytes()[:-1])
                elif fault == 'pc':
                    path = root / 'dut/instructions.jsonl'
                    path.write_text(path.read_text().replace('0x1002', '0x1004'))
                elif fault == 'count':
                    write_json(root / 'dut/decode-result.json', {'status': 'passed', 'instructions': 0})
                elif fault == 'zero':
                    empty = encaps(0, support(1))
                    (root / 'dut/trace.bin').write_bytes(empty)
                    (root / 'dut/instructions.jsonl').write_text('')
                    write_json(root / 'dut/decode-result.json', {'status': 'passed', 'instructions': 0})
                    write_json(comparison, {'status': 'passed', 'bytes': len(empty),
                        'decoded_instructions': 0, 'decoded_pcs': []})
                elif fault == 'metadata':
                    write_json(root / 'dut/metadata.json', {'wp': 1024, 'rp': 1024})
                elif fault == 'unverified':
                    comparison.unlink()
                result = measure(root)
                self.assertEqual(result['status'], 'excluded', result)
                self.assertIsNone(result['bits_per_instruction'])
                self.assertIsNone(result['bits_per_retained_instruction'])
                self.assertTrue(result['reason'])

    def test_cli_separates_reports_preserves_inputs_and_replaces_previous_success_on_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'run'; self.simple(root)
            out = Path(tmp) / 'evaluation'
            with redirect_stdout(io.StringIO()):
                self.assertEqual(main([str(root), '--output', str(out)]), 0)
            summary = json.loads((out / 'diff/summary.json').read_text())
            self.assertEqual(summary, {'eligible': 1, 'lossy': 0, 'excluded': 0})
            for name in ('workload/inputs.json', 'ref/method.json', 'dut/measurements.json', 'diff/summary.txt'):
                self.assertTrue((out / name).is_file())
            write_json(root / 'diff/comparison.json', {'status': 'failed'})
            with redirect_stdout(io.StringIO()):
                self.assertEqual(main([str(root), '--output', str(out)]), 1)
            report = json.loads((out / 'dut/measurements.json').read_text())['measurements'][0]
            self.assertIsNone(report['bits_per_instruction'])
            self.assertEqual(report['status'], 'excluded')
            with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
                main([str(root), '--output', str(root)])


if __name__ == '__main__':
    unittest.main()
