package trace.component

import chisel3._
import chisel3.util._
import trace.interface.Itype

/** Range from a selected trap through its matching return, including nested
  * handlers. Saturation follows the control specification's bounded-counter rule.
  */
class TraceTrapFilterScope(depthBits: Int = 4) extends Module {
  require(depthBits > 0 && depthBits <= 16)
  val io = IO(new Bundle {
    val kind      = Input(UInt(4.W))
    val trigger   = Input(Bool())
    val advance   = Input(Bool())
    val clear     = Input(Bool())
    val matched   = Output(Bool())
    val depth     = Output(UInt(depthBits.W))
    val saturated = Output(Bool())
  })
  val depth   = RegInit(0.U(depthBits.W))
  val start   = Itype.isTrap(io.kind) && io.trigger
  val maximum = ((BigInt(1) << depthBits) - 1).U
  io.matched   := depth =/= 0.U || start
  io.depth     := depth
  io.saturated := !io.clear && io.advance && Itype.isTrap(io.kind) && depth === maximum
  when(io.clear) {
    depth := 0.U
  }.elsewhen(io.advance) {
    when(Itype.isTrap(io.kind) && (depth =/= 0.U || start)) {
      when(depth =/= maximum)(depth := depth + 1.U)
    }.elsewhen(io.kind === Itype.ExpIntReturn && depth =/= 0.U) {
      depth := depth - 1.U
    }
  }
}
