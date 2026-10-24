"""External SBA CSR interactions, with expected semantics and separate behavior coverage."""
import json
from pathlib import Path

from decoder.artifacts import write_json
from decoder.modes import COMBINATIONS, mode_name

ENCODER = 0x20000000
FUNNEL = 0x20010000
SINKS = {'sram': 0x20040000, 'smem': 0x20040000, 'atb': 0x20020000, 'pib': 0x20030000}


class ControlSession:
    def __init__(self, rpc, layout):
        self.rpc, self.layout = rpc, layout
        self.stage = 'initialize'
        self.rows = []

    def read(self, address):
        return int(self.rpc.run(f'read_memory 0x{address:x} 32 1 phys'), 0)

    def write(self, address, value):
        self.rpc.run(f'write_memory 0x{address:x} 32 {{0x{value:x}}} phys')

    def expect(self, name, address, value, expected, mask=0xffffffff):
        if value is not None:
            self.write(address, value)
        actual = self.read(address)
        row = {'stage': self.stage, 'field': name, 'address': hex(address),
               'write': None if value is None else hex(value), 'expected': hex(expected),
               'actual': hex(actual), 'mask': hex(mask),
               'status': 'passed' if actual & mask == expected & mask else 'failed'}
        self.rows.append(row)
        with (self.layout.dut / 'control-interactions.jsonl').open('a') as output:
            output.write(json.dumps(row) + '\n')
        print(f"[CTRL][{self.stage}] {name} @{address:#x} write={row['write']} expected={expected:#x} actual={actual:#x} {row['status']}", flush=True)
        if actual & mask != expected & mask:
            raise ValueError('control interaction failed: ' + name)
        return actual

    def rw(self, name, address, mask, fixed=0, values=(0, 0xffffffff)):
        for value in values:
            self.expect(name, address, value, fixed | (value & mask))

    def ro(self, name, address, expected):
        for value in (None, 0xffffffff):
            self.expect(name, address, value, expected)


