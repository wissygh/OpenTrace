"""Check the complete CoreMark SRAM write stream against SBA and ROB evidence."""
import argparse
from contextlib import redirect_stdout
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter

from decoder.__main__ import load_config, number
from decoder.artifacts import DecodeArtifacts, write_json, write_jsonl
from decoder.tests.check_xiangshan_sba import COMMIT, match_samples
from decoder.tests.run_xiangshan_sba import Tee


RESOURCES = Path(__file__).with_name('resources')
PROJECT = Path(__file__).resolve().parents[2]
FEATURE_MODES = {'baseline': 0, 'cache': 32, 'prediction': 16, 'combined': 48}


def check_optional_packets(packets, features):
    """Check the on-wire feature declaration, and count actual Format0 uses."""
    expected = (features & 0x30) >> 1
    coverage = Counter()
    supports = 0
    for packet in packets:
        fields = packet['fields']
        fmt = fields['format']
        if fmt == 3 and fields['subformat'] == 3:
            supports += 1
            if fields['options'] & 24 != expected:
                raise ValueError('support options disagree with the requested/read-back encoder features')
        if fmt == 0:
            subformat = fields['subformat']
            if subformat == 1:
                if not features & 32:
                    raise ValueError('cache index emitted while jump cache is disabled')
                coverage['cache_index_packets'] += 1
            else:
                if not features & 16:
                    raise ValueError('prediction count emitted while branch prediction is disabled')
                coverage['prediction_count_packets'] += 1
                coverage['correctly_predicted_branches'] += fields['branch_count']
                coverage['prediction_failed_packets'] += bool(fields.get('prediction_failed'))
                coverage['prediction_failed_address_packets'] += bool(fields.get('prediction_failed')) and 'address' in fields
                coverage['prediction_no_address_packets'] += 'address' not in fields
    if not supports:
        raise ValueError('missing feature support declaration')
    return {'support_packets': supports, 'declared_options': expected,
            **{k: coverage[k] for k in ('cache_index_packets', 'prediction_count_packets',
                'correctly_predicted_branches', 'prediction_failed_packets',
                'prediction_failed_address_packets', 'prediction_no_address_packets')}}


def check_benchmark_output(text):
    """The checked ready-to-run 2-iteration image uses these validation seeds."""
    expected = {'seedcrc': 0xe9f5, 'crclist': 0xe714, 'crcmatrix': 0x1fd7,
                'crcstate': 0x8e3a, 'crcfinal': 0x72be}
    iterations = re.findall(r'Iterations\s*:\s*(\d+)', text)
    if not iterations or int(iterations[-1]) != 2 or 'Errors detected' in text:
        raise ValueError('CoreMark iteration count or validation output differs')
    actual = {}
    for name, value in expected.items():
        values = re.findall(r'\b' + name + r'\s*:\s*0x([0-9a-fA-F]+)', text)
        if not values or int(values[-1], 16) != value:
            raise ValueError('CoreMark validation CRC differs: ' + name)
        actual[name] = hex(value)
    return actual


def fst_include():
    candidates = [os.environ.get('VERILATOR_ROOT', ''), '/usr/local/share/verilator']
    if shutil.which('verilator'):
        version = subprocess.check_output(['verilator', '-V'], text=True)
        candidates += re.findall(r'^\s*VERILATOR_ROOT\s*=\s*(\S+)', version, re.MULTILINE)
    for root in candidates:
        path = Path(root) / 'include/gtkwave'
        if (path / 'fstapi.c').is_file():
            return path
    raise RuntimeError('Verilator gtkwave/fstapi.c is required; set VERILATOR_ROOT')


def build_reader(directory, log):
    directory = Path(directory)
    include = fst_include()
    for name in ('fstapi', 'fastlz', 'lz4'):
        subprocess.run(['gcc', '-O2', '-I' + str(include), '-c', str(include / (name + '.c')),
                        '-o', str(directory / (name + '.o'))], stdout=log, stderr=subprocess.STDOUT, check=True)
    target = directory / 'read-sram'
    subprocess.run(['g++', '-O2', '-std=c++17', '-I' + str(include), str(RESOURCES / 'read_sram_fst.cpp'),
        *[str(directory / (name + '.o')) for name in ('fstapi', 'fastlz', 'lz4')], '-lz', '-o', str(target)],
        stdout=log, stderr=subprocess.STDOUT, check=True)
    return target


