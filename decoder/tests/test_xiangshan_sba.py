import unittest
import json
from pathlib import Path
import tempfile
from unittest.mock import Mock, patch
from decoder.encapsulation import DecodeError
from decoder.tests.check_xiangshan_sba import COMMIT, match_samples
from decoder.tests.run_xiangshan_sba import RPC, listening


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
