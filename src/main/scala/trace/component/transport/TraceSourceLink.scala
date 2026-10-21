package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.ResetCatchAndSync
import trace.interface.TraceBeat
import trace.interface.TraceControl

/** Actual encoder-to-funnel link, including the ordered flush frontier of the
  * complete-packet staging buffer after the asynchronous data queue.
  */
class TraceSourceLink(sourceId: Int) extends RawModule {
  val io = IO(new Bundle {
    val sourceClock     = Input(Clock())
    val sourceReset     = Input(AsyncReset())
    val sinkClock       = Input(Clock())
    val sinkReset       = Input(AsyncReset())
    val systemSinkReset = Input(AsyncReset())
    val in              = Flipped(Decoupled(new TraceBeat(4)))
    val out             = Decoupled(new TraceBeat(4))
    val sourceControl   = Flipped(new TraceControl)
    val empty           = Output(Bool())
    val control         = new TraceControl
  })
  val bridge = Module(new TraceAsyncBridge(new TraceBeat(4)))
  bridge.io.sourceClock := io.sourceClock
  bridge.io.sourceReset := io.sourceReset
  bridge.io.sinkClock   := io.sinkClock
  bridge.io.sinkReset   := io.sinkReset
  bridge.io.in <> io.in
  io.sourceControl <> bridge.io.sourceControl
  val stagingReset = ResetCatchAndSync(io.sinkClock, io.systemSinkReset.asBool).asAsyncReset
  val staging      = withClockAndReset(io.sinkClock, stagingReset)(Module(new SourcePacketStager(sourceId)))
  staging.io.in <> bridge.io.out
  staging.io.epochReset := bridge.io.sinkResetActive
  io.out <> staging.io.out
  bridge.io.control.flushReq := io.control.flushReq
  bridge.io.control.syncReq  := io.control.syncReq || staging.io.syncReq
  // An inactive/reset producer has no queue frontier, but must still return
  // ack low when req is withdrawn so a disabled Funnel can finish release.
  io.control.flushAck := staging.io.empty &&
    Mux(bridge.io.sinkResetActive, io.control.flushReq, bridge.io.control.flushAck)
  io.empty := staging.io.empty && !bridge.io.out.valid
}
