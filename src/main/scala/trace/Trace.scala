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

  val encoderVec = (0 until hart_num).map(i => LazyModule(new Encoder(encoderBaseAddr + memStep * i)))
  val funnel = LazyModule(new Funnel(funnelBaseAddr))
  val pib = LazyModule(new PIB(pibBaseAddr))
  val atb = LazyModule(new ATBBridge(atbbridgeBaseAddr))
  val ram = LazyModule(new RAM(ramSinkAddr))

  /**
   * tilelink
   */
  val ctrlmatser = Option.when(busType.isTL && !xs_socmisc)(TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "tlctrl",
      sourceId = IdRange(0, 1)
    ))
  ))))
  val ctrlmasternode = ctrlmatser.map(tl => InModuleBody(tl.makeIOs()))
  ctrlmatser.foreach(bus2Control.tlslavenode.get := TLFragmenter(tlSizeMin, tlSizeMax) :=  TLWidthWidget(tlSizeMax) := _)

  val ctrlnode = Option.when(busType.isTL && xs_socmisc)(TLIdentityNode())
  ctrlnode.foreach(bus2Control.tlslavenode.get := TLFragmenter(tlSizeMin, tlSizeMax) :=  TLWidthWidget(tlSizeMax) := _)

  // for ram
  val ramMaster = Option.when(ramIsSMEM)(TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "tlram",
      sourceId = IdRange(0, 1)
    ))
  ))))
  val ramMasterNode = ramMaster.map(tl => InModuleBody(tl.makeIOs()))
  ram.ramIdentityNode.foreach(_ := ramMaster.get)

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
     * module: [[bus2ctrl]] [[encoderVec]] [[funnel]] [[pib]] [[atb]] [[ram]]
     */
    val bus2ctrl = bus2Control.module
    val encoderModVec = encoderVec.map(_.module)
    val funnelMod = funnel.module
    val pibMod = pib.module
    val atbMod = atb.module
    val ramMod = ram.module

    encoderModVec.map(encoder => dontTouch(encoder.io))
    dontTouch(funnelMod.io)
//    dontTouch(pibMod.io)
//    dontTouch(atbMod.io)
//    dontTouch(ramMod.io)

    /**
     * data path(message stream):
     *         [ingressPort]                      [packets]
     * hart ------------------> [[encoderVec]] ---------------> [[funnel]]
     */
    encoderModVec.zip(fromCore).zip(toCore).map{ case((encoder, ingressPort), toCore) => {
      toCore := encoder.io.toCore
      encoder.io.fromCore := ingressPort
      encoder.io.extTrigIn := io.extTrigIn
    }}
    encoderModVec.zip(funnelMod.io.packets).map{ case (encoder, packet) => packet := encoder.io.packet }

    io.extTrigOut := encoderModVec.map(_.io.extTrigOut.asUInt).reduce(_ | _).asBools

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
    val components: Seq[ComponentBaseImp] = (encoderModVec.toSeq ++ Seq(funnelMod, pibMod, atbMod, ramMod))
    components.map(comp => comp.bus.b2c := bus2ctrl.io.b2c)
    bus2ctrl.io.c2b.error := components.map(_.bus.c2b.error).reduce(_ | _)
    bus2ctrl.io.c2b.dataRead := components.map(_.bus.c2b.dataRead).reduce(_ | _)
  }

  lazy val module = new TraceImp(this)
}

