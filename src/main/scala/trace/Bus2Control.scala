package trace

import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters
import utils._

class Bus2ControlBundle extends Bundle with TraceParameters {
  val address   = UInt(ctrlAddrWidthInner.W)
  val dataWrite = UInt(ctrlDataSize.W)
  //  val dataSize   = UInt(2.W) // 0->32bits, 1->64bits, 2->128bits, 3->256bits todo: support 256 bit mem access
  val readEn  = Bool()
  val writeEn = Bool()
}

class Control2BusBundle extends Bundle with TraceParameters {
  val dataRead = UInt(ctrlDataSize.W)
  val error    = Bool() // reserved
}

class Control2BusRegBundle extends Bundle {
  val b2c = Input(new Bus2ControlBundle)
  val c2b = Output(new Control2BusBundle)
}

class CtrlAsyncSource(implicit p: Parameters) extends LazyModule with TraceParameters {
  val sourceNode = TLAsyncCrossingSource()

  class CtrlAsyncSourceImp(wrapper: CtrlAsyncSource)(implicit p: Parameters) extends LazyModuleImp(wrapper)
      with RequireAsyncReset {}

  lazy val module = new CtrlAsyncSourceImp(this)
}

class CtrlAsyncSink(baseAddr: Int)(implicit p: Parameters) extends LazyModule with TraceParameters {
  val sinkNode = TLAsyncCrossingSink(ctrlAsyncBridge)
  val tlslavenode = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLManagerParameters(
      address = Seq(AddressSet(baseAddr, 0xffff)),
      regionType = RegionType.UNCACHED,
      supportsGet = TransferSizes(ctrlBeatBytes, ctrlBeatBytes),
      supportsPutFull = TransferSizes(ctrlBeatBytes, ctrlBeatBytes),
      fifoId = Some(0)
    )),
    ctrlBeatBytes
  )))
  tlslavenode := sinkNode

  class CtrlAsyncSinkImp(wrapper: CtrlAsyncSink)(implicit p: Parameters) extends LazyModuleImp(wrapper) {
    val io = IO(new Bundle {
      val bus    = Flipped(new Control2BusRegBundle)
      val active = Output(Bool())
      val componentReset = Output(AsyncReset())
    })

    val (tl, _) = tlslavenode.in(0)
    val endpoint = Module(new trace.control.TraceControlEndpoint(baseAddr, tl.params))
    endpoint.io.a <> tl.a
    tl.d <> endpoint.io.d
    io.bus <> endpoint.io.bus
    io.active := endpoint.io.active
    io.componentReset := endpoint.io.componentReset

  }
  lazy val module = new CtrlAsyncSinkImp(this)
}
