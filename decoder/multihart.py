from .encapsulation import DecodeError, PacketParser


class MultiHartDecoder:
    """One framing parser followed by independent stateful source decoders."""
    def __init__(self, decoders, parser=None):
        self.decoders = dict(decoders)
        if not self.decoders:
            raise DecodeError('at least one source mapping is required')
        self.parser = parser or PacketParser()
        for source, decoder in self.decoders.items():
            if source != decoder.source_id:
                raise DecodeError('source map and decoder source_id disagree')

    def feed(self, data):
        result = []
        for packet in self.parser.feed(data):
            decoder = self.decoders.get(packet.source_id)
            if decoder is None:
                raise DecodeError(f'no hart/image mapping for srcID {packet.source_id}')
            try:
                events = decoder.consume(packet)
            except DecodeError as exc:
                raise DecodeError(f'srcID={packet.source_id}, hart={decoder.hart_id}: {exc}') from exc
            if packet.timestamp is not None:
                for event in events:
                    event['capture_timestamp'] = packet.timestamp
            result.extend(events)
        return result

    def finish(self):
        self.parser.finish()
