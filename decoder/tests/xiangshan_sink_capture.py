"""External pin/SBA capture for the real XiangShan ATB, PIB and SMEM profiles."""
from bisect import bisect_right
import json
from pathlib import Path
import re

from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json, write_jsonl
from decoder.encapsulation import DecodeError
from decoder.capture_layout import CaptureLayout
from .check_xiangshan_sba import COMMIT, match_samples
from .run_xiangshan_sba import capture_discovery
from .xiangshan_sba_scenarios import reference


ATB = re.compile(r'\[OT-ATB-DATA\]\s+cycle=\s*(\d+)\s+id=\s*(\d+)\s+'
                 r'bytes=\s*(\d+)\s+data=0x([0-9a-fA-F]+)')
PINS = re.compile(r'\[OT-PIB-PINS\]\s+cycle=\s*(\d+)\s+clock=\s*([01])\s+'
                  r'control=\s*([01])\s+data=0x([0-9a-fA-F]+)')


def atb_bytes(text, bridge_id=42):
    data = bytearray()
    rows = []
    for match in ATB.finditer(text):
        cycle, identity, count = map(int, match.group(1, 2, 3))
        word = int(match.group(4), 16)
        if identity != bridge_id or not 1 <= count <= 4 or word >= 1 << 32:
            raise DecodeError('invalid ATB ID/ATBYTES/data in actual accepted transfer')
        if rows and cycle <= rows[-1]['cycle']:
            raise DecodeError('ATB accepted transfers are not ordered')
        rows.append({'cycle': cycle, 'atid': identity, 'valid_bytes': count, 'data': hex(word)})
        data.extend(word.to_bytes(4, 'little')[:count])
    if not rows:
        raise DecodeError('no actual ATB handshakes recorded')
    return bytes(data), rows


