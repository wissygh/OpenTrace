package trace.component


import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import bundles.{CSRAddrWriteBundle, CSRModule}
import trace.{Control2BusRegBundle, TraceParameters}
import trace.control.ControlBase
import trace.interface.{FromCore, Payload, ToCore}

import scala.collection.immutable.SeqMap

class ComponentBase(baseAddr: Int)(implicit p: Parameters) extends LazyModule with TraceParameters {
  lazy val module = new ComponentBaseImp(this)(baseAddr)
}

class ComponentBaseImp(wrapper: ComponentBase)(baseAddr: Int)(implicit p: Parameters) extends LazyModuleImp(wrapper)
  with ControlBase
  with TraceParameters {

  val bus = IO(new Control2BusRegBundle)

  /**
   * bus 2 control reg
   * Registers in the 4KB range that are not implemented are reserved and read as 0 and ignore writes.
   */
  def connectCtrlReg() = {
    // control write
    for ((id, (wBundle, _)) <- controlRWMap) {
      wBundle.wen := bus.b2c.writeEn && bus.b2c.address === (((baseAddr & mask) + id)).U
      wBundle.wdata := bus.b2c.dataWrite
    }
    // control read
    bus.c2b.dataRead := Mux1H(controlRWMap.map { case (id, (_, rdata)) =>
      (bus.b2c.readEn && bus.b2c.address === ((baseAddr & mask) + id).U) -> rdata
    })
    bus.c2b.error := false.B
  }
}

