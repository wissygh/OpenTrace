import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from decoder import DecodeError, Packet, PacketParser, Profile, ProgramImage, ExecutionDecoder, MultiHartDecoder
from decoder.encapsulation import SyncAcquirer
from decoder.execution import unpack


PROFILE = Profile(address_width=32, privilege_width=2, context_width=0, time_width=0)


def payload(fields):
    """Construct specification fields in transmission order, then sign-compress."""
    value = offset = 0
    for v, width in fields:
        value |= (v & ((1 << width) - 1)) << offset
        offset += width
    if value & (1 << (offset - 1)):
        value -= 1 << offset
    raw = value.to_bytes((offset + 7) // 8, 'little', signed=True)
    while len(raw) > 1 and ((raw[-1] == 0 and raw[-2] < 128) or (raw[-1] == 255 and raw[-2] >= 128)):
        raw = raw[:-1]
    return raw


def sync(address, branch=1, profile=PROFILE, context=0):
    return payload([(3, 2), (0, 2), (branch, 1), (3, profile.privilege_width),
                    (0, profile.time_width), (context, profile.context_width),
                    (address >> profile.address_lsb, profile.address_width - profile.address_lsb)])


def support(status=0, options=0):
    return payload([(3, 2), (3, 2), (1, 1), (0, 1), (status, 2), (options, 6), (0, 1), (0, 1), (0, 2)])


def address(delta, branch_map=None, branches=0, notify=False, updiscon=False):
    aw = PROFILE.address_width - PROFILE.address_lsb
    fields = [(2 if branch_map is None else 1, 2)]
    if branch_map is not None:
        fields += [(branches, 5), (branch_map, (1 << branches.bit_length()) - 1 if branches else 31)]
        if not branches:
            return payload(fields)
    scaled = (delta >> 1) & ((1 << aw) - 1)
    n = int(notify) ^ (scaled >> (aw - 1))
    return payload(fields + [(scaled, aw), (n, 1), (n ^ int(updiscon), 1)])


def encaps(source, body):
    assert 0 < len(body) <= 31
    return bytes([len(body), source]) + body


def program(base, words, extra=()):
    return ProgramImage([(base, b''.join(w.to_bytes(4, 'little') for w in words))] + list(extra))


class FramingTests(unittest.TestCase):
    def test_specification_atb_example_and_chunked_null_packets(self):
        # Chapter 13.1.4, two ATB transfers with srcID carried out-of-band.
        p = PacketParser(source_bits=0, single_source_id=1)
        data = bytes.fromhex('05 32 04 00 00 02 00 80')
        packets = []
        for byte in data:
            packets += p.feed(bytes([byte]))
        p.finish()
        self.assertEqual(packets, [Packet(1, bytes.fromhex('32 04 00 00 02'))])
        self.assertEqual(p.null_packets, 2)

    def test_header_timestamp_and_sixteen_bit_source(self):
        p = PacketParser(16, 2)
        packet, = p.feed(bytes.fromhex('a1 34 12 78 56 73'))
        self.assertEqual(packet, Packet(0x1234, b'\x73', 1, 0x5678))
        p.finish()

    def test_truncated_packet_and_unsupported_geometry(self):
        p = PacketParser()
        p.feed(b'\x05\x00\x73')
        with self.assertRaisesRegex(DecodeError, 'truncated'):
            p.finish()
        with self.assertRaises(DecodeError):
            PacketParser(6)

    def test_capture_acquisition_requires_long_enough_null_run(self):
        p = SyncAcquirer(PacketParser())
        self.assertEqual(p.feed(b'garbage' + bytes(32)), [])
        self.assertEqual(p.feed(b'\0' + encaps(0, b'\x73')), [Packet(0, b'\x73')])
        p.finish()


class ExecutionTests(unittest.TestCase):
    def decoder(self, image, **kwargs):
        return ExecutionDecoder(image, PROFILE, **kwargs)

    def pcs(self, events):
        return [e['pc'] for e in events if e['kind'] == 'instruction']

    def test_interleaved_harts_have_independent_branch_maps_and_delta_references(self):
        a = program(0x1000, [0x13, 0x00000463, 0x13, 0x0080006f, 0x13, 0x8067], [(0x2000, b'\x01\x00')])
        b = ProgramImage([(0x8000, b'\x01\x00' + (0x00000463).to_bytes(4, 'little') + (0x8067).to_bytes(4, 'little')),
                          (0x9000, b'\x13\x00\x00\x00')])
        d0 = self.decoder(a, source_id=0, hart_id=4)
        d1 = self.decoder(b, source_id=1, hart_id=9)
        decoder = MultiHartDecoder({0: d0, 1: d1})
        capture = (encaps(0, support()) + encaps(1, support()) + encaps(0, sync(0x1000)) +
                   encaps(1, sync(0x8000)) + encaps(0, address(0x1000, 0, 1)) +
                   encaps(1, address(0x1000, 1, 1)) + encaps(0, support(1)) + bytes(3))
        events = []
        for start in range(0, len(capture), 3):
            events += decoder.feed(capture[start:start + 3])
        decoder.finish()
        self.assertEqual(self.pcs([e for e in events if e['hart_id'] == 4]), [0x1000, 0x1004, 0x100c, 0x1014, 0x2000])
        self.assertEqual(self.pcs([e for e in events if e['hart_id'] == 9]), [0x8000, 0x8002, 0x8006, 0x9000])
        self.assertTrue(d0.start_of_trace)
        self.assertFalse(d1.start_of_trace)
        self.assertEqual((d0.address, d1.address), (0x2000, 0x9000))

    def test_branch_only_full_map_stops_on_final_branch(self):
        d = self.decoder(program(0x1000, [0x00000063]))
        first = d.consume(Packet(0, sync(0x1000, branch=0)))
        rest = d.consume(Packet(0, address(0, 0, 0)))
        self.assertEqual(self.pcs(first + rest), [0x1000] * 32)
        self.assertEqual(d.branches, 1)

    def test_unused_branch_map_padding_does_not_become_a_future_outcome(self):
        d = self.decoder(program(0x1000, [0x13, 0x00000263, 0x00000263, 0x8067],
                                 [(0x2000, b'\x13\x00\x00\x00')]))
        d.consume(Packet(0, sync(0x1000)))
        # branches=2 allocates three bits, but its third bit is not an outcome.
        d.consume(Packet(0, address(0x1000, 7, 2)))
        self.assertEqual((d.branches, d.branch_map), (0, 0))

    def test_implicit_vectored_interrupt_handler(self):
        d = self.decoder(program(0x1000, [0x13], [(0x200c, b'\x13\x00\x00\x00')]),
                         trap_vectors={3: 0x2001})
        d.consume(Packet(0, support(options=4)))
        d.consume(Packet(0, sync(0x1000)))
        trap = payload([(3, 2), (1, 2), (1, 1), (3, 2), (3, 8), (1, 1), (1, 1)])
        self.assertEqual(self.pcs(d.consume(Packet(0, trap))), [0x200c])

    def test_first_exception_handler_can_have_an_unknown_epc(self):
        d = self.decoder(program(0x2000, [0x13]))
        trap = payload([(3, 2), (1, 2), (1, 1), (3, 2), (2, 8), (0, 1), (1, 1), (0x1000, 31), (0, 32)])
        events = d.consume(Packet(0, trap))
        self.assertEqual(self.pcs(events), [0x2000])
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'exception_pc'], [None])

    def test_precise_context_transition_uses_the_destination_image(self):
        profile = Profile(address_width=32, privilege_width=2, context_width=8, time_width=0)
        old = program(0x1000, [0x8067])
        new = program(0x2000, [0x13])
        d = ExecutionDecoder(old, profile, contexts={1: new})
        d.consume(Packet(0, sync(0x1000, profile=profile)))
        events = d.consume(Packet(0, sync(0x2000, profile=profile, context=1)))
        self.assertEqual(self.pcs(events), [0x2000])
        self.assertEqual(events[-1]['context'], 1)

    def test_updiscon_and_ended_ntr_resolve_the_second_loop_occurrence(self):
        image = program(0x1000, [0x13, 0x13, 0x8067])
        d = self.decoder(image)
        d.consume(Packet(0, sync(0x1000)))
        self.assertEqual(self.pcs(d.consume(Packet(0, address(4, updiscon=True)))), [0x1004, 0x1008, 0x1004])
        d = self.decoder(image)
        d.consume(Packet(0, sync(0x1000)))
        d.consume(Packet(0, address(4)))
        self.assertEqual(self.pcs(d.consume(Packet(0, support(status=3)))), [0x1008, 0x1004])
        self.assertTrue(d.start_of_trace)

    def test_compressed_jumps_branches_and_rv32_cjal(self):
        image = ProgramImage.from_raw(bytes.fromhex('11 a0 11 e0 82 80'), 0x1000, 32)
        self.assertEqual(image.instruction(0x1000).kind, 'direct')
        self.assertEqual(image.instruction(0x1000).immediate, 4)
        self.assertEqual(image.instruction(0x1002).kind, 'branch')
        self.assertEqual(image.instruction(0x1002).immediate, 4)
        self.assertEqual(image.instruction(0x1004).kind, 'indirect')
        self.assertEqual(ProgramImage.from_raw(bytes.fromhex('11 20'), 0x1000, 32).instruction(0x1000).kind, 'direct')
        self.assertEqual(ProgramImage.from_raw(bytes.fromhex('11 20'), 0x1000, 64).instruction(0x1000).kind, 'linear')

    def test_notification_and_signed_negative_delta(self):
        d = self.decoder(program(0x1000, [0x8067], [(0x800, b'\x13\x00\x00\x00')]))
        d.consume(Packet(0, sync(0x1000)))
        events = d.consume(Packet(0, address(-0x800)))
        self.assertEqual(self.pcs(events), [0x800])

    def test_full_address_and_sequential_jump_modes(self):
        d = self.decoder(program(0x1000, [0x1097, 0x8067], [(0x2000, b'\x13\x00\x00\x00')]))
        d.consume(Packet(0, support(options=33)))
        d.consume(Packet(0, sync(0x1000)))
        self.assertEqual(self.pcs(d.consume(Packet(0, address(0x2000, notify=True)))), [0x1004, 0x2000])

    def test_trap_handler_and_faulting_epc_are_distinct(self):
        d = self.decoder(program(0x1000, [0x8067], [(0x2000, b'\x13\x00\x00\x00')]))
        d.consume(Packet(0, sync(0x1000)))
        trap = payload([(3, 2), (1, 2), (1, 1), (3, 2), (12, 8), (0, 1), (0, 1), (0x3000 >> 1, 31), (0x3000, 32)])
        events = d.consume(Packet(0, trap))
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'exception_pc'], [0x3000])
        self.assertEqual(self.pcs(events), [])
        self.assertEqual(self.pcs(d.consume(Packet(0, sync(0x2000)))), [0x2000])

    def test_interrupt_and_trap_return(self):
        image = program(0x1000, [0x13], [(0x2000, (0x30200073).to_bytes(4, 'little')),
                                       (0x1004, b'\x13\x00\x00\x00')])
        d = self.decoder(image)
        d.consume(Packet(0, sync(0x1000)))
        trap = payload([(3, 2), (1, 2), (1, 1), (3, 2), (7, 8), (1, 1), (1, 1), (0x2000 >> 1, 31)])
        self.assertEqual(self.pcs(d.consume(Packet(0, trap))), [0x2000])
        self.assertEqual(self.pcs(d.consume(Packet(0, address(0x1004 - 0x2000)))), [0x1004])

    def test_ambiguous_loop_waits_for_the_next_packet(self):
        d = self.decoder(program(0x1000, [0x13, 0x13, 0x8067]))
        d.consume(Packet(0, sync(0x1000)))
        self.assertEqual(self.pcs(d.consume(Packet(0, address(4)))), [0x1004])
        self.assertTrue(d.inferred_address)
        self.assertEqual(self.pcs(d.consume(Packet(0, sync(0x1008)))), [0x1008])
        self.assertFalse(d.inferred_address)

    def test_unsupported_modes_and_missing_instruction_bytes_fail_explicitly(self):
        d = self.decoder(program(0x1000, [0x13]))
        for options in (2, 8, 16):
            with self.assertRaises(DecodeError):
                d.consume(Packet(0, support(options=options)))
        with self.assertRaisesRegex(DecodeError, 'mapped'):
            d.consume(Packet(0, sync(0x8000)))

    def test_unknown_source_is_not_merged_into_an_existing_hart(self):
        d = MultiHartDecoder({0: self.decoder(program(0x1000, [0x13]))})
        with self.assertRaisesRegex(DecodeError, 'srcID 1'):
            d.feed(encaps(1, sync(0x1000)))


