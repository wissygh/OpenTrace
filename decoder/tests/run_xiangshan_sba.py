"""Run an existing XiangShan emu; control and read trace only through JTAG SBA."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import shutil
import signal
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
    def __init__(self, port, timeout=300, transcript=None, progress_interval=30):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=timeout)
        self.pending = b''
        self.transcript = transcript
        self.timeout = timeout
        self.progress_interval = progress_interval
        self.broken = False

    def run(self, command):
        if self.broken:
            raise RuntimeError('previous RPC transport failed; reconnect before sending another command')
        start = time.monotonic()
        wrapper = 'set __rc [catch {' + command + '} __value]; format "%d|%s" $__rc $__value'
        try:
            self.socket.sendall((wrapper + '\x1a').encode())
            while b'\x1a' not in self.pending:
                elapsed = time.monotonic() - start
                remaining = self.timeout - elapsed
                if remaining <= 0:
                    raise TimeoutError(f'OpenOCD RPC timed out after {elapsed:.1f}s: {command}')
                self.socket.settimeout(min(self.progress_interval, remaining))
                try:
                    data = self.socket.recv(65536)
                except socket.timeout:
                    print(f'[SBA RPC 等待 {time.monotonic()-start:.1f}s/{self.timeout}s] {command}', flush=True)
                    continue
                if not data:
                    raise RuntimeError('OpenOCD closed the Tcl RPC connection')
                self.pending += data
        except (OSError, RuntimeError) as exc:
            self.broken = True
            if self.transcript is not None:
                with Path(self.transcript).open('a') as output:
                    output.write(json.dumps({'command': command, 'return_code': None, 'result': str(exc),
                        'partial_response': self.pending.decode(errors='replace'),
                        'elapsed_seconds': time.monotonic() - start, 'transport_error': True}) + '\n')
            raise
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


def configure_ebreak_action(rpc, enter_debug):
    """Keep the target's resume policy and the explicit DCSR write consistent."""
    action = 'on' if enter_debug else 'off'
    # OpenOCD reapplies this policy when resuming. A raw DCSR.EBREAKM write
    # alone is overwritten by riscv-013.c:set_dcsr_ebreak before execution.
    # This command is also available in the installed OpenOCD 0.12 development
    # build, which predates the newer target configure -ebreak interface.
    rpc.run(f'riscv set_ebreakm {action}')
    response = rpc.run('riscv set_ebreakm')
    actual = re.search(r'riscv_ebreakm enabled:\s*(on|off)\b', response)
    if actual is None or actual.group(1) != action:
        raise RuntimeError(f'OpenOCD EBREAKM resume policy differs: {response!r}, expected {action!r}')
    return action


