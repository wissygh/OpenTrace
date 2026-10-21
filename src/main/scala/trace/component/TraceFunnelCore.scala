package trace.component

import chisel3._
import chisel3.util._
import trace.interface._

/** Whole-packet arbitration with a flush barrier across all upstream links. */
class TraceFunnelCore(numSources: Int, dataBytes: Int = 4) extends Module {
  val io = IO(new Bundle {
    val in            = Vec(numSources, Flipped(Decoupled(new TraceBeat(dataBytes))))
    val sourceControl = Vec(numSources, Flipped(new TraceControl))
    val out           = Decoupled(new TraceBeat(dataBytes))
    val control       = new TraceControl
    val enable        = Input(Bool())
    val disable       = Input(Vec(numSources, Bool()))
    val empty         = Output(Bool())
    val idle          = Output(Bool())
    val quiesce       = Input(Bool())
  })
  val arbiter    = Module(new PacketArbiter(numSources, dataBytes))
  val discarding = Wire(Vec(numSources, Bool()))
  val flushing   = io.control.flushReq
  arbiter.io.enable := (io.enable || flushing) && !io.quiesce
  for (i <- 0 until numSources) {
    // Table 50 requires disabled inputs to be consumed and discarded, rather
    // than backpressured. The decision holds through last in both directions:
    // neither a stalled offered packet nor a discarded tail may change paths.
    val forwarding = RegInit(false.B)
    val dropping   = RegInit(false.B)
    val discard    = dropping || (io.disable(i) && !forwarding && !io.quiesce)
    discarding(i)          := dropping
    arbiter.io.in(i).valid := io.in(i).valid && !discard
    arbiter.io.in(i).bits  := io.in(i).bits
    io.in(i).ready         := discard || arbiter.io.in(i).ready
    when(arbiter.io.selected(i) && arbiter.io.in(i).valid) {
      forwarding := !(arbiter.io.in(i).fire && io.in(i).bits.last)
    }
    when(io.in(i).fire && discard)(dropping := !io.in(i).bits.last)
    io.sourceControl(i).flushReq            := flushing
    io.sourceControl(i).syncReq             := RegNext(io.control.syncReq, false.B)
    // Completed flush inputs stop competing; software-disabled inputs still
    // discard their old frontier and never leak packets into the output.
    arbiter.io.disable(i) := flushing && io.sourceControl(i).flushAck
  }
  io.control.flushAck := reset.asBool || (flushing && io.idle && io.sourceControl.map(_.flushAck).reduce(_ && _))
  io.out <> arbiter.io.out
  io.idle  := arbiter.io.idle && !discarding.asUInt.orR
  io.empty := io.idle && !io.in.map(_.valid).reduce(_ || _)
}
