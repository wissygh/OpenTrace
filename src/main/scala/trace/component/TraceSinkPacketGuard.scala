package trace.component

import chisel3._
import chisel3.util._
import trace.interface.TraceBeat

/** System-reset state survives a selected sink's software Active reset. If that
  * reset cuts a packet, consume only its remaining tail through last; the new
  * sink session can then start at a complete packet boundary.
  */
class TraceSinkPacketGuard(dataBytes: Int = 4) extends Module {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new TraceBeat(dataBytes)))
    val out      = Decoupled(new TraceBeat(dataBytes))
    val active   = Input(Bool())
    val empty    = Output(Bool())
    val dropping = Output(Bool())
  })
  val inPacket = RegInit(false.B)
  val dropping = RegInit(false.B)
  val discard  = dropping || (!io.active && inPacket)
  io.out.valid := io.in.valid && io.active && !discard
  io.out.bits  := io.in.bits
  io.in.ready  := discard || (io.active && io.out.ready)
  io.empty     := !inPacket && !dropping
  io.dropping  := discard
  when(!io.active && inPacket)(dropping := true.B)
  when(io.in.fire) {
    inPacket := !io.in.bits.last
    when(io.in.bits.last)(dropping := false.B)
  }
}
