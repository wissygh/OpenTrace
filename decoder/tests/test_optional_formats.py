import unittest
from decoder import DecodeError, Packet, ProgramImage, ExecutionDecoder
from decoder.execution import Profile, unpack
from decoder.tests.test_decoder import payload, support, sync, address, PROFILE


def cached(index, outcomes=()):
    fields = [(0, 2), (1, 1), (index, 8), (len(outcomes), 5)]
    if outcomes:
        bits = sum(int(not taken) << i for i, taken in enumerate(outcomes))
        fields.append((bits, (1 << len(outcomes).bit_length()) - 1))
    return payload(fields)


class JumpTargetCacheTests(unittest.TestCase):
    def image(self):
        segments = []
        for pc, jump in ((0x1000, 0x28067), (0x2000, 0x30067), (0x2100, 0x28067), (0x2200, 0x28067)):
            segments.append((pc, (0x13).to_bytes(4, 'little') + jump.to_bytes(4, 'little')))
        return ProgramImage(segments)

    def start(self, full=False):
        decoder = ExecutionDecoder(self.image(), PROFILE)
        decoder.consume(Packet(0, support(options=16 | (32 if full else 0))))
        events = decoder.consume(Packet(0, sync(0x1000)))
        events += decoder.consume(Packet(0, address(0x2000 if full else 0x1000)))
        events += decoder.consume(Packet(0, address(0x2100 if full else 0x100)))
        return decoder, events

    def test_cache_hits_and_reference_updates_in_delta_and_full_modes(self):
        for full in (False, True):
            d, events = self.start(full)
            events += d.consume(Packet(0, cached(0)))
            events += d.consume(Packet(0, cached(128)))
            events += d.consume(Packet(0, address(0x2104 if full else 4)))
            events += d.consume(Packet(0, support(1, options=16 | (32 if full else 0))))
            self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'],
                             [0x1000, 0x1004, 0x2000, 0x2004, 0x2100, 0x2104, 0x2000, 0x2004, 0x2100, 0x2104])
            self.assertTrue(d.start_of_trace)

    def test_all_map_sizes_and_empty_map_use_one_bit_subformat(self):
        for count in (0, 1, 2, 3, 4, 7, 8, 15, 16, 30, 31):
            outcomes = tuple(i % 2 == 0 for i in range(count))
            decoded = unpack(cached(0xa5, outcomes), PROFILE, 16)
            self.assertEqual(decoded['index'], 0xa5)
            self.assertEqual(decoded['branches'], count)
            self.assertEqual(decoded['branch_map'], sum(int(not taken) << i for i, taken in enumerate(outcomes)))

    def test_collision_replaces_the_index_and_synchronization_invalidates_it(self):
        d, _ = self.start()
        d.consume(Packet(0, address(0x100)))  # 0x2200 collides with 0x2000 at index zero
        events = d.consume(Packet(0, cached(0)))
        self.assertEqual(events[-1]['pc'], 0x2200)
        d.consume(Packet(0, sync(0x2204)))
        with self.assertRaisesRegex(DecodeError, 'cache index 0 is invalid'):
            d.consume(Packet(0, cached(0)))

    def test_context_only_preserves_cache_and_configuration_clears_it(self):
        d, _ = self.start()
        context = payload([(3, 2), (2, 2), (3, 2)])
        d.consume(Packet(0, context))
        events = d.consume(Packet(0, cached(0)))
        self.assertEqual(events[-1]['pc'], 0x2000)
        d.consume(Packet(0, support(options=16)))
        with self.assertRaisesRegex(DecodeError, 'invalid'):
            d.consume(Packet(0, cached(128)))

    def test_disabled_mode_missing_discovery_and_invalid_index_fail(self):
        with self.assertRaises(DecodeError):
            unpack(cached(0), PROFILE, 0)
        d = ExecutionDecoder(self.image(), Profile(cache_size=0))
        with self.assertRaisesRegex(DecodeError, 'discovery'):
            d.consume(Packet(0, support(options=16)))
        d = ExecutionDecoder(self.image(), PROFILE)
        d.consume(Packet(0, support(options=16)))
        d.consume(Packet(0, sync(0x1000)))
        with self.assertRaisesRegex(DecodeError, 'invalid'):
            d.consume(Packet(0, cached(0)))


if __name__ == '__main__':
    unittest.main()
