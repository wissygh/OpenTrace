package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.component.Encoder

import scala.collection.immutable.SeqMap

object EncoderAddr {
  // reserved:  0x014-0x18, 0x020-0x3f, 0x44, 0x060-0x3ff

  val trTeControl                  = 0x000
  val trTeImpl                     = 0x004
  val trTeInstFeatures             = 0x008
  val trTeInstFilters              = 0x00c

  // data trace control
  val trTeDataControl              = 0x010
  val trTeDataFilters              = 0x01c

  // Timestamp control
  val trTsControl                  = 0x040
  val trTsCounterLow               = 0x048
  val trTsCounterHigh              = 0x04c

  // trigger control
  val trTeTrigDbgControl           = 0x050
  val trTeTrigExtInControl         = 0x054 // don't support
  val trTeTrigExtOutControl        = 0x058 // don't support

  // Filters & comparators
  // filters 0x400-0x5ff
  // comparator 0x600-0x7ff
  val trTeFilter0Control          = 0x400
  val trTeFilter0MatchInst        = 0x404
  val trTeFilter0MatchEcauseLow   = 0x408
  val trTeFilter0MatchEcauseHigh  = 0x40C
  val trTeFilter0MatchValueImpdef = 0x410
  val trTeFilter0MatchMaskImpdef  = 0x414
  val trTeFilter0MatchData        = 0x418

  val trTeComp0Control            = 0x600
  val trTeComp0PMacthLow          = 0x610
  val trTeComp0PMatchHigh         = 0x614
  val trTeComp0SMatchLow          = 0x618
  val trTeComp0SMatchHigh         = 0x61C
}

trait EncoderControl extends ControlBase { self: Encoder =>

  val trTeContrl = Module(new CSRModule("TrTeControl", new TrTeContrlBundle))
    .setAddr(EncoderAddr.trTeControl)

  val trTeImpl = Module(new CSRModule("TrTeImpl", new TrTeImplBundle){})
    .setAddr(EncoderAddr.trTeImpl)

  val trTeInstFeatures = Module(new CSRModule("trTeInstFeatures", new TrTeInstFeaturesBundle){})
    .setAddr(EncoderAddr.trTeInstFeatures)

  val trTeInstFilters = Module(new CSRModule("trTeInstFilters", new TrTeInstFiltersBundle){})
    .setAddr(EncoderAddr.trTeInstFilters)

  val trTeDataControl = Module(new CSRModule("trTeDataControl", new TrTeDataControlBundle){})
    .setAddr(EncoderAddr.trTeDataControl)

  val trTeDataFilters = Module(new CSRModule("trTeDataFilters", new TrTeDataFiltersBundle){})
    .setAddr(EncoderAddr.trTeDataFilters)

  /**
   * timestamp
   */
  val trTsControl = Module(new CSRModule("trTsControl", new OneROFieldBundle){})
    .setAddr(EncoderAddr.trTsControl)

  val trTsCounterLow = Module(new CSRModule("trTsCounterLow", new OneROFieldBundle){})
    .setAddr(EncoderAddr.trTsCounterLow)

  val trTsCounterHigh = Module(new CSRModule("trTsCounterHigh", new OneROFieldBundle){})
    .setAddr(EncoderAddr.trTsCounterHigh)

  /**
   * trigger
   */
  val trTeTrigDbgControl = Module(new CSRModule("trTeTrigDbgControl", new OneFieldBundle){})
    .setAddr(EncoderAddr.trTeTrigDbgControl)

