import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from decoder.tests.build_dual_hart_workload import build, BASES, CRC, RESOURCES
from decoder.tests.check_dual_hart_sba import tuple_hash, check_short_storage
from decoder.tests.run_dual_hart_sba import check_recorder_provenance, read_benchmark_result


class DualHartWorkloadTests(unittest.TestCase):
    def setUp(self):
        self.parent = Path(__file__).resolve().parents[2] / 'delp/tmp/requirements12/tests'
        self.parent.mkdir(parents=True, exist_ok=True)

    def test_real_coremark_result_uses_installed_debugger_fp_name_and_all_crc_values(self):
        values = dict(a7=0x434d4f54, fp=1, a6=2, a1=CRC['seedcrc'], a2=CRC['crclist'],
                      a3=CRC['crcmatrix'], a4=CRC['crcstate'], a5=CRC['crcfinal'], s1=12345, s2=785000)
        called = []
        def reg(name):
            called.append(name)
            return values[name]
        result = read_benchmark_result(reg, 1, 0)
        self.assertIn('fp', called)
        self.assertNotIn('s0', called)
        self.assertEqual((result['cycle_interval'], result['instret_interval']), (12345, 785000))
        self.assertEqual({key: result[key] for key in CRC}, CRC)
        for name in ('a7', 'fp', 'a6', 'a1', 'a2', 'a3', 'a4', 'a5'):
            with self.subTest(register=name):
                prior = values[name]
                values[name] ^= 1
                with self.assertRaises(RuntimeError):
                    read_benchmark_result(reg, 1, 0)
                values[name] = prior
        with self.assertRaises(RuntimeError):
            read_benchmark_result(reg, 1, 1)

    def test_retirement_hash_normalizes_only_the_private_image_base_and_keeps_repetitions(self):
        a = [{'pc': '0x80000008', 'instruction': 0x13, 'size': 4},
             {'pc': '0x8000000c', 'instruction': 0x8082, 'size': 2}] * 2
        b = [dict(row, pc=hex(int(row['pc'], 0) + 0x1000000)) for row in a]
        self.assertEqual(tuple_hash(a, BASES[0]), tuple_hash(b, BASES[1]))
        self.assertNotEqual(tuple_hash(a, BASES[0]), tuple_hash(a[:2], BASES[0]))
        self.assertNotEqual(tuple_hash(a, BASES[0]), tuple_hash(list(reversed(a)), BASES[0]))

    def test_short_capture_requires_all_original_sba_words_in_their_actual_order(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as tmp:
            dut = Path(tmp) / 'dut'; dut.mkdir()
            data = bytes.fromhex('4433221188776655')
            storage = {'wp': 8, 'rp': 8}
            path = dut / 'sba-words.json'
            path.write_text(json.dumps([0x11223344, 0x55667788]))
            check_short_storage(tmp, data, storage)
            for words in ([0x55667788, 0x11223344], [0x11223344], [True, 0x55667788], [-1, 0x55667788]):
                path.write_text(json.dumps(words))
                with self.subTest(words=words), self.assertRaises(ValueError):
                    check_short_storage(tmp, data, storage)

    def test_long_recorder_requires_validation_for_this_immutable_emu(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as tmp:
            tmp = Path(tmp); emu = tmp / 'emu'; emu.write_bytes(b'actual test binary')
            recorder = tmp / 'recorder.sh'; recorder.touch()
            provenance = {'status': 'prepared_not_yet_validated',
                          'emu_sha256': hashlib.sha256(emu.read_bytes()).hexdigest()}
            path = tmp / 'provenance.json'; path.write_text(json.dumps(provenance))
            check_recorder_provenance(recorder, emu, False)
            with self.assertRaisesRegex(ValueError, 'requires validated'):
                check_recorder_provenance(recorder, emu, True)
            provenance['status'] = 'validated'; path.write_text(json.dumps(provenance))
            check_recorder_provenance(recorder, emu, True)
            emu.write_bytes(b'replaced test binary')
            with self.assertRaisesRegex(ValueError, 'another emu'):
                check_recorder_provenance(recorder, emu, False)

    @unittest.skipUnless(shutil.which('riscv64-unknown-elf-gcc'), 'RISC-V compiler unavailable')
    def test_both_fixed_workloads_have_private_images_and_independent_reference_symbols(self):
        for kind in ('basic', 'pressure'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=self.parent) as tmp:
                manifest = build(tmp, kind)
                self.assertEqual([h['source_id'] for h in manifest['harts']], [0, 1])
                one, two = manifest['harts']
                self.assertNotEqual(one['elf_sha256'], two['elf_sha256'])
                for name, address in one['symbols'].items():
                    self.assertEqual(int(two['symbols'][name], 0) - int(address, 0), BASES[1] - BASES[0])
                raw = (Path(tmp) / 'workload.bin').read_bytes()
                for hart, base in enumerate(BASES):
                    code = (Path(tmp) / f'hart{hart}.bin').read_bytes()
                    self.assertEqual(raw[base - BASES[0]:base - BASES[0] + len(code)], code)
                with self.assertRaisesRegex(ValueError, 'already exists'):
                    build(tmp, kind)

    @unittest.skipUnless(shutil.which('gcc'), 'native compiler unavailable')
    def test_original_coremark_algorithms_produce_the_expected_crc_with_the_private_runtime(self):
        origin = Path('/nfs/home/chengguanghui/projects/nexus-am/apps/coremark')
        if not (origin / 'src/core_main.c').is_file():
            self.skipTest('local CoreMark source not present')
        with tempfile.TemporaryDirectory(dir=self.parent) as tmp:
            tmp = Path(tmp)
            source = tmp / 'source'
            shutil.copytree(origin, source, ignore=shutil.ignore_patterns('build', '.git'))
            runtime = RESOURCES / 'dual-hart'
            files = sorted((source / 'src').glob('*.c')) + [runtime / 'runtime.c']
            objects = []
            for index, path in enumerate(files):
                obj = tmp / f'{index}.o'
                subprocess.run(['gcc', '-O2', '-fno-builtin', '-DITERATIONS=2', '-Dmain=coremark_main',
                                '-I' + str(runtime), '-I' + str(source / 'include'), '-c', str(path), '-o', str(obj)],
                               check=True, capture_output=True)
                objects.append(obj)
            wrapper = tmp / 'host.c'
            wrapper.write_text('''#include <stdint.h>
#include <unistd.h>
struct result { uint32_t magic,hart,iterations,seedcrc,crclist,crcmatrix,crcstate,crcfinal;
uint32_t errors,log_bytes;uint64_t begin,end,ibegin,iend;char log[2048]; };
extern volatile struct result trace_result;
void benchmark_run(void);
int main(void){benchmark_run();write(1,(const void*)&trace_result,sizeof(trace_result));return 0;}
''')
            exe = tmp / 'host'
            subprocess.run(['gcc', str(wrapper), *map(str, objects), '-o', str(exe)], check=True, capture_output=True)
            raw = subprocess.check_output([str(exe)], timeout=20)
            import struct
            values = struct.unpack_from('<10I4Q', raw)
            self.assertEqual((values[0], values[1], values[2], values[8]), (0x434d4f54, 0, 2, 0))
            self.assertEqual(dict(zip(CRC, values[3:8])), CRC)
            text = raw[72:72 + values[9]].decode()
            self.assertIn('Iterations       : 2', text)
            self.assertNotIn('Errors detected', text)


if __name__ == '__main__':
    unittest.main()
