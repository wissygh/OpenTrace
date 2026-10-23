package trace.component

import chisel3._
import chisel3.util._

/** Full-address prediction. Saturation drops the oldest call, never the newest.
  * Clear precedes a simultaneous push: a call at a synchronization address is
  * executed after that synchronization by the decoder as well.
  */
class TraceReturnStack(sizeLog: Int = 4, addressWidth: Int = 50) extends Module {
  require(sizeLog >= 1 && sizeLog <= 8 && addressWidth >= 2)
  private val entries = 1 << sizeLog
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val push  = Input(Bool())
    val pop   = Input(Bool())
    val link  = Input(UInt(addressWidth.W))
    val depth = Output(UInt((sizeLog + 1).W))
    val top   = Output(UInt(addressWidth.W))
  })
  val stack     = Reg(Vec(entries, UInt(addressWidth.W)))
  val depth     = RegInit(0.U((sizeLog + 1).W))
  val baseDepth = Mux(io.clear, 0.U, Mux(io.pop && depth =/= 0.U, depth - 1.U, depth))
  val full      = baseDepth === entries.U
  when(io.push) {
    when(full) {
      for (i <- 0 until entries - 1) stack(i) := stack(i + 1)
      stack(entries - 1) := io.link
    }.otherwise(stack(baseDepth(sizeLog - 1, 0)) := io.link)
  }
  depth    := Mux(io.push && !full, baseDepth + 1.U, baseDepth)
  io.depth := depth
  io.top   := Mux(depth === 0.U, 0.U, stack((depth - 1.U)(sizeLog - 1, 0)))
}
