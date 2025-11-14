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

class CtrlAsyncSinkBundle extends Bundle with TraceParameters {
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
  val b2c = Input(new CtrlAsyncSinkBundle)
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
      supportsGet = TransferSizes(1, ctrlBeatBytes),
      supportsPutFull = TransferSizes(1, ctrlBeatBytes),
      fifoId = Some(0)
    )),
    ctrlBeatBytes
  )))
  tlslavenode := sinkNode

  class CtrlAsyncSinkImp(wrapper: CtrlAsyncSink)(implicit p: Parameters) extends LazyModuleImp(wrapper) {
    val io = IO(new Bundle {
      val bus    = Flipped(new Control2BusRegBundle)
      val active = Output(Bool())
    })

    /**
     * bus 2 trace control layer
     */
    val (tl, edge) = tlslavenode.in(0)
    val aChannel   = tl.a
    val dChannel   = tl.d

    val address   = aChannel.bits.address
    val readEn    = aChannel.bits.opcode === TLMessages.Get && aChannel.valid
    val dataWrite = aChannel.bits.data
    val writeEn   = aChannel.bits.opcode === TLMessages.PutFullData && aChannel.valid
    val size      = aChannel.bits.size
    val ackError  = (address(1, 0) =/= 0.U) || io.bus.c2b.error

    io.bus.b2c.address := address
    // bus write
    io.bus.b2c.writeEn   := writeEn
    io.bus.b2c.dataWrite := dataWrite
    // bus read, only support 32 bits!
    io.bus.b2c.readEn := readEn

    dChannel.bits := Mux1H(
      Seq(
        readEn,
        writeEn
      ),
      Seq(
        edge.AccessAck(aChannel.bits, io.bus.c2b.dataRead, ackError, false.B),
        edge.AccessAck(aChannel.bits, ackError)
      )
    )

    dChannel.valid := aChannel.valid
    aChannel.ready := true.B

    /**
     * write components.control.active
     * 1. reset: write 0
     * 2. release: write 1
     * 3. write other
    */
    val active = RegEnable(
      io.bus.b2c.dataWrite(0),
      false.B,
      io.bus.b2c.address === (baseAddr & ctrlMask).U && io.bus.b2c.writeEn
    )
    io.active := active
  }
  lazy val module = new CtrlAsyncSinkImp(this)
}