def check_storage(data, words, metadata):
    if not data or len(data) % 4 or len(words) * 4 != len(data):
        raise ValueError('SRAM write count/byte alignment mismatch')
    for i, row in enumerate(words):
        if row['word_index'] != i or row['word_address'] != i % 256:
            raise ValueError('SRAM word addresses are not consecutive across wrap')
        if int.from_bytes(data[i * 4:i * 4 + 4], 'little') != row['data']:
            raise ValueError('SRAM transaction data differs from capture bytes')
        if i and row['fst_time'] <= words[i - 1]['fst_time']:
            raise ValueError('SRAM write times are not strictly ordered')
    wp = metadata['wp_register']
    if (wp & ~3) != (len(words) % 256) * 4 or bool(wp & 1) != (len(words) >= 256):
        raise ValueError('SBA final WP/Wrap disagrees with all captured SRAM writes')
    sample = metadata['sample_words']
    if len(sample) != 16 or len(words) < len(sample):
        raise ValueError('missing complete 16-word SBA readback sample')
    if metadata['sample_start_word'] != (len(words) - len(sample)) % 256:
        raise ValueError('SBA sample did not begin at the captured tail address')
    expected = [int.from_bytes(data[i:i + 4], 'little') for i in range(len(data) - 64, len(data), 4)]
    if sample != expected or metadata['rp_after_sample'] != (wp & ~3):
        raise ValueError('physical SRAM write stream differs from final SBA readback')


