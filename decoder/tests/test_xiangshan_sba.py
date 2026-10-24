import unittest
import json
from pathlib import Path
import tempfile
import struct
from unittest.mock import Mock, patch
from types import SimpleNamespace
from decoder.encapsulation import DecodeError
from decoder.tests.check_xiangshan_sba import COMMIT, match_samples
from decoder.tests.run_xiangshan_sba import RPC, listening, run, capture_discovery, collect
from decoder.tests.xiangshan_sba_scenarios import reference
from decoder.tests.xiangshan_control_session import ControlSession, run_checks
from decoder.capture_layout import CaptureLayout


class XiangShanSBAChecks(unittest.TestCase):
    def test_control_interaction_persists_bad_readback_before_rejecting(self):
        temporary = Path(__file__).resolve().parents[2] / 'delp/tmp'
        with tempfile.TemporaryDirectory(dir=temporary) as directory:
            layout = CaptureLayout(directory).create()
            rpc = Mock()
            rpc.run.return_value = '0x2'
            session = ControlSession(rpc, layout)
            with self.assertRaisesRegex(ValueError, 'control interaction failed'):
                session.expect('test field', 0x20000008, 1, 1)
            rows = [json.loads(line) for line in (layout.dut / 'control-interactions.jsonl').read_text().splitlines()]
            self.assertEqual((rows[0]['status'], rows[0]['expected'], rows[0]['actual']), ('failed', '0x1', '0x2'))
            self.assertEqual([call.args[0] for call in rpc.run.call_args_list], [
                'write_memory 0x20000008 32 {0x1} phys', 'read_memory 0x20000008 32 1 phys'])

    def test_control_session_rejects_running_core_without_programming_its_controls(self):
        temporary = Path(__file__).resolve().parents[2] / 'delp/tmp'
        with tempfile.TemporaryDirectory(dir=temporary) as directory:
            layout = CaptureLayout(directory).create()
            rpc = Mock()
            rpc.run.side_effect = ['', 'running']
            with self.assertRaisesRegex(ValueError, 'externally halted'):
                run_checks(rpc, layout, 'sram')
            self.assertEqual(rpc.run.call_count, 2)
            self.assertEqual(json.loads((layout.diff / 'control-comparison.json').read_text())['status'], 'failed')

    def test_live_empty_does_not_mask_an_actual_instruction_control_mismatch(self):
        temporary = Path(__file__).resolve().parents[2] / 'delp/tmp'
        with tempfile.TemporaryDirectory(dir=temporary) as directory:
            rpc = Mock()
            rpc.run.return_value = '0x10071'  # Empty=0 while queued support awaits downstream.
            session = ControlSession(rpc, CaptureLayout(directory).create())
            session.expect('control fields', 0x20000000, 1, 0x10079, mask=0xfffffff7)
            rpc.run.return_value = '0x10075'  # Unexpected InstTracing=1 is still a failure.
            with self.assertRaisesRegex(ValueError, 'control interaction failed'):
                session.expect('control fields', 0x20000000, 1, 0x10079, mask=0xfffffff7)

    def test_pressure_halt_drains_old_retirement_before_resume_using_only_physical_sysbus(self):
        commands=[]
        halted=False
        rp=0

        def command(value):
            nonlocal halted,rp
            commands.append(value)
            if value=='riscv dmi_write 0x10 0x80000001':halted=True
            if value=='reg pc':return 'pc (/64): 0x80000016' if halted else 'pc (/64): 0x80000022'
            if value=='reg a0':return 'a0 (/64): 0x50'
            if value=='reg dcsr':return 'dcsr (/32): 0xc0' if halted else 'dcsr (/32): 0x80'
            if value=='resume':halted=False
            if value.startswith('read_memory'):
                self.assertTrue(value.endswith(' phys'))
                address=int(value.split()[1],0)
                if address in (0x20040000,0x20010000,0x20000000):return '0x9'
                if address==0x20000f20:return '0x100'
                if address==0x20040020:return '0x4'
                if address==0x20040028:return hex(rp)
                if address==0x20040040:rp+=4;return '0x0'
                return '0x0'
            return ''

        parent=Path(__file__).resolve().parents[2]/'delp/tmp'
        with tempfile.TemporaryDirectory(dir=parent) as tmp:
            rpc=Mock();rpc.run.side_effect=command
            symbols={'trace_begin':0x80000008,'trace_loop':0x80000016,'trace_target':0x8000001a,'trace_done':0x80000022}
            collect(rpc,tmp,symbols,'pressure',halt_during_pressure=True)
            metadata=json.loads((Path(tmp)/'dut/metadata.json').read_text())
            self.assertTrue(metadata['halt_during_pressure'])
            self.assertEqual(metadata['pressure_halt_pc'],'0x80000016')
            self.assertEqual([entry['cause'] for entry in metadata['debug_events']], [3, 2])
            self.assertTrue(metadata['require_debug_classification'])
        halt=commands.index('riscv dmi_write 0x10 0x80000001')
        release=commands.index('write_memory 0x20040000 32 {0x103} phys')
        drained=commands.index('read_memory 0x20000000 32 1 phys',release)
        resume=commands.index('resume')
        self.assertLess(halt,release);self.assertLess(release,drained);self.assertLess(drained,resume)
        self.assertLess(release,commands.index('wait_halt 120000'))
        self.assertLess(commands.index('wait_halt 120000'),commands.index('riscv dmi_write 0x10 0x1'))
        self.assertLess(commands.index('riscv dmi_write 0x10 0x1'),resume)
        self.assertEqual(commands[0],'riscv set_mem_access sysbus')

    def test_real_discovery_read_keeps_raw_words_and_rejects_wrong_headers(self):
        parent = Path(__file__).resolve().parents[2] / 'delp/tmp'
        parent.mkdir(parents=True, exist_ok=True)
        words = [0x4352544f, 1, 20, 15, 0x10008, 0, 8, 8, 0, 31, 63, 7, 1, 0, 49, 0, 0, 2, 4, 0]
        with tempfile.TemporaryDirectory(dir=parent) as tmp:
            rpc=Mock()
            rpc.run.return_value=' '.join(hex(w) for w in words)
            result=capture_discovery(rpc,tmp)
            rpc.run.assert_called_once_with('read_memory 0x20000e00 32 20 phys')
            self.assertEqual(result['profile']['return_stack_size'],4)
            self.assertEqual((Path(tmp)/'discovery.bin').read_bytes(),struct.pack('<20I',*words))
            rpc.run.return_value=' '.join(hex(w) for w in words[:-1])
            with self.assertRaisesRegex(RuntimeError,'twenty'):capture_discovery(rpc,tmp)
            words[0]=0
            rpc.run.return_value=' '.join(hex(w) for w in words)
            with self.assertRaisesRegex(DecodeError,'header'):capture_discovery(rpc,tmp)

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
