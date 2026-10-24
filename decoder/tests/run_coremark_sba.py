"""Capture one full CoreMark run using external JTAG/SBA and validated SRAM FST observation."""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import time
from pathlib import Path

from decoder.artifacts import write_json
from decoder.capture_layout import CaptureLayout
from decoder.modes import mode_name, support_options
from decoder.performance import measure
from decoder.tests.run_xiangshan_sba import RPC, free_port, listening, stop, capture_discovery, pin_threads
from decoder.tests.check_coremark_sba import check

ROOT = Path(__file__).resolve().parents[2]
END = 0x80001ca0


def retirement_hash(path):
    digest = hashlib.sha256()
    count = 0
    with path.open() as records:
        for line in records:
            row = json.loads(line)
            digest.update(f"{row['pc']}:{row['instruction']}:{row['size']}\n".encode())
            count += 1
    return count, digest.hexdigest()


def verify(directory, reference):
    result = check(directory)
    if result['decoded_instructions'] != 663686:
        raise ValueError('CoreMark full retirement count differs')
    reference_result = json.loads((reference / 'diff/comparison.json').read_text())
    if reference_result['status'] != 'passed':
        raise ValueError('reference CoreMark has not passed independent checks')
    actual = retirement_hash(directory / 'dut/instructions.jsonl')
    expected = retirement_hash(reference / 'dut/instructions.jsonl')
    if actual != expected:
        raise ValueError('full PC/instruction/size retirement tuples differ from baseline')
    write_json(directory / 'diff/baseline-retirement.json', {
        'status': 'passed', 'instructions': actual[0], 'sha256': actual[1],
        'reference': str(reference), 'basis': 'all ordered PC/instruction/size tuples, repeats retained'})
    performance = measure(directory)
    if performance['status'] != 'passed':
        raise ValueError('storage performance prerequisites failed')
    write_json(directory / 'diff/storage-performance.json', performance)
    print(f"[CoreMark] passed {mode_name(FEATURES)}: {performance['bits_per_instruction']:.9f} bit/instruction", flush=True)
    return performance


def layout(name):
    root=OUTPUT
    if root.exists() and any(root.iterdir()):raise RuntimeError('output exists: '+str(root))
    out=CaptureLayout(root).create();(out.dut/'logs').mkdir()
    shutil.copyfile(IMAGE,out.workload/'coremark.bin')
    assert int.from_bytes(IMAGE.read_bytes()[END-0x80000000:END-0x80000000+4],'little')==0x5006b
    write_json(out.workload/'inputs.json',{'original_image':str(IMAGE),'sha256':hashlib.sha256(IMAGE.read_bytes()).hexdigest(),
      'load_base':'0x80000000','hardware_breakpoint_before_exit':hex(END),'iterations':2})
    return out

def pin(pid, start):
    time.sleep(0.4)
    tids=sorted(int(p.name) for p in Path(f'/proc/{pid}/task').iterdir())
    for tid in tids[:8]:os.sched_setaffinity(tid,set(range(start,start+8)))

def pin_actual(parent, start):
    deadline=time.monotonic()+300
    while time.monotonic()<deadline:
        children=Path(f'/proc/{parent}/task/{parent}/children')
        if children.exists():
            for child in children.read_text().split():
                executable=Path(f'/proc/{child}/exe')
                if executable.exists() and executable.resolve().name=='emu':
                    pin_threads(int(child),start)
                    print('[CoreMark] actual emu threads pinned at host CPU '+str(start),flush=True)
                    return
        time.sleep(.2)
    raise RuntimeError('actual emu child not found')

def command(out):
    return [str(EMU),'--no-diff','--ram-size=64MB','--max-cycles=20000000',
      '--dramsim3-outdir='+str(out.dut/'logs'),'-i',str(out.workload/'coremark.bin')]