def check(directory):
    root = Path(directory)
    dut, ref, diff = root / 'dut', root / 'ref', root / 'diff'
    (dut / 'logs').mkdir(exist_ok=True)
    (diff / 'logs').mkdir(exist_ok=True)
    result = {'status': 'failed', 'stage': 'inputs', 'error': None,
              'reference_kind': 'complete physical SRAM writes, final SBA tail, independent compressed ROB samples',
              'capture_method': 'physical SRAM write port in full-clock FST; external JTAG/SBA control and tail readback'}
    observer = None
    failure = None
    try:
        metadata = json.loads((dut / 'capture-metadata.json').read_text())
        if metadata['status'] != 'captured' or metadata['a0'] != 0 or number(metadata['pc']) != 0x80001ca0:
            raise ValueError('CoreMark did not reach the successful exit breakpoint')
        if metadata['dropped'] or metadata['loss_markers']:
            raise ValueError('CoreMark encoder dropped retirement')
        result['benchmark_crc'] = check_benchmark_output((dut / 'logs/uart.log').read_text())
        if any(metadata[name] & 11 != 9 for name in ('encoder', 'funnel', 'sram')):
            raise ValueError('trace components were not disabled and empty')
        mode = metadata.get('mode', 'baseline')
        if mode not in FEATURE_MODES:
            raise ValueError('unknown optional CoreMark mode')
        features = metadata.get('features_requested', 0)
        if features != FEATURE_MODES[mode] or metadata.get('features_readback', 0) & 0x30 != features:
            raise ValueError('optional encoder CSR readback disagrees with the selected mode')
        result.update(mode=mode, encoder_features=features)
        original_inputs = root / 'workload/inputs.json'
        benchmark = root / 'workload/benchmark.json'
        if not benchmark.exists():
            shutil.copyfile(original_inputs, benchmark)
        provenance = json.loads(benchmark.read_text())
        image = root / 'workload/coremark.bin'
        if hashlib.sha256(image.read_bytes()).hexdigest() != provenance['sha256']:
            raise ValueError('CoreMark image differs from its ready-to-run fingerprint')
        result['stage'] = 'physical-sram-writes'
        temporary = PROJECT / 'delp/tmp'
        temporary.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary, prefix='coremark-fst-') as tmp, \
                (dut / 'logs/fst-reader.log').open('w') as log:
            tool = build_reader(tmp, log)
            subprocess.run([str(tool), str(dut / 'trace.fst'), str(dut / 'trace.bin'),
                str(dut / 'sram-writes.tsv'), str(dut / 'sram-signals.tsv')], stdout=log, stderr=subprocess.STDOUT, check=True)
        rows = []
        for line in (dut / 'sram-writes.tsv').read_text().splitlines()[1:]:
            index, when, address, value = line.split('\t')
            rows.append({'word_index': int(index), 'fst_time': int(when), 'word_address': int(address), 'data': int(value, 0)})
        data = (dut / 'trace.bin').read_bytes()
        check_storage(data, rows, metadata)
        print(f'[CoreMark][SRAM] {len(rows)}次真实word写入/{len(data)}bytes；全部地址连续，末段64bytes与SBA一致', flush=True)
        result.update(bytes=len(data), sram_word_writes=len(rows), sram_wraps=len(rows) // 256,
                      sba_verified_tail_bytes=64, dropped=metadata['dropped'], stall_cycles=metadata['stall_cycles'])
        result['stage'] = 'decode'
        observer = DecodeArtifacts(root, overwrite=True)
        observer.snapshot(dut / 'trace.bin', dut / 'trace.json')
        decoder = load_config(dut / 'trace.json', observer=observer)
        events = []
        for offset in range(0, len(data), 4096):
            events.extend(decoder.feed(data[offset:offset + 4096]))
        decoder.finish()
        if not decoder.decoders[0].start_of_trace:
            raise ValueError('CoreMark trace execution segment did not terminate')
        if any(e['kind'] == 'support' and e['qual_status'] == 2 for e in events):
            raise ValueError('CoreMark capture contains trace loss')
        # DecodeArtifacts flushes packet records before execution, so a bad
        # optional packet remains inspectable even when reconstruction fails.
        packets = [json.loads(line) for line in (dut / 'packets.jsonl').read_text().splitlines()]
        result['optional_coverage'] = check_optional_packets(packets, features)
        instructions = [e for e in events if e['kind'] == 'instruction']
        pcs = [e['pc'] for e in instructions]
        result.update(decoded_instructions=len(pcs), decoded_pcs=pcs)
        if not pcs or pcs[0] != 0x80000000 or 0x80001ca0 in pcs:
            raise ValueError('CoreMark start or non-retired exit boundary differs')
        result['stage'] = 'independent-rob'
        raw = (ref / 'rob.log').read_bytes()[metadata['rob_byte_start']:].decode(errors='replace')
        samples = []
        for m in COMMIT.finditer(raw):
            hart, cycle, slot = map(int, m.group(1, 2, 3))
            pc = int(m.group(4), 16)
            if hart == 0 and 0x80000000 <= pc < 0x80000000 + image.stat().st_size:
                samples.append({'index': len(samples), 'hart_id': hart, 'cycle': cycle, 'slot': slot, 'pc': pc})
        if any((b['cycle'], b['slot']) <= (a['cycle'], a['slot']) for a, b in zip(samples, samples[1:])):
            raise ValueError('independent ROB samples are not ordered')
        write_jsonl(ref / 'commit-pc-workload.jsonl', samples)
        matched = match_samples(pcs, [s['pc'] for s in samples])
        with (diff / 'pc-matches.tsv').open('w') as output:
            output.write('sample_index\tcycle\tslot\trob_pc\tdecoder_index\tdecoded_pc\n')
            for row, position in zip(samples, matched):
                output.write(f"{row['index']}\t{row['cycle']}\t{row['slot']}\t{row['pc']:#x}\t{position}\t{pcs[position]:#x}\n")
        result.update(status='passed', stage='complete', rob_samples=len(samples),
                      rob_decoder_indices=matched, coremark_iterations=2)
        print(f'[CoreMark][PC对拍] {len(pcs)}条完整重建、{len(samples)}条独立ROB有序一致；无loss、完整停止', flush=True)
        return result
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as exc:
        failure = exc
        result['error'] = str(exc)
        raise
    finally:
        if observer is not None:
            observer.finish(failure)
        write_json(diff / 'comparison.json', result)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args(argv)
    (args.directory / 'diff/logs').mkdir(parents=True, exist_ok=True)
    with (args.directory / 'diff/logs/check.log').open('w') as log, redirect_stdout(Tee(sys.stdout, log)):
        check(args.directory)


if __name__ == '__main__':
    main()
