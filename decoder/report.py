"""Human-readable views of decoder evidence; JSONL remains the lossless input."""
import argparse
import json
from pathlib import Path
import textwrap
from .disassembly import disassemble, fallback, key


STREAMS = ('framing', 'packets', 'events', 'instructions')
TEXT_REPORT_FILES = tuple(name + '.txt' for name in STREAMS)
STATUS = {0: 'no_change / 配置声明', 1: 'ended_rep / 已报告末指令后结束',
          2: 'trace_lost / 数据丢失', 3: 'ended_ntr / 末指令待推断'}
PRIVILEGE = {0: 'U', 1: 'S/HS', 3: 'M', 4: 'D', 5: 'VU', 6: 'VS'}
OPTIONS = ('sequential_jump', 'implicit_return', 'implicit_exception', 'branch_prediction',
           'jump_target_cache', 'full_address')
OPTION_NAMES = ('sequential-jump', 'implicit-return', 'implicit-exception', 'branch-prediction',
                'jump-cache', 'full-address')


def integer(value):
    return int(value, 0) if isinstance(value, str) else int(value)


def hex_value(value):
    return '无' if value is None else hex(integer(value))


def packet_type(fields):
    fmt, sf = fields.get('format'), fields.get('subformat')
    if fmt == 0 and sf == 0:
        variant = ('无地址，含隐式失败分支' if 'address' not in fields else
                   '带地址与预测失败' if fields.get('prediction_failed') else '带地址')
        return 'Format00', '分支预测计数 / ' + variant
    if fmt == 0 and sf == 1:
        return 'Format01', '跳转目标缓存索引' + (' + 分支图' if fields.get('branches') else '')
    if fmt == 1:
        return 'Format1', '分支图 + 地址' if fields.get('branches') else '完整31分支图，无地址'
    if fmt == 2:
        return 'Format2', '地址'
    if fmt == 3:
        if sf == 0:
            return 'Format30', '同步 / 完整地址'
        if sf == 1:
            return 'Format31', ('中断' if fields.get('interrupt') else '异常') + (
                ' / handler入口' if fields.get('thaddr') else
                ' / 中断位置，地址不表示退休' if fields.get('interrupt') else ' / trap位置，非退休EPC')
        if sf == 2:
            return 'Format32', 'context / 非精确上下文'
        if sf == 3:
            return 'Format33', 'support / ' + STATUS.get(fields.get('qual_status'), '未知状态')
    return '未解析', '格式未解析或未支持'


def _value(name, value):
    if value is None:
        return '无'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, (dict, list, tuple)):
        return json.dumps(value, ensure_ascii=False)
    if name == 'privilege':
        return f'{value} ({PRIVILEGE.get(integer(value), "reserved")})'
    if name == 'qual_status':
        return f'{value} ({STATUS.get(integer(value), "未知")})'
    if name in ('pc', 'reported_pc', 'reference_pc', 'epc', 'instruction', 'tval', 'address',
                'context', 'index', 'offset', 'branch_map', 'options', 'options_before', 'decoder_pc_before'):
        return hex_value(value)
    return str(value)


def report_header(stream):
    common = '完整原始字段见同名JSONL；offset/PC使用十六进制。\n'
    descriptions = {
        'framing': '封装边界由header的payload长度决定；NULL为填充或同步字节，SRAM word不定义包边界。',
        'packets': 'address为半字编码；reported_pc是恢复后的地址。wire bit布局、profile与原始payload见packets.jsonl。',
        'events': '只列非退休事件；完整退休顺序见instructions.txt，原始events.jsonl仍包含全部事件。',
        'instructions': '每行一条退休指令：index packet srcID/hart context PC instruction disassembly。反汇编仅作注释。',
    }
    return common + descriptions[stream] + '\n\n'


