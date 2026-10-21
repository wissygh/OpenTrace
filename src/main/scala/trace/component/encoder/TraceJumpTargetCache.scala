package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters

/** E-Trace 3.2.7: direct mapped by the target's aligned address bits. */
class TraceJumpTargetCache extends Module with TraceParameters {
  val io = IO(new Bundle {
    val address = Input(UInt(iaddress_width_p.W))
    val update  = Input(Bool())
    val clear   = Input(Bool())
    val hit     = Output(Bool())
    val index   = Output(UInt(cache_size_p.W))
  })
  val valid   = RegInit(VecInit(Seq.fill(1 << cache_size_p)(false.B)))
  val entries = Reg(Vec(1 << cache_size_p, UInt(iaddress_width_p.W)))
  val index   = io.address(iaddress_lsb_p + cache_size_p - 1, iaddress_lsb_p)
  io.index := index
  io.hit   := valid(index) && entries(index) === io.address
  when(io.clear) {
    valid.foreach(_ := false.B)
  }.elsewhen(io.update) {
    valid(index)   := true.B
    entries(index) := io.address
  }
}
