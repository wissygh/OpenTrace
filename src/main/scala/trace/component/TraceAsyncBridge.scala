package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._
import trace.interface._

class TraceAsyncEntry[T <: Data](gen: T) extends Bundle {
  val marker = Bool()
  val data   = gen.cloneType
}

/** Ordered data/flush markers plus level-handshaken SYNCREQ events.
  * Resetting either endpoint resets the entire crossing; release is synchronized
  * independently to each clock. Reset is destructive; drain before power-off.
  */
class TraceAsyncBridge[T <: Data](gen: T, depth: Int = 16, stages: Int = 3) extends RawModule {
  val io = IO(new Bundle {
    val sourceClock     = Input(Clock())
    val sourceReset     = Input(AsyncReset())
    val sinkClock       = Input(Clock())
    val sinkReset       = Input(AsyncReset())
    val in              = Flipped(Decoupled(gen.cloneType))
    val sourceControl   = Flipped(new TraceControl)
    val out             = Decoupled(gen.cloneType)
    val sinkResetActive = Output(Bool())
    val control         = new TraceControl
  })
  val combinedReset = io.sourceReset.asBool || io.sinkReset.asBool
  val sourceReset   = ResetCatchAndSync(io.sourceClock, combinedReset, stages).asAsyncReset
  val sinkReset     = ResetCatchAndSync(io.sinkClock, combinedReset, stages).asAsyncReset
  io.sinkResetActive := sinkReset.asBool
  val queue = Module(new AsyncQueue(new TraceAsyncEntry(gen), AsyncQueueParams(depth, stages, safe = true)))
  queue.io.enq_clock := io.sourceClock
  queue.io.enq_reset := sourceReset.asBool
  queue.io.deq_clock := io.sinkClock
  queue.io.deq_reset := sinkReset.asBool

  val request     = Wire(Bool())
  val syncRequest = Wire(Bool())
  val syncAck     = Wire(Bool())
  withClockAndReset(io.sourceClock, sourceReset) {
    val flush     = AsyncResetSynchronizerShiftReg(request, stages)
    val flushSeen = RegNext(flush, false.B)
    val marked    = RegInit(false.B)
    val marker    = flush && flushSeen && io.sourceControl.flushAck && !marked
    io.sourceControl.flushReq := flush
    val sync = AsyncResetSynchronizerShiftReg(syncRequest, stages)
    io.sourceControl.syncReq                 := sync && !RegNext(sync, false.B)
    syncAck                                  := sync
    queue.io.enq.valid                       := marker || (io.in.valid && !marked)
    queue.io.enq.bits.marker                 := marker
    queue.io.enq.bits.data                   := io.in.bits
    io.in.ready                              := queue.io.enq.ready && !marker && !marked
    when(queue.io.enq.fire && marker)(marked := true.B)
    when(!flush)(marked                      := false.B)
  }
  withClockAndReset(io.sinkClock, sinkReset) {
    // Register the request before it crosses; no pulse is sampled directly.
    request := RegNext(io.control.flushReq, false.B)
    val pending      = RegInit(false.B)
    val queued       = RegInit(false.B)
    val acknowledged = AsyncResetSynchronizerShiftReg(syncAck, stages)
    // Retain a new pulse even during the return-to-zero acknowledgment phase.
    // Only requests still waiting for delivery may be coalesced (§4.4).
    when(!pending && !acknowledged && queued) { pending := true.B; queued := false.B }
    when(pending && acknowledged)(pending := false.B)
    when(io.control.syncReq)(queued       := true.B)
    syncRequest                           := pending
    val done  = RegInit(false.B)
    val entry = queue.io.deq
    io.out.valid := entry.valid && !entry.bits.marker && !done
    io.out.bits  := entry.bits.data
    entry.ready  := Mux(entry.bits.marker, io.control.flushReq && !done, io.out.ready && !done)
    when(entry.fire && entry.bits.marker)(done := true.B)
    when(!io.control.flushReq)(done            := false.B)
    io.control.flushAck                        := sinkReset.asBool || done
  }
}
