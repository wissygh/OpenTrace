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
from .check_xiangshan_sba import check
from decoder.artifacts import write_json


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


def collect(rpc, directory, symbols):
    directory = Path(directory)

    def read(address):
        return int(rpc.run(f'read_memory 0x{address:x} 32 1').strip(), 0)

    def write(address, value):
        rpc.run(f'write_memory 0x{address:x} 32 {{0x{value:x}}}')

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
    write(0x20040000, 0x103)  # StopOnWrap, Enable, Active
    write(0x20010000, 3)
    write(0x20000000, 0x2007)  # InstStallEna, InstTracing, Enable, Active
    print('[XS-SBA][2/5] 外部resume纯workload→完成地址硬件断点halt', flush=True)
    rpc.run(f"resume 0x{symbols['trace_begin']:x}")
    rpc.run('wait_halt 120000')
    metadata = {'memory_access': 'sysbus', 'pc': register('pc'), 'a0': register('a0')}
    if metadata['pc'] != symbols['trace_done'] or metadata['a0'] != 18:
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
    write_json(directory / 'metadata.json', metadata)
    wp = metadata['wp']
    if metadata['dropped'] or metadata['loss_markers'] or not 0 < wp < 0x400 or wp & 3:
        raise RuntimeError(f'loss/Wrap or invalid SRAM pointer: {metadata}')
    print(f'[XS-SBA][4/5] 无loss/Wrap，SBA重复读RAMData，共{wp}bytes', flush=True)
    write(0x20040028, 0)
    with (directory / 'trace.bin').open('wb') as capture:
        for offset in range(0, wp, 4):
            word = read(0x20040040)
            capture.write(word.to_bytes(4, 'little'))
            print(f'[SBA-WORD] {offset:04x} {word:08x}', flush=True)
    metadata['rp'] = read(0x20040028)
    write_json(directory / 'metadata.json', metadata)
    if metadata['rp'] != wp:
        raise RuntimeError('RAMData reads did not advance RP exactly once per word')
    (directory / 'trace.json').write_text(json.dumps(
        {'source_bits': 8, 'sources': {'0': {'hart_id': 0, 'elf': 'workload.elf'}}}, indent=2) + '\n')
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


def run(args):
    root = Path(__file__).resolve().parents[2]
    resources = root / 'src/test/resources/xiangshan'
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise RuntimeError('output directory must be empty; existing run evidence will not be overwritten')
    binary = directory / 'workload.bin'
    elf = directory / 'workload.elf'
    source_copy = directory / 'workload'
    shutil.copytree(resources, source_copy)
    subprocess.run([args.cross_prefix + 'gcc', '-march=rv64imc_zicsr_zifencei', '-mabi=lp64',
                    '-mcmodel=medany', '-nostdlib', '-static', '-Wl,--no-relax',
                    '-Wl,-T,' + str(source_copy / 'workload.ld'),
                    str(source_copy / 'sba-workload.S'), '-o', str(elf)], check=True)
    subprocess.run([args.cross_prefix + 'objcopy', '-O', 'binary', str(elf), str(binary)], check=True)
    nm = subprocess.check_output([args.cross_prefix + 'nm', str(elf)], text=True)
    (directory / 'workload.symbols.txt').write_text(nm)
    with (directory / 'workload.disasm').open('w') as disasm:
        subprocess.run([args.cross_prefix + 'objdump', '-d', '-S', str(elf)], stdout=disasm, check=True)
    symbols = {name: int(address, 16) for address, _, name in (line.split() for line in nm.splitlines())
               if name in ('trace_begin', 'trace_done')}
    if set(symbols) != {'trace_begin', 'trace_done'}:
        raise RuntimeError('workload ELF is missing its entry/exit symbols')
    write_json(directory / 'symbols.json', symbols)
    rbb_port, tcl_port = free_port(), free_port()
    while tcl_port == rbb_port:
        tcl_port = free_port()
    env = dict(os.environ, OT_RBB_PORT=str(rbb_port), OT_TCL_PORT=str(tcl_port))
    emu_command = [str(args.emu.resolve()), '--no-diff', '--enable-jtag', f'--remote-jtag-port={rbb_port}',
                   '--ram-size=64MB', '--max-cycles=20000000', '--dramsim3-outdir=.', '-i', str(binary)]
    (directory / 'run.json').write_text(json.dumps({'emu': emu_command, 'rbb_port': rbb_port,
                                                   'tcl_port': tcl_port}, indent=2) + '\n')
    emu = openocd = rpc = None
    with (directory / 'uart.log').open('w') as uart, (directory / 'rob.log').open('w') as rob, \
            (directory / 'openocd.log').open('w') as debugger:
        try:
            emu = subprocess.Popen(emu_command, cwd=directory, stdout=uart, stderr=rob)
            deadline = time.monotonic() + 300
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
                    rpc = RPC(tcl_port, transcript=directory / 'rpc.jsonl')
                except ConnectionRefusedError:
                    time.sleep(0.5)
            collect(rpc, directory, symbols)
        finally:
            if rpc is not None:
                rpc.close()
            # SimJTAG can wait indefinitely on EOF after OpenOCD exits. Stop both
            # owned processes; this does not require a guest trap instruction.
            stop(openocd)
            stop(emu)
    return check(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emu', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--openocd', default='openocd')
    parser.add_argument('--cross-prefix', default='riscv64-unknown-elf-')
    args = parser.parse_args()
    try:
        run(args)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
        raise SystemExit(f'XS JTAG/SBA run failed: {exc}')


if __name__ == '__main__':
    main()
