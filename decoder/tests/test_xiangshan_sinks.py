from pathlib import Path
import json
import signal
import shutil
import subprocess
import tempfile
import time
import unittest

from decoder.encapsulation import DecodeError
from decoder.tests.xiangshan_sink_capture import atb_bytes, pib_bytes
from decoder.tests.xiangshan_sba_scenarios import reference
from decoder.tests.check_xiangshan_sba import check as check_sram
from decoder.tests.run_xiangshan_sba import stop_capture


def pin_line(cycle, clock, data, control=0):
    return f'[OT-PIB-PINS] cycle={cycle} clock={clock} control={control} data=0x{data:04x}\n'


def serial_log(symbols, divider, idle):
    # Independent timed line stimulus; log transitions, not a sample per bit.
    cycle = 10
    previous = idle
    lines = [pin_line(0, 0, idle)]
    for symbol in symbols:
        if symbol != previous:
            lines.append(pin_line(cycle, 0, symbol))
        previous = symbol
        cycle += divider + 1
    return ''.join(lines)


class XiangShanSinkExtractionTests(unittest.TestCase):
    def test_sink_shutdown_flushes_the_final_buffered_observation_record(self):
        temporary = Path(__file__).resolve().parents[2] / 'delp/tmp/requirements9/tools'
        temporary.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary) as directory:
            root = Path(directory)
            ready = root / 'ready'
            program = ('import signal,sys; from pathlib import Path; '
                       'signal.signal(signal.SIGINT,lambda *_:sys.exit(0)); '
                       'print("final accepted sink transfer",flush=False); '
                       'Path(sys.argv[1]).write_text("ready"); signal.pause()')
            with (root / 'stdout.log').open('w') as log:
                process = subprocess.Popen(['python3', '-c', program, str(ready)], stdout=log)
                try:
                    deadline = time.monotonic() + 5
                    while not ready.exists():
                        self.assertIsNone(process.poll())
                        if time.monotonic() > deadline:
                            self.fail('buffered observer did not start')
                        time.sleep(0.01)
                    self.assertEqual((root / 'stdout.log').read_text(), '')
                    stop_capture(process, None, flush_output=True)
                    self.assertEqual(process.returncode, 0)
                    self.assertEqual((root / 'stdout.log').read_text(), 'final accepted sink transfer\n')
                finally:
                    if process.poll() is None:
                        process.kill(); process.wait()

    def test_ordinary_exception_checker_rejects_wrong_tval_even_with_identical_retired_pcs(self):
        # Actual feature-zero capture; the mutation changes tval only, retaining
        # the handler, all retired PCs and the independent ROB samples.
        from decoder.encapsulation import PacketParser
        from decoder.tests.test_decoder import payload
        raw = bytes.fromhex(
            '01001f1100730000000000000000000000000400004001001a1300770000000000000000000000000b5a000000010700'
            'e2ffffffffff0f130077000000000000000000000000025a000000010100f201000a130077000000000000000000000000'
            '02880000000101005f01000f000000')
        code = bytes.fromhex(
            '731040306f00000097020000938242027390523013050000730000001305150000000000130525006f00c001'
            '73232034f323103493834300739013341305a500730020306f000000')
        symbols = {'trace_begin': 0x80000008, 'trace_done': 0x80000044, 'trace_handler': 0x8000002c,
                   'trace_after_ecall': 0x8000001c, 'trace_after_illegal': 0x80000024}
        _, expected = reference(symbols, 'exception')
        temporary = Path(__file__).resolve().parents[2] / 'delp/tmp/requirements9/tools'
        temporary.mkdir(parents=True, exist_ok=True)
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory(dir=temporary) as directory:
                root = Path(directory)
                capture = raw
                if corrupt:
                    parser = PacketParser()
                    packets = parser.feed(raw); parser.finish()
                    pieces = []
                    replaced = False
                    for packet in packets:
                        body = packet.payload
                        if body[0] & 15 == 7 and not replaced:
                            # Explicit trap: 114 prefix bits, then 49 address
                            # bits, then the 50-bit tval. Set only tval to one.
                            value = int.from_bytes(body, 'little', signed=True) & ((1 << 163) - 1)
                            body = payload([(value, 163), (1, 50)])
                            replaced = True
                        pieces.append(bytes([len(body), packet.source_id]) + body)
                    capture = b''.join(pieces)
                    capture += bytes((-len(capture)) % 4)
                (root / 'trace.bin').write_bytes(capture)
                (root / 'code.bin').write_bytes(code)
                (root / 'trace.json').write_text(json.dumps({'sources': {'0': {'hart_id': 0,
                    'raw': 'code.bin', 'base': '0x80000000'}}}))
                (root / 'symbols.json').write_text(json.dumps(symbols))
                (root / 'workload').mkdir()
                (root / 'workload/scenario.json').write_text(json.dumps({'scenario': 'exception'}))
                (root / 'metadata.json').write_text(json.dumps({'memory_access': 'sysbus',
                    'pc': symbols['trace_done'], 'a0': 23, 'encoder': 9, 'funnel': 9, 'sram': 9,
                    'wp': len(capture), 'rp': len(capture), 'dropped': 0, 'loss_markers': 0,
                    'trap_vector_readback': symbols['trace_handler']}))
                (root / 'rob.log').write_text(''.join(
                    f'[opentrace-commit] hart=0 cycle={i} slot=0 pc=0x{pc:x}\n' for i, pc in enumerate(expected)))
                if corrupt:
                    with self.assertRaisesRegex(DecodeError, 'tval changed'):
                        check_sram(root)
                    result = json.loads((root / 'diff/comparison.json').read_text())
                    self.assertEqual(result['decoded_instructions'], 19)
                    self.assertEqual(result['status'], 'failed')
                else:
                    self.assertEqual(check_sram(root)['status'], 'passed')

    def test_atb_uses_only_recorded_handshakes_and_valid_bytes_with_separate_bridge_id(self):
        text = ('unrelated UART output\n'
                '[OT-ATB-DATA] cycle= 10 id= 42 bytes= 4 data=0x04030201\n'
                '[OT-ATB-AF-REQ] cycle=11 oldTransfers=1\n'
                '[OT-ATB-DATA] cycle= 14 id= 42 bytes= 2 data=0xffff0605\n')
        data, rows = atb_bytes(text)
        self.assertEqual(data, bytes(range(1, 7)))
        self.assertEqual([row['valid_bytes'] for row in rows], [4, 2])
        for invalid in (text.replace('id= 42', 'id= 0'), text.replace('bytes= 2', 'bytes= 5'),
                        text.replace('cycle= 14', 'cycle= 10'), 'no handshake'):
            with self.assertRaises(DecodeError):
                atb_bytes(invalid)

    def test_parallel_samples_both_clock_edges_ignores_centered_data_only_transitions(self):
        packet = bytes.fromhex('00 a5 ff 13 80 00')
        for mode in range(8, 13):
            width = 1 << (mode - 8)
            bits = [(byte >> i) & 1 for byte in packet for i in range(8)]
            symbols = [sum(bit << i for i, bit in enumerate(bits[p:p + width])) for p in range(0, len(bits), width)]
            for centered in (False, True):
                with self.subTest(mode=mode, centered=centered):
                    lines = [pin_line(0, 0, 0)]
                    clock = 0
                    for i, data in enumerate(symbols):
                        cycle = 10 + i * 8
                        if centered:
                            lines.append(pin_line(cycle - 2, clock, data))
                        clock ^= 1
                        lines.append(pin_line(cycle, clock, data))
                    actual, _ = pib_bytes(''.join(lines), mode, 3)
                    self.assertEqual(actual, packet)

    def test_uart_recovers_lsb_bytes_and_checks_start_stop_during_long_idle(self):
        packet = bytes.fromhex('00 ff a5 5a 01 80')
        for divider in (0, 1, 3, 31):
            symbols = [1] * 5
            for byte in packet:
                symbols += [0] + [(byte >> i) & 1 for i in range(8)] + [1] * 4
            actual, _ = pib_bytes(serial_log(symbols, divider, 1), 5, divider)
            self.assertEqual(actual, packet)
        invalid = serial_log([0] + [1] * 8 + [0] * 4, 3, 1)
        with self.assertRaisesRegex(DecodeError, 'start/stop'):
            pib_bytes(invalid, 5, 3)

    def test_manchester_recovers_multiple_packets_and_maximum_alignment_frame(self):
        packets = [bytes(33) + b'\x80', b'\xa5\x00\xff', b'\x01']
        for divider in (0, 2, 7):
            symbols = [0] * 5
            for packet in packets:
                bits = [(byte >> i) & 1 for byte in packet for i in range(8)]
                symbols += [1, 0] + [half for bit in bits for half in (bit, 1 - bit)] + [0] * 6
            actual, _ = pib_bytes(serial_log(symbols, divider, 0), 4, divider)
            self.assertEqual(actual, b''.join(packets))
        invalid = serial_log([1, 0, 1, 1, 0, 0], 2, 0)
        with self.assertRaisesRegex(DecodeError, 'stop delimiter'):
            pib_bytes(invalid, 4, 2)

    def test_invalid_control_or_serial_clock_is_rejected(self):
        for text, mode in ((pin_line(1, 1, 0, control=1), 11), (pin_line(1, 1, 0), 5), ('', 4)):
            with self.assertRaises(DecodeError):
                pib_bytes(text, mode, 3)

    @unittest.skipUnless(shutil.which('riscv64-unknown-elf-gcc'), 'RISC-V assembler unavailable')
    def test_exception_fixture_layout_and_reference_keep_both_handler_visits(self):
        root = Path(__file__).resolve().parents[2]
        resources = root / 'src/test/resources/xiangshan'
        temporary = root / 'delp/tmp/requirements9/tools'
        temporary.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary, prefix='exception-assembly-') as directory:
            elf = Path(directory) / 'workload.elf'
            subprocess.run(['riscv64-unknown-elf-gcc', '-march=rv64imc_zicsr_zifencei', '-mabi=lp64',
                            '-mcmodel=medany', '-nostdlib', '-static', '-Wl,--no-relax',
                            '-Wl,-T,' + str(resources / 'workload.ld'), str(resources / 'sba-exception.S'),
                            '-o', str(elf)], check=True, capture_output=True)
            output = subprocess.check_output(['riscv64-unknown-elf-nm', str(elf)], text=True)
            symbols = {name: int(address, 16) for address, _, name in (line.split() for line in output.splitlines())}
            a0, pcs = reference(symbols, 'exception')
            self.assertEqual((a0, len(pcs)), (23, 19))
            self.assertEqual(pcs.count(symbols['trace_handler']), 2)
            self.assertNotIn(symbols['trace_begin'] + 16, pcs)
            self.assertNotIn(symbols['trace_begin'] + 24, pcs)
            self.assertTrue(all(pc > 0x80000000 for pc in pcs))


if __name__ == '__main__':
    unittest.main()
