package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import bundles.{CSRAddrWriteBundle, CSRModule}
import trace.{Control2BusRegBundle, TraceParameters}
import trace.control.EncoderControl
import trace.interface.{FromCore, Payload, ToCore}

import scala.collection.immutable.SeqMap

class Encoder(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr)
  with EncoderControl
{
   val io = IO(new Bundle {
    val fromCore = Input(new FromCore)
    val toCore   = Output(new ToCore)
    val packet  = Output(new Payload)
  })

  io.toCore.stall := 0.U
  io.toCore.enable := 0.U
  io.packet.format := 0.U

  connectCtrlReg()
}
