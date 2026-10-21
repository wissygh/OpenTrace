package trace.component

import chisel3._
import chisel3.util._

/** Instruction payload fields in specification transmission order (LSB first).
  * Format 1's address follows its allocated map width, not a fixed 31-bit field.
  */
object ETracePacketCodec {
  def extend(raw: UInt, width: Int): UInt = {
    require(raw.getWidth <= width)
    if (raw.getWidth == width) raw else Cat(Fill(width - raw.getWidth, raw.head(1)), raw)
  }

  def address(count: UInt, map: UInt, alignedAddress: UInt, notify: Bool, updiscon: Bool, width: Int): UInt = {
    val n          = alignedAddress.head(1).asBool ^ notify
    val u          = n ^ updiscon
    val withoutMap = extend(Cat(u, n, alignedAddress, 2.U(2.W)), width)
    val choices = Seq(1, 3, 7, 15, 31).map { size =>
      (count <= size.U) -> extend(Cat(u, n, alignedAddress, map(size - 1, 0), count, 1.U(2.W)), width)
    }
    Mux(count === 0.U, withoutMap, PriorityMux(choices))
  }

  def branchOnly(map: UInt, width: Int): UInt =
    extend(Cat(map(30, 0), 0.U(5.W), 1.U(2.W)), width)

  /** Preserve a sign bit before rounding to bytes, including all-zero/all-one
    * payloads. valid is separate: payload value zero is not an absence marker.
    */
  def payloadBytes(payload: UInt): UInt = {
    val width     = payload.getWidth
    val different = payload ^ Fill(width, payload.head(1))
    val bits      = Mux(different.orR, (width + 1).U - PriorityEncoder(Reverse(different)), 1.U)
    (bits + 7.U) >> 3
  }

  def encapsulate(payload: UInt, source: UInt, capacity: Int): PacketEnq = {
    require(source.getWidth > 0 && source.getWidth % 8 == 0)
    require(payload.getWidth <= 248)
    require(capacity >= 1 + source.getWidth / 8 + (payload.getWidth + 7) / 8)
    val packet = Wire(new PacketEnq(capacity))
    val length = payloadBytes(payload)
    val header = Wire(UInt(8.W))
    header := length
    val raw = Cat(payload, source, header)
    packet.len := length +& (1 + source.getWidth / 8).U
    for (i <- 0 until capacity) {
      val value = if (8 * i < raw.getWidth) (raw >> (8 * i))(7, 0) else 0.U(8.W)
      packet.data(i) := Mux(i.U < packet.len, value, 0.U)
    }
    packet
  }
}
