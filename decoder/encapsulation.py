"""Streaming RISC-V E-Trace encapsulation, independent of instruction decoding."""
from dataclasses import dataclass


class DecodeError(ValueError):
    pass


@dataclass(frozen=True)
class Packet:
    source_id: int
    payload: bytes
    flow: int = 0
    timestamp: int | None = None


class PacketParser:
    """Parse byte-aligned source IDs; accept arbitrary capture chunk boundaries.

    Input must begin at a packet boundary (or use the documented synchronization
    acquisition mode). Null packets have no srcID or timestamp field.
    """
    def __init__(self, source_bits=8, timestamp_bytes=0, single_source_id=0):
        if source_bits not in (0, 8, 16):
            raise DecodeError('source_bits must be 0, 8 or 16 for this decoder')
        if not 0 <= timestamp_bytes <= 8:
            raise DecodeError('timestamp_bytes must be between 0 and 8')
        self.source_bytes = source_bits // 8
        self.timestamp_bytes = timestamp_bytes
        self.single_source_id = single_source_id
        self.buffer = bytearray()
        self.null_packets = 0
        self.offset = 0

    def feed(self, data):
        self.buffer.extend(data)
        result = []
        while self.buffer:
            header = self.buffer[0]
            length = header & 31
            if not length:
                del self.buffer[:1]
                self.offset += 1
                self.null_packets += 1
                continue
            extended = bool(header & 128)
            if extended and not self.timestamp_bytes:
                raise DecodeError(f'timestamp present with configured width zero at byte {self.offset}')
            time_bytes = self.timestamp_bytes if extended else 0
            size = 1 + self.source_bytes + time_bytes + length
            if len(self.buffer) < size:
                break
            cursor = 1
            source = self.single_source_id
            if self.source_bytes:
                source = int.from_bytes(self.buffer[cursor:cursor + self.source_bytes], 'little')
                cursor += self.source_bytes
            timestamp = None
            if time_bytes:
                timestamp = int.from_bytes(self.buffer[cursor:cursor + time_bytes], 'little')
                cursor += time_bytes
            result.append(Packet(source, bytes(self.buffer[cursor:size]), (header >> 5) & 3, timestamp))
            del self.buffer[:size]
            self.offset += size
        return result

    def finish(self):
        if self.buffer:
            raise DecodeError(f'truncated packet at byte {self.offset}: {len(self.buffer)} bytes remain')


class SyncAcquirer:
    """Find the first normal header after an encapsulation null synchronization run.

    This is for captures beginning at an arbitrary byte in a circular buffer;
    the first source instruction decoder still waits for its own Format 3 sync.
    """
    def __init__(self, parser):
        self.parser = parser
        self.required = 32 + parser.source_bytes + parser.timestamp_bytes
        self.zeros = 0
        self.acquired = False

    def feed(self, data):
        if self.acquired:
            return self.parser.feed(data)
        for i, byte in enumerate(data):
            if byte & 31 == 0:
                self.zeros += 1
            else:
                if self.zeros >= self.required:
                    self.acquired = True
                    return self.parser.feed(data[i:])
                self.zeros = 0
        return []

    def finish(self):
        if not self.acquired:
            raise DecodeError('no encapsulation synchronization sequence found')
        self.parser.finish()
