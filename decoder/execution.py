"""Base E-Trace instruction decoder, following specification Chapter 11.

Every instance owns its reference PC, branch FIFO, qualification and options.
No ELF bytes or trace from another source participate in that state machine.
"""
from dataclasses import dataclass
from collections import deque
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
    f0s_width: int = 1
    cache_size: int = 8
    bpred_size: int = 8
    return_stack_size: int = 0
    max_steps: int = 100000

    def __post_init__(self):
        if not (1 <= self.address_lsb < self.address_width <= 64):
            raise DecodeError('invalid instruction address geometry')
        if not (1 <= self.privilege_width <= 3 and 0 <= self.context_width <= 64 and
                0 <= self.time_width <= 64 and 1 <= self.ecause_width <= 64 and
                1 <= self.encoder_mode_width <= 8 and 0 <= self.f0s_width <= 2 and
                0 <= self.cache_size <= 16 and 0 <= self.bpred_size <= 16 and
                0 <= self.return_stack_size <= 16 and self.max_steps > 0):
            raise DecodeError('invalid discovery profile')


class Bits:
    def __init__(self, data, fields=None):
        if not data:
            raise DecodeError('empty instruction payload')
        # Packets use sign-based compression of their most significant fields.
        self.value = int.from_bytes(data, 'little', signed=True)
        self.position = 0
        self.fields = fields

    def take(self, width, name=None):
        value = (self.value >> self.position) & ((1 << width) - 1)
        if self.fields is not None and name is not None:
            self.fields.append(dict(name=name, bit_offset=self.position, width=width, value=value))
        self.position += width
        return value


