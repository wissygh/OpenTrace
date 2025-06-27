package trace

import chisel3._
import chisel3.util._
import trace.component._
import trace.control._
import trace.interface._
import bundles._
import chisel3.experimental.dataview._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import utils._

import scala.collection.immutable.SeqMap

class Trace(busType: BusType = BusType.TLbus)(implicit p: Parameters) extends LazyModule
  with TraceParameters
{
  val bus2Control = LazyModule(new Bus2Control(busType))

  val tl = Option.when(busType.isTL)(TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1("tl", IdRange(0, 1)))
  ))))
  val tlnode = tl.map(tl => InModuleBody(tl.makeIOs()))

  val axi4 = Option.when(busType.isAXI)(AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters("axi4", IdRange(0, 1)))
  ))))
  val axi4node = axi4.map(axi4 => InModuleBody {
    val axi4node = chisel3.IO(Flipped(new VerilogAXI4Record(axi4.out.head._1.params)))
    axi4node.viewAs[AXI4Bundle] <> axi4.out.head._1
    axi4node
  })

  bus2Control.tlnode.foreach(_ <> tlnode.get)
//  bus2Control.xbar.foreach(_ := tlnode.get)
  bus2Control.axi4node.foreach(_ <> axi4node.get)



  class TraceImp(wrapper: Trace)(implicit p: Parameters) extends LazyModuleImp(wrapper) {
    val io = IO(new Bundle {
      val fromCore = Input(Vec(hart_num, new FromCore))
      val toCore   = Output(Vec(hart_num, new ToCore))
    })

    /**
     * io
     */
    val (fromCore, toCore) = (io.fromCore, io.toCore)


    /**
     * module: [[bus2ctrl]] [[encoderVec]] [[funnel]]
     */
    val encoderVec = Range(0, hart_num).map(i => Module(new Encoder))
    val bus2ctrl = bus2Control.module
    val funnel = Module(new Funnel)

    /**
     * connection
     *
     * 1. data path(message stream):
     *         [ingressPort]                      [packets]
     * hart ------------------> [[encoderVec]] ---------------> [[funnel]]
     *
     * 2. control path:
     *  write:
     *                   [bus write req]           [req/wtrite data]
     *  bus(axi/tl/apb) ----------------> [[bus2ctrl]] ----------------> all component: [[encoderVec]] [[funnel]]
     *
     *  read:
     *                   [bus read req]               [read req]
     *  bus(axi/tl/apb) ----------------> [[bus2ctrl]] ----------------> all component: [[encoderVec]] [[funnel]]
     *             \---- [bus read req] <----------- [read data] <-------/
     */
    encoderVec.zip(fromCore).zip(funnel.io.packets).map{ case((encoder, ingressPort), packet) => {
      encoder.io.bus.b2c := bus2ctrl.io.b2c
      toCore := encoder.io.toCore

      encoder.io.fromCore := ingressPort
      packet := encoder.io.packet
    }}

    val compControl2Bus = (encoderVec.map(_.io.bus) ++ Seq(funnel.io.bus)).map(_.c2b)
    bus2ctrl.io.c2b.error := compControl2Bus.map(_.error).reduce(_ | _)
    bus2ctrl.io.c2b.dataRead := compControl2Bus.map(_.dataRead).reduce(_ | _)
  }

  lazy val module = new TraceImp(this)
}
