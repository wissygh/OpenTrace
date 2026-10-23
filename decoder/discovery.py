"""Read the versioned OpenTrace vendor CSR snapshot of E-Trace Table 42.

An external debugger/supervisory hart reads twenty 32-bit little-endian words
at encoder_base + 0xe00. The values are specification attributes, not widths.
No guessed defaults are substituted for a missing or malformed snapshot.
"""
import argparse
import json
from pathlib import Path
import struct

from .encapsulation import DecodeError


ATTRIBUTES = ('arch', 'bpred_size', 'cache_size', 'call_counter_size', 'context_width',
              'time_width', 'ecause_width', 'f0s_width', 'iaddress_lsb', 'iaddress_width',
              'nocontext', 'notime', 'privilege_width', 'return_stack_size', 'sijump')
MAGIC = 0x4352544f
BLOCK_BYTES = 80


def read_snapshot(path):
    raw = Path(path).read_bytes()
    if len(raw) != BLOCK_BYTES:
        raise DecodeError('discovery snapshot must contain exactly twenty 32-bit words (80 bytes)')
    magic, version, words, count, transport, *values = struct.unpack('<20I', raw)
    if (magic, version, words, count) != (MAGIC, 1, 20, len(ATTRIBUTES)):
        raise DecodeError('unknown or unsupported OpenTrace discovery block header')
    if transport & 0xff000000:
        raise DecodeError('reserved discovery transport bits must be zero')
    attributes = dict(zip(ATTRIBUTES, values))
    if attributes['arch'] != 0:
        raise DecodeError('unsupported E-Trace architecture version')
    if attributes['call_counter_size'] or attributes['return_stack_size'] or attributes['sijump']:
        raise DecodeError('discovered implicit return or sequential jump mode is unsupported')
    if any(attributes[k] not in (0, 1) for k in ('nocontext', 'notime')):
        raise DecodeError('discovered nocontext/notime flags must be boolean')
    for key in ('context_width', 'time_width', 'ecause_width', 'iaddress_width', 'privilege_width'):
        if attributes[key] > 63:
            raise DecodeError('discovered width exceeds the decoder range: ' + key)
    source_bits = transport & 255
    timestamp_bytes = (transport >> 8) & 255
    if source_bits not in (0, 8, 16) or timestamp_bytes > 8:
        raise DecodeError('unsupported discovered encapsulation geometry')
    profile = {'address_width': attributes['iaddress_width'] + 1,
        'address_lsb': attributes['iaddress_lsb'] + 1,
        'privilege_width': attributes['privilege_width'] + 1,
        'context_width': 0 if attributes['nocontext'] else attributes['context_width'] + 1,
        'time_width': 0 if attributes['notime'] else attributes['time_width'] + 1,
        'ecause_width': attributes['ecause_width'] + 1,
        'f0s_width': attributes['f0s_width'], 'cache_size': attributes['cache_size'],
        'bpred_size': attributes['bpred_size'], 'encoder_mode_width': (transport >> 16) & 255}
    # Import locally to keep the discovery format independent from CLI loading.
    from .execution import Profile
    Profile(**profile)
    return {'schema_version': 1, 'attributes': attributes, 'profile': profile,
            'source_bits': source_bits, 'header_timestamp_bytes': timestamp_bytes}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    result = read_snapshot(args.snapshot)
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end='')


if __name__ == '__main__':
    main()
