package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import scala.util.matching.Regex.Match
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.control
import trace.control._
import trace.interface._

class Encoder(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new EncoderImp(this)(baseAddr)
}

class EncoderImp(wrapper: Encoder)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with EncoderControl {
  val io = IO(new Bundle {
    val fromCore = Input(new FromCore)
    val toCore   = Output(new ToCore)
    val packet   = Output(new Payload)
    val hpm      = Option.when(supportHPM)(new Hpm)
  })

  val fromCore = io.fromCore
  val (priv, cause, scontext, hcontext, time, tval, impdef, trigger, blocks) =
    (
      fromCore.priv,
      fromCore.cause,
      fromCore.scontext,
      fromCore.hcontext,
      fromCore.time,
      fromCore.tval,
      fromCore.impdef,
      fromCore.trigger,
      fromCore.blocks
    )

  val block0       = fromCore.blocks(0)
  val ingressValid = fromCore.blocks.map(_.valid).reduce(_ | _)
  val itypeIsInt   = block0.valid && Itype.isInt(block0.bits.itype)
  val itypeIsExp   = block0.valid && Itype.isExp(block0.bits.itype)
  val runInDebug   = priv === Priv.D

  // Trigger module
  val triggerModEn = blocks.map(block => block.valid).reduce(_ | _)
  val traceOn      = trigger.traceOn
  val traceOff     = trigger.traceOff

  val notifyVec = trigger.TraceNotify.zip(blocks.map(block => block.valid)).map {
    case (notify, valid) => notify && valid && teControl.regOut.InstTracing.asBool
  }
  val notifyTrig         = notifyVec.reduce(_ | _)
  val notifyCanGenPacket = notifyTrig & teControl.regOut.InstTracing.asBool // todo: fix trigger module's notify

  /**
   * trigger_end: trigger Module & external trigger
   */

  /**
   * filters match_begin
   * todo: data trace match
   */
  val compMatchVec = Wire(Vec(comparator_num, Bool()))

  def matchFunc(isPfunc: Boolean)(compInput: UInt, CompFunc: UInt, matchRegData: UInt): Bool = {
    val srcData = MuxLookup(compInput, block0.bits.iaddr)(Seq(
      CompInputField.Iaddr.asUInt   -> block0.bits.iaddr, // todo: check per block?
      CompInputField.Context.asUInt -> scontext,
      CompInputField.Tval.asUInt    -> tval
      // CompInputField.Daddr.asUInt -> Daddr // todo: support data trace
    ))
    val eq = srcData === matchRegData
    val ne = srcData =/= matchRegData
    val lt = srcData < matchRegData
    val le = srcData <= matchRegData
    val gt = srcData > matchRegData
    val ge = srcData >= matchRegData

    MuxLookup(CompFunc, true.B)(Seq(
      CompPFuncField.Equal.asUInt            -> eq,
      CompPFuncField.NotEqual.asUInt         -> ne,
      CompPFuncField.LessThan.asUInt         -> lt,
      CompPFuncField.LessEqual.asUInt        -> le,
      CompPFuncField.GreaterThan.asUInt      -> gt,
      CompPFuncField.GreaterEqual.asUInt     -> ge,
      CompPFuncField.AlwaysFalseLatch.asUInt -> (if (isPfunc) false.B else true.B)
    ))
  }

  (0 until comparator_num).map { i =>
    val ctrlReg       = Wire(new TeCompControlBundle)
    val pMatchRegData = Wire(UInt(64.W))
    val sMatchRegData = Wire(UInt(64.W))
    ctrlReg       := teCompControlVec(i).rdata
    pMatchRegData := teCompPMatchHighVec(i).rdata ## teCompPMatchLowVec(i).rdata
    sMatchRegData := teCompSMatchHighVec(i).rdata ## teCompSMatchLowVec(i).rdata

    val pMatch: Bool = matchFunc(true)(
      ctrlReg.PInput.asUInt,
      ctrlReg.PFunc.asUInt,
      Mux(ctrlReg.SFunc === CompSFuncField.AlwaysTrueMask, pMatchRegData & sMatchRegData, pMatchRegData)
    ) || CompInputField.isIaddr(ctrlReg.PInput) && ctrlReg.PNotify.asBool && notifyCanGenPacket
    val sMatch: Bool = matchFunc(false)(ctrlReg.SInput.asUInt, ctrlReg.SFunc.asUInt, sMatchRegData) ||
      CompInputField.isIaddr(ctrlReg.SInput) && ctrlReg.SNotify.asBool && notifyCanGenPacket

    val puntilsMatchReg = RegInit(false.B)
    when(pMatch) {
      puntilsMatchReg := true.B
    }.elsewhen(sMatch) {
      puntilsMatchReg := false.B
    }
    val matchMode = ctrlReg.MatchMode.asUInt
    compMatchVec(i) := (matchMode === MatchModeField.P.asUInt) && pMatch ||
      (matchMode === MatchModeField.PS.asUInt) && (pMatch & sMatch) ||
      (matchMode === MatchModeField.NonPS.asUInt) && !(pMatch & sMatch) ||
      (matchMode === MatchModeField.PUntilS.asUInt) && puntilsMatchReg // comp Match from p match to s match todo: checking spec
  }

