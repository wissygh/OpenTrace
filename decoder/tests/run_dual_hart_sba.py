"""Run two same-clock XiangShan harts through real encoders, shared SRAM and decoder."""
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
from decoder.image import ProgramImage
from decoder.modes import mode_name
from decoder.tests.build_dual_hart_workload import build, CRC
from decoder.tests.check_coremark_sba import build_reader
from decoder.tests.check_dual_hart_sba import check_and_measure
from decoder.tests.run_xiangshan_sba import RPC, free_port, listening, pin_threads, stop_capture


ENCODERS = (0x20000000, 0x20010000)
FUNNEL = 0x20020000
SRAM = 0x20050000


def check_recorder_provenance(recorder, emu, long_capture):
    path = Path(recorder).resolve().parent / 'provenance.json'
    provenance = json.loads(path.read_text())
    if provenance.get('emu_sha256') != hashlib.sha256(Path(emu).read_bytes()).hexdigest():
        raise ValueError('physical SRAM recorder provenance belongs to another emu')
    if long_capture and provenance.get('status') != 'validated':
        raise ValueError('long capture requires validated full/filtered signals and complete short SBA byte equality')
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), **provenance}


def collect(rpc, out, manifest, features, pressure=False, long_capture=False, timeout=7200):
    read = lambda address: int(rpc.run(f'read_memory 0x{address:x} 32 1 phys'), 0)
    def write(address, value):
        rpc.run(f'write_memory 0x{address:x} 32 {{0x{value:x}}} phys')
    def select(hart):
        rpc.run(f'targets xs.cpu{hart}')
    def reg(name):
        value = rpc.run('reg ' + name)
        match = re.search(r':\s*(0x[0-9a-fA-F]+)', value)
        if not match:
            raise RuntimeError('missing actual architectural register: ' + name)
        return int(match.group(1), 16)
    metadata = {'status': 'incomplete', 'same_core_clock': True, 'pressure': pressure or long_capture,
                'capture_method': 'all physical SRAM writes plus SBA tail' if long_capture else 'all bytes from physical SBA RAMData',
                'harts': {}, 'control': 'external JTAG -> Debug SBA physical sysbus'}
    try:
        print('[dual-hart][1/6] select both actual debug targets, install independent completion breakpoints', flush=True)
        for hart, description in enumerate(manifest['harts']):
            select(hart); rpc.run('riscv set_mem_access sysbus')
            actual_hart = reg('mhartid')
            if actual_hart != hart:
                raise RuntimeError('debug target selected the wrong physical hart')
            symbols = description['symbols']
            image = ProgramImage.from_elf(out.workload / description['elf'])
            size = image.instruction(int(symbols['trace_done'], 0)).size
            rpc.run(f"bp {symbols['trace_done']} {size} hw")
            metadata['harts'][str(hart)] = {'actual_mhartid': actual_hart, 'features_requested': features[hart]}
        select(0)
        for address in (*ENCODERS, FUNNEL, SRAM):
            write(address, 1)
            if not read(address) & 1:
                raise RuntimeError('dual component Active did not release')
        write(SRAM + 0x20, 0)
        for hart, address in enumerate(ENCODERS):
            write(address + 0xf04, 1)
            words = [int(v, 0) for v in rpc.run(f'read_memory 0x{address+0xe00:x} 32 20 phys').split()]
            if len(words) != 20:
                raise RuntimeError('discovery word count differs')
            path = out.dut / f'hart{hart}-discovery.bin'
            path.write_bytes(struct.pack('<20I', *words))
            discovery = read_snapshot(path)
            write_json(path.with_suffix('.json'), discovery)
            write(address + 8, features[hart])
            actual = read(address + 8)
            if actual & 0xffff != features[hart] or (actual >> 16) & 0xfff != hart:
                raise RuntimeError('per-encoder features or physical srcID readback differs')
            metadata['harts'][str(hart)]['features_readback'] = actual
        write(SRAM, 1 if pressure or long_capture else 0x103)
        write(FUNNEL, 3)
        for address in ENCODERS:
            write(address, 0xf02007)
        metadata['rob_byte_start'] = (out.ref / 'rob.log').stat().st_size
        print('[dual-hart][2/6] resume both private workloads; shared funnel retains in-band srcID', flush=True)
        for hart, description in enumerate(manifest['harts']):
            select(hart)
            rpc.run('resume ' + description['symbols']['trace_begin'])
        if pressure or long_capture:
            print('[dual-hart][pressure] hold shared SRAM disabled until both physical encoder stalls are visible', flush=True)
            select(0)
            for hart, address in enumerate(ENCODERS):
                for _ in range(40):
                    stalls = read(address + 0xf20)
                    if stalls:
                        break
                else:
                    raise RuntimeError('both harts must stall before the shared sink is released')
                if read(address + 0xf10) or read(address + 0xf18):
                    raise RuntimeError('dual-hart ingress exceeded its real backpressure budget')
                metadata['harts'][str(hart)]['pressure_stalls'] = stalls
            write(SRAM, 3 if long_capture else 0x103)
        deadline = time.monotonic() + timeout
        pending = {0, 1}
        while pending:
            for hart in sorted(pending):
                select(hart)
                state = rpc.run(f'xs.cpu{hart} arp_poll; xs.cpu{hart} curstate').strip()
                if state == 'halted':
                    pending.remove(hart)
                    status = metadata['harts'][str(hart)]
                    status.update(pc=reg('pc'), a0=reg('a0'), dcsr=reg('dcsr'))
                    if status['pc'] != int(manifest['harts'][hart]['symbols']['trace_done'], 0):
                        raise RuntimeError(f'hart {hart} stopped before its real completion boundary')
                    if manifest['kind'] == 'coremark':
                        status['benchmark_result'] = {'magic': reg('a7'), 'hart': reg('s0'), 'errors': status['a0'],
                            'iterations': reg('a6'), 'seedcrc': reg('a1'), 'crclist': reg('a2'),
                            'crcmatrix': reg('a3'), 'crcstate': reg('a4'), 'crcfinal': reg('a5'),
                            'cycle_interval': reg('s1'), 'instret_interval': reg('s2')}
                        record = status['benchmark_result']
                        if record['magic'] != 0x434d4f54 or record['hart'] != hart or record['errors'] or record['iterations'] != 2:
                            raise RuntimeError('actual per-hart benchmark result is invalid')
                        if any(record[key] != value for key, value in CRC.items()):
                            raise RuntimeError('actual per-hart CoreMark CRC mismatch')
                    print(f'[dual-hart][3/6] hart={hart} completed, actual PC={status["pc"]:#x}, DCSR={status["dcsr"]:#x}', flush=True)
            if pending:
                if time.monotonic() > deadline:
                    raise RuntimeError('a dual-hart workload did not complete')
                time.sleep(2)
        print('[dual-hart][4/6] stop both encoders, then shared Funnel and SRAM; verify drain and zero loss', flush=True)
        select(0)
        for hart, address in enumerate(ENCODERS):
            write(address, 0xf00001)
            for _ in range(40):
                value = read(address)
                if value & 11 == 9:
                    break
            else:
                raise RuntimeError('encoder did not drain')
            metadata['harts'][str(hart)]['encoder'] = value
        for name, address in (('funnel', FUNNEL), ('sram', SRAM)):
            write(address, 1)
            for _ in range(40):
                value = read(address)
                if value & 11 == 9:
                    break
            else:
                raise RuntimeError('shared path did not drain')
            metadata[name] = value
        for hart, address in enumerate(ENCODERS):
            status = metadata['harts'][str(hart)]
            for name, offset in (('dropped', 0xf10), ('loss_markers', 0xf18), ('stall_cycles', 0xf20),
                                 ('packet_wait_cycles', 0xf28), ('downstream_wait_cycles', 0xf30)):
                status[name] = read(address + offset)
            if status['dropped'] or status['loss_markers']:
                raise RuntimeError('a hart lost retirement before capture')
        wp = read(SRAM + 0x20)
        if long_capture:
            start = (((wp & ~3) // 4) - 16) % 256
            write(SRAM + 0x28, start * 4)
            words = [read(SRAM + 0x40) for _ in range(16)]
            metadata['storage'] = {'wp_register': wp, 'sample_start_word': start, 'sample_words': words,
                                   'rp_after_sample': read(SRAM + 0x28)}
        else:
            if not 0 < wp < 0x400 or wp & 3:
                raise RuntimeError('short dual capture unexpectedly wrapped')
            write(SRAM + 0x28, 0)
            words = [int(v, 0) for v in rpc.run(f'read_memory 0x{SRAM+0x40:x} 32 1 phys').split()]
            if len(words) != 1:
                raise RuntimeError('invalid RAMData word response')
            words += [read(SRAM + 0x40) for _ in range(wp // 4 - 1)]
            (out.dut / 'trace.bin').write_bytes(b''.join(v.to_bytes(4, 'little') for v in words))
            write_json(out.dut / 'sba-words.json', words)
            metadata['storage'] = {'wp': wp, 'rp': read(SRAM + 0x28)}
        metadata['status'] = 'captured'
        print('[dual-hart][5/6] real SRAM bytes/tail read through physical SBA; decoder checks follow process shutdown', flush=True)
    except BaseException as exc:
        metadata.update(status='failed', error=str(exc)); raise
    finally:
        write_json(out.dut / 'metadata.json', metadata)
    write_json(out.dut / 'trace.json', {'sources': {str(h): {'hart_id': h,
        'discovery': f'hart{h}-discovery.bin', 'elf': '../workload/' + manifest['harts'][h]['elf']}
        for h in (0, 1)}})


def run(args):
    directory = args.output.resolve()
    if directory.exists() and any(directory.iterdir()):
        raise RuntimeError('output must be empty; existing raw evidence cannot be overwritten')
    out = CaptureLayout(directory).create()
    logs = out.dut / 'logs'; logs.mkdir()
    manifest = build(out.workload, args.kind, args.coremark_source)
    rb, tc = free_port(), free_port()
    while tc == rb:
        tc = free_port()
    env = dict(os.environ, OT_RBB_PORT=str(rb), OT_TCL_PORT=str(tc))
    command = [str(args.emu.resolve()), '--no-diff', '--enable-jtag', f'--remote-jtag-port={rb}',
               '--ram-size=64MB', '--max-cycles=20000000', '--dramsim3-outdir=' + str(logs),
               '-i', str(out.workload / 'workload.bin')]
    if args.kind == 'coremark' and not args.recorder:
        raise ValueError('long circular SRAM capture needs a validated physical-port recorder')
    provenance = None
    if args.recorder:
        provenance = check_recorder_provenance(args.recorder, args.emu, args.kind == 'coremark')
        command = [str(args.recorder.resolve()), *command, '--dump-wave-full', '--wave-path=' + str(out.dut / 'trace.fst')]
    emu = debugger = rpc = None
    write_json(out.dut / 'run.json', {'command': command, 'emu_sha256': hashlib.sha256(args.emu.read_bytes()).hexdigest(),
               'features': args.features, 'modes': [mode_name(f) for f in args.features], 'same_core_clock': True,
               'recorder_provenance': provenance})
    try:
        with (logs / 'uart.log').open('w') as uart, (out.ref / 'rob.log').open('w') as rob, (logs / 'openocd.log').open('w') as debug:
            emu = subprocess.Popen(command, cwd=directory, stdout=uart, stderr=rob)
            if not args.recorder:
                pin_threads(emu.pid, args.cpu_start)
            else:
                from decoder.tests.run_coremark_sba import pin_actual
                pin_actual(emu.pid, args.cpu_start)
            deadline = time.monotonic() + 900
            while not listening(rb):
                if emu.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('dual-hart emu JTAG listener unavailable')
                time.sleep(.2)
            debugger = subprocess.Popen(['openocd', '-f', str(out.workload / 'source/openocd-sba.cfg')],
                                        cwd=directory, env=env, stdout=debug, stderr=subprocess.STDOUT)
            while rpc is None:
                if debugger.poll() is not None or emu.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('dual-hart OpenOCD did not initialize both targets')
                try:
                    rpc = RPC(tc, timeout=900, transcript=logs / 'rpc.jsonl')
                except ConnectionRefusedError:
                    time.sleep(.5)
            collect(rpc, out, manifest, args.features, args.kind == 'pressure', args.kind == 'coremark', args.timeout)
    finally:
        if rpc is not None:
            rpc.close()
        if args.recorder and emu is not None and emu.poll() is None:
            children = Path(f'/proc/{emu.pid}/task/{emu.pid}/children')
            if children.exists():
                for child in children.read_text().split():
                    exe = Path(f'/proc/{child}/exe')
                    if exe.exists() and exe.resolve().name == 'emu':
                        os.kill(int(child), signal.SIGINT)
                try:
                    emu.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    pass
        stop_capture(emu, debugger, flush_output=True)
    if args.recorder:
        with (logs / 'fst-reader.log').open('w') as log:
            reader_dir = Path(__file__).resolve().parents[2] / 'delp/tmp/requirements12/fst-readers' / directory.name
            reader_dir.mkdir(parents=True, exist_ok=True)
            tool = build_reader(reader_dir, log)
            physical_capture = out.dut / ('trace.bin' if args.kind == 'coremark' else 'physical-sram.bin')
            subprocess.run([str(tool), str(out.dut / 'trace.fst'), str(physical_capture),
                            str(out.dut / 'sram-writes.tsv'), str(out.dut / 'sram-signals.tsv')],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        rows = []
        for line in (out.dut / 'sram-writes.tsv').read_text().splitlines()[1:]:
            index, when, address, value = line.split('\t')
            rows.append({'word_index': int(index), 'fst_time': int(when), 'word_address': int(address), 'data': int(value, 0)})
        write_jsonl(out.dut / 'sram-writes.jsonl', rows)
        if args.kind != 'coremark':
            if physical_capture.read_bytes() != (out.dut / 'trace.bin').read_bytes():
                raise ValueError('FST physical SRAM writes differ from all independently read SBA bytes')
            write_json(out.diff / 'physical-vs-sba.json', {'status': 'passed', 'bytes': physical_capture.stat().st_size,
                       'sha256': hashlib.sha256(physical_capture.read_bytes()).hexdigest(),
                       'method': 'all physical write-port bytes equal the entire independently read SBA capture'})
    result, performance = check_and_measure(directory, args.baseline, args.isa_reference)
    print(f'[dual-hart][6/6] passed: {result["decoded_instructions"]} complete retirement, '
          f'{result["rob_samples"]} independent ROB samples, {performance["bits_per_instruction"]:.9f} bit/instruction', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emu', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--kind', choices=('basic', 'pressure', 'coremark'), default='basic')
    parser.add_argument('--features', nargs=2, type=lambda x: int(x, 0), default=[0, 0])
    parser.add_argument('--coremark-source', type=Path)
    parser.add_argument('--recorder', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--isa-reference', type=Path)
    parser.add_argument('--cpu-start', type=int, default=160)
    parser.add_argument('--timeout', type=int, default=7200)
    args = parser.parse_args()
    for f in args.features:
        mode_name(f)
    try:
        run(args)
    except Exception as exc:
        layout = CaptureLayout(args.output).create()
        if not (layout.diff / 'comparison.json').exists():
            write_json(layout.diff / 'comparison.json', {'status': 'failed', 'stage': 'capture', 'error': str(exc)})
        raise


if __name__ == '__main__':
    main()
