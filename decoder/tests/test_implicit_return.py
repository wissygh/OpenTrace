"""Independent instruction images and E-Trace wire vectors for full RAS mode."""
from contextlib import redirect_stdout
from dataclasses import replace, asdict
import io
import json
from pathlib import Path
import tempfile
import unittest

from decoder import DecodeError, ExecutionDecoder, Packet, ProgramImage, MultiHartDecoder
from decoder.execution import unpack
from decoder.tests.test_decoder import PROFILE, payload, support, sync, encaps
from decoder.tests.check_implicit_return import main as check_all

P = replace(PROFILE, address_width=50, return_stack_size=4)
BASE = 0x80001000


def addr(value, profile=P, depth=None, notify=False, before_sync=False, branches=()):
    aw = profile.address_width - profile.address_lsb
    scaled = (value >> profile.address_lsb) & ((1 << aw) - 1)
    n = (scaled >> (aw - 1)) ^ int(notify)
    u = n ^ int(before_sync)
    fields = [(1 if branches else 2, 2)]
    if branches:
        count = len(branches)
        fields += [(count, 5), (sum(int(not taken) << i for i, taken in enumerate(branches)), (1 << count.bit_length()) - 1)]
    fields += [(scaled, aw), (n, 1), (u, 1)]
    if depth is not None:
        fields += [(u ^ 1, 1), (depth, profile.return_stack_size + 1)]
    return payload(fields)


def image(words, halfwords=None, xlen=64):
    segments = [(BASE + offset, bits.to_bytes(4, 'little')) for offset, bits in words.items()]
    segments += [(BASE + offset, bits.to_bytes(2, 'little')) for offset, bits in (halfwords or {}).items()]
    return ProgramImage(segments, xlen)


