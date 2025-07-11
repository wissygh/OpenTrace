package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import bundles.{CSRAddrWriteBundle, CSRModule}
import trace.{Control2BusRegBundle, TraceParameters, control}
import trace.control._
import trace.interface._

import scala.util.matching.Regex.Match

class Encoder(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr)
  with EncoderControl
{
  val io = IO(new Bundle {
    val fromCore = Input(new FromCore)
    val extTrigIn = Input(Vec(8, Bool()))
    val extTrigOut = Output(Vec(8, Bool()))
    val toCore   = Output(new ToCore)
    val packet  = Output(new Payload)
  })
  
  val fromCore = io.fromCore
  val (priv, cause, context, time, tval, impdef, trigger, blocks) =
    (fromCore.priv, fromCore.cause, fromCore.context, fromCore.time,
      fromCore.tval, fromCore.impdef, fromCore.trigger, fromCore.blocks)

  val block0 = fromCore.blocks(0)
  val ingressValid =  fromCore.blocks.map(_.valid).reduce(_ | _)
  val itypeIsInt = block0.valid && Itype.isInt(block0.bits.itype)
  val itypeIsExp = block0.valid && Itype.isExp(block0.bits.itype)


  /**
   * external trigger
   */
  // extTrigAction, todo: how to select when trigger module and ext Trigger al fire.
  val trigVec = Wire(Vec(8, UInt(4.W)))
  trigVec.map(_ := 0.U)
  val extTrigAction = PriorityMux(io.extTrigIn, teTrigExtInControl.regOut.asUInt.asTypeOf(trigVec))
  // extTrigEvent todo: start stop check
  io.extTrigOut.zip(teTrigExtOutControl.regOut.asUInt.asTypeOf(trigVec)).map{ case(io, event) =>
    io := (event === TrigEvent.Start.asUInt && teControl.regOut.InstTracing.asBool) ||
      (event === TrigEvent.Start.asUInt && !teControl.regOut.InstTracing.asBool)
  }


  /**
   * control reg io
   */
  controlMods.foreach { mod =>
    mod match {
      case m: HasTeInstFeaturesBundle =>
        m.Id := srcId(baseAddr).U
      case m: HasTeCtrlBundle => // todo fix
        m.stallOrOf   := false.B
        m.allEmitted  := true.B
        m.traceOn     := trigger.traceOn && blocks.map(block => block.valid).reduce(_ | _)
        m.traceOff    := trigger.traceOff && blocks.map(block => block.valid).reduce(_ | _)
      case _ =>
    }
  }

  // traceNoftify, trigger from hart
  val notifyVec = trigger.TraceNotify.zip(blocks.map(block => block.valid)).map{
    case (notify, valid) => notify && valid && teControl.regOut.InstTracing.asBool
  }

  // todo: (tsControl.regOut.Width.asUInt, 0)
  val timeStamp = (tsCounterHigh.regOut.asUInt ## tsCounterLow.regOut.asUInt)(ts_width_p, 0)
  assert(tsControl.regOut.Width.asUInt === ts_width_p.U, s"ts_width_p is not enqual tsControl.regOut.Width!")


  /**
   * match_begin
   * todo: data trace match
   */
  val compMatchVec   = Wire(Vec(filter_num, Bool()))
  def matchPFunc(compInput: UInt, CompPFunc: UInt, matchRegData: UInt): Bool = {
    val srcData = MuxLookup(compInput, block0.bits.iaddr)(Seq(
      CompInputField.Iaddr.asUInt -> block0.bits.iaddr,
      CompInputField.Context.asUInt -> context,
      CompInputField.Tval.asUInt -> tval,
      // CompInputField.Daddr.asUInt -> Daddr // todo: support data trace
    ))
    val eq = srcData === matchRegData
    val ne = srcData =/= matchRegData
    val lt = srcData < matchRegData
    val le = srcData <=  matchRegData
    val gt = srcData > matchRegData
    val ge = srcData >=  matchRegData

    MuxLookup(CompPFunc, eq)(Seq(
      CompPFuncField.Equal.asUInt -> eq,
      CompPFuncField.NotEqual.asUInt -> ne,
      CompPFuncField.LessThan.asUInt -> lt,
      CompPFuncField.LessEqual.asUInt -> le,
      CompPFuncField.GreaterThan.asUInt -> gt,
      CompPFuncField.GreaterEqual.asUInt -> ge,
      CompPFuncField.AlwaysFalseLatch.asUInt -> false.B,
      CompPFuncField.AlwaysTrue.asUInt -> true.B,
    ))
  }

  def matchSFunc(compInput: UInt, CompPFunc: UInt, matchRegData: UInt): Bool = {
    val srcData = MuxLookup(compInput, block0.bits.iaddr)(Seq(
      CompInputField.Iaddr.asUInt -> block0.bits.iaddr,
      CompInputField.Context.asUInt -> context,
      CompInputField.Tval.asUInt -> tval,
      // CompInputField.Daddr.asUInt -> Daddr // todo: support data trace
    ))
    val eq = srcData === matchRegData
    val ne = srcData =/= matchRegData
    val lt = srcData < matchRegData
    val le = srcData <=  matchRegData
    val gt = srcData > matchRegData
    val ge = srcData >=  matchRegData

    MuxLookup(CompPFunc, eq)(Seq(
      CompSFuncField.Equal.asUInt -> eq,
      CompSFuncField.NotEqual.asUInt -> ne,
      CompSFuncField.LessThan.asUInt -> lt,
      CompSFuncField.LessEqual.asUInt -> le,
      CompSFuncField.GreaterThan.asUInt -> gt,
      CompSFuncField.GreaterEqual.asUInt -> ge,
      CompSFuncField.AlwaysTrueMask.asUInt -> true.B,
      CompSFuncField.AlwaysTrue.asUInt -> true.B,
    ))
  }
  (0 until comparator_num).map { i =>
    val ctrlReg = Wire(new TeCompControlBundle)
    val pMatchRegData = Wire(UInt(64.W))
    val sMatchRegData = Wire(UInt(64.W))
    ctrlReg := teCompControlVec(i).rdata
    pMatchRegData := teCompPMatchHighVec(i).rdata ## teCompPMatchLowVec(i).rdata
    sMatchRegData := teCompSMatchHighVec(i).rdata ## teCompSMatchLowVec(i).rdata

    val matchMode = ctrlReg.MatchMode.asUInt
    val pMatch: Bool = matchPFunc(
      ctrlReg.PInput.asUInt,
      ctrlReg.PFunc.asUInt,
      Mux(ctrlReg.SFunc === CompSFuncField.AlwaysTrueMask, pMatchRegData & sMatchRegData, pMatchRegData)
    )
    val sMatch: Bool = matchSFunc(ctrlReg.SInput.asUInt, ctrlReg.SFunc.asUInt, sMatchRegData)

    val puntilsReg = RegInit(false.B)
    when(pMatch){
      puntilsReg := true.B
    }.elsewhen(sMatch){
      puntilsReg := false.B
    }
    compMatchVec(i) := (matchMode === MatchModeField.P.asUInt) && pMatch ||
      (matchMode === MatchModeField.PS.asUInt) && (pMatch & sMatch) ||
      (matchMode === MatchModeField.NonPS.asUInt) && !(pMatch & sMatch) ||
      (matchMode === MatchModeField.PUntilS.asUInt) && puntilsReg // comp Match from p match to s match todo: checking spec
  }

  val privMatchVec   = Wire(Vec(filter_num, Bool()))
  val ecauseMatchVec = Wire(Vec(filter_num, Bool()))
  val intrMatchVec   = Wire(Vec(filter_num, Bool()))
  val comp1MatchVec  = Wire(Vec(filter_num, Bool()))
  val comp2MatchVec  = Wire(Vec(filter_num, Bool()))
  val comp3MatchVec  = Wire(Vec(filter_num, Bool()))
  val impdefMatchVec = Wire(Vec(filter_num, Bool()))
  //  val dtypeMatchVec  = Wire(Vec(filter_num, Bool()))
  //  val dsizeMatchVec  = Wire(Vec(filter_num, Bool()))

  (0 until filter_num).map { i =>
    val ctrlReg = Wire(new TeFilterControlBundle)

    val instReg = Wire(new TeFilterMatchInstBundle)
    val ecauseReg = Wire(UInt(64.W))
    val dataReg = Wire(new TeFilterMatchDataBundle)

    ctrlReg :=teFilterControlVec(i).rdata
    instReg := teFilterMatchInstVec(i).rdata
    ecauseReg := teFilterMatchEcauseHighVec(i).rdata ## teFilterMatchEcauseLowVec(i).rdata
    dataReg := teFilterMatchDataVec(i).rdata

    privMatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchPrivilege.asBool,
      instReg.MatchChoicePrivilege.asUInt === UIntToOH(priv),
      true.B
    )

    ecauseMatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchEcause.asBool,
      ecauseReg === UIntToOH(cause),
      true.B
    )

    intrMatchVec(i) := Mux(ctrlReg.Enable.asBool, Mux(ctrlReg.MatchInterrupt.asBool, itypeIsInt, itypeIsExp), true.B)

    impdefMatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchImpdef.asBool,
      (impdef & teFilterMatchMaskImpdefVec(i).rdata) === (teFilterMatchValueImpdefVec(i).rdata & teFilterMatchMaskImpdefVec(i).rdata),
      true.B
    )

    comp1MatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchComp1.asBool, compMatchVec(ctrlReg.Comp1.asUInt), true.B)
    comp2MatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchComp2.asBool, compMatchVec(ctrlReg.Comp2.asUInt), true.B)
    comp3MatchVec(i) := Mux(ctrlReg.Enable.asBool && ctrlReg.MatchComp3.asBool, compMatchVec(ctrlReg.Comp3.asUInt), true.B)
  }

  // if several filters are enabled they act as logical OR. 
  // Several conditions for single filter act as logical AND.
  val allFiltersMatchVec = Seq(privMatchVec, ecauseMatchVec, intrMatchVec, comp1MatchVec,
    comp2MatchVec, comp3MatchVec, impdefMatchVec).map(_.asUInt).reduce(_ & _)
  val filter1H = teInstFilters.regOut.InstFilters.asUInt(filter_num - 1, 0)
  val match2Gen = Mux(filter1H.orR, Mux1H(filter1H, allFiltersMatchVec), true.B)
  /**
   * match_end
   */

  /**
   * encoder IO
   */
  io.toCore.stall := teControl.regOut.InstStallOrOverflow.asBool && teControl.regOut.InstStallEna.asBool
  io.toCore.enable := teControl.regOut.InstTracing.asBool
  io.packet.format := 0.U
  connectCtrlReg()
}
