"""Guard original SMP image, source identity and independent evidence."""
import json
from pathlib import Path
import tempfile
import unittest

from decoder.image import ProgramImage
from decoder.tests.run_smp_sba import BASE, KERNEL, PAYLOAD, image_elf, validate_initial_hart
from decoder.tests.check_smp_sba import canonical_pc, ordered_reference, audit_rpc


class SMPBootTests(unittest.TestCase):
    def test_initial_external_halt_accepts_real_bbl_continuation_without_assuming_rom(self):
        for hart, pc in ((0, 0x800007ba), (1, 0x800002b0)):
            validate_initial_hart(hart, hart, pc)
        for hart, identity, pc in ((0, 1, BASE), (1, 0, BASE), (0, 0, BASE + 1),
                                   (0, 0, 0x38020800), (1, 1, BASE + PAYLOAD)):
            with self.subTest(hart=hart, pc=pc), self.assertRaises(ValueError):
                validate_initial_hart(hart, identity, pc)

    def test_elf_aliases_preserve_original_shared_bytes_and_never_fabricate_code(self):
        raw = b'\x13\x00\x00\x00' + bytes(PAYLOAD - 4) + bytes.fromhex('afa6c600')
        parent = Path(__file__).resolve().parents[2] / 'delp/tmp/requirements13/tests'
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as tmp:
            path = Path(tmp) / 'aliases.elf'; path.write_bytes(image_elf(raw))
            image = ProgramImage.from_elf(path)
            self.assertEqual(image.read(BASE, len(raw)), raw)
            self.assertEqual(image.read(KERNEL, 4), raw[PAYLOAD:])
            self.assertEqual(image.instruction(BASE + PAYLOAD).bits, image.instruction(KERNEL).bits)
            with self.assertRaises(ValueError):
                image.read(KERNEL + 4, 2)
        with self.assertRaises(ValueError):
            image_elf(bytes(PAYLOAD))

    def test_rob_address_extension_only_uses_discovered_width(self):
        self.assertEqual(canonical_pc(0x3ffff800d16a0, 50), 0xffffffff800d16a0)
        self.assertEqual(canonical_pc(0x80200018, 50), 0x80200018)
        self.assertEqual(canonical_pc(0xffffffff80000000, 64), 0xffffffff80000000)
        for pc, width in ((1 << 50, 50), (-1, 50), (1, 0), (0, 65)):
            with self.subTest(pc=pc, width=width), self.assertRaises(ValueError):
                canonical_pc(pc, width)

    def test_ordered_reference_keeps_repeated_samples_and_rejects_reordering(self):
        samples = [{'cycle': 10, 'slot': 0, 'pc': BASE},
                   {'cycle': 10, 'slot': 1, 'pc': BASE},
                   {'cycle': 11, 'slot': 0, 'pc': BASE + 4}]
        self.assertEqual(ordered_reference(samples, [BASE, BASE, BASE + 2, BASE + 4]), [0, 1, 3])
        for rows, pcs in ((samples, [BASE, BASE + 4]), (samples[::-1], [BASE] * 3),
                          ([samples[0]] * 2, [BASE] * 2), ([], [BASE])):
            with self.subTest(samples=rows), self.assertRaises(ValueError):
                ordered_reference(rows, pcs)

    def test_rpc_audit_requires_actual_sysbus_tail_and_prohibits_pc_redirection(self):
        parent = Path(__file__).resolve().parents[2] / 'delp/tmp/requirements13/tests'
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as tmp:
            root = Path(tmp); logs = root / 'dut/logs'; logs.mkdir(parents=True)
            path = logs / 'rpc.jsonl'
            rows = [{'command': 'riscv set_mem_access sysbus', 'return_code': '0', 'result': ''},
                    {'command': 'read_memory 0x20050040 32 1 phys', 'return_code': '0', 'result': '0x1234'}]
            metadata = {'storage': {'sample_words': [0x1234]}}
            def save(values):
                path.write_text(''.join(json.dumps(row) + '\n' for row in values))
            save(rows); self.assertEqual(audit_rpc(root, metadata)['sba_tail_words'], 1)
            for command in ('reg pc 0x80000000', 'resume 0x80000000', 'riscv set_mem_access progbuf'):
                save(rows + [{'command': command, 'return_code': '0', 'result': ''}])
                with self.subTest(command=command), self.assertRaises(ValueError):
                    audit_rpc(root, metadata)
            save(rows)
            with self.assertRaises(ValueError):
                audit_rpc(root, {'storage': {'sample_words': [0x3412]}})
            save([dict(rows[1], return_code='1')])
            with self.assertRaises(ValueError):
                audit_rpc(root, metadata)


if __name__ == '__main__':
    unittest.main()