  /**
   * Filter
   */
  val trTeFilterControlVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"Control", new TrTeFilterControlBundle))
      .setAddr(EncoderAddr.trTeFilter0Control + 0x20 * i)
  )

  val trTeFilterMatchInstVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchInst", new TrTeFilterMatchInstBundle))
      .setAddr(EncoderAddr.trTeFilter0MatchInst + 0x20 * i)
  )

  val trTeFilterMatchEcauseLowVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchEcauseLow", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeFilter0MatchEcauseLow + 0x20 * i)
  )

  val trTeFilterMatchEcauseHighVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchEcauseHigh", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeFilter0MatchEcauseHigh + 0x20 * i)
  )

  val trTeFilterMatchValueImpdefVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchValueImpdef", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeFilter0MatchValueImpdef + 0x20 * i)
  )

  val trTeFilterMatchMaskImpdefVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchMaskImpdef", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeFilter0MatchMaskImpdef + 0x20 * i)
  )

  val trTeFilterMatchDataVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchData", new TrTeFilterMatchDataBundle))
      .setAddr(EncoderAddr.trTeFilter0MatchData + 0x20 * i)
  )


  /**
   * Compatator
   */
  val trTeCompControlVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"Control", new TrTeCompControlBundle))
      .setAddr(EncoderAddr.trTeComp0Control + 0x20 * j)
  )

  val trTeCompPMacthLowVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"PMacthLow", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeComp0PMacthLow + 0x20 * j)
  )

  val trTeCompPMacthHighVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"PMacthHigh", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeComp0PMatchHigh + 0x20 * j)
  )

  val trTeCompSMacthLowVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"SMacthLow", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeComp0SMatchLow + 0x20 * j)
  )

  val trTeCompSMacthHighVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"SMacthHigh", new OneFieldBundle))
      .setAddr(EncoderAddr.trTeComp0SMatchHigh + 0x20 * j)
  )

  val filterMods: Seq[CSRModule[_]] = trTeFilterControlVec ++ trTeFilterMatchInstVec ++
    trTeFilterMatchEcauseLowVec ++ trTeFilterMatchEcauseHighVec ++
    trTeFilterMatchValueImpdefVec ++ trTeFilterMatchMaskImpdefVec ++
    trTeFilterMatchDataVec

  val comptatorMods: Seq[CSRModule[_]] = trTeCompControlVec ++
    trTeCompPMacthLowVec ++ trTeCompPMacthHighVec ++
    trTeCompSMacthLowVec ++ trTeCompSMacthHighVec

  override val controlMods: Seq[CSRModule[_]] = Seq(
    trTeContrl,
    trTeImpl,
    trTeInstFeatures,
    trTeInstFilters,
    trTeDataControl,
    trTeDataFilters,
    trTsControl,
    trTsCounterLow,
    trTsCounterHigh,
    trTeTrigDbgControl
  ) ++ filterMods ++ comptatorMods

  override val controlRWMap: SeqMap[Int, (CSRAddrWriteBundle[_], UInt)] = SeqMap.from(
    controlMods.map(reg => (reg.addr -> (reg.w -> reg.rdata))).iterator
  )

  override val controlOutMap: SeqMap[Int, UInt] = SeqMap.from(
    controlMods.map(reg => (reg.addr -> reg.regOut.asInstanceOf[CSRBundle].asUInt)).iterator
  )

}


class TrTeContrlBundle extends CSRBundle {
  val Active              = RW(0).withReset(0.U)
  val Enable              = RW(1).withReset(0.U)
  val InstTracing         = RW(2)
  val Emptry              = RO(3).withReset(1.U)
  val InstMode            = InstModeFiled(6, 4, wNoFilter).withReset(InstModeFiled.Baseline)
  val Context             = RW(9).withReset(0.U)
  val InstTrigEnable      = RW(11).withReset(0.U)
  val InstStallOrOverflow = RW(12).withReset(0.U)
  val InstStallEna        = RW(13).withReset(0.U)
  val InhibitSrc          = RW(15).withReset(0.U)
  val InstSyncMode        = InstSyncModeFiled(17, 16, wNoFilter).withReset(InstSyncModeFiled.Off)
  val InstSyncMax         = RW(23, 20)
  val Format              = FormatFiled(26, 24, wNoFilter).withReset(FormatFiled.Etrace)
}

object InstModeFiled extends CSREnum with WARLApply {
  val Othertrace = Value(0.U)
  val Baseline   = Value(3.U)
  val Optimized  = Value(6.U)

  // only support Baseline
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(Othertrace, Baseline, Optimized)
}

object InstSyncModeFiled extends CSREnum with WARLApply {
  val Off         = Value(0.U)
  val CountTrace  = Value(1.U)
  val CountCycle  = Value(2.U)
  val CountInstr  = Value(3.U)
}

object FormatFiled extends CSREnum with WARLApply {
  val Etrace  = Value(0.U)
  val Ntrace  = Value(1.U)
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(Etrace, Ntrace)
}

class TrTeImplBundle extends CSRBundle {
  val VerMajor      = RO(3, 0).withReset(0.U)
  val VerMinor      = RO(7, 4).withReset(0.U)
  val CompType      = TrcompTypeFiled(11, 8, wNoFilter).withReset(TrcompTypeFiled.Encoder)
  val ProtocolMajor = RO(19, 16).withReset(0.U)
  val ProtocolMinor = RO(23, 20).withReset(0.U)
}

