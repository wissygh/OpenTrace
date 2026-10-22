import json
from pathlib import Path
import tempfile
import unittest
from decoder.__main__ import load_config
from decoder.capture_layout import CaptureLayout
from decoder.tests.organize_artifacts import organize
from decoder.tests import test_artifacts
from decoder.tests.check_xiangshan_sba import check


class CaptureLayoutTests(unittest.TestCase):
    def test_migrate_flat_sba_inputs_preserve_bytes_replay_and_idempotence(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            test_artifacts.SBACaptureArtifacts().fixture(base)
            original = (base / 'trace.bin').read_bytes()
            (base / 'openocd.log').write_text('original JTAG transcript\n')
            organize(base)
            self.assertEqual(set(p.name for p in base.iterdir()), {'workload', 'dut', 'ref', 'diff'})
            self.assertEqual((base / 'dut/trace.bin').read_bytes(), original)
            self.assertEqual((base / 'dut/logs/openocd.log').read_text(), 'original JTAG transcript\n')
            decoder = load_config(base / 'dut/trace.json')
            decoder.feed(original); decoder.finish()
            organize(base)
            result = check(base)
            self.assertEqual(result['status'], 'passed')
            self.assertEqual(set(p.name for p in base.iterdir()), {'workload', 'dut', 'ref', 'diff'})
            self.assertFalse((base / 'dut/capture.bin').exists())
            self.assertFalse((base / 'dut/decode').exists())

    def test_migrate_legacy_decode_keeps_duplicate_evidence_and_rebases_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_artifacts.SBACaptureArtifacts().fixture(root)
            decode = root / 'decode'; (decode / 'workload').mkdir(parents=True)
            (decode / 'capture.bin').write_bytes((root / 'trace.bin').read_bytes())
            (decode / 'workload/image.bin').write_bytes((root / 'workload.bin').read_bytes())
            (decode / 'capture-config.json').write_text(json.dumps({'sources': {
                '0': {'hart_id': 0, 'raw': 'workload/image.bin', 'base': '0x80000000'}}}))
            (decode / 'events.jsonl').write_text('decode events\n')
            (root / 'events.jsonl').write_text('duplicate historical events\n')
            organize(root)
            self.assertEqual((root / 'dut/events.jsonl').read_text(), 'decode events\n')
            self.assertEqual((root / 'dut/legacy-events.jsonl').read_text(), 'duplicate historical events\n')
            self.assertTrue((root / 'dut/legacy-capture.bin').exists())
            load_config(root / 'dut/legacy-config.json')

    def test_collision_refuses_to_overwrite_unrelated_capture(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            CaptureLayout(root).create()
            (root / 'capture.bin').write_bytes(b'new')
            (root / 'dut/trace.bin').write_bytes(b'old')
            with self.assertRaisesRegex(ValueError, 'overwrite'):
                organize(root)
            self.assertEqual((root / 'dut/trace.bin').read_bytes(), b'old')
            self.assertEqual((root / 'capture.bin').read_bytes(), b'new')


if __name__ == '__main__':
    unittest.main()
