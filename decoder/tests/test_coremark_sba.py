"""A full SRAM stream must survive clock sampling, wrap and physical readback."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from decoder.tests.check_coremark_sba import PROJECT, RESOURCES, build_reader, check, check_benchmark_output, check_storage, fst_include, check_optional_packets, observe_returns
from decoder.tests.test_implicit_return import P, BASE, addr, image
from decoder.tests.test_decoder import sync, support
from decoder import Packet, ExecutionDecoder


class SRAMFSTMonitorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not all(shutil.which(name) for name in ('gcc', 'g++', 'verilator')):
            raise unittest.SkipTest('native FST monitor needs gcc, g++ and Verilator')
        parent = PROJECT / 'delp/tmp'
        parent.mkdir(parents=True, exist_ok=True)
        cls.temporary = tempfile.TemporaryDirectory(dir=parent, prefix='coremark-monitor-test-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        with (cls.root / 'build.log').open('w') as log:
            cls.reader = build_reader(cls.root, log)
            cls.writer = cls.root / 'write-fixture'
            subprocess.run(['g++', '-O2', '-std=c++17', '-I' + str(fst_include()),
                str(RESOURCES / 'write_sram_fst_fixture.cpp'),
                *[str(cls.root / (name + '.o')) for name in ('fstapi', 'fastlz', 'lz4')],
                '-lz', '-o', str(cls.writer)], stdout=log, stderr=subprocess.STDOUT, check=True)

    def capture(self, mode):
        fst, raw, tsv, signals = [self.root / (mode + suffix) for suffix in ('.fst', '.bin', '.tsv', '-signals.tsv')]
        subprocess.run([str(self.writer), str(fst), mode], check=True)
        result = subprocess.run([str(self.reader), str(fst), str(raw), str(tsv), str(signals)], capture_output=True, text=True)
        return result, raw, tsv

    def test_pre_edge_data_repeated_writes_and_a_complete_ring_wrap(self):
        result, raw, tsv = self.capture('good')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(raw.read_bytes(), bytes.fromhex('11111111') + bytes.fromhex('22222222') * 299)
        lines = tsv.read_text().splitlines()
        self.assertEqual(len(lines), 301)
        self.assertEqual(int(lines[-1].split('\t')[2]), 43)
        self.assertIn('words=300 bytes=1200', result.stderr)

    def test_single_edge_missing_signals_and_address_gaps_are_rejected(self):
        for mode, code, reason in [('flat', 6, 'rising_edges=0'),
                                    ('jump', 6, 'nonconsecutive SRAM write'),
                                    ('missing', 5, 'missing SRAM signals')]:
            with self.subTest(mode=mode):
                result, _, _ = self.capture(mode)
                self.assertEqual(result.returncode, code)
                self.assertIn(reason, result.stderr)


class CoreMarkStorageChecks(unittest.TestCase):
    def test_return_coverage_counts_actual_predictions_and_preserves_execution(self):
        code=image({0:0x100000ef,0x100:0x8067,4:0x13,8:0x13})
        decoder=ExecutionDecoder(code,P)
        counts=observe_returns(decoder)
        events=[]
        for body in (support(options=2),sync(BASE,profile=P),addr(8,depth=0),support(1,options=2)):
            events+=decoder.consume(Packet(0,body))
        self.assertEqual([e['pc'] for e in events if e['kind']=='instruction'],[BASE,BASE+0x100,BASE+4,BASE+8])
        self.assertEqual(counts,{'implicit_returns':1,'explicit_returns':0,'maximum_stack_depth':1})
        self.assertTrue(decoder.start_of_trace)

    def test_csr_features_match_support_and_actual_format0_usage(self):
        for features in (0, 8, 16, 24, 32, 40, 48, 56):
            with self.subTest(features=features):
                fields = [{'format': 3, 'subformat': 3, 'options': ((features & 0x30) >> 1) | ((features & 8) >> 2)}]
                if features & 16:
                    fields += [{'format': 0, 'subformat': 0, 'branch_count': 31, 'prediction_failed': True},
                               {'format': 0, 'subformat': 0, 'branch_count': 80,
                                'address': 8, 'prediction_failed': True}]
                if features & 8:
                    fields += [{'format': 2, 'irreport': True, 'irdepth': 3}]
                if features & 32:
                    fields += [{'format': 0, 'subformat': 1, 'index': 7}]
                coverage = check_optional_packets([{'fields': f} for f in fields], features)
                self.assertEqual(coverage['declared_options'], ((features & 0x30) >> 1) | ((features & 8) >> 2))
                self.assertEqual(coverage['return_depth_reports'], int(bool(features & 8)))
                self.assertEqual(coverage['cache_index_packets'], int(bool(features & 32)))
                self.assertEqual(coverage['correctly_predicted_branches'], 111 if features & 16 else 0)
                self.assertEqual(coverage['prediction_failed_packets'], 2 if features & 16 else 0)
                self.assertEqual(coverage['prediction_failed_address_packets'], 1 if features & 16 else 0)
                self.assertEqual(coverage['prediction_no_address_packets'], 1 if features & 16 else 0)
        for fields, enabled in [([{'format': 3, 'subformat': 3, 'options': 0}], 48),
                ([{'format': 0, 'subformat': 1, 'index': 0}], 0),
                ([{'format': 0, 'subformat': 0, 'branch_count': 31}], 0), ([], 16)]:
            with self.assertRaises(ValueError):
                check_optional_packets([{'fields': f} for f in fields], enabled)

    def test_exit_pc_accepts_the_shared_hex_json_representation(self):
        for pc in ('0x80001ca0', 0x80001ca0):
            with self.subTest(pc=pc), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                for category in ('dut/logs', 'workload', 'ref', 'diff'):
                    (root / category).mkdir(parents=True)
                (root / 'dut/capture-metadata.json').write_text(json.dumps({
                    'status': 'captured', 'a0': 0, 'pc': pc, 'dropped': 0, 'loss_markers': 0}))
                (root / 'dut/logs/uart.log').write_text('')
                # A valid exit reaches the independent CRC validation; it must
                # not be rejected merely because write_json serializes hex PCs.
                with self.assertRaisesRegex(ValueError, 'iteration count'):
                    check(root)

    def test_original_benchmark_crc_and_iterations_must_pass(self):
        text = ('Iterations : 2\nseedcrc : 0xe9f5\n[0]crclist : 0xe714\n'
                '[0]crcmatrix : 0x1fd7\n[0]crcstate : 0x8e3a\n[0]crcfinal : 0x72be\n')
        self.assertEqual(check_benchmark_output(text)['crcfinal'], '0x72be')
        for invalid in (text.replace('0x1fd7', '0xffff'), text.replace('Iterations : 2', 'Iterations : 1'),
                        text + 'Errors detected', text.replace('[0]crcstate : 0x8e3a\n', '')):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                check_benchmark_output(invalid)

    def fixture(self):
        words = [{'word_index': i, 'word_address': i % 256, 'fst_time': i * 2 + 3,
                  'data': 0x89abcdef ^ i} for i in range(272)]
        data = b''.join(r['data'].to_bytes(4, 'little') for r in words)
        metadata = {'wp_register': 65, 'sample_start_word': 0,
                    'sample_words': [r['data'] for r in words[-16:]], 'rp_after_sample': 64}
        return data, words, metadata

    def test_cumulative_storage_is_verified_by_real_wp_wrap_and_sba_tail(self):
        data, words, metadata = self.fixture()
        check_storage(data, words, metadata)
        for fault in ('address', 'time', 'wp', 'wrap', 'tail', 'sample_start', 'rp', 'size'):
            with self.subTest(fault=fault):
                data, words, metadata = self.fixture()
                if fault == 'address': words[256]['word_address'] = 1
                elif fault == 'time': words[1]['fst_time'] = words[0]['fst_time']
                elif fault == 'wp': metadata['wp_register'] += 4
                elif fault == 'wrap': metadata['wp_register'] &= ~1
                elif fault == 'tail': metadata['sample_words'][0] ^= 1
                elif fault == 'sample_start': metadata['sample_start_word'] += 1
                elif fault == 'rp': metadata['rp_after_sample'] = 0
                elif fault == 'size': data = data[:-1]
                with self.assertRaises(ValueError):
                    check_storage(data, words, metadata)

    def test_failed_capture_replaces_a_previous_pass_before_decode(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for category in ('dut', 'workload', 'ref', 'diff'):
                (root / category).mkdir()
            (root / 'dut/capture-metadata.json').write_text(json.dumps({'status': 'failed'}))
            (root / 'diff/comparison.json').write_text(json.dumps({'status': 'passed'}))
            with self.assertRaisesRegex(ValueError, 'successful exit'):
                check(root)
            result = json.loads((root / 'diff/comparison.json').read_text())
            self.assertEqual((result['status'], result['stage']), ('failed', 'inputs'))
            self.assertFalse((root / 'dut/trace.bin').exists())


if __name__ == '__main__':
    unittest.main()
