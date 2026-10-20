"""Base E-Trace instruction decoder, following specification Chapter 11.

Every instance owns its reference PC, branch FIFO, qualification and options.
No ELF bytes or trace from another source participate in that state machine.
"""
from dataclasses import dataclass
from .encapsulation import DecodeError
from .image import signed


@dataclass(frozen=True)
class Profile:
    address_width: int = 50
    address_lsb: int = 1
    privilege_width: int = 3
    context_width: int = 32
    time_width: int = 64
    ecause_width: int = 8
    encoder_mode_width: int = 1
    max_steps: int = 100000

    def __post_init__(self):
        if not (1 <= self.address_lsb < self.address_width <= 64):
            raise DecodeError('invalid instruction address geometry')
        if not (1 <= self.privilege_width <= 3 and 0 <= self.context_width <= 64 and
                0 <= self.time_width <= 64 and 1 <= self.ecause_width <= 64 and
                1 <= self.encoder_mode_width <= 8 and self.max_steps > 0):
            raise DecodeError('invalid discovery profile')


class Bits:
    def __init__(self, data):
        if not data:
            raise DecodeError('empty instruction payload')
        # Packets use sign-based compression of their most significant fields.
        self.value = int.from_bytes(data, 'little', signed=True)
        self.position = 0

    def take(self, width):
        value = (self.value >> self.position) & ((1 << width) - 1)
        self.position += width
        return value


def unpack(payload, profile, options):
    b = Bits(payload)
    fmt = b.take(2)
    packet = {'format': fmt}
    aw = profile.address_width - profile.address_lsb
    if fmt == 0:
        raise DecodeError('Format 0 branch prediction / target cache is not implemented')
    if fmt == 3:
        sf = b.take(2)
        packet['subformat'] = sf
        if sf == 3:
            packet['ienable'] = b.take(1)
            packet['mode'] = b.take(profile.encoder_mode_width)
            packet['qual_status'] = b.take(2)
            packet['options'] = b.take(6)
            packet['denable'] = b.take(1)
            packet['dloss'] = b.take(1)
            packet['doptions'] = b.take(2)
            return packet
        if sf != 2:
            packet['branch'] = b.take(1)
        packet['privilege'] = b.take(profile.privilege_width)
        packet['time'] = b.take(profile.time_width) if profile.time_width else None
        packet['context'] = b.take(profile.context_width) if profile.context_width else None
        if sf == 1:
            packet['ecause'] = b.take(profile.ecause_width)
            packet['interrupt'] = b.take(1)
            packet['thaddr'] = b.take(1)
        if sf != 2:
            omitted = sf == 1 and packet['thaddr'] and options & 4
            packet['address'] = None if omitted else b.take(aw)
        if sf == 1 and not packet['interrupt']:
            packet['tval'] = b.take(profile.address_width)
        return packet
    if fmt == 1:
        branches = b.take(5)
        packet['branches'] = branches
        raw_map = b.take((1 << branches.bit_length()) - 1 if branches else 31)
        packet['branch_map'] = raw_map & ((1 << (branches or 31)) - 1)
        if not branches:
            return packet
    address = b.take(aw)
    notify = b.take(1)
    updiscon = b.take(1)
    packet.update(address=address, notify=bool(notify ^ (address >> (aw - 1))),
                  updiscon=bool(updiscon ^ notify))
    return packet


