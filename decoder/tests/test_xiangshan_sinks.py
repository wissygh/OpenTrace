from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from decoder.encapsulation import DecodeError
from decoder.tests.xiangshan_sink_capture import atb_bytes, pib_bytes
from decoder.tests.xiangshan_sba_scenarios import reference


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
