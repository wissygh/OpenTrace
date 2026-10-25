"""Generate an ISA reference independently of the trace encoder/decoder."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path

from decoder.artifacts import write_json
from decoder.image import ProgramImage
from decoder.tests.build_dual_hart_workload import CRC


def run(library, workload, output, hart):
    library, workload, output = Path(library).resolve(), Path(workload).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError('independent ISA reference output must be empty')
    manifest = json.loads((workload / 'manifest.json').read_text())
    description = manifest['harts'][hart]
    base = int(description['base'], 0)
    start, done = [int(description['symbols'][name], 0) for name in ('trace_begin', 'trace_done')]
    image = ProgramImage.from_elf(workload / description['elf'])
    model = ctypes.CDLL(str(library))
    state_size = ctypes.c_uint.in_dll(model, 'DIFFTEST_REG_SIZE').value
    # Verified ABI of this ready-to-run reference: 32 GPR + 32 FPR + 18
    # shadow CSR words precede PC. Check the real reset PC and first steps.
    if state_size != 1400:
        raise ValueError('NEMU state ABI changed; recheck isa_difftest_regcpy before using this reference')
    model.difftest_set_ramsize.argtypes = [ctypes.c_size_t]
    model.difftest_set_ramsize(64 * 1024 * 1024)
    model.difftest_init()
    model.difftest_regcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_bool]
    model.difftest_memcpy.argtypes = [ctypes.c_uint64, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_bool]
    model.difftest_csrcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool]
    model.difftest_exec.argtypes = [ctypes.c_uint64]
    state = (ctypes.c_uint64 * 512)()
    model.difftest_regcpy(state, False, False)
    if state[82] != 0x10000000 or state[64] != 3:
        raise ValueError('NEMU ABI/reset privilege/PC differs')
    raw = (workload / description['binary']).read_bytes()
    buffer = ctypes.create_string_buffer(raw)
    model.difftest_memcpy(base, buffer, len(raw), True)
    csrs = (ctypes.c_uint64 * 4096)()
    model.difftest_csrcpy(csrs, False)
    csrs[0xf14] = hart
    csrs[0x744] = 8  # Same ordinary-trap boot state as the real boot ROM.
    model.difftest_csrcpy(csrs, True)
    state[82] = start
    model.difftest_regcpy(state, True, False)
    digest = hashlib.sha256()
    count = 0
    with (output / 'instructions.jsonl').open('w') as records:
        while count < 5_000_000:
            model.difftest_regcpy(state, False, False)
            pc = state[82]
            if pc == done:
                break
            if not base <= pc < base + 0x80000:
                raise ValueError(f'NEMU left private executable image at {pc:#x}; mcause={state[73]:#x}')
            instruction = image.instruction(pc)
            digest.update(f'{pc-base:x}:{instruction.bits:x}:{instruction.size}\n'.encode())
            records.write(json.dumps({'index': count, 'hart_id': hart, 'pc': hex(pc),
                                     'instruction': instruction.bits, 'size': instruction.size}) + '\n')
            model.difftest_exec(1)
            count += 1
        else:
            raise ValueError('NEMU did not reach the successful workload boundary')
    record = {'magic': state[17], 'hart': state[8], 'iterations': state[16], 'errors': state[10],
              'seedcrc': state[11], 'crclist': state[12], 'crcmatrix': state[13],
              'crcstate': state[14], 'crcfinal': state[15]}
    if record['magic'] != 0x434d4f54 or record['hart'] != hart or record['iterations'] != 2 or record['errors']:
        raise ValueError('independent ISA benchmark result invalid: ' + str(record))
    if any(record[key] != value for key, value in CRC.items()):
        raise ValueError('independent ISA benchmark CRC differs')
    result = {'status': 'passed', 'hart_id': hart, 'retired_instructions': count,
              'relative_retirement_sha256': digest.hexdigest(), 'actual_result': record,
              'library': str(library), 'library_sha256': hashlib.sha256(library.read_bytes()).hexdigest(),
              'state_abi_bytes': state_size, 'pc_abi_word': 82, 'reset_pc_verified': '0x10000000',
              'elf_sha256': description['elf_sha256'], 'binary_sha256': description['binary_sha256'],
              'method': 'actual NEMU ISA execution, no trace packets or decoder used to predict PCs'}
    write_json(output / 'summary.json', result)
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--workload', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--hart', type=int, choices=(0, 1), required=True)
    args = parser.parse_args()
    run(args.library, args.workload, args.output, args.hart)


if __name__ == '__main__':
    main()
