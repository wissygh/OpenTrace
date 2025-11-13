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

class CtrlSource(implicit p: Parameters) extends LazyModule with TraceParameters {
  val sourceNode = TLAsyncCrossingSource()

  class CtrlSourceImp(wrapper: CtrlSource)(implicit p: Parameters) extends LazyModuleImp(wrapper)
      with RequireAsyncReset {}

  lazy val module = new CtrlSourceImp(this)
}

class Bus2Control(baseAddr: Int)(implicit p: Parameters) extends LazyModule with TraceParameters {
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

  class Bus2ControlImp(wrapper: Bus2Control)(implicit p: Parameters) extends LazyModuleImp(wrapper) {
    val io = IO(Flipped(new Control2BusRegBundle))

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
    val ackError  = (address(1, 0) =/= 0.U) || io.c2b.error

    io.b2c.address := address
    // bus write
    io.b2c.writeEn   := writeEn
    io.b2c.dataWrite := dataWrite
    // bus read, only support 32 bits!
    io.b2c.readEn := readEn

    dChannel.bits := Mux1H(
      Seq(
        readEn,
        writeEn
      ),
      Seq(
        edge.AccessAck(aChannel.bits, io.c2b.dataRead, ackError, false.B),
        edge.AccessAck(aChannel.bits, ackError)
      )
    )

    dChannel.valid := aChannel.valid
    aChannel.ready := true.B
  }

  lazy val module = new Bus2ControlImp(this)
}