def unpack(payload, profile, options, fields=None):
    b = Bits(payload, fields)
    fmt = b.take(2, 'format')
    packet = {'format': fmt}
    aw = profile.address_width - profile.address_lsb
    def return_fields(previous):
        if options & 2:
            report = b.take(1, 'irreport')
            depth = b.take(profile.return_stack_size + 1, 'irdepth')
            packet.update(irreport=bool(report ^ previous), irdepth=depth)
            if report != previous and depth > (1 << profile.return_stack_size):
                raise DecodeError('implicit return depth exceeds the discovered stack capacity')
            if report == previous and depth != (((1 << (profile.return_stack_size + 1)) - 1) if previous else 0):
                raise DecodeError('inactive implicit return depth must repeat its preceding bit')
    if fmt == 0:
        sf = b.take(profile.f0s_width, 'subformat') if profile.f0s_width else (1 if options & 16 else 0)
        packet['subformat'] = sf
        if sf == 0 and options & 8 and profile.bpred_size:
            count = b.take(32, 'branch_count')
            branch_format = b.take(2, 'branch_fmt')
            if branch_format == 1 or (branch_format == 0 and count == 0xffffffff):
                raise DecodeError('invalid Format0 branch_fmt or maximum-count encoding')
            packet.update(branch_count=count + 31, prediction_failed=branch_format in (0, 3),
                          prediction_max=count == 0xffffffff and branch_format == 2)
            if branch_format == 0:
                packet.update(notify=False, updiscon=False)
                return packet
        elif sf == 1 and options & 16 and profile.cache_size:
            packet.update(index=b.take(profile.cache_size, 'index'), branches=b.take(5, 'branches'), notify=False, updiscon=False)
            branches = packet['branches']
            map_width = (1 << branches.bit_length()) - 1
            raw_map = b.take(map_width, 'branch_map') if branches else 0
            packet['branch_map'] = raw_map & ((1 << branches) - 1)
            return_fields((raw_map >> (map_width - 1)) & 1 if branches else (branches >> 4))
            return packet
        else:
            raise DecodeError('Format 0 requires an enabled, supported optional format')
    if fmt == 3:
        sf = b.take(2, 'subformat')
        packet['subformat'] = sf
        if sf == 3:
            packet['ienable'] = b.take(1, 'ienable')
            packet['mode'] = b.take(profile.encoder_mode_width, 'encoder_mode')
            packet['qual_status'] = b.take(2, 'qual_status')
            packet['options'] = b.take(6, 'ioptions')
            packet['denable'] = b.take(1, 'denable')
            packet['dloss'] = b.take(1, 'dloss')
            packet['doptions'] = b.take(2, 'doptions')
            return packet
        if sf != 2:
            packet['branch'] = b.take(1, 'branch')
        packet['privilege'] = b.take(profile.privilege_width, 'privilege')
        packet['time'] = b.take(profile.time_width, 'time') if profile.time_width else None
        packet['context'] = b.take(profile.context_width, 'context') if profile.context_width else None
        if sf == 1:
            packet['ecause'] = b.take(profile.ecause_width, 'ecause')
            packet['interrupt'] = b.take(1, 'interrupt')
            packet['thaddr'] = b.take(1, 'thaddr')
        if sf != 2:
            omitted = sf == 1 and packet['thaddr'] and options & 4
            packet['address'] = None if omitted else b.take(aw, 'address')
        if sf == 1 and not packet['interrupt']:
            packet['tval'] = b.take(profile.address_width, 'tval')
        return packet
    if fmt == 1:
        branches = b.take(5, 'branches')
        packet['branches'] = branches
        raw_map = b.take((1 << branches.bit_length()) - 1 if branches else 31, 'branch_map')
        packet['branch_map'] = raw_map & ((1 << (branches or 31)) - 1)
        if not branches:
            return packet
    address = b.take(aw, 'address')
    notify = b.take(1, 'notify')
    updiscon = b.take(1, 'updiscon')
    packet.update(address=address, notify=bool(notify ^ (address >> (aw - 1))),
                  updiscon=bool(updiscon ^ notify))
    return_fields(updiscon)
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
        self.branch_runs = deque()
        self.prediction_states = {}
        self.skip_sync_prediction = False
        self.stop_at_last_branch = False
        self.inferred_address = False
        self.start_of_trace = True
        self.options = 0
        self.privilege = 0
        self.context = None
        self.time = None
        self.events = []
        self.target_cache = {}
        self.return_stack = []
        self.last_inferred_return = False

    def _instr(self, pc):
        image = self.contexts.get(self.context, self.image)
        return image.instruction(pc)

    def _implicit_handler(self, packet):
        vector = self.trap_vectors.get(packet['privilege'])
        if vector is None:
            raise DecodeError('implicit exception mode requires the appropriate trap vector')
        if isinstance(vector, bool) or not isinstance(vector, int) or not 0 <= vector <= self.mask:
            raise DecodeError('trap vector must be an unsigned address within XLEN')
        mode = vector & 3
        if mode not in (0, 1):
            raise DecodeError('unsupported trap-vector mode')
        handler = (vector & ~3) + (4 * packet['ecause'] if mode == 1 and packet['interrupt'] else 0)
        if handler > self.mask:
            raise DecodeError('vectored trap handler exceeds XLEN')
        return handler

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

    def _clear_branches(self):
        self.branches = 0
        self.branch_runs.clear()

    def _append_map(self, bits, count):
        if count:
            self.branch_runs.append([0, count, bits])
            self.branches += count

    def _append_predictions(self, count, failed):
        self.branch_runs.append([1, count, 0])
        self.branches += count
        if failed:
            self.branch_runs.append([2, 1, 0])
            self.branches += 1

    def _take_branch(self):
        if not self.branches:
            raise DecodeError(f'branch outcome unavailable at 0x{self.pc:x}')
        index = (self.pc >> self.profile.address_lsb) & ((1 << self.profile.bpred_size) - 1)
        state = self.prediction_states.get(index, 1)
        run = self.branch_runs[0]
        if run[0] == 0:
            taken = not (run[2] & 1)
            run[2] >>= 1
        else:
            taken = bool(state & 2) ^ (run[0] == 2)
        run[1] -= 1
        self.branches -= 1
        if not run[1]:
            self.branch_runs.popleft()
        if self.options & 8:
            if self.skip_sync_prediction:
                self.skip_sync_prediction = False
            else:
                predicted = bool(state & 2)
                next_prediction = taken if bool(state & 1) == taken else predicted
                self.prediction_states[index] = (int(next_prediction) << 1) | int(taken)
        return taken

    def _next(self, address, packet=None):
        instr = self._instr(self.pc)
        old = self.pc
        stop = False
        self.last_inferred_return = False
        is_return = instr.kind == 'indirect' and instr.rs1 in (1, 5) and instr.rd not in (1, 5)
        failed_return = bool(packet and packet.get('irreport') and
                             packet['irdepth'] == len(self.return_stack))
        if instr.kind == 'direct':
            self.pc += instr.immediate
        elif instr.kind == 'absolute':
            self.pc = instr.immediate & ~1
        elif self.options & 2 and is_return and self.return_stack and not failed_return:
            self.pc = self.return_stack.pop()
            self.last_inferred_return = True
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
            self.pc, stop = (self.address if is_return and failed_return else address), True
        elif instr.kind == 'branch':
            taken = self._take_branch()
            self.pc += instr.immediate if taken else instr.size
        else:
            self.pc += instr.size
        self.pc &= self.mask
        # Co-routine swaps (different link registers) stay explicit and do not
        # enter this stack; tail calls have no link-register destination.
        is_call = instr.kind in ('direct', 'indirect', 'absolute') and instr.rd in (1, 5) and not (
            instr.kind == 'indirect' and instr.rs1 in (1, 5) and instr.rs1 != instr.rd)
        if self.options & 2 and is_call:
            capacity = 1 << self.profile.return_stack_size
            if len(self.return_stack) == capacity:
                del self.return_stack[0]
            self.return_stack.append((old + instr.size) & self.mask)
        if stop and self.options & 16:
            index = (self.pc >> self.profile.address_lsb) & ((1 << self.profile.cache_size) - 1)
            self.target_cache[index] = self.pc
        self.last_pc = old
        return stop

    def _follow(self, address, packet):
        previous_address = self.pc
        for _ in range(self.profile.max_steps):
            if self.inferred_address:
                stop = self._next(previous_address, packet)
                self._report_pc()
                if stop:
                    self.inferred_address = False
                # A depth report identifies an occurrence across inferred
                # returns even when no explicit discontinuity ends the search.
                depth_matches = not packet.get('irreport') or packet['irdepth'] == len(self.return_stack)
                if not (self.options & 2 and self.pc == address and depth_matches and not self._unprocessed()):
                    continue
                self.inferred_address = False
                target_image = None
            else:
                stop = self._next(address, packet)
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
                depth_matches = not packet.get('irreport') or packet['irdepth'] == len(self.return_stack)
                if (packet['notify'] or packet.get('prediction_max')) and depth_matches:
                    return
                inferable = self._instr(self.last_pc).kind not in ('indirect', 'xret') or self.last_inferred_return
                if inferable and not packet['updiscon'] and depth_matches:
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
                if packet['mode']:
                    raise DecodeError('unsupported encoder instruction mode')
                if packet['options'] & 2 and not self.profile.return_stack_size:
                    raise DecodeError('implicit return mode requires return_stack_size discovery')
                if packet['options'] & 16 and not self.profile.cache_size:
                    raise DecodeError('jump target cache requires non-zero cache_size discovery')
                if packet['options'] & 8 and not self.profile.bpred_size:
                    raise DecodeError('branch prediction requires non-zero bpred_size discovery')
                if packet['options'] & 24 == 24 and not self.profile.f0s_width:
                    raise DecodeError('multiple Format0 extensions require a subformat field')
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
                    self._clear_branches()
                    self.inferred_address = False
                self.target_cache.clear()
                self.return_stack.clear()
                self.prediction_states.clear()
                self.skip_sync_prediction = False
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
                    self.target_cache.clear()
                    self.return_stack.clear()
                    self.awaiting_handler = True
                    self.inferred_address = False
                    self._clear_branches()
                    self.prediction_states.clear()
                    self.skip_sync_prediction = False
                    return self.events
            if address is None:
                full_address = self._implicit_handler(packet)
            else:
                full_address = self._absolute(address)
            self.inferred_address = False
            self.address = full_address
            if sf == 1 or self.start_of_trace or self.awaiting_handler:
                self._clear_branches()
            # The synchronization instruction is in the new context; intermediate
            # instructions up to it still use the old image/context.
            target_image = self.contexts.get(packet['context'], self.image)
            if target_image.instruction(full_address).kind == 'branch':
                self._append_map(packet['branch'], 1)
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
            self.target_cache.clear()
            self.return_stack.clear()
            self.prediction_states.clear()
            self.skip_sync_prediction = target_image.instruction(full_address).kind == 'branch'
            return self.events
        if self.start_of_trace:
            # A circular capture may begin with packets preceding this hart's sync.
            self._emit('unsynchronized', format=fmt)
            return self.events
        if fmt == 0:
            if packet['subformat'] == 1:
                if packet['index'] not in self.target_cache:
                    raise DecodeError(f"jump target cache index {packet['index']} is invalid")
                self.address = self.target_cache[packet['index']]
                self.stop_at_last_branch = False
                self._append_map(packet['branch_map'], packet['branches'])
            else:
                self._append_predictions(packet['branch_count'], packet['prediction_failed'])
                self.stop_at_last_branch = 'address' not in packet
                if 'address' in packet:
                    absolute = self._absolute(packet['address'])
                    self.address = absolute if self.options & 32 else (self.address + absolute) & self.mask
            self._follow(self.address, packet)
            return self.events
        if fmt == 2 or packet['branches']:
            self.stop_at_last_branch = False
            if self.options & 32:
                self.address = self._absolute(packet['address'])
            else:
                self.address = (self.address + self._absolute(packet['address'])) & self.mask
        if fmt == 1:
            self.stop_at_last_branch = packet['branches'] == 0
            self._append_map(packet['branch_map'], packet['branches'] or 31)
        self._follow(self.address, packet)
        return self.events