def collect(rpc, directory, symbols, scenario='basic', features=0, discovery=False, halt_during_pressure=False,
            debug_entry=None):
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

    debug_events = []

    def debug_event(kind, cause, pc=None):
        actual_pc, dcsr = register('pc'), register('dcsr')
        if (dcsr >> 6) & 7 != cause or (pc is not None and actual_pc != pc):
            raise RuntimeError(f'{kind} did not produce the expected actual Debug entry: pc={actual_pc:#x}, dcsr={dcsr:#x}')
        debug_events.append({'kind': kind, 'cause': cause, 'pc': hex(actual_pc), 'dcsr': hex(dcsr)})

    def drain_halted():
        for _ in range(30):
            if read(0x20000000) & 8:
                return
        raise RuntimeError('queued retirement did not drain while the hart was halted')

    print('[XS-SBA][1/5] hart已halt；强制sysbus，设置完成地址的硬件断点', flush=True)
    rpc.run('riscv set_mem_access sysbus')
    rpc.run(f"bp 0x{symbols['trace_done']:x} 2 hw")
    if scenario in ('ebreak-debug', 'ebreak-exception'):
        configure_ebreak_action(rpc, scenario == 'ebreak-debug')
        dcsr = register('dcsr')
        dcsr = dcsr | (1 << 15) if scenario == 'ebreak-debug' else dcsr & ~(1 << 15)
        rpc.run(f'reg dcsr 0x{dcsr:x}')
        if bool(register('dcsr') & (1 << 15)) != (scenario == 'ebreak-debug'):
            raise RuntimeError('DCSR.EBREAKM configuration was rejected')
        rpc.run('reg mstatus 0x0')
        rpc.run(f"reg mtvec 0x{symbols['trace_handler']:x}")
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
        if actual_features & 0xfb != features:
            raise RuntimeError('encoder rejected the requested instruction features')
    write(0x20040000, 0x101 if scenario == 'pressure' else 0x103)  # delayed sink enable for pressure
    write(0x20010000, 3)
    write(0x20000000, 0x2007)  # InstStallEna, InstTracing, Enable, Active
    print('[XS-SBA][2/5] 外部resume纯workload→完成地址硬件断点halt', flush=True)
    if debug_entry == 'single-step':
        rpc.run(f"step 0x{symbols['trace_begin']:x}")
        rpc.run('wait_halt 120000')
        debug_event('single-step', 4, symbols['trace_begin'] + 2)
        drain_halted()
        rpc.run('resume')
    elif scenario == 'ebreak-debug':
        rpc.run(f"resume 0x{symbols['trace_begin']:x}")
        rpc.run('wait_halt 120000')
        debug_event('ebreak-debug', 1, symbols['trace_debug_break'])
        drain_halted()
        # An ebreak entering Debug is not retired. Explicit debugger PC advance
        # skips it once; both the original stop PC and write stay in the RPC log.
        rpc.run(f"reg pc 0x{symbols['trace_after_ebreak']:x}")
        if register('pc') != symbols['trace_after_ebreak']:
            raise RuntimeError('debugger failed to advance beyond the non-retired ebreak')
        rpc.run('resume')
    else:
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
            debug_event('external-haltreq', 3, halt_pc)
            drain_halted()
            rpc.run('resume')
    rpc.run('wait_halt 120000')
    metadata = {'memory_access': 'sysbus', 'pc': register('pc'), 'a0': register('a0'),
                'scenario': scenario, 'pressure_stalls_before_release': pressure_stalls}
    if debug_entry or halt_during_pressure:
        debug_event('hardware-trigger', 2, symbols['trace_done'])
        metadata.update(debug_entry=debug_entry, debug_events=debug_events, require_debug_classification=True)
    if scenario in ('exception', 'ebreak-exception'):
        metadata['trap_vector_readback'] = register('mtvec')
        if metadata['trap_vector_readback'] != symbols['trace_handler']:
            raise RuntimeError('actual mtvec differs from the workload handler')
    if scenario == 'ebreak-exception':
        metadata.update(ordinary_ebreak_cause=register('mcause'), ordinary_ebreak_tval=register('mtval'))
        if metadata['ordinary_ebreak_cause'] != 3:
            raise RuntimeError('ordinary ebreak did not update mcause to 3')
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
    if scenario in ('exception', 'ebreak-exception'):
        config['sources']['0']['trap_vectors'] = {'3': hex(metadata['trap_vector_readback'])}
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


