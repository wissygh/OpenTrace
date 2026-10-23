"""Fixed assembly references; these do not use decoded trace to predict PCs."""
from decoder.__main__ import number


def reference(symbols, scenario='basic'):
    start, done = number(symbols['trace_begin']), number(symbols['trace_done'])
    if scenario == 'basic':
        expected = [start, start + 2] + [start + x for _ in range(4) for x in (4, 6, 8)]
        return 18, expected + [start + x for x in (12, 16, 20, 28, 30, 24, 26)]
    if scenario == 'pressure':
        loop, target = number(symbols['trace_loop']), number(symbols['trace_target'])
        if (loop, target, done) != (start + 14, start + 18, start + 26):
            raise ValueError('pressure assembly instruction layout changed; update the independent reference')
        expected = [start + x for x in (0, 2, 6, 10)]
        expected += [pc for _ in range(80) for pc in (loop, target, target + 2, target + 4)]
        return 80, expected
    if scenario == 'exception':
        handler = number(symbols['trace_handler'])
        after_ecall = number(symbols['trace_after_ecall'])
        after_illegal = number(symbols['trace_after_illegal'])
        if (handler, after_ecall, after_illegal, done) != (start + 36, start + 20, start + 28, start + 60):
            raise ValueError('exception assembly layout changed; update the independent reference')
        path = [handler + offset for offset in range(0, 24, 4)]
        return 23, [start + offset for offset in (0, 4, 8, 12)] + path + [after_ecall] + path + [after_illegal, start + 32]
    raise ValueError(f'unknown SBA scenario: {scenario}')
