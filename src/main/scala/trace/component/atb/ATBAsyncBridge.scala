package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._
import trace.interface._

/** Standard ATB endpoints on unrelated clocks. Flush markers never reach ATDATA. */
class ATBAsyncBridge(dataBytes: Int = 4, depth: Int = 16, exposeClockRequest: Boolean = false) extends RawModule {
  val io = IO(new Bundle {
    val sourceClock        = Input(Clock())
    val sourceReset        = Input(AsyncReset())
    val sinkClock          = Input(Clock())
    val sinkReset          = Input(AsyncReset())
    val slave              = new ATBSlave(dataBytes)
    val master             = new ATBMaster(dataBytes)
    val sourceClockRequest = Option.when(exposeClockRequest)(Output(Bool()))
  })
  val bridge = Module(new TraceAsyncBridge(new ATData(dataBytes), depth))
  bridge.io.sourceClock := io.sourceClock
  bridge.io.sourceReset := io.sourceReset
  bridge.io.sinkClock   := io.sinkClock
  bridge.io.sinkReset   := io.sinkReset
  val commonReset = io.sourceReset.asBool || io.sinkReset.asBool
  val sourceReset = ResetCatchAndSync(io.sourceClock, commonReset).asAsyncReset
  val sinkReset   = ResetCatchAndSync(io.sinkClock, commonReset).asAsyncReset
  if (exposeClockRequest) {
    val wake = Module(new TraceClockRequest(depth))
    wake.io.sourceClock       := io.sourceClock
    wake.io.sourceReset       := io.sourceReset
    wake.io.sinkClock         := io.sinkClock
    wake.io.sinkReset         := io.sinkReset
    wake.io.accepted          := bridge.io.in.fire
    wake.io.delivered         := bridge.io.out.fire
    wake.io.sourcePending     := io.slave.at.valid || io.slave.wakeup || bridge.io.sourceControl.flushReq
    io.sourceClockRequest.get := wake.io.request
  }
  bridge.io.in.valid := io.slave.at.valid && io.slave.atclken
  bridge.io.in.bits  := io.slave.at.bits
  io.slave.at.ready  := bridge.io.in.ready && io.slave.atclken
  withClockAndReset(io.sourceClock, sourceReset) {
    val done = RegInit(false.B)
    io.slave.af.valid                               := bridge.io.sourceControl.flushReq && !done
    io.slave.af.bits                                := 0.U.asTypeOf(io.slave.af.bits)
    when(io.slave.atclken && io.slave.af.fire)(done := true.B)
    when(!bridge.io.sourceControl.flushReq)(done    := false.B)
    bridge.io.sourceControl.flushAck                := done
    // Preserve the event if ATCLKEN is low in the receiving source domain.
    val syncPending = RegInit(false.B)
    io.slave.syncReq                                  := syncPending && io.slave.atclken
    when(io.slave.atclken)(syncPending                := false.B)
    when(bridge.io.sourceControl.syncReq)(syncPending := true.B)
  }
  io.master.at.valid  := bridge.io.out.valid
  io.master.at.bits   := bridge.io.out.bits
  bridge.io.out.ready := io.master.at.ready && io.master.atclken
  withClockAndReset(io.sinkClock, sinkReset) {
    val flush = Module(new ATBFlushController)
    flush.io.request           := io.master.af.valid
    flush.io.clockEnable       := io.master.atclken
    flush.io.available         := true.B
    flush.io.upstreamAck       := bridge.io.control.flushAck
    flush.io.localDone         := true.B
    bridge.io.control.flushReq := flush.io.upstreamReq
    io.master.af.ready         := flush.io.ready
    bridge.io.control.syncReq  := io.master.syncReq && io.master.atclken
    val wakeup = RegNext(bridge.io.out.valid || flush.io.active || io.master.af.valid, false.B)
    io.master.wakeup := wakeup
  }
}
