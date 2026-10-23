import unittest

from decoder import DecodeError, ExecutionDecoder, Packet, Profile, ProgramImage
from decoder.execution import unpack
from decoder.tests.test_decoder import payload, support, sync


PROFILE = Profile(address_width=32, privilege_width=3, context_width=0, time_width=0)


def trap(privilege=3, interrupt=False, cause=2, handler=True, address=None, tval=0):
    fields = [(3, 2), (1, 2), (1, 1), (privilege, 3), (cause, 8),
              (int(interrupt), 1), (int(handler), 1)]
    if address is not None:
        fields.append((address >> 1, 31))
    if not interrupt:
        fields.append((tval, 32))
    return payload(fields)


class ImplicitExceptionTests(unittest.TestCase):
    def decoder(self, vectors=None, target=0x2000):
        image = ProgramImage([(0x1000, bytes.fromhex('1300000013000000')),
                              (target, bytes.fromhex('1300000013000000'))])
        decoder = ExecutionDecoder(image, PROFILE, trap_vectors=vectors)
        decoder.consume(Packet(0, support(options=4)))
        decoder.consume(Packet(0, sync(0x1000, profile=PROFILE)))
        return decoder

    def test_direct_and_vectored_modes_use_destination_privilege_and_only_vector_interrupts(self):
        for privilege in (1, 3, 6):
            for mode in (0, 1):
                for interrupt in (False, True):
                    with self.subTest(privilege=privilege, mode=mode, interrupt=interrupt):
                        target = 0x201c if mode and interrupt else 0x2000
                        decoder = self.decoder({privilege: 0x2000 | mode}, target)
                        events = decoder.consume(Packet(0, trap(privilege, interrupt, cause=7, tval=0x80001234)))
                        self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], [target])
                        self.assertEqual(decoder.address, target)
                        if not interrupt:
                            self.assertEqual([e['pc'] for e in events if e['kind'] == 'exception_pc'], [0x1004])
                            self.assertEqual(next(e for e in events if e['kind'] == 'trap')['tval'], 0x80001234)

    def test_epc_and_later_synchronization_do_not_need_a_vector(self):
        decoder = self.decoder()
        events = decoder.consume(Packet(0, trap(handler=False, address=0x1004, tval=0x1234)))
        self.assertFalse(any(e['kind'] == 'instruction' for e in events))
        events = decoder.consume(Packet(0, sync(0x2000, profile=PROFILE)))
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], [0x2000])
        self.assertEqual(decoder.address, 0x2000)

    def test_invalid_or_missing_vectors_are_rejected_without_guessing_handler(self):
        for vectors, reason in ((None, 'appropriate trap vector'), ({1: 0x2000}, 'appropriate trap vector'),
                                ({3: 0x2002}, 'unsupported trap-vector mode'),
                                ({3: -1}, 'unsigned address'), ({3: True}, 'unsigned address'),
                                ({3: 1 << 64}, 'unsigned address'),
                                ({3: 0xfffffffffffffffd}, 'exceeds XLEN')):
            with self.subTest(vectors=vectors):
                decoder = self.decoder(vectors)
                with self.assertRaisesRegex(DecodeError, reason):
                    decoder.consume(Packet(0, trap(interrupt=True, cause=7)))

    def test_tval_begins_immediately_after_thaddr_when_handler_address_is_omitted(self):
        for value in (0, 1, 0x12345678, 0x80000000, 0xffffffff):
            body = trap(tval=value)
            fields = []
            decoded = unpack(body, PROFILE, options=4, fields=fields)
            self.assertIsNone(decoded['address'])
            self.assertEqual(decoded['tval'], value)
            self.assertNotIn('address', [field['name'] for field in fields])


if __name__ == '__main__':
    unittest.main()
