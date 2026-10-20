package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.util._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters
import scala.util.matching.Regex.Match
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.control._
import trace.interface._
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper

class Encoder(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new EncoderImp(this)(baseAddr)
}

class EncoderImp(wrapper: Encoder)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with EncoderControl {
  val io = IO(new Bundle {
    val fromCore  = Input(new FromCore)
    val toCore    = Output(new ToCore)
    val hpm       = Option.when(supportHPM)(new Hpm)
    val out = Decoupled(new TraceBeat(encoderAtbDataBeats))
    val control = new TraceControl
  })

  /**
   * interface -> packetGen -> fifo -> atb
   */
  // module
  val fifoParam = FifoParam(
    isLeaf = true,
    fifoDepth = maxPackets * 3,
    enqDataBytes = maxPackets,
    deqDataBytes = encoderAtbDataBeats
  )

  val blockBuffer = Module(new BlockBuffer(core_stall_response_cycles_p))
  val packetGen   = Module(new PacketGen)
  val fifo        = Module(new Fifo(fifoParam))
  val timestamp   = Module(new EncoderTimestampCounter)

  // connection
  val ingressEnabled = teControl.regOut.Enable.asBool &&
    (teControl.regOut.InstTracing.asBool || teControl.regOut.InstTrigEnable.asBool)
  blockBuffer.io.fromCore := io.fromCore
  blockBuffer.io.fromCore.time := timestamp.io.traceTime
  blockBuffer.io.fromCore.blocks.zip(io.fromCore.blocks).foreach { case (buffered, incoming) =>
    buffered.valid := incoming.valid && ingressEnabled
  }
  blockBuffer.io.fromCore.trigger.traceOn := io.fromCore.trigger.traceOn &&
    teControl.regOut.Enable.asBool && teControl.regOut.InstTrigEnable.asBool
  blockBuffer.io.fromCore.trigger.traceOff := io.fromCore.trigger.traceOff &&
    teControl.regOut.Enable.asBool && teControl.regOut.InstTrigEnable.asBool
  val blockBufferIsFull  = blockBuffer.io.bufferIsFull
  val deqFromBlockBuffer = blockBuffer.io.deqEntry

  packetGen.io.block := deqFromBlockBuffer
  packetGen.io.syncReq := io.control.syncReq
  packetGen.io.flushReq := io.control.flushReq
  packetGen.io.stopReq := io.fromCore.hartIsReset || io.fromCore.priv === Priv.D
  blockBuffer.io.deqReady := packetGen.io.blockReady

  /**
    * trace run control
    * trigger action subject to any optional filtering.
    * start: 1. write 1 to InstTracing; 2. trigger trace on ; -> instTracing = 1 -> notifyVec and filters can work
    * end  : 1. write 0 to InstTracing; 2. trigger trace off;
    * 
    * for trigger:
    * 1. Trace-on and Trace-off actions provide a means for the hart to control when tracing starts and stops. 
    * It is recommended that tracing starts from the oldest instruction retired in the cycle that Trace-on 
    * is asserted, and stops following the newest instruction retired in the cycle that Trace-off is asserted
    * (subject to any optional filtering). 
    * Start in the cycle trace-on; Stop next the cycle trace-off.
    * 
    * 2. Trace-notify provides means to ensure that a specified instruction is explicitly reported 
    * (subject to any optional filtering).
    * 
    */

  /**
   * filters match_begin
   * todo: data trace match
   */
  val block         = deqFromBlockBuffer
  val compMatchVec = Wire(Vec(comparator_num, Bool()))
  val compNotifyVec = Wire(Vec(comparator_num, Bool()))

  def comparatorMatch(index: UInt): Bool =
    MuxLookup(index, false.B)(compMatchVec.zipWithIndex.map { case (result, i) => i.U -> result })