def run_checks(rpc, layout, sink):
    session = ControlSession(rpc, layout)
    error = None
    try:
        rpc.run('riscv set_mem_access sysbus')
        state = rpc.run('xs.cpu curstate').strip()
        if state != 'halted':
            raise ValueError('control tests require externally halted DUT')
        session.stage = 'Active/reset'
        for base in (ENCODER, FUNNEL, SINKS[sink]):
            session.write(base, 0)
            if session.read(base) & 1:
                raise ValueError('component did not enter Active reset')
            session.write(base, 1)
            if session.read(base) & 11 != 9:
                raise ValueError('component did not become Active, disabled and Empty')
        session.stage = 'encoder capabilities/features'
        session.ro('TeImpl version/type/protocol', ENCODER + 4, 0x101)
        for name, features in COMBINATIONS.items():
            session.expect(name, ENCODER + 8, features, 0x80000000 | features)
        session.expect('unsupported features/SrcID/SrcBits/ImRetMode RO', ENCODER + 8, 0xffffffff, 0x8000003b)
        session.expect('default optional modes off', ENCODER + 8, 0, 0x80000000)
        session.ro('DataControl unsupported', ENCODER + 0x10, 0)
        session.ro('DataFilters unsupported', ENCODER + 0x1c, 0)
        session.stage = 'instruction qualification/synchronization'
        for value in (1, 5, 0x801, 0x2001, 0xf00001, 0x1001):
            # InstTracing requires Enable; RW1C status cannot be set by software.
            expected = 0x10079 | (value & 0xf02802) | (value & 4 if value & 2 else 0)
            # Feature edits can queue support packets while downstream remains
            # disabled. Empty is live state, checked separately at reset/drain.
            session.expect('InstTracing/InstTrigEnable/InstStallEna/InstSyncMax/RW1C', ENCODER, value, expected, mask=0xfffffff7)
        session.stage = 'filter and comparator fields'
        session.rw('TeInstFilters selector', ENCODER + 12, 1)
        # The selected filter is disabled while individual fields are programmed.
        session.write(ENCODER + 12, 0)
        for offset in (0x408, 0x40c, 0x410, 0x414, 0x610, 0x614, 0x618, 0x61c):
            session.rw('exception/impdef/primary/secondary match words', ENCODER + offset, 0xffffffff)
        session.ro('data filter match unsupported', ENCODER + 0x418, 0)
        for value, expected in ((0x108, 0x108), (0x84, 0x08), (0x40, 0x40), (0, 0)):
            session.expect('MatchPrivilege WARL and MatchInterrupt', ENCODER + 0x404, value, expected)
        for value, expected in ((0, 0), (0x1111f, 0x1111f), (0, 0)):
            session.expect('filter Enable and match qualifications', ENCODER + 0x400, value, expected)
        for value, expected in ((0, 0), (0x21, 0x21), (0x0f, 0x01), (0, 0)):
            session.expect('comparator primary/secondary operation WARL', ENCODER + 0x600, value, expected)
        session.stage = 'timestamp behavior'
        base = ENCODER + 0x40
        fixed = session.read(base) & ~0x830e
        session.expect('timestamp stop/reset/prescale/Enable', base, 4, fixed)
        session.expect('timestamp reset low', ENCODER + 0x48, None, 0)
        session.expect('timestamp reset high', ENCODER + 0x4c, None, 0)
        session.write(base, 0xb)
        first = session.read(ENCODER + 0x48)
        second = session.read(ENCODER + 0x48)
        if second <= first:
            raise ValueError('timestamp Count/RunInDebug did not advance the real counter')
        session.write(base, 0)
        stopped = session.read(ENCODER + 0x48)
        session.expect('timestamp Count stop freezes actual counter', ENCODER + 0x48, None, stopped)
        session.expect('timestamp reset W1 command self-clears', base, 4, fixed)
        session.expect('timestamp reset clears actual counter', ENCODER + 0x48, None, 0)
        session.stage = 'Funnel fields'
        session.ro('Funnel implementation', FUNNEL + 4, 0x801)
        session.rw('Funnel DisInput present-source mask', FUNNEL + 8, 1)
        session.write(FUNNEL + 8, 0)
        for offset in (0x40, 0x48, 0x4c):
            session.ro('Funnel timestamp not implemented', FUNNEL + offset, 0)
        session.stage = sink + ' fields'
        base = SINKS[sink]
        if sink == 'sram':
            session.ro('SRAM implementation/HasSRAM/HasSMEM', base + 4, 0x1901)
            for offset, expected in ((0x10, 0), (0x14, 0), (0x18, 0x3fc), (0x1c, 0), (0x24, 0), (0x2c, 0)):
                session.ro('SRAM fixed geometry/high pointers', base + offset, expected)
            for pointer in (0x20, 0x28):
                for value in (0, 0x3fc, 0xffffffff, 0):
                    session.expect('SRAM aligned bounded WP/RP and Wrap clear', base + pointer, value, value & 0x3fc)
            session.rw('SRAM StopOnWrap/reserved MemFormat/AsyncFreq', base, 0x100, 9, values=(1, 0xfffffffd, 1))
        elif sink == 'atb':
            session.rw('ATB AsyncFreq/implementation', base + 4, 0x7000, 0xe01)
            identity = 1
            for value in (1, 42, 111, 0, 112, 127):
                if 1 <= value < 112:
                    identity = value
                session.expect('ATB legal ID and reserved-ID retention', base, 1 | (value << 8), 9 | (identity << 8))
        elif sink == 'pib':
            session.ro('PIB implementation', base + 4, 0xa01)
            for mode in (0, 4, 5, 8, 9, 10, 11, 12):
                value = 1 | (mode << 4) | (3 << 16) | 0x100
                expected = 9 | (mode << 4) | (3 << 16) | (0x100 if mode >= 8 else 0)
                session.expect('PIB Mode/Divider/ClkCenter serial-vs-parallel', base, value, expected)
        # Reset every modified component before the separately checked workload.
        session.stage = 'restore'
        for base in (ENCODER, FUNNEL, SINKS[sink]):
            session.write(base, 0)
            session.write(base, 1)
        print('[CTRL] CSR interactions complete; next run checks actual trace generation, transport and retirement', flush=True)
    except BaseException as exc:
        error = str(exc)
        raise
    finally:
        write_json(layout.diff / 'control-comparison.json', {
            'status': 'failed' if error else 'passed', 'error': error, 'interactions': len(session.rows),
            'control': 'external JTAG -> Debug SBA, physical sysbus; DUT halted',
            'behavior_verified_here': ['Active reset/release', 'timestamp advance/stop/reset', 'WARL retention'],
            'register_semantics_only': ['filter/comparator qualification', 'optional compression mode selection', 'Funnel DisInput', 'sink configuration'],
            'remaining_behavior': ['filter/comparator effect on actual retired stream', 'trigger from real core', 'sink busy reconfiguration', 'SMEM error injection'],
            'platform_limitations': ['default XiangShan has no HPM event input', 'physical CDC/STA not covered']})
