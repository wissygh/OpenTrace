from copy import deepcopy
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

from decoder.encapsulation import DecodeError
from decoder.tests.check_xiangshan_sba import check_debug_boundaries
from decoder.tests.xiangshan_sba_scenarios import reference
from decoder.tests.run_xiangshan_sba import configure_ebreak_action


class XiangShanDebugBoundaryTests(unittest.TestCase):
    symbols = {'trace_begin': 0x80000008, 'trace_done': 0x80000034,
               'trace_debug_break': 0x80000010, 'trace_after_ebreak': 0x80000014,
               'trace_handler': 0x80000020}

    def test_ebreak_policy_survives_openocd_resume_and_checks_target_readback(self):
        for debug, action in ((False, 'off'), (True, 'on')):
            with self.subTest(debug=debug):
                rpc = Mock()
                rpc.run.side_effect = ['', f'riscv_ebreakm enabled: {action}\n']
                self.assertEqual(configure_ebreak_action(rpc, debug), action)
                self.assertEqual([call.args[0] for call in rpc.run.call_args_list], [
                    f'riscv set_ebreakm {action}', 'riscv set_ebreakm'])
                rpc.run.reset_mock()
                rpc.run.side_effect = ['', 'riscv_ebreakm enabled: on' if not debug else 'riscv_ebreakm enabled: off']
                with self.assertRaisesRegex(RuntimeError, 'resume policy differs'):
                    configure_ebreak_action(rpc, debug)

    def metadata(self, request='hardware-trigger'):
        entries = []
        if request in ('single-step', 'ebreak-debug'):
            cause = 4 if request == 'single-step' else 1
            entries.append({'kind': request, 'cause': cause, 'dcsr': hex(cause << 6), 'pc': '0x80000010'})
        entries.append({'kind': 'hardware-trigger', 'cause': 2, 'dcsr': '0x80', 'pc': '0x80000034'})
        return {'debug_entry': request, 'debug_events': entries,
                'ordinary_ebreak_cause': 3, 'ordinary_ebreak_tval': 0x80000010}

    def test_debug_modes_end_segments_without_creating_any_architectural_trap(self):
        for request in ('hardware-trigger', 'single-step', 'ebreak-debug'):
            with self.subTest(request=request):
                metadata = self.metadata(request)
                events = [{'kind': 'support', 'qual_status': 1} for _ in metadata['debug_events']]
                self.assertEqual(check_debug_boundaries(events, metadata, self.symbols, 'basic')['status'], 'passed')
                # The originally observed false cause=0 trap must fail even
                # when the captured and independently retired PC lists match.
                ghost = {'kind': 'trap', 'ecause': 0, 'interrupt': False, 'tval': 0, 'handler': False}
                with self.assertRaisesRegex(DecodeError, 'incorrectly encoded'):
                    check_debug_boundaries(events + [ghost], metadata, self.symbols, 'basic')
                with self.assertRaisesRegex(DecodeError, 'trace-end'):
                    check_debug_boundaries(events[:-1], metadata, self.symbols, 'basic')

    def test_every_requested_stop_uses_the_actual_dcsr_cause_in_order(self):
        metadata = self.metadata('single-step')
        events = [{'kind': 'support', 'qual_status': 1}] * 2
        for mutation in ('missing', 'reordered', 'incorrect-cause', 'wrong-pc'):
            bad = deepcopy(metadata)
            if mutation == 'missing':
                bad['debug_events'].pop(0)
            elif mutation == 'reordered':
                bad['debug_events'].reverse()
            elif mutation == 'incorrect-cause':
                bad['debug_events'][0]['dcsr'] = '0x80'
            else:
                bad['debug_events'][-1]['pc'] = '0x80000030'
            with self.subTest(mutation=mutation), self.assertRaises(DecodeError):
                check_debug_boundaries(events, bad, self.symbols, 'basic')

    def test_ordinary_ebreak_keeps_the_real_cause_tval_epc_and_handler(self):
        metadata = self.metadata('ebreak-exception')
        trap = {'kind': 'trap', 'ecause': 3, 'interrupt': False, 'tval': 0x80000010, 'handler': True}
        epc = {'kind': 'exception_pc', 'pc': 0x80000010}
        end = {'kind': 'support', 'qual_status': 1}
        for explicit_epc in (False, True):
            events = [epc, trap, end]
            if explicit_epc:
                events.insert(1, dict(trap, handler=False))
                events.insert(2, {'kind': 'exception_pc', 'pc': None})
            self.assertEqual(check_debug_boundaries(events, metadata, self.symbols, 'ebreak-exception')['status'], 'passed')
            for bad in ([epc, dict(trap, tval=0), end], [dict(epc, pc=0x80000014), trap, end],
                        [epc, trap, dict(trap, handler=False), end]):
                with self.assertRaises(DecodeError):
                    check_debug_boundaries(bad, metadata, self.symbols, 'ebreak-exception')

    @unittest.skipUnless(shutil.which('riscv64-unknown-elf-gcc'), 'RISC-V assembler unavailable')
    def test_ebreak_fixture_layout_keeps_the_nonretired_instruction_out_of_both_references(self):
        root = Path(__file__).resolve().parents[2]
        resources = root / 'src/test/resources/xiangshan'
        temporary = root / 'delp/tmp/q040/tools'
        temporary.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary, prefix='ebreak-assembly-') as directory:
            elf = Path(directory) / 'workload.elf'
            binary = Path(directory) / 'workload.bin'
            subprocess.run(['riscv64-unknown-elf-gcc', '-march=rv64imc_zicsr_zifencei', '-mabi=lp64',
                            '-mcmodel=medany', '-nostdlib', '-static', '-Wl,--no-relax',
                            '-Wl,-T,' + str(resources / 'workload.ld'), str(resources / 'sba-debug-ebreak.S'),
                            '-o', str(elf)], check=True, capture_output=True)
            nm = subprocess.check_output(['riscv64-unknown-elf-nm', str(elf)], text=True)
            symbols = {name: int(address, 16) for address, _, name in (line.split() for line in nm.splitlines())}
            for name, value in self.symbols.items():
                self.assertEqual(symbols[name], value)
            subprocess.run(['riscv64-unknown-elf-objcopy', '-O', 'binary', str(elf), str(binary)], check=True)
            offset = symbols['trace_debug_break'] - 0x80000000
            self.assertEqual(binary.read_bytes()[offset:offset + 4], bytes.fromhex('73001000'))
            for scenario, length in (('ebreak-debug', 5), ('ebreak-exception', 10)):
                value, pcs = reference(symbols, scenario)
                self.assertEqual((value, len(pcs)), (15, length))
                self.assertNotIn(symbols['trace_debug_break'], pcs)
                self.assertEqual(pcs.count(symbols['trace_handler']), int(scenario == 'ebreak-exception'))
