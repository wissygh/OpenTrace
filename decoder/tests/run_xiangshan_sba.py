"""Run an existing XiangShan emu; control and read trace only through JTAG SBA."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import shutil
import subprocess
import time
import struct
from contextlib import redirect_stdout
import sys
from .check_xiangshan_sba import check
from decoder.artifacts import write_json
from decoder.capture_layout import CaptureLayout
from decoder.discovery import read_snapshot
from .xiangshan_sba_scenarios import reference


class RPC:
    def __init__(self, port, timeout=300, transcript=None):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=timeout)
        self.pending = b''
        self.transcript = transcript

    def run(self, command):
        start = time.monotonic()
        wrapper = 'set __rc [catch {' + command + '} __value]; format "%d|%s" $__rc $__value'
        self.socket.sendall((wrapper + '\x1a').encode())
        while b'\x1a' not in self.pending:
            data = self.socket.recv(65536)
            if not data:
                raise RuntimeError('OpenOCD closed the Tcl RPC connection')
            self.pending += data
        response, self.pending = self.pending.split(b'\x1a', 1)
        code, separator, value = response.decode().partition('|')
        elapsed = time.monotonic() - start
        print(f'[SBA RPC {elapsed:.1f}s] {command}: {code}|{value}', flush=True)
        if self.transcript is not None:
            with Path(self.transcript).open('a') as output:
                output.write(json.dumps({'command': command, 'return_code': code, 'result': value,
                                         'elapsed_seconds': elapsed}) + '\n')
        if separator != '|' or code != '0':
            raise RuntimeError(f'OpenOCD command failed: {command}: {response!r}')
        return value

    def close(self):
        self.socket.close()


def capture_discovery(rpc, directory):
    """Read real Table42 vendor CSR words; preserve exact little-endian bytes."""
    words = [int(value, 0) for value in rpc.run('read_memory 0x20000e00 32 20 phys').split()]
    if len(words) != 20:
        raise RuntimeError('SBA discovery must return twenty 32-bit words')
    path = Path(directory) / 'discovery.bin'
    path.write_bytes(struct.pack('<20I', *words))
    discovered = read_snapshot(path)
    write_json(path.with_suffix('.json'), discovered)
    print('[XS-SBA][discovery] 实际20word读取通过，return_stack_size=' +
          str(discovered['attributes']['return_stack_size']), flush=True)
    return discovered


def pin_threads(pid, cpu_start):
    if cpu_start is None:
        return
    time.sleep(0.5)
    tids = sorted(int(p.name) for p in Path(f'/proc/{pid}/task').iterdir())
    for i, tid in enumerate(tids):
        os.sched_setaffinity(tid, {cpu_start + i % 8})


def collect(rpc, directory, symbols, scenario='basic', features=0, discovery=False, halt_during_pressure=False):
    directory = Path(directory)
    layout = CaptureLayout(directory).create()
    expected_a0, _ = reference(symbols, scenario)

    def read(address):
        return int(rpc.run(f'read_memory 0x{address:x} 32 1 phys').strip(), 0)

    def write(address, value):
        rpc.run(f'write_memory 0x{address:x} 32 {{0x{value:x}}} phys')

    def register(name):
        value = rpc.run(f'reg {name}')
        match = re.search(r':\s*(0x[0-9a-fA-F]+)', value)
        if not match:
            raise RuntimeError(f'cannot parse {name} register response: {value!r}')
        return int(match.group(1), 16)

    print('[XS-SBA][1/5] hart已halt；强制sysbus，设置完成地址的硬件断点', flush=True)
    rpc.run('riscv set_mem_access sysbus')
    rpc.run(f"bp 0x{symbols['trace_done']:x} 2 hw")
    for address in (0x20040000, 0x20010000, 0x20000000):
        write(address, 1)
        if not read(address) & 1:
            raise RuntimeError(f'component Active did not release: {address:#x}')
    write(0x20040020, 0)
    write(0x20000f04, 1)
    actual_features = None
    if discovery:
        capture_discovery(rpc, layout.dut)
    if features or discovery:
        write(0x20000008, features)
        actual_features = read(0x20000008)
        if actual_features & 0xf9 != features:
            raise RuntimeError('encoder rejected the requested instruction features')
    write(0x20040000, 0x101 if scenario == 'pressure' else 0x103)  # delayed sink enable for pressure
    write(0x20010000, 3)
    write(0x20000000, 0x2007)  # InstStallEna, InstTracing, Enable, Active
    print('[XS-SBA][2/5] 外部resume纯workload→完成地址硬件断点halt', flush=True)
    rpc.run(f"resume 0x{symbols['trace_begin']:x}")
    pressure_stalls = 0
    halt_pc = None
    if scenario == 'pressure':
        print('[XS-SBA][pressure] SRAM暂不接纳；外部SBA轮询实际stall计数，再放开sink', flush=True)
        for _ in range(20):
            pressure_stalls = read(0x20000f20)
            if pressure_stalls:
                break
        else:
            raise RuntimeError('pressure workload never asserted trace stall')
        if read(0x20000f10) or read(0x20000f18):
            raise RuntimeError('core exceeded the actual trace stall budget before sink release')
        if halt_during_pressure:
            print('[XS-SBA][stop-frontier] 真实stall积压期间外部halt；放开SRAM后先排完旧退休，再resume', flush=True)
            # OpenOCD's RISC-V halt_go polls inside target_halt even for
            # "halt 0". Set hart0's Debug Module HALTREQ directly so SBA can
            # release the sink before the hart services that request.
            rpc.run('riscv dmi_write 0x10 0x80000001')
        write(0x20040000, 0x103)
        if halt_during_pressure:
            rpc.run('wait_halt 120000')
            rpc.run('riscv dmi_write 0x10 0x1')  # clear HALTREQ; keep DMACTIVE
            halt_pc = register('pc')
            for _ in range(30):
                if read(0x20000000) & 8:
                    break
            else:
                raise RuntimeError('queued retirement did not drain while the hart was halted')
            rpc.run('resume')
    rpc.run('wait_halt 120000')
    metadata = {'memory_access': 'sysbus', 'pc': register('pc'), 'a0': register('a0'),
                'scenario': scenario, 'pressure_stalls_before_release': pressure_stalls}
    if actual_features is not None:
        metadata.update(features_requested=features, features_readback=actual_features)
    if halt_pc is not None:
        metadata.update(halt_during_pressure=True, pressure_halt_pc=hex(halt_pc))
    if metadata['pc'] != symbols['trace_done'] or metadata['a0'] != expected_a0:
        raise RuntimeError(f'workload did not finish correctly: {metadata}')
    print('[XS-SBA][3/5] SBA按encoder→Funnel→SRAM停止；轮询Disable/Empty', flush=True)
    for name, address in (('encoder', 0x20000000), ('funnel', 0x20010000), ('sram', 0x20040000)):
        write(address, 1)
        for _ in range(20):
            metadata[name] = read(address)
            if metadata[name] & 0xb == 9:
                break
        else:
            raise RuntimeError(f'{name} did not become disabled and empty')
    for name, address in (('wp', 0x20040020), ('dropped', 0x20000f10),
                          ('loss_markers', 0x20000f18), ('stall_cycles', 0x20000f20)):
        metadata[name] = read(address)
    write_json(layout.dut / 'metadata.json', metadata)
    wp = metadata['wp']
    if metadata['dropped'] or metadata['loss_markers'] or not 0 < wp < 0x400 or wp & 3:
        raise RuntimeError(f'loss/Wrap or invalid SRAM pointer: {metadata}')
    print(f'[XS-SBA][4/5] 无loss/Wrap，SBA重复读RAMData，共{wp}bytes', flush=True)
    write(0x20040028, 0)
    with (layout.dut / 'trace.bin').open('wb') as capture:
        for offset in range(0, wp, 4):
            word = read(0x20040040)
            capture.write(word.to_bytes(4, 'little'))
            print(f'[SBA-WORD] {offset:04x} {word:08x}', flush=True)
    metadata['rp'] = read(0x20040028)
    write_json(layout.dut / 'metadata.json', metadata)
    if metadata['rp'] != wp:
        raise RuntimeError('RAMData reads did not advance RP exactly once per word')
    config = {'source_bits': 8, 'sources': {'0': {'hart_id': 0, 'elf': '../workload/workload.elf'}}}
    if discovery:
        config['discovery'] = 'discovery.bin'
    (layout.dut / 'trace.json').write_text(json.dumps(config, indent=2) + '\n')
    print('[XS-SBA][5/5] 真实SBA捕获已保存，交decoder和独立ROB日志', flush=True)


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def listening(port):
    # A TCP connection would consume remote_bitbang's only client slot.
    # Inspect the Linux listener table instead; stdio may buffer its banner.
    for line in Path('/proc/net/tcp').read_text().splitlines()[1:]:
        fields = line.split()
        if fields[3] == '0A' and int(fields[1].rsplit(':', 1)[1], 16) == port:
            return True
    return False


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


class Tee:
    def __init__(self, console, log):
        self.console, self.log = console, log

    def write(self, value):
        self.console.write(value)
        self.log.write(value)

    def flush(self):
        self.console.flush()
        self.log.flush()


def run(args):
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise RuntimeError('output directory must be empty; existing run evidence will not be overwritten')
    layout = CaptureLayout(directory).create()
    logs = layout.dut / 'logs'; logs.mkdir()
    with (logs / 'runner.log').open('w') as output, redirect_stdout(Tee(sys.stdout, output)):
        try:
            result = run_stages(args, layout)
            write_json(layout.dut / 'run-result.json', {'status': 'passed'})
            return result
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
            write_json(layout.dut / 'run-result.json', {'status': 'failed', 'error': str(exc)})
            if not (layout.diff / 'comparison.json').exists():
                write_json(layout.diff / 'comparison.json', {'status': 'failed', 'stage': 'capture', 'error': str(exc)})
            raise


def run_stages(args, layout):
    root = Path(__file__).resolve().parents[2]
    resources = root / 'src/test/resources/xiangshan'
    directory = layout.root
    logs = layout.dut / 'logs'
    binary = layout.workload / 'workload.bin'
    elf = layout.workload / 'workload.elf'
    source_copy = layout.workload / 'source'
    shutil.copytree(resources, source_copy)
    build_logs = layout.workload / 'logs'; build_logs.mkdir()
    with (build_logs / 'build.log').open('w') as build_log:
        subprocess.run([args.cross_prefix + 'gcc', '-march=rv64imc_zicsr_zifencei', '-mabi=lp64',
                        '-mcmodel=medany', '-nostdlib', '-static', '-Wl,--no-relax',
                        '-Wl,-T,' + str(source_copy / 'workload.ld'),
                        str(source_copy / ('sba-pressure.S' if args.pressure else 'sba-workload.S')), '-o', str(elf)],
                       stdout=build_log, stderr=subprocess.STDOUT, check=True)
        subprocess.run([args.cross_prefix + 'objcopy', '-O', 'binary', str(elf), str(binary)],
                       stdout=build_log, stderr=subprocess.STDOUT, check=True)
    nm = subprocess.check_output([args.cross_prefix + 'nm', str(elf)], text=True)
    (layout.workload / 'workload.symbols.txt').write_text(nm)
    with (layout.workload / 'workload.disasm').open('w') as disasm:
        subprocess.run([args.cross_prefix + 'objdump', '-d', '-S', str(elf)], stdout=disasm, check=True)
    symbols = {name: int(address, 16) for address, _, name in (line.split() for line in nm.splitlines())
               if name in ('trace_begin', 'trace_done', 'trace_loop', 'trace_target')}
    if not {'trace_begin', 'trace_done'} <= set(symbols):
        raise RuntimeError('workload ELF is missing its entry/exit symbols')
    write_json(layout.workload / 'symbols.json', {name: hex(value) for name, value in symbols.items()})
    scenario = 'pressure' if args.pressure else 'basic'
    reference(symbols, scenario)
    write_json(layout.workload / 'scenario.json', {'scenario': scenario})
    rbb_port, tcl_port = free_port(), free_port()
    while tcl_port == rbb_port:
        tcl_port = free_port()
    env = dict(os.environ, OT_RBB_PORT=str(rbb_port), OT_TCL_PORT=str(tcl_port))
    emu_command = [str(args.emu.resolve()), '--no-diff', '--enable-jtag', f'--remote-jtag-port={rbb_port}',
                   '--ram-size=64MB', '--max-cycles=20000000', '--dramsim3-outdir=' + str(logs), '-i', str(binary)]
    (layout.dut / 'run.json').write_text(json.dumps({'emu': emu_command, 'rbb_port': rbb_port,
                                                   'tcl_port': tcl_port}, indent=2) + '\n')
    emu = openocd = rpc = None
    with (logs / 'uart.log').open('w') as uart, (layout.ref / 'rob.log').open('w') as rob, \
            (logs / 'openocd.log').open('w') as debugger:
        try:
            emu = subprocess.Popen(emu_command, cwd=directory, stdout=uart, stderr=rob)
            pin_threads(emu.pid, getattr(args, 'cpu_start', None))
            deadline = time.monotonic() + getattr(args, 'startup_timeout', 300)
            # Do not connect a TCP probe to remote_bitbang: it accepts only one client.
            while not listening(rbb_port):
                if emu.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('emu did not start its remote_bitbang listener')
                time.sleep(0.2)
            openocd = subprocess.Popen([args.openocd, '-f', str(source_copy / 'openocd-sba.cfg')],
                                      cwd=directory, env=env, stdout=debugger, stderr=subprocess.STDOUT)
            print('[XS-SBA] 等待OpenOCD识别Debug Module、halt并开放Tcl RPC', flush=True)
            while rpc is None:
                if emu.poll() is not None or openocd.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('OpenOCD did not become ready; inspect openocd.log')
                try:
                    rpc = RPC(tcl_port, timeout=getattr(args, 'rpc_timeout', 300), transcript=logs / 'rpc.jsonl')
                except ConnectionRefusedError:
                    time.sleep(0.5)
            collect(rpc, directory, symbols, scenario, getattr(args, 'features', 0), getattr(args, 'discovery', False),
                    getattr(args, 'halt_during_pressure', False))
        finally:
            if rpc is not None:
                rpc.close()
            # SimJTAG can wait indefinitely on EOF after OpenOCD exits. Stop both
            # owned processes; this does not require a guest trap instruction.
            stop(openocd)
            stop(emu)
    diff_logs = layout.diff / 'logs'; diff_logs.mkdir()
    with (diff_logs / 'check.log').open('w') as output, redirect_stdout(Tee(sys.stdout, output)):
        return check(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emu', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--openocd', default='openocd')
    parser.add_argument('--pressure', action='store_true', help='hold SRAM disabled until real core trace stall, then release')
    parser.add_argument('--cross-prefix', default='riscv64-unknown-elf-')
    parser.add_argument('--features', type=lambda value: int(value, 0), default=0)
    parser.add_argument('--discovery', action='store_true', help='read Table42 from real encoder vendor CSR')
    parser.add_argument('--startup-timeout', type=int, default=300)
    parser.add_argument('--rpc-timeout', type=int, default=300)
    parser.add_argument('--cpu-start', type=int, help='pin the eight emulator threads to consecutive host CPUs')
    parser.add_argument('--halt-during-pressure', action='store_true', help='halt while stalled, drain old retirement, then resume')
    args = parser.parse_args()
    if args.halt_during_pressure and not args.pressure:
        parser.error('--halt-during-pressure requires --pressure')
    try:
        run(args)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
        raise SystemExit(f'XS JTAG/SBA run failed: {exc}')


if __name__ == '__main__':
    main()
