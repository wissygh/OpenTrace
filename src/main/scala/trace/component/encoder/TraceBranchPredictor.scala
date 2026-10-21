package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters

object TraceBranchPredictor {
  val maximumCorrectBranches: BigInt = (BigInt(1) << 32) + 30
  def countAfter(current:   UInt, correct: Bool): UInt = current + correct.asUInt
  def encodedCount(correct: UInt): UInt = (correct - 31.U)(31, 0)
  def atMaximum(correct:    UInt): Bool = correct === maximumCorrectBranches.U
}

/** E-Trace 3.2.6: the MSB predicts, the LSB remembers the last outcome. */
class TraceBranchPredictor extends Module with TraceParameters {
  val io = IO(new Bundle {
    val address = Input(UInt(iaddress_width_p.W))
    val taken   = Input(Bool())
    val update  = Input(Bool())
    val clear   = Input(Bool())
    val correct = Output(Bool())
  })
  val states = RegInit(VecInit(Seq.fill(1 << bpred_size_p)(1.U(2.W))))
  val index  = io.address(iaddress_lsb_p + bpred_size_p - 1, iaddress_lsb_p)
  val state  = states(index)
  io.correct := state(1) === io.taken
  when(io.clear) {
    states.foreach(_ := 1.U)
  }.elsewhen(io.update) {
    states(index) := Cat(Mux(state(0) === io.taken, io.taken, state(1)), io.taken)
  }
}