class TrTeInstFeaturesBundle extends CSRBundle {
  val InstNoAddrDiff         = RW(0)
  val InstNoTrapDiff         = RW(1)
  val InstEnSiJump           = RW(2)
  val InstEnImRet            = RW(3)
  val InstEnBranchPred       = RW(4)
  val InstEnJumpTargetCache  = RW(5)
  val InstImRetMode          = ImpRetModeFiled(7, 6, wNoFilter)
  val InstEnRepHistoty       = RW(8)
  val InstEnAllJumps         = RW(9)
  val InstExtendAddrMSB      = RW(10)
  val SrcID                  = RW(27, 16)
  val SrcBits                = RW(31, 28)
}

object ImpRetModeFiled extends CSREnum with WARLApply {
  val None     = Value(0.U)
  val Simple   = Value(1.U)
  val Partial  = Value(2.U)
  val FullAddr = Value(3.U)
}

class TrTeInstFiltersBundle extends CSRBundle {
  val InstFilters         = RW(15, 0)
}

class TrTeDataControlBundle extends CSRBundle {
  val DataImplemented    = RO(0).withReset(0.U)
  val DataTracing        = RW(1)
  val DataTrigEnable     = RW(2)
  val DataStallOrOf      = RW(3)
  val DataStallEna       = RW(4)
  val DataDrop           = RW(5)
  val DataDropEna        = RW(6)
  val DataNoValue        = RW(16)
  val DataNoAddr         = RW(17)
  val DataAddrCompress   = DataAddrCompressFiled(19, 18, wNoFilter)
}

object DataAddrCompressFiled extends CSREnum with WARLApply {
  val FullAddr       = Value(0.U)
  val XOR            = Value(1.U)
  val Diff           = Value(2.U)
  val PtoCompression = Value(3.U)
}

class TrTeDataFiltersBundle extends CSRBundle {
  val DataFilters         = RW(15, 0)
}


class TrTsControlBundle extends CSRBundle {
  val Active              = RW(0).withReset(0.U)
  val Count               = RW(1).withReset(0.U)
  val Reset               = RW(2).withReset(0.U)
  val RunInDebug          = RW(3).withReset(1.U)
  val Mode                = TrTsModeFiled(6, 4, wNoFilter).withReset(TrTsModeFiled.None)
  val Prescale            = RW(9, 8).withReset(0.U)
  val Enable              = RW(15).withReset(0.U)
  // (23, 16) todo: ?
  val Width               = RO(29, 24) // todo:?
}


object TrTsModeFiled extends CSREnum with WARLApply {
  val None            = Value(0.U)
  val External        = Value(1.U)
  val InternalSystem  = Value(2.U)
  val InternalCore    = Value(3.U)
  val Shared          = Value(4.U)
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(None, InternalCore)
}

class TrTeFilterControlBundle extends CSRBundle {
  val Enable           = RW(0).withReset(0.U)
  val MatchPrivilege   = RW(1).withReset(0.U)
  val MatchEcause      = RW(2).withReset(0.U)
  val MatchInterrupt   = RW(3).withReset(1.U)
  val MatchComp1       = RW(4).withReset(0.U)
  val Comp1            = RW(7, 5).withReset(0.U)
  val MatchComp2       = RW(8).withReset(0.U)
  val Comp2            = RW(11, 9).withReset(0.U)
  val MatchComp3       = RW(12).withReset(0.U)
  val Comp3            = RW(15, 13).withReset(0.U)
  val MatchImpdef      = RW(16).withReset(0.U)
  val MatchDtype       = RW(24).withReset(0.U)
  val MatchDSize       = RW(25).withReset(0.U)
}

class TrTeFilterMatchInstBundle extends CSRBundle {
  val MatchChoicePrivilege  = RW(7, 0).withReset(0.U)
  val MatchValueInterrupt   = RW(8).withReset(0.U)
}

class TrTeFilterMatchDataBundle extends CSRBundle {
  val MatchChoiceDtype  = RW(15, 0).withReset(0.U)
  val MatchChoiceDsize  = RW(23, 16).withReset(0.U)
}

class TrTeCompControlBundle extends CSRBundle {
  val PInput       = RW(1,  0).withReset(0.U)
  val SInput       = RW(3,  2).withReset(0.U)
  val PFunc        = RW(6,  4).withReset(0.U)
  val SFunc        = RW(10, 8).withReset(0.U)
  val MatchMode    = RW(13, 12).withReset(0.U)
  val PNtify       = RW(14).withReset(0.U)
  val SNtify       = RW(15).withReset(0.U)
}
