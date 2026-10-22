import unittest
import json
from pathlib import Path
import tempfile
from unittest.mock import Mock, patch
from types import SimpleNamespace
from decoder.encapsulation import DecodeError
from decoder.tests.check_xiangshan_sba import COMMIT, match_samples
from decoder.tests.run_xiangshan_sba import RPC, listening, run
from decoder.tests.xiangshan_sba_scenarios import reference


class XiangShanSBAChecks(unittest.TestCase):
    def test_compressed_samples_keep_repeated_loop_iterations(self):
        decoded = [8, 10, 12, 14, 16, 12, 14, 16]
        self.assertEqual(match_samples(decoded, [8, 12, 16, 12, 16]), [0, 2, 4, 5, 7])

    def test_duplicate_and_reordered_samples_cannot_be_discarded(self):
        for samples in ([8, 12, 12, 16], [16, 12]):
            with self.subTest(samples=samples), self.assertRaises(DecodeError):
                match_samples([8, 12, 16], samples)

    def test_missing_reference_is_not_a_pass(self):
        with self.assertRaises(DecodeError):
            match_samples([8, 12, 16], [])

    def test_partial_log_line_is_not_a_reference_instruction(self):
        complete = '[opentrace-commit] hart= 0 cycle= 100 slot=2 pc=0x0000080000008\n'
        partial = '[opentrace-commit] hart= 0 cycle= 101 slot=0 pc=0x000008'
        matches = list(COMMIT.finditer(complete + partial))
        self.assertEqual(len(matches), 1)
        self.assertEqual(int(matches[0].group(4), 16), 0x80000008)

    def test_pressure_reference_preserves_all_loop_iterations_and_instruction_sizes(self):
        start = 0x80000008
        symbols = {'trace_begin': hex(start), 'trace_done': hex(start + 26),
                   'trace_loop': hex(start + 14), 'trace_target': hex(start + 18)}
        a0, pcs = reference(symbols, 'pressure')
        self.assertEqual((a0, len(pcs)), (80, 324))
        self.assertEqual(pcs[:8], [start + x for x in (0, 2, 6, 10, 14, 18, 20, 22)])
        self.assertEqual(pcs[-4:], pcs[4:8])
        self.assertTrue(all(pc > 0x80000000 for pc in pcs))
        symbols['trace_done'] = hex(start + 28)
        with self.assertRaisesRegex(ValueError, 'layout changed'):
            reference(symbols, 'pressure')

    def test_build_failure_preserves_failed_capture_status_before_target_collection(self):
        parent = Path(__file__).resolve().parents[2] / 'delp/tmp'
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as tmp:
            output = Path(tmp) / 'run'
            args = SimpleNamespace(output=output, emu=Path('/unused/emu'), pressure=False,
                                   cross_prefix='opentrace-missing-cross-tool-', openocd='openocd')
            with self.assertRaises(FileNotFoundError), patch('decoder.tests.run_xiangshan_sba.collect') as collect:
                run(args)
            collect.assert_not_called()
            report = json.loads((output / 'diff/comparison.json').read_text())
            self.assertEqual((report['status'], report['stage']), ('failed', 'capture'))
            self.assertTrue((output / 'workload/logs/build.log').exists())
            self.assertFalse((output / 'dut/trace.bin').exists())

    def test_rpc_handles_fragmented_and_coalesced_responses(self):
        connection = Mock()
        connection.recv.side_effect = [b'0|0x', b'1901\x1a0|', b'next\x1a']
        with patch('socket.create_connection', return_value=connection):
            rpc = RPC(1234)
        self.assertEqual(rpc.run('read_memory 0x20040004 32 1'), '0x1901')
        self.assertEqual(rpc.run('test-command'), 'next')
        self.assertTrue(all(call.args[0].endswith(b'\x1a') for call in connection.sendall.call_args_list))

    def test_rpc_reports_target_errors_and_closed_connections(self):
        for reply in (b'1|SBA error\x1a', b''):
            connection = Mock()
            connection.recv.return_value = reply
            with patch('socket.create_connection', return_value=connection):
                rpc = RPC(1234)
            with self.subTest(reply=reply), self.assertRaises(RuntimeError):
                rpc.run('read_memory 0x20040004 32 1')

    def test_listener_readiness_does_not_consume_the_jtag_connection(self):
        table = ('header\n'
                 '0: 0100007F:6072 00000000:0000 0A 0 0 0 0 0 0\n'
                 '1: 0100007F:6073 0100007F:1234 01 0 0 0 0 0 0\n')
        with patch('pathlib.Path.read_text', return_value=table), patch('socket.create_connection') as connect:
            self.assertTrue(listening(24690))
            self.assertFalse(listening(24691))
            connect.assert_not_called()

    def test_rpc_transcript_keeps_success_and_target_failure(self):
        connection = Mock()
        connection.recv.side_effect = [b'0|0x1901\x1a', b'1|SBA failed\x1a']
        with tempfile.TemporaryDirectory() as tmp:
            transcript = Path(tmp) / 'rpc.jsonl'
            with patch('socket.create_connection', return_value=connection):
                rpc = RPC(1234, transcript=transcript)
            rpc.run('read_memory 0x20040004 32 1')
            with self.assertRaises(RuntimeError):
                rpc.run('read_memory 0x20040000 32 1')
            rows = [json.loads(line) for line in transcript.read_text().splitlines()]
            self.assertEqual([row['return_code'] for row in rows], ['0', '1'])
            self.assertEqual(rows[1]['result'], 'SBA failed')


if __name__ == '__main__':
    unittest.main()
