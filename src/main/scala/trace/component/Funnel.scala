package trace.component


import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import bundles.{CSRAddrWriteBundle, CSRModule}
import trace.{Control2BusRegBundle, TraceParameters}
import trace.control.FunnelControl
import trace.interface.{FromCore, Payload, ToCore}

import scala.collection.immutable.SeqMap


class Funnel(baseAddr: Int)(implicit p: Parameters) extends CompBase(baseAddr)
  with FunnelControl
{

  val io = IO(new Bundle {
    val packets = Input(Vec(hart_num, new Payload))
  })

  val bus = IO(new Control2BusRegBundle)

  /**
   * bus 2 control
   * Registers in the 4KB range that are not implemented are reserved and read as 0 and ignore writes.
   */
  // control write

  for ((id, (wBundle, _)) <- controlRWMap) {
    wBundle.wen := bus.b2c.writeEn && bus.b2c.address === (baseAddr + id).U
    wBundle.wdata := bus.b2c.dataWrite
  }
  // control read
  bus.c2b.dataRead := Mux1H(controlOutMap.map { case (id, data) =>
    (bus.b2c.readEn && bus.b2c.address === id.U) -> data
  })

  bus.c2b.error := false.B

}
