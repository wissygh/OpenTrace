"""Minimal ELF32/ELF64 loader and RV32/RV64 instruction control-flow classification."""
from dataclasses import dataclass
from pathlib import Path
import struct
from .encapsulation import DecodeError


def signed(value, width):
    value &= (1 << width) - 1
    return value - (1 << width) if value & (1 << (width - 1)) else value


@dataclass(frozen=True)
class Instruction:
    pc: int
    bits: int
    size: int
    kind: str = 'linear'
    immediate: int = 0
    rd: int = 0
    rs1: int = 0


class ProgramImage:
    def __init__(self, segments=(), xlen=64):
        if xlen not in (32, 64):
            raise DecodeError('only RV32 and RV64 images are supported')
        self.xlen = xlen
        self.segments = list(segments)

    @classmethod
    def from_raw(cls, data, base, xlen=64):
        return cls([(base, bytes(data))], xlen)

    @classmethod
    def from_elf(cls, path, load_bias=0):
        data = Path(path).read_bytes()
        if len(data) < 16 or data[:4] != b'\x7fELF' or data[5] != 1 or data[6] != 1:
            raise DecodeError('expected a little-endian ELF file')
        if data[4] == 2:
            header_fmt, program_fmt, xlen = '<HHIQQQIHHHHHH', '<IIQQQQQQ', 64
        elif data[4] == 1:
            header_fmt, program_fmt, xlen = '<HHIIIIIHHHHHH', '<IIIIIIII', 32
        else:
            raise DecodeError('unsupported ELF class')
        try:
            h = struct.unpack_from(header_fmt, data, 16)
            if h[1] != 243 or h[0] not in (2, 3):
                raise DecodeError('expected a RISC-V executable or shared-object ELF')
            phoff, phsize, phnum = h[4], h[8], h[9]
            if phsize < struct.calcsize(program_fmt):
                raise DecodeError('invalid ELF program-header size')
            segments = []
            for i in range(phnum):
                ph = struct.unpack_from(program_fmt, data, phoff + i * phsize)
                if ph[0] != 1:
                    continue
                if xlen == 64:
                    offset, address, size, memsize = ph[2], ph[3], ph[5], ph[6]
                else:
                    offset, address, size, memsize = ph[1], ph[2], ph[4], ph[5]
                if size > memsize or offset + size > len(data):
                    raise DecodeError('truncated ELF load segment')
                # Only file-backed instructions are fetched; do not fabricate BSS code.
                segments.append((address + load_bias, data[offset:offset + size]))
            if not segments:
                raise DecodeError('ELF contains no loadable image')
            return cls(segments, xlen)
        except struct.error as exc:
            raise DecodeError('truncated ELF headers') from exc

    def read(self, address, size):
        for base, data in self.segments:
            if base <= address and address + size <= base + len(data):
                return data[address - base:address - base + size]
        raise DecodeError(f'instruction bytes not mapped at 0x{address:x} ({size} bytes)')

    def instruction(self, pc):
        if pc & 1:
            raise DecodeError(f'unaligned instruction address 0x{pc:x}')
        half = int.from_bytes(self.read(pc, 2), 'little')
        if half & 3 != 3:
            if half == 0:
                raise DecodeError(f'reserved zero instruction at 0x{pc:x}')
            q, f = half & 3, half >> 13
            if q == 1 and (f == 5 or (f == 1 and self.xlen == 32)):
                imm = (((half >> 12) & 1) << 11 | ((half >> 11) & 1) << 4 |
                       ((half >> 9) & 3) << 8 | ((half >> 8) & 1) << 10 |
                       ((half >> 7) & 1) << 6 | ((half >> 6) & 1) << 7 |
                       ((half >> 3) & 7) << 1 | ((half >> 2) & 1) << 5)
                return Instruction(pc, half, 2, 'direct', signed(imm, 12), 1 if f == 1 else 0)
            if q == 1 and f in (6, 7):
                imm = (((half >> 12) & 1) << 8 | ((half >> 10) & 3) << 3 |
                       ((half >> 5) & 3) << 6 | ((half >> 3) & 3) << 1 |
                       ((half >> 2) & 1) << 5)
                return Instruction(pc, half, 2, 'branch', signed(imm, 9))
            if q == 2 and f == 4 and ((half >> 2) & 31) == 0:
                rs1 = (half >> 7) & 31
                if rs1:
                    return Instruction(pc, half, 2, 'indirect', 0, 1 if half & 4096 else 0, rs1)
                if half & 4096:
                    return Instruction(pc, half, 2, 'break')
            if q == 1 and f == 3 and ((half >> 7) & 31) not in (0, 2):
                imm = (((half >> 12) & 1) << 5 | ((half >> 2) & 31)) << 12
                return Instruction(pc, half, 2, 'lui', signed(imm, 18), (half >> 7) & 31)
            return Instruction(pc, half, 2)
        if (half >> 2) & 7 == 7:
            raise DecodeError('instructions longer than 32 bits are not supported')
        bits = int.from_bytes(self.read(pc, 4), 'little')
        op, rd, rs1 = bits & 127, (bits >> 7) & 31, (bits >> 15) & 31
        if op == 0x63:
            if (bits >> 12) & 7 in (2, 3):
                raise DecodeError('reserved conditional branch encoding')
            imm = ((bits >> 31) << 12 | ((bits >> 7) & 1) << 11 |
                   ((bits >> 25) & 63) << 5 | ((bits >> 8) & 15) << 1)
            return Instruction(pc, bits, 4, 'branch', signed(imm, 13))
        if op == 0x6f:
            imm = ((bits >> 31) << 20 | ((bits >> 12) & 255) << 12 |
                   ((bits >> 20) & 1) << 11 | ((bits >> 21) & 1023) << 1)
            return Instruction(pc, bits, 4, 'direct', signed(imm, 21), rd)
        if op == 0x67:
            if (bits >> 12) & 7:
                raise DecodeError('reserved JALR encoding')
            return Instruction(pc, bits, 4, 'absolute' if rs1 == 0 else 'indirect', signed(bits >> 20, 12), rd, rs1)
        if bits in (0x00200073, 0x10200073, 0x30200073, 0x7b200073):
            return Instruction(pc, bits, 4, 'xret')
        if bits in (0x00000073, 0x00100073):
            return Instruction(pc, bits, 4, 'break')
        if op in (0x17, 0x37):
            return Instruction(pc, bits, 4, 'auipc' if op == 0x17 else 'lui', signed(bits & 0xfffff000, 32), rd)
        if op not in (3, 7, 15, 19, 27, 35, 39, 47, 51, 59, 67, 71, 75, 79, 83, 87, 115):
            raise DecodeError(f'unsupported instruction 0x{bits:08x} at 0x{pc:x}')
        return Instruction(pc, bits, 4)
