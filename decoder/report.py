"""Human-readable views of decoder evidence; JSONL remains the lossless input."""
import argparse
import json
from pathlib import Path
import textwrap


STREAMS = ('framing', 'packets', 'events', 'instructions')
TEXT_REPORT_FILES = tuple(name + '.txt' for name in STREAMS)
STATUS = {0: 'no_change / 配置声明', 1: 'ended_rep / 已报告末指令后结束',
          2: 'trace_lost / 数据丢失', 3: 'ended_ntr / 末指令待推断'}
PRIVILEGE = {0: 'U', 1: 'S/HS', 3: 'M', 4: 'D', 5: 'VU', 6: 'VS'}
OPTIONS = ('sequential_jump', 'implicit_return', 'implicit_exception', 'branch_prediction',
           'jump_target_cache', 'full_address')


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


def _field(lines, name, value, indent=2):
    prefix = ' ' * indent + name + ': '
    text = _value(name, value)
    if isinstance(value, (dict, list, tuple)):
        lines.append(' ' * indent + name + ':')
        lines.extend(' ' * (indent + 2) + line for line in json.dumps(value, ensure_ascii=False, indent=2).splitlines())
    else:
        lines.extend(textwrap.wrap(prefix + text, width=112, subsequent_indent=' ' * (indent + 2),
                                   break_long_words=False, break_on_hyphens=False) or [prefix])


def render_record(stream, row, index):
    """Render saved raw records only; never infer an executed PC from a raw delta."""
    lines = []
    source = f'srcID={row.get("source_id", "无")} hart={row.get("hart_id", "无")}'
    if stream == 'packets':
        fields = row.get('fields', {})
        kind, meaning = packet_type(fields)
        title = f'Packet #{row.get("packet_index", index):04d} | {kind} | {meaning}'
        lines.extend((title, '-' * min(112, len(title) + 16)))
        lines.append(f'  {source}  offset={hex_value(row.get("offset"))}  bytes={row.get("byte_length", "无")}')
        if row.get('reported_pc') is not None:
            role = 'reported_pc'
            if kind == 'Format31':
                role = ('handler_pc' if fields.get('thaddr') else 'interrupt_pc（不代表退休）'
                        if fields.get('interrupt') else 'exception_pc（不代表退休）')
            lines.append(f'  {role}: {hex_value(row["reported_pc"])}')
        elif kind == 'Format01':
            lines.append('  reported_pc: 缓存未命中/未知，不能凭index伪造地址')
        else:
            lines.append('  reported_pc: 本包无地址字段')
        if 'address_delta_hex' in row:
            lines.append(f'  reference_pc: {hex_value(row.get("reference_pc"))}  delta: {row["address_delta_hex"]}')
        if 'branch_map' in fields and (fields.get('branches') or fields.get('format') == 1):
            count = fields.get('branches', 0) or (31 if fields.get('format') == 1 else 0)
            bits = integer(fields['branch_map'])
            outcomes = ' '.join('N' if bits & (1 << i) else 'T' for i in range(count))
            lines.append(f'  branch_outcomes: {outcomes}  （执行顺序，T=taken/0，N=not-taken/1）')
        if 'branch_count' in fields:
            lines.append(f'  correctly_predicted_branches: {fields["branch_count"]}')
        if 'options' in fields:
            enabled = ', '.join(name for i, name in enumerate(OPTIONS) if integer(fields['options']) & (1 << i))
            lines.append('  enabled_options: ' + (enabled or '无'))
        lines.append('  decoded_fields（解释值；address为半字编码，完整PC见reported_pc）:')
        for name, value in fields.items():
            _field(lines, name, value, 4)
        lines.append('  wire_fields（payload内bit偏移；高位可能由符号扩展恢复）:')
        lines.append('    field                    bits        width  raw_value')
        for field in row.get('wire_fields', []):
            lo, width = field['bit_offset'], field['width']
            bits = f'{lo + width - 1}:{lo}' if width > 1 else str(lo)
            lines.append(f'    {field["name"]:<24} {bits:<11} {width:>5}  {hex_value(field["value"])}')
        for name in ('header', 'raw_hex', 'payload_hex', 'options_before', 'profile', 'pc', 'wire_note', 'error'):
            if name in row:
                _field(lines, 'decoder_pc_before' if name == 'pc' else name, row[name])
    elif stream == 'framing':
        kind = 'NULL / 填充或同步序列字节' if row.get('null') else 'NORMAL / 封装包'
        title = f'Frame #{index:04d} | {kind} | offset={hex_value(row.get("offset"))}'
        lines.extend((title, '-' * min(112, len(title) + 12)))
        for name in ('packet_index', 'payload_type', 'byte_length', 'source_id', 'timestamp', 'header', 'raw_hex'):
            if name in row:
                _field(lines, name, row[name])
        if not row.get('null'):
            lines.append(f'  详细类型/完整地址: packets.txt 的 Packet #{row.get("packet_index", "无")}')
        lines.append('  边界依据: 封装header的payload字节数；SRAM word边界不等于packet边界。')
    else:
        kind = row.get('kind', '未知事件')
        names = {'instruction': '退休指令', 'exception_pc': '异常位置 / 不退休', 'trap': 'trap事件',
                 'context': '上下文变化', 'support': '配置/生命周期', 'sync': '同步'}
        title = f'{"Instruction" if stream == "instructions" else "Event"} #{index:04d} | {kind} / {names.get(kind, kind)}'
        lines.extend((title, '-' * min(112, len(title) + 12)))
        lines.append(f'  {source}  packet=#{row.get("packet_index", "无")}  PC={hex_value(row.get("pc"))}')
        if kind == 'instruction':
            lines.append(f'  instruction: {hex_value(row.get("instruction"))}  size: {row.get("size")} bytes')
        for name, value in row.items():
            if name not in ('source_id', 'hart_id', 'packet_index', 'kind', 'pc', 'instruction', 'size'):
                _field(lines, name, value)
    return '\n'.join(lines) + '\n\n'


def report_end(error=None):
    return '解码状态: ' + ('失败 / ' + str(error) if error else '完成') + '\n'


def render_directory(directory):
    """Regenerate views of existing JSONL without changing capture or records."""
    root = Path(directory)
    dut = root / 'dut' if (root / 'dut').is_dir() else root
    for stream in STREAMS:
        source = dut / (stream + '.jsonl')
        if not source.exists():
            continue
        # Incremental rendering also leaves already-read records on a truncated line.
        with source.open() as data, (dut / (stream + '.txt')).open('w') as output:
            for index, line in enumerate(data):
                if line.strip():
                    output.write(render_record(stream, json.loads(line), index))
            result = dut / 'decode-result.json'
            if result.exists():
                output.write(report_end(json.loads(result.read_text()).get('error')))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    render_directory(args.directory)
