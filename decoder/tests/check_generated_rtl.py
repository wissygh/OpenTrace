"""Validate the public profile of all generated supported RTL configurations."""
import hashlib
import json
from pathlib import Path
import re
import sys


def ports(source):
    source = re.sub(r'//[^\n]*', '', source)
    source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    match = re.search(r'\bmodule\s+Trace\s*\((.*?)\);', source, re.S)
    if not match:
        raise ValueError('generated Trace module/header is missing')
    result = {}
    direction, width = None, None
    for declaration in match.group(1).split(','):
        declaration = declaration.strip()
        full = re.fullmatch(r'(input|output|inout)\s+(?:wire\s+|logic\s+)?(?:\[(\d+):(\d+)\]\s*)?(\w+)', declaration)
        if full:
            direction, high, low, name = full.groups()
            width = abs(int(high) - int(low)) + 1 if high is not None else 1
        elif re.fullmatch(r'\w+', declaration) and direction:
            name = declaration
        else:
            raise ValueError(f'unrecognized Trace port declaration: {declaration!r}')
        if name in result:
            raise ValueError(f'duplicate Trace port: {name}')
        result[name] = (direction, width)
    return result


def main(directory='build/rtl', include_smem=False, include_hpm=False):
    directory = Path(directory)
    configurations = [('tl', 1, False), ('axi', 1, False), ('apb', 1, False),
                      ('tl-2hart-sram', 2, False), ('axi-2hart-sram', 2, False),
                      ('apb-2hart-sram', 2, False), ('tl-2hart-atb', 2, True)]
    if include_smem:
        configurations += [('tl-2hart-smem', 2, False), ('axi-2hart-smem', 2, False), ('apb-2hart-smem', 2, False)]
    if include_hpm:
        configurations += [('tl-2hart-hpm', 2, False)]
    manifest = []
    for name, harts, atb in configurations:
        top = directory / name / 'Trace.sv'
        source = top.read_text()
        actual = ports(source)

        def require(name, direction, width=1):
            if actual.get(name) != (direction, width):
                raise ValueError(f'{top}: {name}: {actual.get(name)!r} != {(direction, width)!r}')

        sources = {int(m.group(1)) for key in actual if (m := re.fullmatch(r'io_fromCore_(\d+)_priv', key))}
        if sources != set(range(harts)):
            raise ValueError(f'{top}: hart ports mismatch: {sources!r}')
        for hart in range(harts):
            for field, width in [('priv', 3), ('context', 32), ('ctype', 2), ('cause', 8), ('tval', 50), ('time', 64)]:
                require(f'io_fromCore_{hart}_{field}', 'input', width)
            for block in range(3):
                require(f'io_fromCore_{hart}_blocks_{block}_valid', 'input')
                for field, width in [('iaddr', 50), ('itype', 4), ('iretire', 7), ('ilastsize', 1), ('trigNotify', 1)]:
                    require(f'io_fromCore_{hart}_blocks_{block}_bits_{field}', 'input', width)
            require(f'io_toCore_{hart}_enable', 'output')
            require(f'io_toCore_{hart}_stall', 'output')
        for domain in ('core', 'ctrl', 'trace'):
            require(f'io_clock_{domain}', 'input')
            require(f'io_reset_{domain}', 'input')
        if atb:
            for field in ('atclken', 'at_ready', 'af_valid', 'syncReq'):
                require('io_atbm_' + field, 'input')
            for field in ('at_valid', 'af_ready', 'wakeup'):
                require('io_atbm_' + field, 'output')
            for field, width in [('data', 32), ('bytes', 2), ('id', 7)]:
                require('io_atbm_at_bits_' + field, 'output', width)
            require('io_clock_atb', 'input')
            require('io_reset_atb_n', 'input')
            if any(key.startswith('io_atbm_') and ('last' in key or 'packetBytes' in key) for key in actual):
                raise ValueError(f'{top}: internal packet metadata exposed on standard ATB')
        elif any(key.startswith('io_atbm_') for key in actual):
            raise ValueError(f'{top}: SRAM configuration unexpectedly exports an ATB sink')
        hpm = name.endswith('-hpm')
        if hpm:
            for hart in range(harts):
                for event in range(2):
                    prefix = f'io_hpm_{hart}_eventVec_{event}_'
                    require(prefix + 'hpmaddr', 'output', 5)
                    require(prefix + 'hpmevent', 'output', 55)
                    require(prefix + 'inc', 'input', 6)
        elif any(key.startswith('io_hpm_') for key in actual):
            raise ValueError(f'{top}: default profile unexpectedly exports core HPM pins')
        bus = name.split('-')[0]
        bus_ports = {
            'tl': [('ctrlTLMaster_0_a_valid', 'input', 1), ('ctrlTLMaster_0_d_ready', 'input', 1),
                   ('ctrlTLMaster_0_a_bits_data', 'input', 32), ('ctrlTLMaster_0_d_bits_data', 'output', 32)],
            'axi': [('ctrlAXIMaster_awvalid', 'input', 1), ('ctrlAXIMaster_arvalid', 'input', 1),
                    ('ctrlAXIMaster_wdata', 'input', 32), ('ctrlAXIMaster_rdata', 'output', 32)],
            'apb': [('ctrlAPBMaster_0_psel', 'input', 1), ('ctrlAPBMaster_0_pready', 'output', 1),
                    ('ctrlAPBMaster_0_pwdata', 'input', 32), ('ctrlAPBMaster_0_prdata', 'output', 32)],
        }
        for pin, direction, width in bus_ports[bus]:
            require(pin, direction, width)
        filelist = (top.parent / 'filelist.f').read_text().splitlines()
        missing = [filename for filename in filelist if filename and not (top.parent / filename).is_file()]
        if missing:
            raise ValueError(f'{top}: generated file list references missing files: {missing!r}')
        smem = name.endswith('-smem')
        if smem:
            for channel in ('aw', 'w', 'ar'):
                require(f'io_smem_{channel}_valid', 'output')
                require(f'io_smem_{channel}_ready', 'input')
            for channel in ('b', 'r'):
                require(f'io_smem_{channel}_valid', 'input')
                require(f'io_smem_{channel}_ready', 'output')
            require('io_smem_aw_bits_addr', 'output', 64)
            require('io_smem_w_bits_data', 'output', 32)
            require('io_smem_w_bits_strb', 'output', 4)
            require('io_smem_b_bits_resp', 'input', 2)
        elif any(key.startswith('io_smem_') for key in actual):
            raise ValueError(f'{top}: memory master appears on a non-SMEM configuration')
        manifest.append({'configuration': name, 'harts': harts, 'sink': 'ATB' if atb else 'SMEM' if smem else 'SRAM',
                         'ports': len(actual), 'modules': len(filelist), 'sha256': hashlib.sha256(top.read_bytes()).hexdigest()})
        print(f'[rtl-profile] {name}: hart={harts}, sink={manifest[-1]["sink"]}, {len(actual)} 个端口符合冻结接口')
    output = directory.parent / 'validation' / 'rtl-profile-manifest.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'[rtl-profile] {len(configurations)} 种配置生成和端口检查通过，manifest: {output}')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/rtl', include_smem='--smem' in sys.argv[2:], include_hpm='--hpm' in sys.argv[2:])
