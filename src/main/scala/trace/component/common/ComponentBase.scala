package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.experimental.hierarchy.public
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters
import scala.collection.immutable.SeqMap
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.control.ControlBase
import trace.interface.FromCore
import trace.interface.ToCore

class ComponentBase(baseAddr: Int)(implicit p: Parameters) extends LazyModule with TraceParameters {

  override def shouldBeInlined: Boolean = false

  val compBaseAddress = baseAddr
  lazy val module     = new ComponentBaseImp(this)(baseAddr)
}

class ComponentBaseImp(wrapper: ComponentBase)(baseAddr: Int)(implicit p: Parameters) extends LazyModuleImp(wrapper)
    with RequireAsyncReset
    with ControlBase
    with TraceParameters {

  val compBaseAddress = baseAddr

  val bus = IO(new Control2BusRegBundle)

  /**
   * bus 2 control reg
   * Registers in the 4KB range that are not implemented are reserved and read as 0 and ignore writes.
   */
  def connectCtrlReg() = {
    // control write
    for ((id, (wBundle, _)) <- controlRWMap) {
      wBundle.wen   := bus.b2c.writeEn && bus.b2c.address === ((baseAddr & ctrlMask) + id).U
      wBundle.wdata := bus.b2c.dataWrite
    }
    // control read
    bus.c2b.dataRead := Mux1H(controlRWMap.map { case (id, (_, rdata)) =>
      (bus.b2c.readEn && bus.b2c.address === ((baseAddr & ctrlMask) + id).U) -> rdata
    })
    bus.c2b.error := false.B
  }
}