class ImplicitReturnTests(unittest.TestCase):
    def test_capture_rejects_wrong_mode_bits_stale_features_loss_and_pointer_evidence(self):
        temporary = Path(__file__).resolve().parents[2] / 'delp/tmp'
        temporary.mkdir(parents=True, exist_ok=True)
        for fault in (None, 'mode', 'features', 'loss', 'pointer'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=temporary) as tmp:
                root=Path(tmp)
                for category in ('workload','dut','ref','diff'): (root/category).mkdir()
                raw=bytearray(0x104)
                for offset,word in {0:0x100000ef,0x100:0x8067,4:0x13,8:0x13}.items():
                    raw[offset:offset+4]=word.to_bytes(4,'little')
                (root/'workload/code.bin').write_bytes(raw)
                config={'profile':asdict(P),'sources':{'0':{'hart_id':0,'raw':'../workload/code.bin','base':hex(BASE)}}}
                (root/'dut/trace.json').write_text(json.dumps(config))
                data=b''.join(encaps(0,b) for b in (support(options=2),sync(BASE,profile=P),addr(8,depth=0),support(1,options=2)))
                data+=bytes(-len(data)%4)
                (root/'dut/trace.bin').write_bytes(data)
                (root/'ref/expected.json').write_text(json.dumps([BASE+i for i in (0,0x100,4,8)]))
                metadata={'features_requested':8,'features_readback':0x80000008,'dropped':0,
                          'loss_markers':0,'wp':len(data),'rp':len(data)}
                if fault=='mode':metadata['features_readback']|=0xc0
                if fault=='features':metadata.update(features_requested=0,features_readback=0x80000000)
                if fault=='loss':metadata['dropped']=1
                if fault=='pointer':metadata['rp']-=4
                (root/'dut/metadata.json').write_text(json.dumps(metadata))
                (root/'summary.json').write_text(json.dumps({'status':'passed'}))
                with redirect_stdout(io.StringIO()):
                    if fault:
                        with self.assertRaises(ValueError):check_all([str(root)])
                    else:check_all([str(root)])
                result=json.loads((root/'diff/comparison.json').read_text())
                self.assertEqual(result['status'],'failed' if fault else 'passed')
                self.assertEqual(json.loads((root/'summary.json').read_text())['status'],'failed' if fault else 'passed')

    def decode(self, img, packets, profile=P, options=2):
        d = ExecutionDecoder(img, profile)
        events = []
        for body in [support(options=options), sync(BASE, profile=profile), *packets, support(1, options=options)]:
            events += d.consume(Packet(0, body))
        self.assertTrue(d.start_of_trace)
        return d, [e['pc'] for e in events if e['kind'] == 'instruction']

    def test_nested_calls_and_sync_at_call_infer_both_returns(self):
        code = image({0: 0x100000ef, 0x100: 0x100000ef, 0x200: 0x8067, 0x104: 0x8067, 4: 0x13, 8: 0x13})
        d, pcs = self.decode(code, [addr(8, depth=0)])
        self.assertEqual(pcs, [BASE + i for i in (0, 0x100, 0x200, 0x104, 4, 8)])
        self.assertEqual(d.return_stack, [])

    def test_return_followed_by_a_new_call_ends_without_aliasing_the_earlier_return_depth(self):
        code=image({0:0x100000ef,0x100:0x8067,4:0x1fc000ef,0x200:0x13,0x204:0x13})
        _,pcs=self.decode(code,[addr(0x204)])
        self.assertEqual(pcs,[BASE+i for i in (0,0x100,4,0x200,0x204)])

    def test_consecutive_depth_anchors_resolve_returns_without_repeating_retirement(self):
        code = image({0: 0x100000ef, 0x100: 0x100000ef, 0x200: 0x8067, 0x104: 0x8067, 4: 0x13, 8: 0x13})
        _, pcs = self.decode(code, [addr(0x200, depth=2), addr(-0xfc, depth=1),
                                   sync(BASE+4, profile=P), addr(4, depth=0)])
        self.assertEqual(pcs, [BASE+i for i in (0, 0x100, 0x200, 0x104, 4, 8)])

    def test_recursive_notify_and_stop_select_the_correct_occurrence_of_the_same_pc(self):
        code = image({0: 0x100000ef, 0x100: 0x463, 0x104: 0xffdff0ef, 0x108: 0x8067, 4: 0x13, 8: 0x13})
        for notify in (False, True):
            with self.subTest(notify=notify):
                _, pcs = self.decode(code, [addr(0x108, depth=2, notify=notify, branches=[False, False, True]),
                                           addr(-0x100, depth=0)])
                self.assertEqual(pcs, [BASE+i for i in (0, 0x100, 0x104, 0x100, 0x104, 0x100, 0x108, 0x108, 0x108, 4, 8)])

    def test_loss_and_state_of_interleaved_sources_are_independent(self):
        code = image({0: 0x300e7, 0x100: 0x8067, 4: 0x13, 8: 0x13})
        a = ExecutionDecoder(code, P, source_id=0, hart_id=3)
        b = ExecutionDecoder(code, replace(P, return_stack_size=1), source_id=1, hart_id=4)
        d = MultiHartDecoder({0:a, 1:b})
        for source in (0,1):
            d.feed(encaps(source,support(options=2)) + encaps(source,sync(BASE,profile=P)) +
                   encaps(source,addr(0x100)))
        self.assertEqual(a.return_stack, [BASE+4]); self.assertEqual(b.return_stack, [BASE+4])
        d.feed(encaps(0,support(2,options=2)))
        self.assertEqual(a.return_stack, []); self.assertEqual(b.return_stack, [BASE+4])
        events=d.feed(encaps(1,addr(-0xf8,profile=b.profile,depth=0)) + encaps(1,support(1,options=2)))
        self.assertEqual([e['pc'] for e in events if e['kind']=='instruction'], [BASE+4,BASE+8])
        self.assertTrue(a.start_of_trace);self.assertTrue(b.start_of_trace)

    def test_format0_cache_depth_uses_allocated_map_msb_and_zero_branch_count(self):
        for branches in (0,1,2,3,4,7,8,15,16,31):
            width=(1<<branches.bit_length())-1
            raw_map=(1<<width)-1
            prefix=[(0,2),(1,1),(0x81,8),(branches,5)] + ([(raw_map,width)] if branches else [])
            preceding=1 if branches else 0
            body=payload(prefix+[(preceding ^ 1,1),(16,5)])
            p=unpack(body,P,18)
            self.assertTrue(p['irreport']);self.assertEqual(p['irdepth'],16)
            self.assertEqual(p['branch_map'],(1<<branches)-1)

    def test_modified_return_reports_before_pop_and_leaves_prediction_stack(self):
        code = image({0: 0x100000ef, 0x100: 0x100000ef, 0x200: 0x8067, 0x300: 0x13, 0x304: 0x13})
        d = ExecutionDecoder(code, P)
        for body in (support(options=2), sync(BASE, profile=P), addr(0x300, depth=2)):
            events = d.consume(Packet(0, body))
        self.assertEqual([e['pc'] for e in events if e['kind'] == 'instruction'], [BASE + i for i in (0x100, 0x200, 0x300)])
        self.assertEqual(d.return_stack, [BASE+4, BASE+0x104])
        d.consume(Packet(0, addr(4, depth=2)))
        self.assertEqual(d.pc, BASE+0x304)

    def test_x5_calls_compressed_returns_and_tail_calls_follow_the_same_stack(self):
        # jal x5,+0x100, then c.jr x5: link is caller+4.
        code = image({0: 0x100002ef, 4: 0x13, 8: 0x13}, {0x100: 0x8282})
        _, pcs = self.decode(code, [addr(8, depth=0)])
        self.assertEqual(pcs, [BASE+i for i in (0, 0x100, 4, 8)])
        # RV32 C.JAL +4, C.JR ra: the saved return address is +2.
        code = image({0x10: 0x13}, {0: 0x2011, 2: 0xa039, 4: 0x8082}, xlen=32)
        d = ExecutionDecoder(code, P)
        d.consume(Packet(0, support(options=2)))
        d.consume(Packet(0, sync(BASE, profile=P)))
        self.assertFalse(d._next(BASE+4, {})); self.assertEqual(d.return_stack, [BASE+2])
        self.assertFalse(d._next(0, {})); self.assertEqual(d.pc, BASE+2)
        self.assertEqual(d.return_stack, [])
        # jal x0 is a tail call; no push. Return while empty stays explicit.
        code = image({0: 0x1000006f, 0x100: 0x8067, 0x200: 0x13, 0x204: 0x13})
        _, pcs = self.decode(code, [addr(0x200), addr(4, depth=0)])
        self.assertEqual(pcs, [BASE+i for i in (0, 0x100, 0x200, 0x204)])

    def test_each_source_owns_stack_and_overflow_keeps_recent_calls(self):
        p = replace(P, return_stack_size=1)
        code = image({0: 0x100000ef, 0x100: 0x100000ef, 0x200: 0x100000ef, 0x300: 0x8067,
                      0x204: 0x8067, 0x104: 0x8067, 4: 0x13, 8: 0x13})
        d, pcs = self.decode(code, [addr(4, profile=p), addr(4, profile=p, depth=0)], profile=p)
        self.assertEqual(pcs, [BASE+i for i in (0, 0x100, 0x200, 0x300, 0x204, 0x104, 4, 8)])
        other = ExecutionDecoder(code, p, source_id=1)
        self.assertEqual(other.return_stack, [])
        self.assertIsNot(d.return_stack, other.return_stack)

    def test_discovery_required_and_irdepth_fields_validate_bounds_and_fill(self):
        d = ExecutionDecoder(image({0:0x13}), PROFILE)
        with self.assertRaisesRegex(DecodeError, 'requires return_stack_size'):
            d.consume(Packet(0, support(options=2)))
        with self.assertRaisesRegex(DecodeError, 'capacity'):
            unpack(addr(4, depth=17), P, 2)
        fields=[]
        p=unpack(addr(-4, depth=16), P, 2, fields)
        self.assertTrue(p['irreport']);self.assertEqual(p['irdepth'],16)
        self.assertEqual([f['name'] for f in fields][-2:], ['irreport','irdepth'])
        p=unpack(addr(-4), P, 2)
        self.assertFalse(p['irreport']);self.assertEqual(p['irdepth'],31)
