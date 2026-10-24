"""Unambiguous names for the supported independent encoder feature switches."""
from itertools import product


FEATURE_BITS = (1, 2, 8, 16, 32)
FEATURE_MASK = sum(FEATURE_BITS)
FEATURE_NAMES = ((2, 'implicit-exception'), (8, 'implicit-return'),
                 (16, 'branch-prediction'), (32, 'jump-cache'))
LEGACY_MODES = {'baseline': 0, 'cache': 32, 'prediction': 16, 'combined': 48,
                'implicit': 8, 'implicit-prediction': 24, 'implicit-combined': 56}


def mode_name(features):
    if not isinstance(features, int) or isinstance(features, bool) or features < 0 or features & ~FEATURE_MASK:
        raise ValueError('unsupported encoder feature mask')
    return '+'.join(['full-address' if features & 1 else 'delta-address',
                     *[name for bit, name in FEATURE_NAMES if features & bit]])


def support_options(features):
    mode_name(features)
    return ((features & 1) << 5) | ((features & 2) << 1) | ((features & 8) >> 2) | ((features & 0x30) >> 1)


COMBINATIONS = {mode_name(sum(bit for bit, on in zip(FEATURE_BITS, enabled) if on)):
                sum(bit for bit, on in zip(FEATURE_BITS, enabled) if on)
                for enabled in product((False, True), repeat=len(FEATURE_BITS))}
FEATURE_MODES = {**LEGACY_MODES, **COMBINATIONS}
