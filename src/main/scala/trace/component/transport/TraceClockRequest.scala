package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._

/** Optional source-clock request to an always-on clock manager. This is not
  * ATWAKEUP: that standard signal remains registered in the external ATCLK.
  * The manager synchronizes this level, opens the destination clock, and keeps
  * it running until this request and its own local work are both quiescent.
  */
class TraceClockRequest(depth: Int = 16, stages: Int = 3) extends RawModule {
  require(depth >= 2 && isPow2(depth) && stages >= 2)
  // Include return-synchronizer lag as well as maximum queued beats. A very
  // shallow queue or unusually long synchronizer must not alias stale counts.
  private val bits = log2Ceil(depth + stages + 2) + 1
  val io = IO(new Bundle {
    val sourceClock   = Input(Clock())
    val sourceReset   = Input(AsyncReset())
    val sinkClock     = Input(Clock())
    val sinkReset     = Input(AsyncReset())
    val accepted      = Input(Bool())
    val delivered     = Input(Bool())
    val sourcePending = Input(Bool())
    val request       = Output(Bool())
  })
  val combinedReset = io.sourceReset.asBool || io.sinkReset.asBool
  val sourceReset   = ResetCatchAndSync(io.sourceClock, combinedReset, stages).asAsyncReset
  val sinkReset     = ResetCatchAndSync(io.sinkClock, combinedReset, stages).asAsyncReset
  val deliveredGray = Wire(UInt(bits.W))
  withClockAndReset(io.sinkClock, sinkReset) {
    val binary = RegInit(0.U(bits.W))
    val gray   = RegInit(0.U(bits.W))
    val next   = binary + io.delivered.asUInt
    binary        := next
    gray          := next ^ (next >> 1)
    deliveredGray := gray
  }
  withClockAndReset(io.sourceClock, sourceReset) {
    val binary   = RegInit(0.U(bits.W))
    val next     = binary + io.accepted.asUInt
    val nextGray = next ^ (next >> 1)
    binary := next
    val delivered = AsyncResetSynchronizerShiftReg(deliveredGray, stages)
    val request   = RegInit(false.B)
    request    := io.sourcePending || nextGray =/= delivered
    io.request := request
  }
}
