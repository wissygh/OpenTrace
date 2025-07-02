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

trait EncoderControl extends ControlBase { self: Encoder =>

  val trTeControl = Module(new CSRModule("TrTeControl", new TrTeControlBundle))
    .setAddr(CtrlReg.trTeControl)

  val trTeImpl = Module(new CSRModule("TrTeImpl", new TrTeImplBundle){})
    .setAddr(CtrlReg.trTeImpl)

  val trTeInstFeatures = Module(new CSRModule("trTeInstFeatures", new TrTeInstFeaturesBundle){})
    .setAddr(CtrlReg.trTeInstFeatures)

  val trTeInstFilters = Module(new CSRModule("trTeInstFilters", new TrTeInstFiltersBundle){})
    .setAddr(CtrlReg.trTeInstFilters)

  val trTeDataControl = Module(new CSRModule("trTeDataControl", new TrTeDataControlBundle){})
    .setAddr(CtrlReg.trTeDataControl)

  val trTeDataFilters = Module(new CSRModule("trTeDataFilters", new TrTeDataFiltersBundle){})
    .setAddr(CtrlReg.trTeDataFilters)

  /**
   * timestamp
   */
  val trTsControl = Module(new CSRModule("trTsControl", new OneROFieldBundle){})
    .setAddr(CtrlReg.trTsControl)

  val trTsCounterLow = Module(new CSRModule("trTsCounterLow", new OneROFieldBundle){})
    .setAddr(CtrlReg.trTsCounterLow)

  val trTsCounterHigh = Module(new CSRModule("trTsCounterHigh", new OneROFieldBundle){})
    .setAddr(CtrlReg.trTsCounterHigh)

  /**
   * trigger
   */
  val trTeTrigDbgControl = Module(new CSRModule("trTeTrigDbgControl", new OneFieldBundle){})
    .setAddr(CtrlReg.trTeTrigDbgControl)

  /**
   * Filter
   */
  val trTeFilterControlVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"Control", new TrTeFilterControlBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0Control, i))
  )

  val trTeFilterMatchInstVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchInst", new TrTeFilterMatchInstBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0MatchInst, i))
  )

  val trTeFilterMatchEcauseLowVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchEcauseLow", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0MatchEcauseLow, i))
  )

  val trTeFilterMatchEcauseHighVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchEcauseHigh", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0MatchEcauseHigh, i))
  )

  val trTeFilterMatchValueImpdefVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchValueImpdef", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0MatchValueImpdef, i))
  )

  val trTeFilterMatchMaskImpdefVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchMaskImpdef", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0MatchMaskImpdef, i))
  )

  val trTeFilterMatchDataVec: Seq[CSRModule[_]] = Range(0, filter_num).map(i =>
    Module(new CSRModule(s"trTeFilter$i" + s"MatchData", new TrTeFilterMatchDataBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeFilter0MatchData, i))
  )


  /**
   * Compatator
   */
  val trTeCompControlVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"Control", new TrTeCompControlBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeComp0Control, j))
  )

  val trTeCompPMatchLowVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"PMatchLow", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeComp0PMatchLow, j))
  )

  val trTeCompPMatchHighVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"PMatchHigh", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeComp0PMatchHigh, j))
  )

  val trTeCompSMatchLowVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"SMatchLow", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeComp0SMatchLow, j))
  )

  val trTeCompSMatchHighVec: Seq[CSRModule[_]] = Range(0, comparator_num).map(j =>
    Module(new CSRModule(s"trTeComp$j" + s"SMatchHigh", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.trTeComp0SMatchHigh, j))
  )

  val filterMods: Seq[CSRModule[_]] = trTeFilterControlVec ++ trTeFilterMatchInstVec ++
    trTeFilterMatchEcauseLowVec ++ trTeFilterMatchEcauseHighVec ++
    trTeFilterMatchValueImpdefVec ++ trTeFilterMatchMaskImpdefVec ++
    trTeFilterMatchDataVec

  val comptatorMods: Seq[CSRModule[_]] = trTeCompControlVec ++
    trTeCompPMatchLowVec ++ trTeCompPMatchHighVec ++
    trTeCompSMatchLowVec ++ trTeCompSMatchHighVec

  override val controlMods: Seq[CSRModule[_]] = Seq(
    trTeControl,
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

}


class TrTeControlBundle extends CSRBundle {
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
