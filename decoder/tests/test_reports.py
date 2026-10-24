"""Readable evidence must agree with machine records, including partial failures."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from decoder.__main__ import load_config
from decoder.artifacts import DecodeArtifacts, DECODE_REPORT_FILES
from decoder.encapsulation import DecodeError
from decoder.report import packet_type, render_directory, render_record
from decoder.disassembly import disassemble
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

    def test_detailed_multiline_views_keep_wire_profile_and_events_and_replay_does_not_change_evidence(self):
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
            instruction_text = (dut / 'instructions.txt').read_text()
            self.assertIn('instruction: 0x13  nop  size: 4 bytes', instruction_text)
            self.assertEqual(instruction_text.count('packet='), 2)
            self.assertIn('0x80001000', (dut / 'events.txt').read_text())
            packet_text = (dut / 'packets.txt').read_text()
            for detail in ('decoded_fields', 'wire_fields', 'profile:', 'raw_hex:', 'payload_hex:', 'header:'):
                self.assertIn(detail, packet_text)
            self.assertIn('privilege:', instruction_text)
            self.assertIn('context:', instruction_text)
            self.assertEqual(len((dut / 'events.jsonl').read_text().splitlines()), 3)
            views = {p: p.read_bytes() for p in dut.glob('*.txt')}
            before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in dut.iterdir() if p.suffix != '.txt'}
            render_directory(output)
            self.assertEqual(views, {p: p.read_bytes() for p in views})
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

    def test_disassembly_preserves_real_pc_encoding_and_rv32_rv64_compressed_meaning(self):
        items = [(32, 0x80001000, 0x2085, 2), (64, 0x80001000, 0x2085, 2),
                 (64, 0x80001002, 0x8082, 2), (64, 0x80001004, 0x0000006f, 4),
                 (64, 0xffffffff80001000, 0x13, 4)]
        result = disassemble(items)
        self.assertEqual(result[items[0]], 'jal 0x80001060')
        self.assertEqual(result[items[1]], 'addw ra,ra,1')
        self.assertEqual(result[items[2]], 'ret')
        self.assertEqual(result[items[3]], 'j 0x80001004')
        self.assertEqual(result[items[4]], 'nop')

    def test_disassembly_fails_explicitly_without_inventing_instructions(self):
        item = (64, 0x80001000, 0x8082, 2)
        with patch('decoder.disassembly.shutil.which', return_value=None):
            self.assertEqual(disassemble([item])[item], '.hword 0x8082 [缺少RISC-V objdump]')
        self.assertIn('objdump失败', disassemble([item], objdump='/does/not/exist')[item])

    def test_replay_uses_source_context_xlen_and_keeps_incomplete_capture_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            dut = Path(tmp)
            (dut / 'trace.json').write_text(json.dumps({'sources': {
                '0': {'xlen': 64, 'contexts': {'5': {'xlen': 32}}}, '1': {'xlen': 64}}}))
            rows = [{'kind': 'instruction', 'source_id': src, 'context': ctx, 'hart_id': src,
                     'pc': '0x80001000', 'instruction': 0x2085, 'size': 2}
                    for src, ctx in [(0, 5), (1, None)]]
            (dut / 'instructions.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
            render_directory(dut)
            text = (dut / 'instructions.txt').read_text()
            self.assertIn('jal 0x80001060', text)
            self.assertIn('addw ra,ra,1', text)
            self.assertIn('缺少decode-result.json', text)
