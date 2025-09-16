package trace

import bundles._
import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import trace.component._
import trace.component.ComponentBase
import trace.control._
import trace.interface._
import utils._

class Trace(busType: BusType = BusType.TL, xs_socmisc: Boolean = false)(implicit p: Parameters) extends LazyModule
    with TraceParameters {
  val bus2Control = LazyModule(new Bus2Control)

  val encoderVec = (0 until hart_num).map(i => LazyModule(new Encoder(encoderBaseAddr + ctrlMemStep * i)))
  val funnel     = LazyModule(new Funnel(funnelBaseAddr))
  val pib        = LazyModule(new PIB(pibBaseAddr))
  val atb        = LazyModule(new ATBBridge(atbbridgeBaseAddr))
  val ram        = LazyModule(new SRAM(ramSinkAddr))

  /**
   * tilelink ctrl
   */
  val tlmatser = Option.when(busType.isTL && !xs_socmisc)(TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "tlctrl",
      sourceId = IdRange(0, 1)
    ))
  ))))
  val ctrlTLMaster = tlmatser.map(tl => InModuleBody(tl.makeIOs()))
  tlmatser.foreach(
    bus2Control.tlslavenode := TLFragmenter(ctrlTLSizeMin, ctrlTLSizeMax) := TLWidthWidget(ctrlTLSizeMax) := _
  )

  val ctrlnode = Option.when(busType.isTL && xs_socmisc)(TLIdentityNode())
  ctrlnode.foreach(
    bus2Control.tlslavenode := TLFragmenter(ctrlTLSizeMin, ctrlTLSizeMax) := TLWidthWidget(ctrlTLSizeMax) := _
  )

  /**
   * axi ctrl
   */
  private val dummy = Option.when(busType.isAXI)(LazyModule(new TLError(
    params = DevNullParams(
      address = AddressSet(0, traceBaseAddr - 1).subtract(ctrlAddressSet),
      maxAtomic = 8,
      maxTransfer = ctrlDataSize
    ),
    beatBytes = ctrlDataSize / 8
  )))
  private val xbar = Option.when(busType.isAXI)(TLXbar())
  xbar.foreach { bar =>
    dummy.get.node          := bar
    bus2Control.tlslavenode := bar
  }

  val aximaster = Option.when(busType.isAXI)(AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters("axi4ctrl", IdRange(0, 1)))
  ))))
  val axi4node = aximaster.map(axi4 =>
    InModuleBody {
      val ctrlAXIMaster = chisel3.IO(Flipped(new VerilogAXI4Record(axi4.out.head._1.params)))
      ctrlAXIMaster.viewAs[AXI4Bundle] <> axi4.out.head._1
      ctrlAXIMaster
    }
  )

  aximaster.foreach(
    xbar.get :=
      TLFIFOFixer() :=
      AXI4ToTL() :=
      AXI4UserYanker(Some(1)) :=
      AXI4Fragmenter() :=
      AXI4Buffer() :=
      AXI4Buffer() :=
      AXI4IdIndexer(1) :=
      _
  )

  /**
   * apb ctrl
   */
  val apbmaster =
    Option.when(busType.isAPB)(APBMasterNode(Seq(APBMasterPortParameters(Seq(APBMasterParameters("traceAPBctrl"))))))
  val ctrlAPBMaster = apbmaster.map(apb => InModuleBody(apb.makeIOs()))

  apbmaster.foreach(
    bus2Control.tlslavenode :=
      TLBuffer() :=
      TLFragmenter(ctrlTLSizeMin, ctrlTLSizeMax) :=
      TLWidthWidget(ctrlTLSizeMax) :=
      APBToTL() :=
      _
  )

  class TraceImp(wrapper: Trace)(implicit p: Parameters) extends LazyModuleImp(wrapper) with RequireAsyncReset {
    val io = IO(new Bundle {
      val fromCore = Input(Vec(hart_num, new FromCore))
      val toCore   = Output(Vec(hart_num, new ToCore))
      val hpm      = Option.when(supportHPM)(Vec(hart_num, new Hpm))
    })

    /**
     * io
     */
    val (fromCore, toCore) = (io.fromCore, io.toCore)

    /**
     * module: [[bus2ctrl]] [[encoderVec]] [[funnel]] [[pib]] [[atb]] [[ram]]
     */
    val bus2ctrl      = bus2Control.module
    val encoderModVec = encoderVec.map(_.module)
    val funnelMod     = funnel.module
    val pibMod        = pib.module
    val atbMod        = atb.module
    val ramMod        = ram.module

    encoderModVec.map(encoder => dontTouch(encoder.io))

    /**
     * data path(message stream):
     *         [ingressPort]                      [packets]
     * hart ------------------> [[encoderVec]] ---------------> [[funnel]]
     */
    encoderModVec.zip(fromCore).zip(toCore).map {
      case ((encoder, ingressPort), toCore) => {
        toCore              := encoder.io.toCore
        encoder.io.fromCore := ingressPort
      }
    }
    encoderModVec.zip(funnelMod.io.atbsVec).map { case (encoder, atbs) => atbs <> encoder.io.atbm }
    ramMod.io.atbs <> funnelMod.io.atbm

    // hpm
    io.hpm.foreach(iohpm =>
      iohpm.zip(encoderModVec).map { case (io, encoder) =>
        io.eventVec <> encoder.io.hpm.get.eventVec
      }
    )

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
    val components: Seq[ComponentBaseImp] = encoderModVec.toSeq ++ Seq(funnelMod, pibMod, atbMod, ramMod)
    components.map(comp => comp.bus.b2c := bus2ctrl.io.b2c)
    bus2ctrl.io.c2b.error    := components.map(_.bus.c2b.error).reduce(_ | _)
    bus2ctrl.io.c2b.dataRead := components.map(_.bus.c2b.dataRead).reduce(_ | _)
  }

  lazy val module = new TraceImp(this)
}