class ExecutionDecoder:
    def __init__(self, image, profile=None, source_id=0, hart_id=0, contexts=None, trap_vectors=None):
        self.image = image
        self.profile = profile or Profile()
        self.source_id = source_id
        self.hart_id = hart_id
        self.contexts = contexts or {}
        self.trap_vectors = trap_vectors or {}
        self.mask = (1 << image.xlen) - 1
        self.pc = None
        self.last_pc = None
        self.awaiting_handler = False
        self.address = 0
        self.branches = 0
        self.branch_map = 0
        self.stop_at_last_branch = False
        self.inferred_address = False
        self.start_of_trace = True
        self.options = 0
        self.privilege = 0
        self.context = None
        self.time = None
        self.events = []

    def _instr(self, pc):
        image = self.contexts.get(self.context, self.image)
        return image.instruction(pc)

    def _emit(self, kind, **fields):
        event = dict(source_id=self.source_id, hart_id=self.hart_id, kind=kind,
                     privilege=self.privilege, context=self.context, time=self.time)
        event.update(fields)
        self.events.append(event)

    def _report_pc(self, image=None):
        instr = image.instruction(self.pc) if image is not None else self._instr(self.pc)
        self._emit('instruction', pc=self.pc, instruction=instr.bits, size=instr.size)

    def _unprocessed(self, image=None):
        instr = image.instruction(self.pc) if image is not None else self._instr(self.pc)
        return self.branches != int(instr.kind == 'branch')

    def _next(self, address):
        instr = self._instr(self.pc)
        old = self.pc
        stop = False
        if instr.kind == 'direct':
            self.pc += instr.immediate
        elif instr.kind == 'absolute':
            self.pc = instr.immediate & ~1
        elif self.options & 1 and instr.kind == 'indirect' and self.last_pc is not None:
            previous = self._instr(self.last_pc)
            if previous.kind in ('auipc', 'lui') and previous.rd == instr.rs1 and instr.rs1:
                self.pc = ((self.last_pc if previous.kind == 'auipc' else 0) +
                           previous.immediate + instr.immediate) & ~1
            else:
                self.pc, stop = address, True
        elif instr.kind in ('indirect', 'xret'):
            if self.stop_at_last_branch:
                raise DecodeError('uninferable discontinuity in address-free branch map')
            self.pc, stop = address, True
        elif instr.kind == 'branch':
            if not self.branches:
                raise DecodeError(f'branch outcome unavailable at 0x{old:x}')
            taken = not (self.branch_map & 1)
            self.branch_map >>= 1
            self.branches -= 1
            self.pc += instr.immediate if taken else instr.size
        else:
            self.pc += instr.size
        self.pc &= self.mask
        self.last_pc = old
        return stop

    def _follow(self, address, packet):
        previous_address = self.pc
        for _ in range(self.profile.max_steps):
            if self.inferred_address:
                stop = self._next(previous_address)
                self._report_pc()
                if stop:
                    self.inferred_address = False
                continue
            stop = self._next(address)
            target_image = (self.contexts.get(packet['context'], self.image)
                            if packet['format'] == 3 and self.pc == address else None)
            self._report_pc(target_image)
            if self.stop_at_last_branch and self.branches == 1 and self._instr(self.pc).kind == 'branch':
                self.stop_at_last_branch = False
                return
            if stop:
                if self._unprocessed(target_image):
                    raise DecodeError('unprocessed branch outcomes at reported jump target')
                return
            if self.pc != address or self.stop_at_last_branch or self._unprocessed(target_image):
                continue
            if packet['format'] != 3:
                if packet['notify']:
                    return
                if self._instr(self.last_pc).kind not in ('indirect', 'xret') and not packet['updiscon']:
                    self.inferred_address = True
                    return
            elif packet['privilege'] == self.privilege or self._instr(self.last_pc).kind == 'xret':
                return
        raise DecodeError('execution reconstruction exceeded max_steps; check image, profile or trace')

    def _absolute(self, encoded):
        return (signed(encoded, self.profile.address_width - self.profile.address_lsb) << self.profile.address_lsb) & self.mask

    def consume(self, encapsulated):
        self.events = []
        packet = unpack(encapsulated.payload, self.profile, self.options)
        fmt = packet['format']
        if fmt == 3:
            sf = packet['subformat']
            if sf == 3:
                if packet['mode'] or packet['options'] & (2 | 8 | 16):
                    raise DecodeError('implicit return, branch prediction and target cache modes are not implemented')
                if packet['denable']:
                    raise DecodeError('data trace is not implemented')
                self.options = packet['options']
                status = packet['qual_status']
                if status == 3 and self.inferred_address and not self.start_of_trace:
                    target = self.pc
                    self.inferred_address = False
                    for _ in range(self.profile.max_steps):
                        stop = self._next(target)
                        self._report_pc()
                        if stop:
                            break
                    else:
                        raise DecodeError('ended_ntr reconstruction exceeded max_steps')
                if status or not packet['ienable']:
                    self.start_of_trace = True
                    self.awaiting_handler = False
                    self.branches = self.branch_map = 0
                    self.inferred_address = False
                self._emit('support', **packet)
                return self.events
            if sf == 2:
                self.privilege = packet['privilege']
                self.context = packet['context']
                self.time = packet['time']
                self._emit('context')
                return self.events
            address = packet['address']
            if sf == 1:
                if not packet['interrupt']:
                    if self.awaiting_handler:
                        epc = None
                    elif self.start_of_trace or (self._instr(self.pc).kind in ('indirect', 'xret') and not packet['thaddr']):
                        epc = None if packet['thaddr'] else self._absolute(address)
                    elif self._instr(self.pc).kind == 'break':
                        epc = self.pc
                    else:
                        # The faulting instruction did not retire. Follow the
                        # known last instruction's control flow without emitting it.
                        self._next(self.pc)
                        epc = self.pc
                    self._emit('exception_pc', pc=epc)
                self._emit('trap', ecause=packet['ecause'], interrupt=bool(packet['interrupt']),
                           tval=packet.get('tval'), handler=bool(packet['thaddr']))
                if not packet['thaddr']:
                    self.awaiting_handler = True
                    self.inferred_address = False
                    self.branches = self.branch_map = 0
                    return self.events
            if address is None:
                vector = self.trap_vectors.get(packet['privilege'])
                if vector is None:
                    raise DecodeError('implicit exception mode requires the appropriate trap vector')
                mode = vector & 3
                if mode not in (0, 1):
                    raise DecodeError('unsupported trap-vector mode')
                full_address = (vector & ~3) + (4 * packet['ecause'] if mode == 1 and packet['interrupt'] else 0)
            else:
                full_address = self._absolute(address)
            self.inferred_address = False
            self.address = full_address
            if sf == 1 or self.start_of_trace or self.awaiting_handler:
                self.branches = self.branch_map = 0
            # The synchronization instruction is in the new context; intermediate
            # instructions up to it still use the old image/context.
            target_image = self.contexts.get(packet['context'], self.image)
            if target_image.instruction(full_address).kind == 'branch':
                self.branch_map |= packet['branch'] << self.branches
                self.branches += 1
            if sf == 0 and not self.start_of_trace and not self.awaiting_handler:
                self._follow(full_address, packet)
                if self.events and self.events[-1]['kind'] == 'instruction':
                    self.events[-1].update(privilege=packet['privilege'], context=packet['context'], time=packet['time'])
            else:
                self.pc = self.last_pc = full_address
                self.context, self.privilege, self.time = packet['context'], packet['privilege'], packet['time']
                self._report_pc()
            self.context, self.privilege, self.time = packet['context'], packet['privilege'], packet['time']
            self.start_of_trace = False
            self.awaiting_handler = False
            return self.events
        if self.start_of_trace:
            # A circular capture may begin with packets preceding this hart's sync.
            self._emit('unsynchronized', format=fmt)
            return self.events
        if fmt == 2 or packet['branches']:
            self.stop_at_last_branch = False
            if self.options & 32:
                self.address = self._absolute(packet['address'])
            else:
                self.address = (self.address + self._absolute(packet['address'])) & self.mask
        if fmt == 1:
            self.stop_at_last_branch = packet['branches'] == 0
            self.branch_map |= packet['branch_map'] << self.branches
            self.branches += packet['branches'] or 31
        self._follow(self.address, packet)
        return self.events
