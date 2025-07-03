package trace

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

import utils._

class Bus2ControlBundle extends Bundle with TraceParameters {
  val address    = UInt(addrWidthInner.W)
  val dataWrite  = UInt(dataSize.W)
  //  val dataSize   = UInt(2.W) // 0->32bits, 1->64bits, 2->128bits, 3->256bits todo: support 256 bit mem access
  val readEn     = Bool()
  val writeEn    = Bool()
}

class Control2BusBundle extends Bundle with TraceParameters {
  val dataRead   = UInt(dataSize.W)
  val error      = Bool() // reserved
}

class Control2BusRegBundle extends Bundle {
  val b2c = Input(new Bus2ControlBundle)
  val c2b = Output(new Control2BusBundle)
}

class Bus2Control(busType: BusType = BusType.TLbus)(implicit p: Parameters) extends LazyModule with TraceParameters {

  def beatBytes = dataSize / 8
  val tlslavenode = Option.when(busType.isTL)(TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLManagerParameters(
    address = Seq(addressSet),
    regionType = RegionType.UNCACHED,
    supportsGet = TransferSizes(1, beatBytes),
    supportsPutFull = TransferSizes(1, beatBytes),
    fifoId = Some(0)
  )), beatBytes))))

  val axi4 = Option.when(busType.isAXI)(AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters("axi4", IdRange(0, 1)))
  ))))
  val axi4node = axi4.map(axi4 => InModuleBody {
    val axi4node = chisel3.IO(Flipped(new VerilogAXI4Record(axi4.out.head._1.params)))
    axi4node.viewAs[AXI4Bundle] <> axi4.out.head._1
    axi4node
  })

  class Bus2ControlImp(wrapper: Bus2Control)(implicit p: Parameters) extends LazyModuleImp(wrapper){
    val io = IO(Flipped(new Control2BusRegBundle))
    /**
     * bus 2 trace control layer
     */
      if(busType.isTL){

        val (tl, edge) = tlslavenode.get.in(0)
        val aChannel = tl.a
        val dChannel = tl.d

        val address   = aChannel.bits.address
        val readEn    = aChannel.bits.opcode === TLMessages.Get && aChannel.valid
        val dataWrite = aChannel.bits.data
        val writeEn   = aChannel.bits.opcode === TLMessages.PutFullData && aChannel.valid
        val size      = aChannel.bits.size

        io.b2c.address := address
        // bus write
        io.b2c.writeEn   := writeEn
        io.b2c.dataWrite := dataWrite
        // bus read, only support 32 bits!
        io.b2c.readEn   := readEn
        dChannel.bits := Mux1H(
          Seq(
            readEn,
            writeEn
          ),
          Seq(
            edge.AccessAck(aChannel.bits, io.c2b.dataRead, false.B, ((address(1, 0) =/= 0.U) || (size =/= tlSize.U) || io.c2b.error)),
            edge.AccessAck(aChannel.bits)
          )
        )

        dChannel.valid := aChannel.valid
        aChannel.ready := true.B
      } else if(busType.isAXI) {
        // todo axi 2 control

      }
  }

  lazy val module = new Bus2ControlImp(this)
}