def stop_capture(emu, debugger, flush_output=False):
    """Finish owned sink observers before closing JTAG and forcing shutdown."""
    if flush_output and emu is not None and emu.poll() is None:
        # Emu's SIGINT handler exits its normal loop and flushes buffered printf
        # records. Those records contain the final external pin/AXI transfers.
        emu.send_signal(signal.SIGINT)
        try:
            emu.wait(timeout=30)
        except subprocess.TimeoutExpired:
            pass
    stop(debugger)
    stop(emu)


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
    debug_entry = getattr(args, 'debug_entry', None)
    shutil.copytree(resources, source_copy)
    build_logs = layout.workload / 'logs'; build_logs.mkdir()
    with (build_logs / 'build.log').open('w') as build_log:
        subprocess.run([args.cross_prefix + 'gcc', '-march=rv64imc_zicsr_zifencei', '-mabi=lp64',
                        '-mcmodel=medany', '-nostdlib', '-static', '-Wl,--no-relax',
                        '-Wl,-T,' + str(source_copy / 'workload.ld'),
                        str(source_copy / ('sba-debug-ebreak.S' if debug_entry in ('ebreak-debug', 'ebreak-exception') else
                                           'sba-pressure.S' if args.pressure else
                                           'sba-exception.S' if getattr(args, 'trap', False) else 'sba-workload.S')), '-o', str(elf)],
                       stdout=build_log, stderr=subprocess.STDOUT, check=True)
        subprocess.run([args.cross_prefix + 'objcopy', '-O', 'binary', str(elf), str(binary)],
                       stdout=build_log, stderr=subprocess.STDOUT, check=True)
    nm = subprocess.check_output([args.cross_prefix + 'nm', str(elf)], text=True)
    (layout.workload / 'workload.symbols.txt').write_text(nm)
    with (layout.workload / 'workload.disasm').open('w') as disasm:
        subprocess.run([args.cross_prefix + 'objdump', '-d', '-S', str(elf)], stdout=disasm, check=True)
    symbols = {name: int(address, 16) for address, _, name in (line.split() for line in nm.splitlines())
               if name in ('trace_begin', 'trace_done', 'trace_loop', 'trace_target',
                           'trace_handler', 'trace_after_ecall', 'trace_after_illegal',
                           'trace_debug_break', 'trace_after_ebreak')}
    if not {'trace_begin', 'trace_done'} <= set(symbols):
        raise RuntimeError('workload ELF is missing its entry/exit symbols')
    write_json(layout.workload / 'symbols.json', {name: hex(value) for name, value in symbols.items()})
    scenario = debug_entry if debug_entry in ('ebreak-debug', 'ebreak-exception') else (
        'pressure' if args.pressure else 'exception' if getattr(args, 'trap', False) else 'basic')
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
            if getattr(args, 'sink', 'sram') == 'smem':
                from .xiangshan_sink_capture import capture_sba_capabilities
                capture_sba_capabilities(rpc, layout)
            if getattr(args, 'check_control', False):
                from .xiangshan_control_session import run_checks
                run_checks(rpc, layout, getattr(args, 'sink', 'sram'))
            if getattr(args, 'sink', 'sram') == 'sram':
                collect(rpc, directory, symbols, scenario, getattr(args, 'features', 0), getattr(args, 'discovery', False),
                        getattr(args, 'halt_during_pressure', False), debug_entry)
            else:
                from .xiangshan_sink_capture import collect as collect_sink
                collect_sink(rpc, directory, symbols, scenario, args)
        finally:
            if rpc is not None:
                rpc.close()
            # SimJTAG can wait indefinitely on EOF after OpenOCD exits. Stop both
            # owned processes; this does not require a guest trap instruction.
            stop_capture(emu, openocd, flush_output=getattr(args, 'sink', 'sram') != 'sram')
    diff_logs = layout.diff / 'logs'; diff_logs.mkdir()
    with (diff_logs / 'check.log').open('w') as output, redirect_stdout(Tee(sys.stdout, output)):
        if getattr(args, 'sink', 'sram') != 'sram':
            from .xiangshan_sink_capture import check as check_sink
            return check_sink(directory)
        return check(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emu', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--openocd', default='openocd')
    parser.add_argument('--pressure', action='store_true', help='hold SRAM disabled until real core trace stall, then release')
    parser.add_argument('--trap', action='store_true', help='exercise real machine exceptions with a known mtvec')
    parser.add_argument('--sink', choices=('sram', 'atb', 'pib', 'smem'), default='sram',
                        help='must match the sink selected in the actual emu build')
    parser.add_argument('--pib-mode', type=int, choices=(4, 5, 8, 9, 10, 11, 12), default=11)
    parser.add_argument('--pib-divider', type=int, default=3)
    parser.add_argument('--pib-center', action='store_true')
    parser.add_argument('--cross-prefix', default='riscv64-unknown-elf-')
    parser.add_argument('--features', type=lambda value: int(value, 0), default=0)
    parser.add_argument('--check-control', action='store_true', help='run logged external SBA control-field interactions before workload')
    parser.add_argument('--discovery', action='store_true', help='read Table42 from real encoder vendor CSR')
    parser.add_argument('--startup-timeout', type=int, default=300)
    parser.add_argument('--rpc-timeout', type=int, default=300)
    parser.add_argument('--cpu-start', type=int, help='pin the eight emulator threads to consecutive host CPUs')
    parser.add_argument('--halt-during-pressure', action='store_true', help='halt while stalled, drain old retirement, then resume')
    parser.add_argument('--debug-entry', choices=('hardware-trigger', 'single-step', 'ebreak-debug', 'ebreak-exception'),
                        help='validate actual DCSR entry cause and ordered trace boundaries; requires the Q040 fix')
    args = parser.parse_args()
    if args.debug_entry and (args.sink != 'sram' or args.trap or
                             (args.pressure and args.debug_entry != 'hardware-trigger')):
        parser.error('--debug-entry requires SRAM and a compatible basic/ebreak workload')
    if args.trap and args.pressure:
        parser.error('--trap and --pressure select different workloads')
    if not 0 <= args.pib_divider <= 65535 or (args.pib_center and args.pib_mode < 8):
        parser.error('PIB divider must fit sixteen bits; clock center requires a parallel mode')
    if args.halt_during_pressure and args.sink != 'sram':
        parser.error('--halt-during-pressure currently requires the SRAM collector')
    if args.halt_during_pressure and not args.pressure:
        parser.error('--halt-during-pressure requires --pressure')
    try:
        run(args)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
        raise SystemExit(f'XS JTAG/SBA run failed: {exc}')


if __name__ == '__main__':
    main()
