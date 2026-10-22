"""Readable evidence must agree with machine records, including partial failures."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from decoder.__main__ import load_config
from decoder.artifacts import DecodeArtifacts, DECODE_REPORT_FILES
from decoder.encapsulation import DecodeError
from decoder.report import packet_type, render_directory, render_record
from decoder.tests.test_decoder import address, encaps, support, sync


class ReadableReports(unittest.TestCase):
    def test_packet_headings_cover_all_variants_and_distinguish_epc_from_handler(self):
        cases = [({'format': 0, 'subformat': 0, 'branch_count': 31}, 'Format00', '无地址'),
                 ({'format': 0, 'subformat': 0, 'address': 0, 'prediction_failed': True}, 'Format00', '预测失败'),
                 ({'format': 0, 'subformat': 1, 'index': 4, 'branches': 0, 'branch_map': 0}, 'Format01', '缓存'),
                 ({'format': 1, 'branches': 0, 'branch_map': 0}, 'Format1', '31分支'),
                 ({'format': 1, 'branches': 2, 'branch_map': 2}, 'Format1', '分支图'),
                 ({'format': 2, 'address': 4}, 'Format2', '地址'),
                 ({'format': 3, 'subformat': 0}, 'Format30', '同步'),
                 ({'format': 3, 'subformat': 1, 'interrupt': 0, 'thaddr': 0}, 'Format31', '非退休EPC'),
                 ({'format': 3, 'subformat': 1, 'interrupt': 1, 'thaddr': 1}, 'Format31', 'handler'),
                 ({'format': 3, 'subformat': 2}, 'Format32', 'context'),
                 ({'format': 3, 'subformat': 3, 'qual_status': 2, 'options': 24}, 'Format33', 'trace_lost')]
        for fields, kind, meaning in cases:
            with self.subTest(fields=fields):
                self.assertEqual(packet_type(fields)[0], kind)
                text = render_record('packets', {'fields': fields, 'source_id': 3, 'hart_id': 7,
                    'packet_index': 4, 'offset': 19, 'reported_pc': '0x80001000'}, 0)
                self.assertIn(kind, text)
                self.assertIn(meaning, text)
                self.assertIn('srcID=3 hart=7', text)
                self.assertIn('0x80001000', text)
                if fields.get('thaddr') == 0:
                    self.assertIn('exception_pc（不代表退休）', text)
                if kind == 'Format01':
                    self.assertNotIn('branch_outcomes:', text)
        outcomes = render_record('packets', {'fields': {'format': 1, 'branches': 2, 'branch_map': 2}}, 0)
        self.assertIn('branch_outcomes: T N', outcomes)

    def test_chunked_decode_automatically_exports_multiline_views_and_replay_does_not_change_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'code.bin').write_bytes((0x13).to_bytes(4, 'little') * 2)
            config = root / 'config.json'
            config.write_text(json.dumps({'profile': {'address_width': 32, 'privilege_width': 2,
                'context_width': 0, 'time_width': 0}, 'sources': {'0': {'hart_id': 4,
                    'raw': 'code.bin', 'base': '0x80001000', 'xlen': 32}}}))
            data = bytes(2) + encaps(0, sync(0x80001000)) + encaps(0, address(4, notify=True)) + encaps(0, support(1))
            (root / 'capture.bin').write_bytes(data)
            output = root / 'out'
            observer = DecodeArtifacts(output)
            observer.snapshot(root / 'capture.bin', config)
            decoder = load_config(config, observer=observer)
            for byte in data:
                decoder.feed(bytes([byte]))
            decoder.finish(); observer.finish()
            dut = output / 'dut'
            for stem in ('framing', 'packets', 'events', 'instructions'):
                text = (dut / (stem + '.txt')).read_text()
                self.assertGreater(len(text.splitlines()), 6)
                self.assertIn('解码状态: 完成', text)
            packet = json.loads((dut / 'packets.jsonl').read_text().splitlines()[0])
            self.assertEqual(packet['packet_type'], 'Format30')
            self.assertIn('0x80001000', (dut / 'packets.txt').read_text())
            self.assertIn('NULL', (dut / 'framing.txt').read_text())
            self.assertIn('packet_index: 0', (dut / 'framing.txt').read_text())
            before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in dut.iterdir() if p.suffix != '.txt'}
            render_directory(output)
            self.assertEqual(before, {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in before})
            self.assertTrue(set(f'{stem}.txt' for stem in ('framing', 'packets', 'events', 'instructions')) <= set(DECODE_REPORT_FILES))
            with self.assertRaisesRegex(ValueError, 'already exist'):
                DecodeArtifacts(output)

    def test_failure_footer_and_epc_events_remain_readable_without_claiming_retirement(self):
        text = render_record('events', {'kind': 'exception_pc', 'pc': 0x80000028,
                                        'source_id': 0, 'hart_id': 0, 'packet_index': 3}, 9)
        self.assertIn('不退休', text)
        self.assertIn('0x80000028', text)
        with tempfile.TemporaryDirectory() as tmp:
            observer = DecodeArtifacts(tmp)
            observer.events([{'kind': 'exception_pc', 'pc': 0x80000028, 'source_id': 0, 'hart_id': 0}])
            observer.finish(DecodeError('truncated packet'))
            dut = Path(tmp) / 'dut'
            self.assertIn('失败 / truncated packet', (dut / 'events.txt').read_text())
            self.assertEqual((dut / 'instructions.jsonl').read_text(), '')
            self.assertIn('失败', (dut / 'instructions.txt').read_text())
            render_directory(tmp)
            self.assertIn('失败', (dut / 'events.txt').read_text())
