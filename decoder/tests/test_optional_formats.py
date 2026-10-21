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


def predicted(correct, delta=None, failed=False, notify=False, branch_format=None):
    bf = (0 if delta is None else 3 if failed else 2) if branch_format is None else branch_format
    fields = [(0, 2), (0, 1), (correct - 31, 32), (bf, 2)]
    if delta is not None:
        scaled = (delta >> 1) & 0x7fffffff
        n = (scaled >> 30) ^ int(notify)
        fields += [(scaled, 31), (n, 1), (n, 1)]
    return payload(fields)


class BranchPredictionTests(unittest.TestCase):
    def program(self, twice=False, failed=True):
        raw = bytearray(0x114)
        pcs = [0x1000] + [0x1004 + i * 4 for i in range(31)]
        if failed:
            pcs += [0x1080]
        if twice:
            pcs += [0x1088 + i * 4 for i in range(31)] + [0x1104, 0x110c, 0x1110]
        else:
            pcs += [0x1088 if failed else 0x1080]
        for pc in pcs:
            word = 0x1463 if pc in range(0x1004, 0x1080, 4) or (twice and pc in range(0x1088, 0x1104, 4)) else 0x13
            if failed and pc == 0x1080 or twice and pc == 0x1104:
                word = 0x463
            raw[pc - 0x1000:pc - 0x1000 + 4] = word.to_bytes(4, 'little')
        return ProgramImage.from_raw(raw, 0x1000), pcs

    def decoder(self, twice=False, failed=True):
        image, pcs = self.program(twice, failed)
        d = ExecutionDecoder(image, PROFILE)
        d.consume(Packet(0, support(options=8)))
        events = d.consume(Packet(0, sync(0x1000)))
        return d, events, pcs

    def test_consecutive_zero_payloads_preserve_each_implicit_failed_branch(self):
        d, events, pcs = self.decoder(twice=True)
        self.assertEqual(predicted(31), b'\0')
        for _ in range(2):
            events += d.consume(Packet(0, predicted(31)))
        events += d.consume(Packet(0, address(0x10c, notify=True)))
        events += d.consume(Packet(0, address(4)))
        events += d.consume(Packet(0, support(1, options=8)))
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], pcs)
        self.assertTrue(d.start_of_trace)

    def test_address_fail_and_last_correct_branch_anchors(self):
        for failed in (False, True):
            d, events, pcs = self.decoder(failed=failed)
            anchor = 0x1080 if failed else 0x107c
            events += d.consume(Packet(0, predicted(31, anchor - 0x1000, failed=failed, notify=True)))
            events += d.consume(Packet(0, address(pcs[-1] - anchor)))
            events += d.consume(Packet(0, support(1, options=8)))
            self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], pcs)

    def test_full_32_bit_count_and_invalid_formats(self):
        maximum = 0xffffffff + 31
        result = unpack(predicted(maximum, 4), PROFILE, 8)
        self.assertEqual(result['branch_count'], maximum)
        self.assertTrue(result['prediction_max'])
        for invalid in (predicted(31, branch_format=1), predicted(maximum)):
            with self.assertRaises(DecodeError):
                unpack(invalid, PROFILE, 8)
        with self.assertRaises(DecodeError):
            unpack(predicted(31), PROFILE, 0)

    def test_discovery_and_ambiguous_subformat_are_rejected(self):
        image, _ = self.program()
        for profile, options in ((Profile(bpred_size=0), 8), (Profile(f0s_width=0), 24)):
            d = ExecutionDecoder(image, profile)
            with self.assertRaises(DecodeError):
                d.consume(Packet(0, support(options=options)))


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
