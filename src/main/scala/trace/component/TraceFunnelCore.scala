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
  val arbiter = Module(new PacketArbiter(numSources, dataBytes))
  arbiter.io.in <> io.in
  val flushing = io.control.flushReq
  arbiter.io.enable := (io.enable || flushing) && !io.quiesce
  // Drain buffered disabled inputs too; completed inputs stop competing (§4.2).
  for (i <- 0 until numSources) {
    io.sourceControl(i).flushReq := flushing
    io.sourceControl(i).syncReq  := RegNext(io.control.syncReq, false.B)
    arbiter.io.disable(i)        := Mux(flushing, io.sourceControl(i).flushAck, io.disable(i))
  }
  io.control.flushAck := reset.asBool || (flushing && io.sourceControl.map(_.flushAck).reduce(_ && _))
  io.out <> arbiter.io.out
  io.empty := arbiter.io.idle && !io.in.map(_.valid).reduce(_ || _)
  io.idle  := arbiter.io.idle
}
