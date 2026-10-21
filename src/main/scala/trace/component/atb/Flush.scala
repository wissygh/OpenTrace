package trace.component

import chisel3._
import chisel3.util._

/** IHI0032C AF handshake, backed by an internal four-phase flush barrier.
  * A held AFVALID starts another flush after the preceding barrier releases.
  * Completion can retire even if a downstream reset removed AFVALID.
  */
class ATBFlushController extends Module {
  val io = IO(new Bundle {
    val request     = Input(Bool())
    val clockEnable = Input(Bool())
    val available   = Input(Bool())
    val upstreamAck = Input(Bool())
    val localDone   = Input(Bool())
    val upstreamReq = Output(Bool())
    val ready       = Output(Bool())
    val active      = Output(Bool())
  })
  val idle :: flushing :: release :: Nil = Enum(3)
  val state                              = RegInit(idle)
  io.upstreamReq := state === flushing
  io.ready := reset.asBool || (state === idle && !io.available) ||
    (state === flushing && io.upstreamAck && io.localDone)
  io.active := state =/= idle
  when(io.clockEnable) {
    switch(state) {
      is(idle)(when(io.request && io.available)(state := flushing))
      is(flushing)(when(io.ready)(state := release))
      is(release)(when(!io.upstreamAck)(state := idle))
    }
  }
}