def render_record(stream, row, index, assembly=None):
    """Compact views; retain raw JSONL as the complete evidence, without guessing PCs."""
    source = f'srcID={row.get("source_id", "无")} hart={row.get("hart_id", "无")}'
    if stream == 'packets':
        fields = row.get('fields', {})
        kind, meaning = packet_type(fields)
        lines = [f'Packet #{row.get("packet_index", index):04d} | {kind} | {meaning}',
                 f'  {source} offset={hex_value(row.get("offset"))} bytes={row.get("byte_length", "无")}']
        if row.get('reported_pc') is not None:
            role = 'reported_pc'
            if kind == 'Format31':
                role = ('handler_pc' if fields.get('thaddr') else 'interrupt_pc（不代表退休）'
                        if fields.get('interrupt') else 'exception_pc（不代表退休）')
            lines.append(f'  {role}: {hex_value(row["reported_pc"])}')
        if 'address_delta_hex' in row:
            lines.append(f'  reference_pc={hex_value(row.get("reference_pc"))} delta={row["address_delta_hex"]}')
        values = ' '.join(f'{name}={_value(name, value)}' for name, value in sorted(fields.items())
                          if name not in ('format', 'subformat'))
        lines.extend(textwrap.wrap('  fields: ' + values, width=140, subsequent_indent='    ',
                                   break_long_words=False, break_on_hyphens=False))
        if 'branch_map' in fields and (fields.get('branches') or fields.get('format') == 1):
            count = fields.get('branches', 0) or (31 if fields.get('format') == 1 else 0)
            bits = integer(fields['branch_map'])
            outcomes = ' '.join('N' if bits & (1 << i) else 'T' for i in range(count))
            lines.append(f'  branch_outcomes: {outcomes}')
        if 'options' in fields:
            enabled = ', '.join(name for i, name in enumerate(OPTION_NAMES)
                                if integer(fields['options']) & (1 << i))
            lines.append('  enabled_options: ' + (enabled or '无'))
        if 'raw_hex' in row:
            lines.append('  raw: ' + row['raw_hex'])
        if 'error' in row:
            lines.append('  error: ' + str(row['error']))
        return '\n'.join(lines) + '\n\n'
    if stream == 'framing':
        label = 'NULL' if row.get('null') else f'packet_index: {row.get("packet_index", "无")} srcID={row.get("source_id", "无")}'
        stamp = '' if row.get('timestamp') is None else f' timestamp={row["timestamp"]}'
        return f'#{index:04d} offset={hex_value(row.get("offset"))} bytes={row.get("byte_length", "无")} {label}{stamp} raw={row.get("raw_hex", "")}\n'
    kind = row.get('kind', '未知事件')
    if stream == 'events' and kind == 'instruction':
        return ''
    packet = row.get('packet_index', '无')
    if stream == 'instructions':
        bits, size = integer(row['instruction']), integer(row['size'])
        assembly = assembly if assembly is not None else fallback(key(row), '反汇编待完成')
        context = hex_value(row.get('context'))
        return (f'{row.get("index", index):07d} packet={packet} {source} context={context} '
                f'{hex_value(row.get("pc"))} 0x{bits:0{size * 2}x} {assembly}\n')
    names = {'exception_pc': '异常位置 / 不退休', 'trap': 'trap事件',
             'context': '上下文变化', 'support': '配置/生命周期', 'sync': '同步'}
    extra = ' '.join(f'{name}={_value(name, value)}' for name, value in sorted(row.items())
                     if name not in ('kind', 'packet_index', 'source_id', 'hart_id') and value is not None)
    return f'#{index:04d} {kind} / {names.get(kind, kind)} packet={packet} {source} {extra}\n'


def instruction_xlens(dut):
    """Recover XLEN for old records; source/context may select different images."""
    config = dut / 'trace.json'
    if not config.exists():
        return {}
    result = {}
    for source, description in json.loads(config.read_text()).get('sources', {}).items():
        for context, image in [(None, description), *description.get('contexts', {}).items()]:
            xlen = image.get('xlen', 64)
            if 'elf' in image:
                with (dut / image['elf']).open('rb') as binary:
                    ident = binary.read(5)
                xlen = 32 if ident == b'\x7fELF\x01' else 64
            result[integer(source), None if context is None else integer(context)] = xlen
    return result


def render_instructions(dut, error=None):
    source = dut / 'instructions.jsonl'
    xlens = instruction_xlens(dut)

    def instruction_key(row):
        source = integer(row.get('source_id', 0))
        context = row.get('context')
        context = None if context is None else integer(context)
        return key(row, xlens.get((source, context), xlens.get((source, None), 64)))

    with source.open() as data:
        annotations = disassemble(instruction_key(json.loads(line)) for line in data if line.strip())
    with source.open() as data, (dut / 'instructions.txt').open('w') as output:
        output.write(report_header('instructions'))
        for index, line in enumerate(data):
            if line.strip():
                row = json.loads(line)
                output.write(render_record('instructions', row, index, annotations[instruction_key(row)]))
        output.write(report_end(error))


def report_end(error=None):
    return '解码状态: ' + ('失败 / ' + str(error) if error else '完成') + '\n'


def render_directory(directory):
    """Regenerate views of existing JSONL without changing capture or records."""
    root = Path(directory)
    dut = root / 'dut' if (root / 'dut').is_dir() else root
    result = dut / 'decode-result.json'
    error = json.loads(result.read_text()).get('error') if result.exists() else '尚未完成/缺少decode-result.json'
    for stream in STREAMS:
        source = dut / (stream + '.jsonl')
        if not source.exists():
            continue
        if stream == 'instructions':
            render_instructions(dut, error)
            continue
        with source.open() as data, (dut / (stream + '.txt')).open('w') as output:
            output.write(report_header(stream))
            for index, line in enumerate(data):
                if line.strip():
                    output.write(render_record(stream, json.loads(line), index))
            output.write(report_end(error))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    render_directory(args.directory)
