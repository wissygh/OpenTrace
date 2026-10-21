package trace.component

import chisel3._
import chisel3.util._

/** Serialize software Disable drains and downstream flush barriers. A Disable
  * arriving during another frontier needs a fresh marker after its release.
  */
class FunnelFlushController extends Module {
  val io = IO(new Bundle {
    val disableEvent = Input(Bool())
    val request      = Input(Bool())
    val complete     = Input(Bool())
    val released     = Input(Bool())
    val upstreamReq  = Output(Bool())
    val ack          = Output(Bool())
    val busy         = Output(Bool())
  })
  val idle :: automatic :: external :: releasing :: Nil = Enum(4)
  val state                                             = RegInit(idle)
  val pending                                           = RegInit(false.B)
  when(state === idle) {
    when(pending) { state := automatic; pending := false.B }
      .elsewhen(io.request)(state := external)
  }
  when(state === automatic && io.complete)(state               := releasing)
  when(state === external && io.complete && !io.request)(state := releasing)
  when(state === releasing && io.released)(state               := idle)
  // Keep a newly arriving Disable even on the edge that starts another drain.
  when(io.disableEvent)(pending := true.B)
  io.upstreamReq                := state === automatic || state === external
  io.ack                        := state === external && io.request && io.complete
  io.busy                       := state =/= idle || pending || io.disableEvent || io.request
}