def pib_bytes(text, mode, divider):
    rows = [dict(cycle=int(m[1]), clock=int(m[2]), control=int(m[3]), data=int(m[4], 16))
            for m in PINS.finditer(text)]
    if not rows or any(row['control'] for row in rows):
        raise DecodeError('missing PIB transitions or TRC_CTL is not zero')
    if any(a['cycle'] >= b['cycle'] for a, b in zip(rows, rows[1:])):
        raise DecodeError('PIB transitions are not ordered')
    data = bytearray()
    if 8 <= mode <= 12:
        width = 1 << (mode - 8)
        clock = pending = count = 0
        for row in rows:
            if row['clock'] != clock:
                pending |= (row['data'] & ((1 << width) - 1)) << count
                count += width
                while count >= 8:
                    data.append(pending & 255)
                    pending >>= 8
                    count -= 8
            clock = row['clock']
        # Idle clock edges after a complete packet may leave zero partial bits.
        if pending:
            raise DecodeError('PIB parallel stream ended in nonzero partial byte')
        return bytes(data), rows
    if mode not in (4, 5) or any(row['clock'] for row in rows):
        raise DecodeError('unsupported PIB serial mode or unexpected trace clock')
    times = [row['cycle'] for row in rows]
    period = divider + 1

    def value(twice_cycle):
        index = bisect_right(times, twice_cycle // 2) - 1
        return (rows[index]['data'] & 1) if index >= 0 else (1 if mode == 5 else 0)

    previous = 1 if mode == 5 else 0
    consumed_until = -1
    for row in rows:
        bit = row['data'] & 1
        start = row['cycle']
        edge = previous == (1 if mode == 5 else 0) and bit != previous
        previous = bit
        if not edge or start < consumed_until:
            continue
        if mode == 5:
            symbols = [value(2 * start + (2 * i + 1) * period) for i in range(10)]
            if symbols[0] != 0 or symbols[9] != 1:
                raise DecodeError('PIB UART start/stop bit differs')
            data.append(sum(symbols[i + 1] << i for i in range(8)))
            consumed_until = start + 10 * period
        else:
            if [value(2 * start + period), value(2 * start + 3 * period)] != [1, 0]:
                raise DecodeError('PIB Manchester start delimiter differs')
            bits = []
            for pair in range(34 * 8 + 1):
                first = value(2 * start + (4 * pair + 5) * period)
                second = value(2 * start + (4 * pair + 7) * period)
                if first == second:
                    if first or not bits or len(bits) % 8:
                        raise DecodeError('invalid PIB Manchester stop delimiter or byte length')
                    consumed_until = start + (4 + 2 * len(bits)) * period
                    break
                bits.append(first)
            else:
                raise DecodeError('PIB Manchester packet exceeds implemented maximum')
            data.extend(sum(bits[offset + i] << i for i in range(8)) for offset in range(0, len(bits), 8))
    if not data:
        raise DecodeError('no complete PIB serial frames recorded')
    return bytes(data), rows


def collect(rpc, directory, symbols, scenario, args):
    layout = CaptureLayout(directory)
    sink = args.sink
    base = {'atb': 0x20020000, 'pib': 0x20030000, 'smem': 0x20040000}[sink]

    def read(address):
        return int(rpc.run(f'read_memory 0x{address:x} 32 1 phys'), 0)

    def write(address, value):
        rpc.run(f'write_memory 0x{address:x} 32 {{0x{value:x}}} phys')

    def register(name):
        match = re.search(r':\s*(0x[0-9a-fA-F]+)', rpc.run('reg ' + name))
        if not match:
            raise RuntimeError('missing architectural register: ' + name)
        return int(match.group(1), 16)

    print(f'[XS-{sink}][1/4] external JTAG/SBA configures actual sink, Funnel and encoder', flush=True)
    rpc.run('riscv set_mem_access sysbus')
    rpc.run(f"bp 0x{symbols['trace_done']:x} 2 hw")
    for address in (base, 0x20010000, 0x20000000):
        write(address, 1)
        if not read(address) & 1:
            raise RuntimeError('component Active did not release')
    capture_discovery(rpc, layout.dut)
    write(0x20000f04, 1)
    write(0x20000008, args.features)
    features = read(0x20000008)
    if features & 0xfb != args.features:
        raise RuntimeError('encoder rejected requested features')
    configuration = 1
    metadata = {'sink': sink, 'scenario': scenario, 'memory_access': 'sysbus',
                'features_requested': args.features, 'features_readback': features,
                'sink_implementation': hex(read(base + 4))}
    if sink == 'atb':
        configuration |= 42 << 8
        metadata['bridge_atid'] = 42
    elif sink == 'pib':
        configuration |= (args.pib_mode << 4) | (args.pib_divider << 16) | (int(args.pib_center) << 8)
        metadata.update(pib_mode=args.pib_mode, pib_divider=args.pib_divider, pib_center=args.pib_center)
    else:
        memory = 0x83000000
        write(base + 0x10, memory); write(base + 0x14, 0)
        write(base + 0x18, memory + 0xfffc); write(base + 0x1c, 0)
        write(base + 0x20, memory); write(base + 0x24, 0)
        metadata['buffer_base'] = hex(memory)
    write(base, configuration)
    actual = read(base)
    if sink == 'pib' and (actual & 0xffff7ff0) != (configuration & 0xffff7ff0):
        raise RuntimeError('PIB mode/divider/center configuration rejected')
    if scenario != 'pressure':
        write(base, configuration | 2)
    write(0x20010000, 3)
    # Keep periodic resynchronization out of this short control experiment: a
    # second Format30 must come from the real external ATB SYNCREQ.
    write(0x20000000, 0xf02007)
    print(f'[XS-{sink}][2/4] resume pure workload; pressure holds selected sink disabled until actual stall', flush=True)
    rpc.run(f"resume 0x{symbols['trace_begin']:x}")
    metadata['pressure_stalls_before_release'] = 0
    if scenario == 'pressure':
        for _ in range(20):
            metadata['pressure_stalls_before_release'] = read(0x20000f20)
            if metadata['pressure_stalls_before_release']:
                break
        else:
            raise RuntimeError('selected sink did not propagate actual core backpressure')
        if read(0x20000f10) or read(0x20000f18):
            raise RuntimeError('encoder lost retirement while the selected sink was disabled')
        write(base, configuration | 2)
    rpc.run('wait_halt 120000')
    metadata.update(pc=register('pc'), a0=register('a0'))
    if scenario == 'exception':
        metadata['trap_vector_readback'] = register('mtvec')
        if metadata['trap_vector_readback'] != symbols['trace_handler']:
            raise RuntimeError('workload mtvec differs from the supplied decoder vector')
    expected_a0, _ = reference(symbols, scenario)
    if metadata['pc'] != symbols['trace_done'] or metadata['a0'] != expected_a0:
        raise RuntimeError('workload did not reach the expected exit/a0')
    print(f'[XS-{sink}][3/4] stop encoder→Funnel→selected sink, checking Disabled/Empty', flush=True)
    for name, address, off in (('encoder', 0x20000000, 0xf00001), ('funnel', 0x20010000, 1), ('sink_control', base, configuration)):
        write(address, off)
        for _ in range(30):
            metadata[name] = read(address)
            if metadata[name] & 11 == 9:
                break
        else:
            raise RuntimeError(name + ' did not drain')
    for name, offset in (('dropped', 0xf10), ('loss_markers', 0xf18), ('stall_cycles', 0xf20)):
        metadata[name] = read(0x20000000 + offset)
    if sink == 'smem':
        status = read(base + 0xf00)
        pointer = (read(base + 0x24) << 32) | read(base + 0x20)
        if status & 0x107 or pointer & 1:
            raise RuntimeError('SMEM fault/busy or unexpected wrap')
        pointer &= ~3
        length = pointer - memory
        if not 0 < length <= 0x10000 or length % 4:
            raise RuntimeError('SMEM committed byte count is invalid')
        words = [int(value, 0) for value in rpc.run(f'read_memory 0x{memory:x} 32 {length // 4} phys').split()]
        if len(words) != length // 4:
            raise RuntimeError('SBA memory read length differs from SMEM committed data')
        (layout.dut / 'trace.bin').write_bytes(b''.join(word.to_bytes(4, 'little') for word in words))
        write_json(layout.dut / 'smem-words.json', [hex(word) for word in words])
        metadata.update(smem_status=status, committed_pointer=hex(pointer), stored_bytes=length,
                        capture_method='actual system DRAM, read entirely through external physical SBA')
    else:
        metadata['capture_method'] = 'external ATB accepted transfers' if sink == 'atb' else 'external digital PIB pins'
    config = {'discovery': 'discovery.bin', 'sources': {'0': {'hart_id': 0, 'elf': '../workload/workload.elf'}}}
    if scenario == 'exception':
        config['sources']['0']['trap_vectors'] = {'3': hex(metadata['trap_vector_readback'])}
    write_json(layout.dut / 'trace.json', config)
    write_json(layout.dut / 'metadata.json', metadata)
    print(f'[XS-{sink}][4/4] capture metadata saved; pin extraction and full decoder/ROB checks follow emu shutdown', flush=True)


def check(directory):
    layout = CaptureLayout(directory)
    result = {'status': 'failed', 'stage': 'capture', 'error': None}
    observer = failure = None
    try:
        m = json.loads((layout.dut / 'metadata.json').read_text())
        if m['memory_access'] != 'sysbus' or m['dropped'] or m['loss_markers']:
            raise DecodeError('non-SBA control or encoder loss in selected sink capture')
        if any(m[name] & 11 != 9 for name in ('encoder', 'funnel', 'sink_control')):
            raise DecodeError('selected trace path did not drain')
        if m['scenario'] == 'pressure' and not (m['stall_cycles'] and m['pressure_stalls_before_release']):
            raise DecodeError('selected sink capture lacks real pressure evidence')
        if m['sink'] in ('atb', 'pib'):
            text = (layout.dut / 'logs/uart.log').read_text(errors='replace')
            if m['sink'] == 'atb':
                data, rows = atb_bytes(text, m['bridge_atid'])
                requests = text.count('[OT-ATB-AF-REQ]')
                acknowledgements = text.count('[OT-ATB-AF-ACK]')
                if requests != 1 or acknowledgements != 1 or '[OT-ATB-SYNC]' not in text:
                    raise DecodeError('external ATB flush/sync did not complete')
                frontier = re.search(r'\[OT-ATB-AF-REQ\]\s+cycle=\s*(\d+)\s+oldTransfers=\s*(\d+)', text)
                if frontier is None or int(frontier.group(2)) < 12:
                    raise DecodeError('ATB flush was requested before actual instruction trace reached the pins')
                result.update(flush_requests=requests, flush_acknowledgements=acknowledgements, sync_requests=1)
            else:
                data, rows = pib_bytes(text, m['pib_mode'], m['pib_divider'])
            (layout.dut / 'trace.bin').write_bytes(data)
            write_jsonl(layout.dut / 'transport-samples.jsonl', rows)
        data = (layout.dut / 'trace.bin').read_bytes()
        symbols = json.loads((layout.workload / 'symbols.json').read_text())
        start, done = number(symbols['trace_begin']), number(symbols['trace_done'])
        expected_a0, expected = reference(symbols, m['scenario'])
        if number(m['pc']) != done or m['a0'] != expected_a0:
            raise DecodeError('selected sink workload exit differs')
        samples = []
        previous = None
        for match in COMMIT.finditer((layout.ref / 'rob.log').read_text(errors='replace')):
            hart, cycle, slot = map(int, match.group(1, 2, 3))
            pc = int(match.group(4), 16)
            if hart != 0:
                raise DecodeError('unexpected hart in single-core ROB log')
            if previous is not None and (cycle, slot) <= previous:
                raise DecodeError('ROB samples are not ordered')
            previous = cycle, slot
            if start <= pc < done:
                samples.append({'cycle': cycle, 'slot': slot, 'pc': pc})
        write_jsonl(layout.ref / 'commit-pc-workload.jsonl', samples)
        write_json(layout.diff / 'expected-pcs.json', {'expected_pcs': expected})
        result['stage'] = 'decode'
        observer = DecodeArtifacts(directory, overwrite=True)
        observer.snapshot(layout.dut / 'trace.bin', layout.dut / 'trace.json')
        decoder = load_config(layout.dut / 'trace.json', acquire=m['sink'] in ('atb', 'pib'), observer=observer)
        events = []
        for offset in range(0, len(data), 3):
            events.extend(decoder.feed(data[offset:offset + 3]))
        decoder.finish()
        if m['sink'] == 'atb':
            packets = [json.loads(line)['fields'] for line in (layout.dut / 'packets.jsonl').read_text().splitlines()]
            synchronizations = sum((p['format'], p.get('subformat')) == (3, 0) for p in packets)
            if synchronizations < 2:
                raise DecodeError('external ATB SYNCREQ did not produce an additional instruction synchronization')
            result['instruction_synchronizations'] = synchronizations
        pcs = [event['pc'] for event in events if event['kind'] == 'instruction']
        if pcs != expected:
            first = next((i for i, pair in enumerate(zip(pcs, expected)) if pair[0] != pair[1]), min(len(pcs), len(expected)))
            raise DecodeError(f'selected sink retirement differs at index {first}, lengths {len(pcs)}/{len(expected)}')
        if not decoder.decoders[0].start_of_trace or any(e['kind'] == 'support' and e['qual_status'] == 2 for e in events):
            raise DecodeError('selected sink trace lost data or did not end')
        if m['scenario'] == 'exception':
            traps = [e for e in events if e['kind'] == 'trap']
            epcs = [e['pc'] for e in events if e['kind'] == 'exception_pc']
            if [(e['ecause'], e['interrupt']) for e in traps[:2]] != [(11, False), (2, False)]:
                raise DecodeError('selected sink lost the ordinary ecall/illegal trap causes')
            if any(e['tval'] != 0 for e in traps[:2]):
                raise DecodeError('selected sink changed tval or retained an omitted-address gap')
            if epcs[:2] != [start + 16, start + 24]:
                raise DecodeError('selected sink lost the ordinary faulting EPCs')
            if number(m['trap_vector_readback']) != number(symbols['trace_handler']):
                raise DecodeError('selected sink actual mtvec differs from the handler configuration')
        indices = match_samples(pcs, [row['pc'] for row in samples])
        result.update(status='passed', stage='complete', sink=m['sink'], stored_capture_bytes=len(data),
                      decoded_instructions=len(pcs), decoded_pcs=pcs, rob_samples=len(samples), rob_decoder_indices=indices)
        print(f'[XS-{m["sink"]}][PC] {len(data)} captured bytes, {len(pcs)} complete retirement, '
              f'{len(samples)} independent ROB samples passed', flush=True)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        failure = exc
        result['error'] = str(exc)
        raise
    finally:
        if observer is not None:
            observer.finish(failure)
        write_json(layout.diff / 'comparison.json', result)
    return result
