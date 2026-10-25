"""Build private, separately linked same-clock hart workloads for real emu tests."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from decoder.artifacts import write_json


RESOURCES = Path(__file__).resolve().parents[2] / 'src/test/resources/xiangshan'
BASES = (0x80000000, 0x81000000)
CRC = {'seedcrc': 0xe9f5, 'crclist': 0xe714, 'crcmatrix': 0x1fd7,
       'crcstate': 0x8e3a, 'crcfinal': 0x72be}


def build(directory, kind='basic', coremark=None, cross='riscv64-unknown-elf-'):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    source = directory / 'source'
    if source.exists():
        raise ValueError('workload source already exists; retain previous evidence')
    shutil.copytree(RESOURCES / 'dual-hart', source)
    if kind == 'coremark':
        coremark = Path(coremark).resolve()
        shutil.copytree(coremark / 'src', source / 'coremark/src')
        shutil.copytree(coremark / 'include', source / 'coremark/include')
        files = sorted((source / 'coremark/src').glob('*.c'))
        files += [source / 'runtime.c', source / 'start.S']
    else:
        name = 'sba-pressure.S' if kind == 'pressure' else 'sba-workload.S'
        shutil.copyfile(RESOURCES / name, source / name)
        files = [source / name]
    version = subprocess.check_output([cross + 'gcc', '--version'], text=True).splitlines()[0]
    rows = []
    for hart, base in enumerate(BASES):
        elf, binary = directory / f'hart{hart}.elf', directory / f'hart{hart}.bin'
        command = [cross + 'gcc', '-march=rv64gc', '-mabi=lp64d', '-mcmodel=medany',
                   '-msmall-data-limit=0', '-O2', '-g', '-ffreestanding', '-fno-builtin',
                   '-fno-tree-loop-distribute-patterns', '-ffunction-sections', '-fdata-sections',
                   '-nostdlib', '-static', '-Wl,--no-relax', '-Wl,--gc-sections',
                   '-Wl,--defsym=TRACE_BASE=' + hex(base), '-Wl,-T,' + str(source / 'workload.ld')]
        if kind == 'coremark':
            command += ['-DITERATIONS=2', '-Dmain=coremark_main', '-I' + str(source),
                        '-I' + str(source / 'coremark/include')]
        command += [str(path) for path in files] + ['-lgcc', '-o', str(elf)]
        with (directory / f'hart{hart}-build.log').open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
            subprocess.run([cross + 'objcopy', '-O', 'binary', str(elf), str(binary)],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        nm = subprocess.check_output([cross + 'nm', '-n', str(elf)], text=True)
        (directory / f'hart{hart}.symbols.txt').write_text(nm)
        symbols = {parts[2]: int(parts[0], 16) for line in nm.splitlines()
                   if len(parts := line.split()) == 3}
        with (directory / f'hart{hart}.disasm').open('w') as log:
            subprocess.run([cross + 'objdump', '-d', str(elf)], stdout=log, check=True)
        rows.append({'hart_id': hart, 'source_id': hart, 'base': hex(base),
                     'elf': elf.name, 'binary': binary.name,
                     'elf_sha256': hashlib.sha256(elf.read_bytes()).hexdigest(),
                     'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
                     'symbols': {name: hex(value) for name, value in symbols.items()
                                 if name.startswith('trace_') or name in ('_start', '__stack_top')},
                     'build_command': command})
    first, second = [directory / row['binary'] for row in rows]
    if first.stat().st_size >= BASES[1] - BASES[0]:
        raise ValueError('hart image overlaps the next private region')
    combined = bytearray(BASES[1] - BASES[0] + second.stat().st_size)
    combined[:first.stat().st_size] = first.read_bytes()
    combined[BASES[1] - BASES[0]:] = second.read_bytes()
    image = directory / 'workload.bin'
    image.write_bytes(combined)
    manifest = {'kind': kind, 'same_core_clock': True, 'compiler': version,
                'image_sha256': hashlib.sha256(combined).hexdigest(), 'harts': rows,
                'source_sha256': {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in sorted(source.rglob('*')) if p.is_file()},
                'coremark_origin': str(coremark) if kind == 'coremark' else None,
                'coremark_crc_expected': CRC if kind == 'coremark' else None,
                'scope': 'two isolated benchmark instances, not a standardized multicore CoreMark score'}
    write_json(directory / 'manifest.json', manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--kind', choices=('basic', 'pressure', 'coremark'), default='basic')
    parser.add_argument('--coremark', type=Path)
    args = parser.parse_args()
    if args.kind == 'coremark' and args.coremark is None:
        parser.error('--coremark source directory required')
    build(args.directory, args.kind, args.coremark)


if __name__ == '__main__':
    main()
