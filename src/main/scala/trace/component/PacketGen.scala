package trace.component

import cats.syntax.validated
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters
import trace.interface._
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper

class EncoderCtrlInterface extends Bundle with TraceParameters {
  val srcid   = UInt(src_bits_p.W)
  val tracing = Bool()
}

class PacketGen extends Module with TraceParameters {

  val io = IO(new Bundle {
    val fromCore = Input(new FromCore)
    val fromCtrl = Input(new EncoderCtrlInterface)
    val packet   = DecoupledIO(new PacketEnq(maxPackets))
  })
  val fromCore = io.fromCore
  val (priv, cause, scontext, hcontext, time, tval, impdef, trigger, blocks) =
    (
      fromCore.priv,
      fromCore.cause,
      fromCore.scontext,
      fromCore.hcontext,
      fromCore.time,
      fromCore.tval,
      fromCore.impdef,
      fromCore.trigger,
      fromCore.blocks
    )

  val block0   = blocks(0)
  val validVec = blocks.map(_.valid)
  val itypeVec = blocks.map(_.bits.itype)
  val isBrhVec = itypeVec.map(it => Itype.isBranchType(it))

  /**
    * state machine
    */
  val start = !RegNext(io.fromCtrl.tracing) && io.fromCtrl.tracing
  val end   = RegNext(io.fromCtrl.tracing) && !io.fromCtrl.tracing

  val s_idle :: s_trace :: Nil = Enum(2)
  val state                    = RegInit(s_idle)
  val stateNext                = WireInit(state)

  state := stateNext
  switch(state) {
    is(s_idle) {
      when(start) {
        stateNext := s_trace
      }
    }

    is(s_trace) {
      when(end) {
        stateNext := s_idle
      }
    }
  }

  /**
 * Format3 Payload
 * 30: Synchronisation, Start of tracing, or resync.
 * 31: Trap, Exception or interrupt cause and trap handler address.
 * 32: Context, Context change.
 * 33: Support, Supporting information for the decoder.
 * 
 * Format2
 *  differential address and no branch information.
 * 
 * Format1
 *  includes branch information and may include differential address.
 * 
 * Format0
 *  00: no address; 00 address
 *  01: no branchmap; branchmap
 */

  /**
   * start
   */

  /**
    * normal
    */

  /**
    * end
    */

  /**
   * packet gen todo: delete
   */
  io.packet.valid     := true.B
  io.packet.bits.len  := 9.U
  io.packet.bits.data := VecInit(Seq(9.U(8.W)) ++ Seq.fill(maxPackets - 1)(0.U(8.W)))
}