def trace():
    out=layout(MODE)
    rb,tc=free_port(),free_port()
    while tc==rb:tc=free_port()
    env=dict(os.environ,OT_RBB_PORT=str(rb),OT_TCL_PORT=str(tc))
    cfg=out.workload/'openocd-sba.cfg'
    shutil.copyfile(ROOT/'src/test/resources/xiangshan/openocd-sba.cfg',cfg)
    cmd=[str(RECORDER)]+command(out)+['--enable-jtag',f'--remote-jtag-port={rb}','--dump-wave-full',
      '--wave-path='+str(out.dut/'trace.fst')]
    write_json(out.dut/'run.json',{'emu':cmd,'rbb_port':rb,'tcl_port':tc,
      'capture_method':'all physical SRAM write-port transactions in full-clock FST, final SBA tail readback',
      'control':'external JTAG -> debug SBA, physical sysbus','encoder_control':'0xf02007',
      'stop_on_wrap':False,'source_bits':8,'mode':MODE,'te_inst_features':FEATURES,'requested_options':support_options(FEATURES),'emu_sha256':EMU_SHA,'recorder_provenance':PROVENANCE})
    emu=debugger=rpc=None
    metadata={'status':'incomplete','capture_method':'physical SRAM write port + final SBA sample','emu_sha256':EMU_SHA}
    try:
      with (out.dut/'logs/uart.log').open('w') as uart,(out.ref/'rob.log').open('w') as rob,(out.dut/'logs/openocd.log').open('w') as dbg:
        emu=subprocess.Popen(cmd,cwd=out.root,stdout=uart,stderr=rob)
        pin_actual(emu.pid,CPU_START)
        deadline=time.monotonic()+3600
        while not listening(rb):
            if emu.poll() is not None or time.monotonic()>deadline:raise RuntimeError('CoreMark emu listener missing')
            time.sleep(.5)
        debugger=subprocess.Popen(['openocd','-f',str(cfg)],cwd=out.root,env=env,stdout=dbg,stderr=subprocess.STDOUT)
        while rpc is None:
            if emu.poll() is not None or debugger.poll() is not None or time.monotonic()>deadline:raise RuntimeError('CoreMark OpenOCD startup failed')
            try:rpc=RPC(tc,timeout=900,transcript=out.dut/'logs/rpc.jsonl')
            except ConnectionRefusedError:time.sleep(.5)
        def read(addr):return int(rpc.run(f'read_memory 0x{addr:x} 32 1 phys'),0)
        def write(addr,value):rpc.run(f'write_memory 0x{addr:x} 32 {{0x{value:x}}} phys')
        def reg(name):return int(re.search(r':\s*(0x[0-9a-fA-F]+)',rpc.run('reg '+name)).group(1),16)
        rpc.run('riscv set_mem_access sysbus')
        rpc.run(f'bp 0x{END:x} 4 hw')
        print('[CoreMark] external JTAG/SBA ready; configure '+MODE,flush=True)
        for addr in (0x20040000,0x20010000,0x20000000):write(addr,1)
        write(0x20040020,0);write(0x20000f04,1)
        write(0x20000008,FEATURES)
        actual=read(0x20000008)
        if actual&0xfb!=FEATURES:raise RuntimeError(f'optional features rejected: {actual:#x}')
        discovered=capture_discovery(rpc,out.dut)
        if discovered['attributes']['return_stack_size']!=4:raise RuntimeError('new RAS not present')
        metadata.update(mode=MODE,features_requested=FEATURES,features_readback=actual)
        write(0x20040000,3);write(0x20010000,3);write(0x20000000,0xf02007)
        metadata['rob_byte_start']=(out.ref/'rob.log').stat().st_size
        write_json(out.dut/'capture-metadata.json',metadata)
        print('[CoreMark] trace configured; resume original image from its entry',flush=True)
        rpc.run('resume 0x80000000')
        deadline=time.monotonic()+TIMEOUT
        while True:
            state=rpc.run('xs.cpu arp_poll; xs.cpu curstate')
            if state.strip()=='halted':break
            if emu.poll() is not None or time.monotonic()>deadline:raise RuntimeError('CoreMark did not reach its exit breakpoint')
            time.sleep(10)
        metadata.update(pc=reg('pc'),a0=reg('a0'))
        if metadata['pc']!=END or metadata['a0']!=0:raise RuntimeError('CoreMark exit PC/result mismatch')
        for name,addr,value in [('encoder',0x20000000,0xf00001),('funnel',0x20010000,1),('sram',0x20040000,1)]:
            write(addr,value)
            for _ in range(30):
                status=read(addr)
                if status&11==9:break
            else:raise RuntimeError(name+' did not drain')
            metadata[name]=status
        metadata.update(wp_register=read(0x20040020),dropped=read(0x20000f10),
          loss_markers=read(0x20000f18),stall_cycles=read(0x20000f20),
          packet_wait_cycles=read(0x20000f28),downstream_wait_cycles=read(0x20000f30),
          packet_accepted=read(0x20000f38),wait_status=read(0x20000f00))
        if metadata['dropped'] or metadata['loss_markers']:raise RuntimeError('CoreMark trace lost retirement')
        wp=(metadata['wp_register']&~3)//4
        start=(wp-16)%256;write(0x20040028,start*4)
        words=[read(0x20040040) for _ in range(16)]
        metadata.update(sample_start_word=start,sample_words=words,rp_after_sample=read(0x20040028),status='captured')
        if metadata['rp_after_sample']!=wp*4:raise RuntimeError('SBA sample RP mismatch')
        print('[CoreMark] full benchmark reached its success exit; trace drained, tail sampled by SBA',flush=True)
    except BaseException as exc:
        metadata.update(status='failed',error=str(exc));raise
    finally:
        write_json(out.dut/'capture-metadata.json',metadata)
        if rpc is not None:rpc.close()
        if emu is not None and emu.poll() is None:
            children=Path(f'/proc/{emu.pid}/task/{emu.pid}/children')
            if children.exists():
                for child in children.read_text().split():
                    exe=Path(f'/proc/{child}/exe')
                    if exe.exists() and exe.resolve().name=='emu':os.kill(int(child),signal.SIGINT)
            else:emu.send_signal(signal.SIGINT)
            try:emu.wait(timeout=30)
            except subprocess.TimeoutExpired:stop(emu)
        stop(debugger)
    write_json(out.dut/'trace.json',{'discovery':'discovery.bin','sources':{'0':{'hart_id':0,'raw':'../workload/coremark.bin','base':'0x80000000','xlen':64}}})

    return out.root


