package trace

import chisel3._
import chisel3.experimental.dataview._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

import utils._



class Bus2ControlBundle extends Bundle {
  val address    = UInt(64.W)
  val dataWrite  = UInt(32.W)
  //  val dataSize   = UInt(2.W) // 0->32bits, 1->64bits, 2->128bits, 3->256bits todo: support 256 bit mem access
  val readEn     = Bool()
  val writeEn    = Bool()
}

class Control2BusBundle extends Bundle {
  val dataRead   = UInt(32.W)
  val error      = Bool() // reserved
}

class Control2BusRegBundle extends Bundle {
  val b2c = Input(new Bus2ControlBundle)
  val c2b = Output(new Control2BusBundle)
}

//val io = IO(new Bundle {
//  val rdEn = Input(Bool())
//  val wrEn = Input(Bool())
//  val addrIn = Input(UInt(128.W))
//  val dataIn = Input(UInt(128.W))
//  val sizeIn = Input(UInt(3.W))
//  val rdLegal = Output(Bool())
//  val wrLegal = Output(Bool())
//  val rdDone = Output(Bool())
//  val wrDone = Output(Bool())
//  val respError = Output(Bool())
//  val dataOut = Output(UInt(8.W))
//}


class Bus2Control(busType: BusType = BusType.TLbus)(implicit p: Parameters) extends LazyModule {

  val tl = Option.when(busType.isTL)(TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1("tl", IdRange(0, 1)))
  ))))
  val tlnode = tl.map(tl => InModuleBody(tl.makeIOs()))

//  val xbar = Option.when(busType.isTL)(TLXbar())
//  xbar.foreach(_ := tlnode.get)


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
        val address    = Reg(UInt(64.W))
        val dataWrite  = Reg(UInt(32.W))
        val readEn     = RegInit(false.B)
        val writeEn    = RegInit(false.B)

        val aChannel = wrapper.tlnode.get.head.a
        val dChannel = wrapper.tlnode.get.head.d

        address   := aChannel.bits.address
        writeEn   := aChannel.bits.opcode === TLMessages.Get && aChannel.valid
        dataWrite := aChannel.bits.data
        readEn    := aChannel.bits.opcode === TLMessages.PutFullData && aChannel.valid

        io.b2c.address := address
        // bus write
        io.b2c.writeEn   := writeEn
        io.b2c.dataWrite := dataWrite
        // bus read
        io.b2c.readEn   := readEn
        dChannel.bits.data := RegNext(io.c2b.dataRead)
        dChannel.valid := RegNext(readEn)
        dChannel.bits.corrupt := RegNext(address(1, 0) =/= 0.U) || io.c2b.error //todo: check msb of addr

        aChannel.ready := true.B
      } else if(busType.isAXI) {
        // todo axi 2 control

      }
  }

  lazy val module = new Bus2ControlImp(this)
}
