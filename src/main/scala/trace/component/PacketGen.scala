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
  val srcid = UInt(src_bits_p.W)
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

  val block0 = fromCore.blocks(0)

  /**
   * packet gen
   */
  io.packet.valid     := true.B
  io.packet.bits.len  := 9.U
  io.packet.bits.data := VecInit(Seq(9.U(8.W)) ++ Seq.fill(maxPackets - 1)(0.U(8.W)))
}
