package trace

import chisel3._
import chisel3.util._
import trace.component.{ComponentBase, _}
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

class Trace(busType: BusType = BusType.TLbus, xs_socmisc: Boolean = false)(implicit p: Parameters) extends LazyModule
  with TraceParameters
{
  val bus2Control = LazyModule(new Bus2Control(busType))

  /**
   * tilelink
   */
  val tlmatser = Option.when(busType.isTL && !xs_socmisc)(TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "tl",
      sourceId = IdRange(0, 1)
    ))
  ))))
  val tlmasternode = tlmatser.map(tl => InModuleBody(tl.makeIOs()))
  tlmatser.foreach(bus2Control.tlslavenode.get := TLFragmenter(tlSizeMin, tlSizeMax) :=  TLWidthWidget(tlSizeMax) := _)

  val tlnode = Option.when(busType.isTL && xs_socmisc)(TLIdentityNode())
  tlnode.foreach(bus2Control.tlslavenode.get := TLFragmenter(tlSizeMin, tlSizeMax) :=  TLWidthWidget(tlSizeMax) := _)

  /**
   * axi todo
   */
  val axi4 = Option.when(busType.isAXI && !xs_socmisc)(AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters("axi4", IdRange(0, 1)))
  ))))
  val axi4node = axi4.map(axi4 => InModuleBody {
    val axi4node = chisel3.IO(Flipped(new VerilogAXI4Record(axi4.out.head._1.params)))
    axi4node.viewAs[AXI4Bundle] <> axi4.out.head._1
    axi4node
  })

  /**
   * apb todo
   */
  val apb = Option.when(busType.isAPB && !xs_socmisc)(AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters("axi4", IdRange(0, 1)))
  ))))
  val apbnode = apb.map(axi4 => InModuleBody {
    val axi4node = chisel3.IO(Flipped(new VerilogAXI4Record(axi4.out.head._1.params)))
    axi4node.viewAs[AXI4Bundle] <> axi4.out.head._1
    axi4node
  })


  class TraceImp(wrapper: Trace)(implicit p: Parameters) extends LazyModuleImp(wrapper) with RequireAsyncReset {
    val io = IO(new Bundle {
      val fromCore = Input(Vec(hart_num, new FromCore))
      val extTrigIn = Input(Vec(extTrig_num, Bool()))
      val extTrigOut = Output(Vec(extTrig_num, Bool()))
      val toCore = Output(Vec(hart_num, new ToCore))
    })

    /**
     * io
     */
    val (fromCore, toCore) = (io.fromCore, io.toCore)

    /**
     * module: [[bus2ctrl]] [[encoderVec]] [[funnel]] [[pib]]
     */
    val bus2ctrl = bus2Control.module
    val encoderVec = (0 until hart_num).map(i => Module(new Encoder(encoderBaseAddr + memStep * i)))
    val funnel = Module(new Funnel(funnelBaseAddr))
    val pib = Module(new PIB(pibBaseAddr))

    /**
     * data path(message stream):
     *         [ingressPort]                      [packets]
     * hart ------------------> [[encoderVec]] ---------------> [[funnel]]
     */
    encoderVec.zip(fromCore).zip(toCore).map{ case((encoder, ingressPort), toCore) => {
      toCore := encoder.io.toCore
      encoder.io.fromCore := ingressPort
      encoder.io.extTrigIn := io.extTrigIn
    }}
    encoderVec.zip(funnel.io.packets).map{ case (encoder, packet) => packet := encoder.io.packet }

    io.extTrigOut := encoderVec.map(_.io.extTrigOut.asUInt).reduce(_ | _).asBools

    /**
     * control path:
     *  write:
     *                   [bus write req]           [req/wtrite data]
     *  bus(axi/tl/apb) ----------------> [[bus2ctrl]] ----------------> all component: [[encoderVec]] [[funnel]]
     *
     *  read:
     *                   [bus read req]               [read req]
     *  bus(axi/tl/apb) ----------------> [[bus2ctrl]] ----------------> all component: [[encoderVec]] [[funnel]]
     *             \---- [bus read req] <----------- [read data] <-------/
     */
    val components: Seq[ComponentBase] = (encoderVec.toSeq ++ Seq(funnel, pib))
    components.map(comp => comp.bus.b2c := bus2ctrl.io.b2c)
    bus2ctrl.io.c2b.error := components.map(_.bus.c2b.error).reduce(_ | _)
    bus2ctrl.io.c2b.dataRead := components.map(_.bus.c2b.dataRead).reduce(_ | _)
  }

  lazy val module = new TraceImp(this)
}