class ElfAndCliTests(unittest.TestCase):
    def test_elf32_and_elf64_load_executable_segments(self):
        for bits in (32, 64):
            with self.subTest(bits=bits), tempfile.TemporaryDirectory() as tmp:
                ident = b'\x7fELF' + bytes([2 if bits == 64 else 1, 1, 1]) + bytes(9)
                if bits == 64:
                    header = struct.pack('<HHIQQQIHHHHHH', 2, 243, 1, 0x1000, 64, 0, 0, 64, 56, 1, 0, 0, 0)
                    ph = struct.pack('<IIQQQQQQ', 1, 5, 0x100, 0x1000, 0x1000, 4, 4, 4)
                else:
                    header = struct.pack('<HHIIIIIHHHHHH', 2, 243, 1, 0x1000, 52, 0, 0, 52, 32, 1, 0, 0, 0)
                    ph = struct.pack('<IIIIIIII', 1, 0x100, 0x1000, 0x1000, 4, 4, 5, 4)
                file = Path(tmp) / 'code.elf'
                file.write_bytes((ident + header + ph).ljust(0x100, b'\0') + b'\x13\x00\x00\x00')
                image = ProgramImage.from_elf(file)
                self.assertEqual((image.xlen, image.instruction(0x1000).bits), (bits, 0x13))

    def test_cli_decodes_a_capture_and_rejects_truncation(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / 'code.bin').write_bytes(b'\x13\x00\x00\x00')
            (base / 'config.json').write_text(json.dumps({'profile': {'address_width': 32, 'privilege_width': 2,
                'context_width': 0, 'time_width': 0}, 'sources': {'0': {'hart_id': 4, 'raw': 'code.bin', 'base': '0x1000'}}}))
            capture = base / 'trace.bin'
            capture.write_bytes(encaps(0, sync(0x1000)))
            command = [sys.executable, '-m', 'decoder', str(capture), '--config', str(base / 'config.json')]
            result = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            event = json.loads(result.stdout)
            self.assertEqual((event['hart_id'], event['pc']), (4, 0x1000))
            capture.write_bytes(capture.read_bytes()[:-1])
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)


if __name__ == '__main__':
    unittest.main()