  val privMatchVec   = Wire(Vec(filter_num, Bool()))
  val ecauseMatchVec = Wire(Vec(filter_num, Bool()))
  val intExpMatchVec = Wire(Vec(filter_num, Bool()))
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

    privMatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchPrivilege.asBool,
      instReg.MatchChoicePrivilege.asUInt === UIntToOH(priv),
      true.B
    )

    // todo: Add count to hand nested trap.
    ecauseMatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchEcause.asBool, ecauseReg === UIntToOH(cause), true.B)

    // todo: Add count to hand nested trap.
    intExpMatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchInterrupt.asBool,
      Mux(instReg.MatchValueInterrupt.asBool, itypeIsInt, itypeIsExp),
      true.B
    )

    impdefMatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchImpdef.asBool,
      (impdef & teFilterMatchMaskImpdefVec(i).rdata) ===
        (teFilterMatchValueImpdefVec(i).rdata & teFilterMatchMaskImpdefVec(i).rdata),
      true.B
    )

    comp1MatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchComp1.asBool,
      compMatchVec(ctrlReg.Comp1.asUInt),
      true.B
    )
    comp2MatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchComp2.asBool,
      compMatchVec(ctrlReg.Comp2.asUInt),
      true.B
    )
    comp3MatchVec(i) := Mux(
      ctrlReg.Enable.asBool && ctrlReg.MatchComp3.asBool,
      compMatchVec(ctrlReg.Comp3.asUInt),
      true.B
    )
  }

  // if several filters are enabled they act as logical OR.
  // Several conditions for single filter act as logical AND.
  val allFiltersMatchVec = Seq(
    privMatchVec,
    ecauseMatchVec,
    intExpMatchVec,
    comp1MatchVec,
    comp2MatchVec,
    comp3MatchVec,
    impdefMatchVec
  ).map(_.asUInt).reduce(_ & _)
  val filter1H = teInstFilters.regOut.InstFilters.asUInt

  val filterCanGenPacket = Mux(filter1H.orR, Mux1H(filter1H, allFiltersMatchVec), true.B)

  /**
   * filters match_end
   */

  /**
   * time stamp. todo: support what type counter? bus clock or hart clock? be determined to trace's clock domain
   * todo: prescale_p rw
   */
  val w1toTsReset: Bool = tsControl.w.wen && tsControl.w.wdataFields.Reset.asBool
  // Real time counter.
  val timeCount = RegInit(0.U((ts_width_p + prescale_p).W))

  val countEn = Mux(
    runInDebug,
    tsControl.regOut.RunInDebug.asBool,
    tsControl.regOut.Count.asUInt === TsCountEnField.Runs.asUInt
  )
  when(w1toTsReset) {
    timeCount := 0.U
  }.elsewhen(countEn) {
    timeCount := timeCount + 1.U
  }

  /**
   * packets
   */
  val carryContext = teControl.regOut.Context.asBool
  val carrySrc     = !teControl.regOut.InhibitSrc.asBool && teInstFeatures.regOut.SrcBits.asUInt.orR
  val carryTime    = tsControl.regOut.Enable

  val syncOther    = WireInit(false.B)
  val syncPeriodic = WireInit(false.B)
  val syncCount    = RegInit(0.U(synccounter_width_p.W))
  when(syncOther || teControl.w.wen && (teControl.w.wdataFields.InstSyncMode =/= teControl.regOut.InstSyncMode)) {
    syncCount := 0.U
  }.elsewhen(countEn) {
    val tmpSyncCount = syncCount +& 1.U
    syncPeriodic := tmpSyncCount.head(1)
    syncCount    := tmpSyncCount.tail(1)
  }
  val syncEn = syncOther || syncPeriodic

  val stallHart       = teControl.regOut.InstStallOrOverflow.asBool && teControl.regOut.InstStallEna.asBool
  val instTraceEnable = teControl.regOut.InstTracing.asBool

  /**
   * control reg io
   */
  controlMods.foreach { mod =>
    mod match {
      case m: HasTeInstFeaturesBundle =>
        m.id := srcId(baseAddr).U
      case _ =>
    }
    mod match {
      case m: HasTeCtrlBundle => // todo fix
        m.stallOrOf    := false.B
        m.allEmitted   := true.B
        m.triggerValid := triggerModEn
        m.traceOn      := traceOn
        m.traceOff     := traceOff
      case _ =>
    }
    mod match {
      case m: HasTsCountBundle =>
        m.timeCount := timeCount(ts_width_p + prescale_p - 1, prescale_p)
      case _ =>
    }
  }

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
   * encoder IO
   */
  io.toCore.stall  := stallHart
  io.toCore.enable := instTraceEnable
  io.packet.format := 0.U
  connectCtrlReg()
}