def main(argv=None):
    global EMU, IMAGE, OUTPUT, MODE, FEATURES, CPU_START, RECORDER, PROVENANCE, EMU_SHA, TIMEOUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emu', required=True, type=Path)
    parser.add_argument('--image', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--features', required=True, type=lambda value: int(value, 0))
    parser.add_argument('--recorder', required=True, type=Path, help='validated FST observer wrapper for this exact binary')
    parser.add_argument('--provenance', required=True, type=Path)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--cpu-start', required=True, type=int)
    parser.add_argument('--timeout', type=int, default=7200)
    args = parser.parse_args(argv)
    EMU, IMAGE, OUTPUT = args.emu.resolve(), args.image.resolve(), args.output.resolve()
    FEATURES, CPU_START = args.features, args.cpu_start
    MODE = mode_name(FEATURES)
    RECORDER, TIMEOUT = args.recorder.resolve(), args.timeout
    PROVENANCE = json.loads(args.provenance.read_text())
    EMU_SHA = hashlib.sha256(EMU.read_bytes()).hexdigest()
    if PROVENANCE.get('status') != 'validated' or PROVENANCE.get('emu_sha256') != EMU_SHA:
        raise ValueError('recorder not validated for this exact emu SHA256')
    for callback in PROVENANCE['change_callbacks']:
        source = Path(callback.get('archived_generated_source', callback['generated_source']))
        if hashlib.sha256(source.read_bytes()).hexdigest() != callback['sha256']:
            raise ValueError('archived recorder callback fingerprint changed')
    print('[CoreMark] start '+MODE+f' CSR={FEATURES:#x}', flush=True)
    directory = trace()
    verify(directory, args.reference.resolve())


if __name__ == '__main__':
    main()
