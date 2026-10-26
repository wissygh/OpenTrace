"""Capture unmodified Linux SMP boot through real dual-hart trace and SBA."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import time

from decoder.artifacts import write_json, write_jsonl
from decoder.capture_layout import CaptureLayout
from decoder.discovery import read_snapshot
from decoder.tests.run_dual_hart_sba import ENCODERS, FUNNEL, SRAM, check_recorder_provenance
from decoder.tests.run_xiangshan_sba import RPC, free_port, listening, stop_capture


ROOT = Path(__file__).resolve().parents[2]
BASE = 0x80000000
KERNEL = 0xffffffff80000000
PAYLOAD = 0x200000
# Both paths execute the original shared AMO lottery at payload+0x18. Hart 0
# stops before setup_vm, hart 1 before the secondary stack wait. No PC writes.
BOUNDARIES = (0x8020002a, 0x802000dc)


def validate_initial_hart(hart, identity, pc):
    # JTAG examination takes guest cycles. The unmodified image is already
    # executing BBL by the first external halt; requiring a ROM PC is invalid.
    # Accept its real BBL continuation, never restart at BASE or rewrite state.
    if identity != hart or pc & 1 or not BASE <= pc < BASE + PAYLOAD:
        raise ValueError('initial hart identity or original BBL continuation differs')


def image_elf(raw):
    """Expose original bytes at physical boot and linked kernel addresses."""
    if len(raw) <= PAYLOAD:
        raise ValueError('SMP image is missing its kernel payload')
    segments = [(BASE, raw), (KERNEL, raw[PAYLOAD:])]
    header = b'\x7fELF' + bytes((2, 1, 1)) + bytes(9)
    header += struct.pack('<HHIQQQIHHHHHH', 2, 243, 1, BASE, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    offset = 64 + 56 * len(segments)
    programs, body = [], []
    for address, data in segments:
        programs.append(struct.pack('<IIQQQQQQ', 1, 5, offset, address, address, len(data), len(data), 1))
        body.append(data); offset += len(data)
    return header + b''.join(programs) + b''.join(body)


def snapshot(source, out):
    raw = source.read_bytes()
    shutil.copyfile(source, out.workload / 'workload.bin')
    (out.workload / 'workload.elf').write_bytes(image_elf(raw))
    comparisons = {}
    for name, base, offset in (('bbl.txt', BASE, 0), ('vmlinux.txt', KERNEL, PAYLOAD)):
        path = source.parent / name
        shutil.copyfile(path, out.workload / name)
        checked = 0
        for line in path.read_text().splitlines():
            match = re.match(r'\s*([0-9a-f]+):\s+([0-9a-f]{4}(?:[0-9a-f]{4})?)\s', line)
            if not match:
                continue
            address, encoded = int(match[1], 16), match[2]
            where = address - base + offset
            if not 0 <= where < len(raw):
                continue
            expected = int(encoded, 16).to_bytes(len(encoded) // 2, 'little')
            if raw[where:where + len(expected)] != expected:
                raise ValueError(f'{name} differs from unmodified binary at {address:#x}')
            checked += 1
        if not checked:
            raise ValueError('no independently disassembled instructions checked')
        comparisons[name] = {'instructions_checked': checked, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    shutil.copyfile(ROOT / 'src/test/resources/xiangshan/dual-hart/openocd-sba.cfg', out.workload / 'openocd-sba.cfg')
    manifest = {'kind': 'linux-smp-boot-handshake', 'origin': str(source.resolve()),
                'image_sha256': hashlib.sha256(raw).hexdigest(), 'unmodified_image': True,
                'same_core_clock': True, 'disassembly': comparisons,
                'boundaries': [hex(v) for v in BOUNDARIES],
                'scope': 'shared BBL wakeup/entry-point and kernel AMO lottery; not full Linux boot performance'}
    write_json(out.workload / 'manifest.json', manifest)
    return manifest


def collect(rpc, out, features, timeout):
    def select(hart):
        rpc.run(f'targets xs.cpu{hart}')
    def reg(name):
        reply = rpc.run('reg ' + name)
        match = re.search(r':\s*(0x[0-9a-fA-F]+)', reply)
        if not match:
            raise ValueError('missing actual register ' + name)
        return int(match[1], 16)
    def read(address):
        return int(rpc.run(f'read_memory {address:#x} 32 1 phys'), 0)
    def write(address, value):
        rpc.run(f'write_memory {address:#x} 32 {{{value:#x}}} phys')
    metadata = {'status': 'incomplete', 'same_core_clock': True,
                'capture_method': 'all physical SRAM writes plus SBA tail',
                'control': 'external JTAG -> Debug SBA physical sysbus', 'harts': {}}
    try:
        print('[SMP][1/6] 两核真实mhartid，原始启动PC；安装原镜像共享AMO之后硬件断点', flush=True)
        rpc.run('poll off')
        for hart in (0, 1):
            select(hart); rpc.run('riscv set_mem_access sysbus')
            identity, start = reg('mhartid'), reg('pc')
            validate_initial_hart(hart, identity, start)
            rpc.run(f'bp {BOUNDARIES[hart]:#x} {4 if hart else 2} hw')
            metadata['harts'][str(hart)] = {'actual_mhartid': identity, 'initial_pc': start,
                'features_requested': features, 'expected_stop': BOUNDARIES[hart]}
        select(0)
        for address in (*ENCODERS, FUNNEL, SRAM):
            write(address, 1)
            if not read(address) & 1:
                raise ValueError('component Active did not release')
        write(SRAM + 0x20, 0)
        for hart, address in enumerate(ENCODERS):
            write(address + 0xf04, 1)
            words = [int(v, 0) for v in rpc.run(f'read_memory {address+0xe00:#x} 32 20 phys').split()]
            if len(words) != 20:
                raise ValueError('incomplete hardware discovery')
            path = out.dut / f'hart{hart}-discovery.bin'
            path.write_bytes(struct.pack('<20I', *words)); write_json(path.with_suffix('.json'), read_snapshot(path))
            write(address + 8, features)
            actual = read(address + 8)
            if actual & 0xffff != features or (actual >> 16) & 0xfff != hart:
                raise ValueError('actual encoder features/srcID differ')
            metadata['harts'][str(hart)]['features_readback'] = actual
        write(SRAM, 3); write(FUNNEL, 3)
        for address in ENCODERS:
            write(address, 0xf02007)
        metadata['rob_byte_start'] = (out.ref / 'rob.log').stat().st_size
        print('[SMP][2/6] 原PC自然resume，两核运行同一个未经改写的BBL/Linux程序', flush=True)
        for hart in (0, 1):
            select(hart)
            metadata['harts'][str(hart)]['capture_start_cycle'] = reg('cycle')
            rpc.run('resume')
        deadline, progress, pending = time.monotonic() + timeout, time.monotonic(), {0, 1}
        while pending:
            for hart in sorted(pending):
                select(hart)
                if rpc.run(f'xs.cpu{hart} arp_poll; xs.cpu{hart} curstate').strip() == 'halted':
                    status = metadata['harts'][str(hart)]
                    status.update(pc=reg('pc'), dcsr=reg('dcsr'), a0=reg('a0'), a1=reg('a1'), a3=reg('a3'),
                                  cycle=reg('cycle'), instret=reg('instret'))
                    if status['pc'] != BOUNDARIES[hart] or status['dcsr'] >> 6 & 7 != 2:
                        raise ValueError('unexpected stop before real SMP completion boundary')
                    pending.remove(hart)
                    print(f'[SMP][3/6] hart{hart}共享AMO后真实断点{status["pc"]:#x}，a3={status["a3"]}', flush=True)
            if time.monotonic() >= deadline:
                raise TimeoutError('SMP capture did not reach both original program boundaries')
            if pending:
                time.sleep(5)
                if time.monotonic() - progress >= 30:
                    print(f'[SMP][运行] 等待hart{sorted(pending)}，UART/ROB持续写入，剩余{int(deadline-time.monotonic())}s', flush=True)
                    progress = time.monotonic()
        print('[SMP][4/6] 停止两Encoder→Funnel→SRAM并核对排空/无loss', flush=True)
        select(0)
        for name, address in [('0', ENCODERS[0]), ('1', ENCODERS[1]), ('funnel', FUNNEL), ('sram', SRAM)]:
            write(address, 0xf00001 if name in ('0', '1') else 1)
            for _ in range(60):
                value = read(address)
                if value & 11 == 9:
                    break
            else:
                raise ValueError('component failed to drain: ' + name)
            if name in ('0', '1'):
                metadata['harts'][name]['encoder'] = value
            else:
                metadata[name] = value
        for hart, address in enumerate(ENCODERS):
            status = metadata['harts'][str(hart)]
            for name, offset in (('dropped', 0xf10), ('loss_markers', 0xf18), ('stall_cycles', 0xf20),
                                 ('packet_wait_cycles', 0xf28), ('downstream_wait_cycles', 0xf30)):
                status[name] = read(address + offset)
            if status['dropped'] or status['loss_markers']:
                raise ValueError('actual ingress trace loss')
        wp = read(SRAM + 0x20); start = ((wp & ~3) // 4 - 16) % 256
        write(SRAM + 0x28, start * 4)
        metadata['storage'] = {'wp_register': wp, 'sample_start_word': start,
                               'sample_words': [read(SRAM + 0x40) for _ in range(16)],
                               'rp_after_sample': read(SRAM + 0x28)}
        metadata['status'] = 'captured'
        print('[SMP][5/6] 全物理SRAM写流由已验证观察器保存，真实SBA读取末16word', flush=True)
    except BaseException as exc:
        metadata.update(status='failed', error=str(exc)); raise
    finally:
        write_json(out.dut / 'metadata.json', metadata)
    write_json(out.dut / 'trace.json', {'sources': {str(h): {'hart_id': h,
        'elf': '../workload/workload.elf', 'discovery': f'hart{h}-discovery.bin'} for h in (0, 1)}})


def run(args):
    directory = args.output.resolve()
    if directory.exists() and any(directory.iterdir()):
        raise ValueError('retain prior evidence: output must be empty')
    out = CaptureLayout(directory).create(); logs = out.dut / 'logs'; logs.mkdir()
    snapshot(args.image, out)
    provenance = check_recorder_provenance(args.recorder, args.emu, True)
    rb, tc = free_port(), free_port()
    while tc == rb:
        tc = free_port()
    command = [str(args.recorder.resolve()), str(args.emu.resolve()), '--no-diff', '--enable-jtag',
               f'--remote-jtag-port={rb}', '--ram-size=256MB', '--max-cycles=2000000',
               '--dramsim3-outdir=' + str(logs), '-i', str(out.workload / 'workload.bin'),
               '--dump-wave-full', '--wave-path=' + str(out.dut / 'trace.fst')]
    write_json(out.dut / 'run.json', {'command': command, 'features': args.features,
               'recorder_provenance': provenance, 'emu_sha256': hashlib.sha256(args.emu.read_bytes()).hexdigest()})
    emu = debugger = rpc = None
    try:
        with (logs / 'uart.log').open('w') as uart, (out.ref / 'rob.log').open('w') as rob, (logs / 'openocd.log').open('w') as debug:
            emu = subprocess.Popen(command, cwd=directory, stdout=uart, stderr=rob)
            from decoder.tests.run_coremark_sba import pin_actual
            pin_actual(emu.pid, args.cpu_start)
            deadline, progress = time.monotonic() + 3600, time.monotonic()
            while not listening(rb):
                if emu.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('SMP emu JTAG unavailable')
                time.sleep(.2)
            env = dict(os.environ, OT_RBB_PORT=str(rb), OT_TCL_PORT=str(tc))
            debugger = subprocess.Popen(['openocd', '-f', str(out.workload / 'openocd-sba.cfg')],
                cwd=directory, env=env, stdout=debug, stderr=subprocess.STDOUT)
            while rpc is None:
                if debugger.poll() is not None or emu.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('SMP OpenOCD initialization failed')
                try:
                    rpc = RPC(tc, timeout=900, transcript=logs / 'rpc.jsonl')
                except ConnectionRefusedError:
                    time.sleep(.5)
                    if time.monotonic() - progress >= 30:
                        print('[SMP][startup] 两核JTAG检查/初始halt进行中', flush=True); progress = time.monotonic()
            collect(rpc, out, args.features, args.timeout)
    finally:
        if rpc is not None:
            rpc.close()
        if emu is not None and emu.poll() is None:
            children = Path(f'/proc/{emu.pid}/task/{emu.pid}/children')
            if children.exists():
                for child in children.read_text().split():
                    executable = Path(f'/proc/{child}/exe')
                    if executable.exists() and executable.resolve() == args.emu.resolve():
                        os.kill(int(child), signal.SIGINT)
                try:
                    emu.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    pass
        stop_capture(emu, debugger)
    reader = ROOT / 'delp/tmp/requirements12/fst-readers/requirements12-dual-coremark-jump-cache/read-sram'
    if not reader.exists():
        from decoder.tests.check_coremark_sba import build_reader
        build_dir = ROOT / 'delp/tmp/requirements13/fst-reader'; build_dir.mkdir(parents=True, exist_ok=True)
        with (logs / 'fst-build.log').open('w') as log:
            reader = build_reader(build_dir, log)
    with (logs / 'fst-reader.log').open('w') as log:
        subprocess.run([str(reader), str(out.dut / 'trace.fst'), str(out.dut / 'trace.bin'),
                        str(out.dut / 'sram-writes.tsv'), str(out.dut / 'sram-signals.tsv')], stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = []
    for line in (out.dut / 'sram-writes.tsv').read_text().splitlines()[1:]:
        index, when, address, value = line.split('\t')
        rows.append({'word_index': int(index), 'fst_time': int(when), 'word_address': int(address), 'data': int(value, 0)})
    write_jsonl(out.dut / 'sram-writes.jsonl', rows)
    print('[SMP][6/6] 原始物理字节采集完成，开始逐srcID解码与独立ROB对拍', flush=True)
    from decoder.tests.check_smp_sba import check
    check(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('emu', 'image', 'recorder', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--features', type=lambda x: int(x, 0), choices=(0, 8, 24, 56), default=0)
    parser.add_argument('--cpu-start', type=int, default=176)
    parser.add_argument('--timeout', type=int, default=7200)
    args = parser.parse_args()
    try:
        run(args)
    except Exception as exc:
        out = CaptureLayout(args.output).create()
        if not (out.diff / 'comparison.json').exists():
            write_json(out.diff / 'comparison.json', {'status': 'failed', 'stage': 'capture', 'error': str(exc)})
        raise


if __name__ == '__main__':
    main()