  for (i <- 0 until comparator_num) {
    val comparator = Module(new TraceComparator)
    comparator.io.block := block
    comparator.io.control := teCompControlVec(i).rdata
    comparator.io.primary := Cat(teCompPMatchHighVec(i).rdata, teCompPMatchLowVec(i).rdata)
    comparator.io.secondary := Cat(teCompSMatchHighVec(i).rdata, teCompSMatchLowVec(i).rdata)
    // Prime from the new control word on its actual CSR write edge.
    comparator.io.configure := teCompControlVec(i).w.wen
    when(teCompControlVec(i).w.wen) { comparator.io.control := teCompControlVec(i).w.wdata }
    comparator.io.advance := block.valid && packetGen.io.blockReady && teControl.regOut.Enable.asBool
    compMatchVec(i) := comparator.io.matched
    compNotifyVec(i) := comparator.io.notification
  }
  packetGen.io.block.notifyRequested := block.notifyRequested || compNotifyVec.asUInt.orR

  val filterEnableVec = Wire(Vec(filter_num, Bool()))
  val privMatchVec   = Wire(Vec(filter_num, Bool()))
  val trapMatchVec = Wire(Vec(filter_num, Bool()))
  val filterSelection = teInstFilters.regOut.InstFilters.asUInt
  val comp1MatchVec  = Wire(Vec(filter_num, Bool()))
  val comp2MatchVec  = Wire(Vec(filter_num, Bool()))
  val comp3MatchVec  = Wire(Vec(filter_num, Bool()))
  val impdefMatchVec = Wire(Vec(filter_num, Bool()))
  //  val dtypeMatchVec  = Wire(Vec(filter_num, Bool()))
  //  val dsizeMatchVec  = Wire(Vec(filter_num, Bool()))

  (0 until filter_num).map { i =>
    val ctrlReg = Wire(new TeFilterControlBundle)

    val instReg   = Wire(new TeFilterMatchInstBundle)
    val ecauseReg = Wire(UInt(64.W))
    val dataReg   = Wire(new TeFilterMatchDataBundle)

    ctrlReg   := teFilterControlVec(i).rdata
    instReg   := teFilterMatchInstVec(i).rdata
    ecauseReg := teFilterMatchEcauseHighVec(i).rdata ## teFilterMatchEcauseLowVec(i).rdata
    dataReg   := teFilterMatchDataVec(i).rdata

    filterEnableVec(i) := ctrlReg.Enable.asBool
    privMatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchPrivilege.asBool,
      TraceFilterLogic.privilege(instReg.MatchChoicePrivilege.asUInt, block.priv),
      true.B
    )

    val scope = Module(new TraceTrapFilterScope(filter_trap_depth_bits_p))
    val trapCondition = ctrlReg.MatchEcause.asBool || ctrlReg.MatchInterrupt.asBool
    scope.io.kind := block.itype
    scope.io.trigger := TraceFilterLogic.trap(ecauseReg, block.cause, block.itype,
      ctrlReg.MatchEcause.asBool, ctrlReg.MatchInterrupt.asBool, instReg.MatchValueInterrupt.asBool)
    scope.io.advance := block.valid && packetGen.io.blockReady && teControl.regOut.Enable.asBool
    scope.io.clear := !teControl.regOut.Enable.asBool || !ctrlReg.Enable.asBool ||
      !filterSelection(i) || !trapCondition || teInstFilters.w.wen ||
      teFilterControlVec(i).w.wen || teFilterMatchInstVec(i).w.wen ||
      teFilterMatchEcauseLowVec(i).w.wen || teFilterMatchEcauseHighVec(i).w.wen ||
      (scope.io.advance && block.traceLost)
    trapMatchVec(i) := !trapCondition || scope.io.matched

    impdefMatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchImpdef.asBool,
      (block.impdef & teFilterMatchMaskImpdefVec(i).rdata) ===
        (teFilterMatchValueImpdefVec(i).rdata & teFilterMatchMaskImpdefVec(i).rdata),
      true.B
    )

    comp1MatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchComp1.asBool,
      comparatorMatch(ctrlReg.Comp1.asUInt),
      true.B
    )
    comp2MatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchComp2.asBool,
      comparatorMatch(ctrlReg.Comp2.asUInt),
      true.B
    )
    comp3MatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchComp3.asBool,
      comparatorMatch(ctrlReg.Comp3.asUInt),
      true.B
    )
  }

  // if several filters are enabled they act as logical OR.
  // Several conditions for single filter act as logical AND.
  val allFiltersMatchVec = Seq(
    privMatchVec,
    trapMatchVec,
    comp1MatchVec,
    comp2MatchVec,
    comp3MatchVec,
    impdefMatchVec
  ).map(_.asUInt).reduce(_ & _)
  val filterRes = TraceFilterLogic.selected(filterSelection, filterEnableVec.asUInt, allFiltersMatchVec)

  /**
   * filters match_end
   */

  timestamp.io.control := tsControl.regOut
  timestamp.io.resetCount := tsControl.w.wen && tsControl.w.wdataFields.Reset.asBool
  timestamp.io.debug := io.fromCore.priv === Priv.D

  /**
   * packets
   */

  // todo：remove following two lines
  val stallHart = ingressEnabled && (blockBufferIsFull || fifo.io.ctrl.fifoIsFull) &&
    teControl.regOut.InstStallEna.asBool
  val instTraceEnable = ingressEnabled

  /**
   * hpm
   */
  io.hpm.foreach { iovec =>
    val addrVec  = teHpmAddrVec.get
    val eventVec = teHpmEventHighVec.get.zip(teHpmEventLowVec.get).map { case (hi, lo) => hi.rdata ## lo.rdata }
    val incVec   = teHpmEventDataVec.get

    (0 until hpmevent_num).map { i =>
      iovec.eventVec(i).hpmaddr  := addrVec(i).rdata
      iovec.eventVec(i).hpmevent := Mux(iovec.eventVec(i).hpmaddr =/= 0.U, eventVec(i), 0.U)
      incVec(i) match {
        case m: HasHpmEventDataBundle =>
          m.inc := iovec.eventVec(i).inc
        case _ =>
      }
    }
  }

  /**
   * interface -> packetGen -> fifo -> atb
   */

  packetGen.io.fromCtrl.filterRes         := filterRes
  packetGen.io.fromCtrl.fifoIsFull        := fifo.io.ctrl.fifoIsFull
  packetGen.io.fromCtrl.teCtrl            := teControl.regOut
  packetGen.io.fromCtrl.teInstFeat        := teInstFeatures.regOut
  packetGen.io.fromCtrl.teDataCtrl        := teDataControl.regOut
  fifo.io.packetEnq <> packetGen.io.packet
  io.out <> fifo.io.atbm
  fifo.io.control.flushReq := io.control.flushReq && packetGen.io.flushAck
  fifo.io.control.syncReq := false.B
  io.control.flushAck := fifo.io.control.flushAck

  /**
   * encoder IO
   */
  io.toCore.stall  := stallHart
  io.toCore.enable := instTraceEnable

  /**
   * control reg io
   */
  val fifoIsEmpty = blockBuffer.io.empty && fifo.io.ctrl.fifoIsEmpty && packetGen.io.empty
  controlMods.foreach { mod =>
    mod match {
      case m: HasTeInstFeaturesBundle =>
        m.id := srcId(baseAddr).U
      case _ =>
    }
    mod match {
      case m: HasTeCtrlBundle => // trace all blocks of "traceOn or traceOff cycle"
        m.traceOn  := block.valid && packetGen.io.blockReady && block.trigger === TriggerAction.TraceOn
        m.traceOff := block.valid && packetGen.io.blockReady &&
          (block.trigger === TriggerAction.TraceOff || block.traceOffAfter)
      case _ =>
    }
    mod match {
      case m: HasTsCountBundle =>
        m.timeCount := timestamp.io.value
      case _ =>
    }
    mod match {
      case m: HasCtrlBundle =>
        m.fifoIsEmpty := fifoIsEmpty
        m.fifoIsFull  := stallHart || blockBuffer.io.overflow
      case _ =>
    }
  }

  connectCtrlReg()
}
