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

class TraceTopIO(numHarts: Int = 1, useAtbSink: Boolean = false) extends Bundle with TraceParameters {
  val fromCore    = Input(Vec(numHarts, new FromCore))
  val toCore      = Output(Vec(numHarts, new ToCore))
  val hpm         = Option.when(supportHPM)(Vec(numHarts, new Hpm))
  val atbm = Option.when(useAtbSink)(new ATBMaster(funnelAtbDataBeats))
  val clock_atb = Option.when(useAtbSink)(Input(Clock()))
  val reset_atb_n = Option.when(useAtbSink)(Input(Bool()))
  val clock_core  = Input(Clock())
  val reset_core  = Input(Reset())
  val clock_ctrl  = Input(Clock())
  val reset_ctrl  = Input(Reset())
  val clock_trace = Input(Clock())
  val reset_trace = Input(Reset())
}

class Trace(busType: BusType = BusType.TL, xs_socmisc: Boolean = false,
    val numHarts: Int = 1, val useAtbSink: Boolean = false)(implicit p: Parameters) extends LazyModule
    with TraceParameters {

  require(numHarts > 0 && numHarts <= 16, "Funnel supports 1 to 16 harts")
  override def hart_num = numHarts

  val encoderVec = (0 until hart_num).map(i => LazyModule(new Encoder(encoderBaseAddr + ctrlMemStep * i)))
  val funnel     = LazyModule(new Funnel(funnelBaseAddr, numHarts))
  val pib        = LazyModule(new PIB(pibBaseAddr))
  val atb        = LazyModule(new ATBBridge(atbbridgeBaseAddr))
  val ram        = LazyModule(new SRAM(ramSinkAddr))
  val components: Seq[ComponentBase] = encoderVec.toSeq ++ Seq(funnel, pib, atb, ram)

  val ctrlSources: Seq[CtrlAsyncSource] = (0 until components.length).map(i => LazyModule(new CtrlAsyncSource)).toSeq
  val ctrlSinks:   Seq[CtrlAsyncSink]   = components.map(comp => LazyModule(new CtrlAsyncSink(comp.compBaseAddress)))

  /**
   * control path:
   *  write:
   *                   [bus write req]           [req/wtrite data]
   *  bus(axi/tl/apb) ----------------> [[bus2ctrl]] ----------------> all component: [[encoderVec]] [[funnel]] [[pib]] [[atb]] [[ram]]
   *
   *  read:
   *                   [bus read req]               [read req]
   *  bus(axi/tl/apb) ----------------> [[bus2ctrl]] ----------------> all component: [[encoderVec]] [[funnel]] [[pib]] [[atb]] [[ram]]
   *             \---- [bus read resp] <----------- [read data] <-------/
   */
  val tlXbar = LazyModule(new TLXbar())
  ctrlSinks.zip(ctrlSources).map { case (sink, source) => sink.sinkNode := source.sourceNode := tlXbar.node }

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
    tlXbar.node := TLWidthWidget(ctrlTLSizeMin) := _
  )

  val ctrlnode = Option.when(busType.isTL && xs_socmisc)(TLIdentityNode())
  ctrlnode.foreach(
    tlXbar.node := TLWidthWidget(ctrlTLSizeMin) := _
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
    dummy.get.node := bar
    tlXbar.node    := bar
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
    tlXbar.node :=
      TLWidthWidget(ctrlTLSizeMin) :=
      APBToTL() :=
      _
  )

  class TraceImp(wrapper: Trace)(implicit p: Parameters) extends LazyRawModuleImp(wrapper) {
    val io = IO(new TraceTopIO(numHarts, useAtbSink))

    /**
     * io
     */
    val (fromCore, toCore) = (io.fromCore, io.toCore)

    /**
     * module: [[bus2ctrl]] [[encoderVec]] [[funnel]] [[pib]] [[atb]] [[ram]]
     */
    val encoderModVec = encoderVec.map(_.module)
    val funnelMod     = funnel.module
    val pibMod        = pib.module
    val atbMod        = atb.module
    val ramMod        = ram.module

    val ctrlSourceMods = ctrlSources.map(_.module)
    val ctrlSinkMods   = ctrlSinks.map(_.module)
    val compMods:         Seq[ComponentBaseImp] = components.map(_.module)
    val comp_traceDomain: Seq[ComponentBaseImp] = Seq(funnelMod, pibMod, atbMod, ramMod)

    encoderModVec.map(encoder => dontTouch(encoder.io))

    compMods.zip(ctrlSinkMods).map { case (comp, sink) => comp.bus <> sink.io.bus }

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
    encoderModVec.zipWithIndex.foreach { case (encoder, i) =>
      val crossing = Module(new TraceAsyncBridge(new TraceBeat(encoderAtbDataBeats)))
      crossing.io.sourceClock := io.clock_core
      crossing.io.sourceReset := encoder.reset.asAsyncReset
      crossing.io.sinkClock := io.clock_trace
      crossing.io.sinkReset := funnelMod.reset.asAsyncReset
      crossing.io.in <> encoder.io.out
      encoder.io.control <> crossing.io.sourceControl
      funnelMod.io.in(i) <> crossing.io.out
      crossing.io.control <> funnelMod.io.sourceControl(i)
    }
    if (useAtbSink) {
      atbMod.io.in <> funnelMod.io.out
      funnelMod.io.control <> atbMod.io.upstream
      val crossing = Module(new ATBAsyncBridge(funnelAtbDataBeats))
      crossing.io.sourceClock := io.clock_trace
      crossing.io.sourceReset := atbMod.reset.asAsyncReset
      crossing.io.sinkClock := io.clock_atb.get
      crossing.io.sinkReset := (!io.reset_atb_n.get).asAsyncReset
      crossing.io.slave.at <> atbMod.io.atbm.at
      atbMod.io.atbm.af <> crossing.io.slave.af
      atbMod.io.atbm.syncReq := crossing.io.slave.syncReq
      crossing.io.slave.wakeup := atbMod.io.atbm.wakeup
      crossing.io.slave.atclken := true.B
      atbMod.io.atbm.atclken := true.B
      io.atbm.get <> crossing.io.master
      ramMod.io.in.valid := false.B
      ramMod.io.in.bits := 0.U.asTypeOf(ramMod.io.in.bits)
    } else {
      ramMod.io.in <> funnelMod.io.out
      atbMod.io.in.valid := false.B
      atbMod.io.in.bits := 0.U.asTypeOf(atbMod.io.in.bits)
      atbMod.io.atbm.at.ready := false.B
      atbMod.io.atbm.atclken := true.B
      atbMod.io.atbm.af.valid := false.B
      atbMod.io.atbm.af.bits := 0.U.asTypeOf(atbMod.io.atbm.af.bits)
      atbMod.io.atbm.syncReq := false.B
      atbMod.io.upstream.flushAck := true.B
      funnelMod.io.control.flushReq := false.B
      funnelMod.io.control.syncReq := false.B
    }

    // hpm
    io.hpm.foreach(iohpm =>
      iohpm.zip(encoderModVec).map { case (io, encoder) =>
        io.eventVec <> encoder.io.hpm.get.eventVec
      }
    )

    /**
    * clock & reset
    */
    ctrlSourceMods.map(_.clock := io.clock_ctrl)
    ctrlSourceMods.map(_.reset := io.reset_ctrl)

    ctrlSinkMods.take(hart_num).map(_.clock := io.clock_core)
    ctrlSinkMods.drop(hart_num).map(_.clock := io.clock_trace)
    ctrlSinkMods.take(hart_num).map(_.reset := io.reset_core)
    ctrlSinkMods.drop(hart_num).map(_.reset := io.reset_trace)

    compMods.zip(ctrlSinkMods).map {
      case (comp, sink) => {
        comp.clock := sink.clock
        comp.reset := sink.io.componentReset
      }
    }
  }

  lazy val module = new TraceImp(this)
}
