"""Batch GNU RISC-V disassembly of exact retired encodings at their real PCs."""
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile


def number(value):
    return int(value, 0) if isinstance(value, str) else int(value)


def key(row, xlen=64):
    return (number(row.get('xlen', xlen)), number(row['pc']),
            number(row['instruction']), number(row['size']))


def fallback(item, reason='未识别'):
    _, _, bits, size = item
    return f'{".hword" if size == 2 else ".word"} 0x{bits:0{size * 2}x} [{reason}]'


def _elf(items, xlen):
    """One executable section per instruction prevents gaps/data/overlaps shifting decode."""
    is64 = xlen == 64
    header_size, section_size = (64, 64) if is64 else (52, 40)
    data = bytearray(header_size)
    strings = bytearray(b'\0.shstrtab\0')
    sections = [(0,) * 10]
    for index, (_, pc, bits, size) in enumerate(items):
        name = len(strings)
        strings.extend(f'.trace_{index}\0'.encode())
        offset = len(data)
        data.extend(bits.to_bytes(size, 'little'))
        sections.append((name, 1, 6, pc, offset, size, 0, 0, 2, 0))
    sections.append((1, 3, 0, 0, len(data), len(strings), 0, 0, 1, 0))
    data.extend(strings)
    data.extend(bytes(-len(data) % 8))
    section_offset = len(data)
    for section in sections:
        data.extend(struct.pack('<IIQQQQIIQQ' if is64 else '<IIIIIIIIII', *section))
    ident = b'\x7fELF' + bytes((2 if is64 else 1, 1, 1)) + bytes(9)
    header = struct.pack('<HHIQQQIHHHHHH' if is64 else '<HHIIIIIHHHHHH',
                         2, 243, 1, 0, 0, section_offset, 1, header_size, 0, 0,
                         section_size, len(sections), len(sections) - 1)
    data[:header_size] = ident + header
    return data


def disassemble(items, objdump=None):
    """Return annotations only; tool absence never changes execution reconstruction."""
    items = set(items)
    result = {item: fallback(item) for item in items}
    objdump = objdump or shutil.which('riscv64-unknown-elf-objdump') or shutil.which('riscv64-linux-gnu-objdump')
    if not objdump:
        return {item: fallback(item, '缺少RISC-V objdump') for item in items}
    temporary = Path(__file__).resolve().parents[1] / 'delp/tmp'
    temporary.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=temporary, prefix='disassembly-') as directory:
        for xlen in (32, 64):
            selected = sorted(item for item in items if item[0] == xlen)
            # Bound ELF section counts and subprocess output for large programs.
            for start in range(0, len(selected), 4096):
                batch = selected[start:start + 4096]
                path = Path(directory) / 'instructions.elf'
                path.write_bytes(_elf(batch, xlen))
                try:
                    output = subprocess.run([objdump, '-D', '-z', '-w', str(path)],
                                            capture_output=True, text=True, check=True, timeout=30).stdout
                except (OSError, subprocess.SubprocessError):
                    result.update((item, fallback(item, 'objdump失败')) for item in batch)
                    continue
                current = None
                for line in output.splitlines():
                    section = re.fullmatch(r'Disassembly of section \.trace_(\d+):', line)
                    if section:
                        current = batch[int(section[1])]
                        continue
                    instruction = re.match(r'\s*([0-9a-fA-F]+):\s+([0-9a-fA-F]+)\s+(.+)', line)
                    if current and instruction:
                        _, pc, bits, _ = current
                        if int(instruction[1], 16) == pc and int(instruction[2], 16) == bits:
                            # Synthetic section labels are not workload symbols.
                            assembly = re.sub(r'\s*<[^>]+>', '', instruction[3])
                            result[current] = ' '.join(assembly.split())
                        current = None
    return result
