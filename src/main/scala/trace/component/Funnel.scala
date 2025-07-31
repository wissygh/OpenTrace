package trace.component


import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import bundles.{CSRAddrWriteBundle, CSRModule}
import trace.{Control2BusRegBundle, TraceParameters}
import trace.control.FunnelControl
import trace.interface.{FromCore, Payload, ToCore}

import scala.collection.immutable.SeqMap

class Funnel(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new FunnelImp(this)(baseAddr)
}

class FunnelImp(wrapper: Funnel)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
  with FunnelControl
{

  val io = IO(new Bundle {
    val packets = Input(Vec(hart_num, new Payload))
  })

  connectCtrlReg()
}
