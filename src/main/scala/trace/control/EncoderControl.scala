package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.component.EncoderImp

class EncoderTraceControlRegister extends CSRModule("TeControl", new TeControlBundle)
    with HasTeCtrlBundle with HasCtrlBundle {
  regOut.Active := !reset.asBool

  when(wen) {
    reg.InstTracing := wdata.InstTracing && wdata.Enable
  }.elsewhen((traceOn || traceOff) & reg.InstTrigEnable.asBool & reg.Enable.asBool) {
    // Projected trace-on qualifies the current group; trace-off wins after it.
    reg.InstTracing := traceOn && !traceOff
  }

  // RW1C: a zero write preserves the sticky status. A new hardware event wins
  // a simultaneous clear, so software cannot erase an ongoing stall/loss.
  reg.InstStallOrOverflow := reg.InstStallOrOverflow
  when(wen && (wdata.Enable || wdata.InstStallOrOverflow)) {
    reg.InstStallOrOverflow := false.B
  }
  when(fifoIsFull) { reg.InstStallOrOverflow := true.B }

  regOut.Emptry := fifoIsEmpty
}

class EncoderInstructionFeaturesRegister extends CSRModule("TeInstFeatures", new TeInstFeaturesBundle)
    with HasTeInstFeaturesBundle {
  regOut.SrcID := id
}

trait EncoderControl extends ControlBase { self: EncoderImp =>

  val teControl = Module(new EncoderTraceControlRegister).setAddr(CtrlReg.TeControl)

  val teImpl = Module(new CSRModule("TeImpl", new TeImplBundle))
    .setAddr(CtrlReg.TeImpl)

  val teInstFeatures = Module(new EncoderInstructionFeaturesRegister)
    .setAddr(CtrlReg.TeInstFeatures)

  val teInstFilters = Module(new CSRModule("TeInstFilters", new TeInstFiltersBundle) {})
    .setAddr(CtrlReg.TeInstFilters)

  val teDataControl = Module(new CSRModule("TeDataControl", new TeDataControlBundle) {})
    .setAddr(CtrlReg.TeDataControl)

  val teDataFilters = Module(new CSRModule("TeDataFilters", new TeDataFiltersBundle) {})
    .setAddr(CtrlReg.TeDataFilters)

  /**
   * timestamp
   */
  val tsControl = Module(new EncoderTimestampControlRegister)
    .setAddr(CtrlReg.TsControl)

  val tsCounterLow = Module(new CSRModule("TsCounterLow", new OneROFieldBundle) with HasTsCountBundle {
    regOut.ALL := timeCount(31, 0)
  })
    .setAddr(CtrlReg.TsCounterLow)

  val tsCounterHigh = Module(new CSRModule("TsCounterHigh", new OneROFieldBundle) with HasTsCountBundle {
    regOut.ALL := timeCount(63, 32)
  })
    .setAddr(CtrlReg.TsCounterHigh)

  /**
   * Filter:
   *  logical or : all enabled filters' condition;
   *  logical and: all enabled conditions in a single filter.
   */
  val teFilterControlVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"Control", new TeFilterControlBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0Control, i))
  )

  val teFilterMatchInstVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"MatchInst", new TeFilterMatchInstBundle) {})
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0MatchInst, i))
  )

  val teFilterMatchEcauseLowVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"MatchEcauseLow", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0MatchEcauseLow, i))
  )

  val teFilterMatchEcauseHighVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"MatchEcauseHigh", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0MatchEcauseHigh, i))
  )

  val teFilterMatchValueImpdefVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"MatchValueImpdef", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0MatchValueImpdef, i))
  )

  val teFilterMatchMaskImpdefVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"MatchMaskImpdef", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0MatchMaskImpdef, i))
  )

  val teFilterMatchDataVec: Seq[CSRModule[_]] = (0 until filter_num).map(i =>
    Module(new CSRModule(s"TeFilter$i" + s"MatchData", new TeFilterMatchDataBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeFilter0MatchData, i))
  )

  /**
   * Compatator
   */
  val teCompControlVec: Seq[CSRModule[_]] = (0 until comparator_num).map(j =>
    Module(new CSRModule(s"TeComp$j" + s"Control", new TeCompControlBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeComp0Control, j))
  )

  val teCompPMatchLowVec: Seq[CSRModule[_]] = (0 until comparator_num).map(j =>
    Module(new CSRModule(s"TeComp$j" + s"PMatchLow", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeComp0PMatchLow, j))
  )

  val teCompPMatchHighVec: Seq[CSRModule[_]] = (0 until comparator_num).map(j =>
    Module(new CSRModule(s"TeComp$j" + s"PMatchHigh", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeComp0PMatchHigh, j))
  )

  val teCompSMatchLowVec: Seq[CSRModule[_]] = (0 until comparator_num).map(j =>
    Module(new CSRModule(s"TeComp$j" + s"SMatchLow", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeComp0SMatchLow, j))
  )

  val teCompSMatchHighVec: Seq[CSRModule[_]] = (0 until comparator_num).map(j =>
    Module(new CSRModule(s"TeComp$j" + s"SMatchHigh", new OneFieldBundle))
      .setAddr(CtrlReg.filterCompAddrGen(CtrlReg.TeComp0SMatchHigh, j))
  )

  /**
   * custom hpm
   */
  val teHpmAddrVec = Option.when(supportHPM)(Range(0, hpmevent_num).map(i =>
    Module(new CSRModule(s"TeHpmAddr$i", new HpmAddrBundle))
      .setAddr(CtrlReg.hpmAddrGen(CtrlReg.TeHpm0Addr, i))
  ))

  val teHpmEventLowVec = Option.when(supportHPM)(Range(0, hpmevent_num).map(i =>
    Module(new CSRModule(s"TeHpmEventLow$i", new HpmEventLowBundle))
      .setAddr(CtrlReg.hpmAddrGen(CtrlReg.TeHpm0EventLow, i))
  ))

  val teHpmEventHighVec = Option.when(supportHPM)(Range(0, hpmevent_num).map(i =>
    Module(new CSRModule(s"TeHpmEventHigh$i", new HpmEventHighBundle))
      .setAddr(CtrlReg.hpmAddrGen(CtrlReg.TeHpm0EventHigh, i))
  ))

  val teHpmEventDataVec = Option.when(supportHPM)(Range(0, hpmevent_num).map(i =>
    Module(new CSRModule(s"TeHpmEventData$i", new HpmEventDataBundle) with HasHpmEventDataBundle {
      reg.ALL := inc
    })
      .setAddr(CtrlReg.hpmAddrGen(CtrlReg.TeHpm0EventData, i))
  ))

  val filterMods: Seq[CSRModule[_]] = teFilterControlVec ++ teFilterMatchInstVec ++
    teFilterMatchEcauseLowVec ++ teFilterMatchEcauseHighVec ++
    teFilterMatchValueImpdefVec ++ teFilterMatchMaskImpdefVec ++
    teFilterMatchDataVec

  val comptatorMods: Seq[CSRModule[_]] = teCompControlVec ++
    teCompPMatchLowVec ++ teCompPMatchHighVec ++
    teCompSMatchLowVec ++ teCompSMatchHighVec

  val hpmMods: Seq[CSRModule[_]] = if (supportHPM)
    teHpmAddrVec.get ++ teHpmEventLowVec.get ++ teHpmEventHighVec.get ++ teHpmEventDataVec.get
  else Seq()

  override val controlMods: Seq[CSRModule[_]] = Seq(
    teControl,
    teImpl,
    teInstFeatures,
    teInstFilters,
    teDataControl,
    teDataFilters,
    tsControl,
    tsCounterLow,
    tsCounterHigh
  ) ++ filterMods ++ comptatorMods ++ hpmMods

}

class TeControlBundle extends CSRBundle with TraceParameters {
  val Active      = RW(0).withReset(0.U)
  val Enable      = RW(1).withReset(0.U)
  val InstTracing = if (test_xsEmu) RW(2).withReset(1.U) else RW(2).withReset(0.U)
  val Emptry      = RO(3).withReset(1.U) // Reads as 1 when all generated trace have been emitted.
  val InstMode            = InstModeROField(6, 4).withReset(InstModeROField.VendorDef)
  val Context             = RO(9).withReset(0.U) // E-Trace Chapter 2, Table 2
  val InstTrigEnable      = RW(11).withReset(0.U)
  val InstStallOrOverflow = RW(12).withReset(0.U)
  val InstStallEna        = RW(13).withReset(0.U)                                              // 0: drop 1: stall
  val InhibitSrc          = RO(15).withReset(0.U)
  val InstSyncMode        = InstSyncModeROField(17, 16).withReset(InstSyncModeROField.Packets) // only support Packets
  val InstSyncMax =
    InstSyncMaxField(23, 20, wNoFilter).withReset(
      InstSyncMaxField.Default
    ) // counter >= 2^(InstSyncMax + 4), send sync package.
  val Format = FormatROField(26, 24).withReset(FormatROField.Etrace)
}

object InstModeROField extends CSREnum with ROApply {
  val DisableInstTrace = Value(0.U)
  val BTM              = Value(3.U) // branch taken trace
  val HTM              = Value(6.U) // history trace message
  val VendorDef        = Value(7.U)

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(DisableInstTrace, BTM, HTM)
}

object InstSyncModeROField extends CSREnum with ROApply {
  val Off          = Value(0.U)
  val Packets      = Value(1.U)
  val Cycles       = Value(2.U)
  val InstHalfWord = Value(3.U)
}

object InstSyncMaxField extends CSREnum with WARLApply with TraceParameters {
  val Default = Value(2.U)
}

object FormatROField extends CSREnum with ROApply {
  val Etrace = Value(0.U)
  val Ntrace = Value(1.U)
}

class TeImplBundle extends CSRBundle {
  val VerMajor      = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor      = MinorVersion(7, 4).withReset(MinorVersion.Default)
  val CompType      = TrcompTypeField(11, 8).withReset(TrcompTypeField.Encoder)
  val ProtocolMajor = MajorProtocol(19, 16).withReset(MajorProtocol.EDefault)
  val ProtocolMinor = MinorProtocol(23, 20).withReset(MinorProtocol.Default)
}

/**
 * Instruction trace bundle
 */

class TeInstFeaturesBundle extends CSRBundle with TraceParameters {
  val NoAddrDiff        = (if (supportFullAddr) RW(0) else RO(0)).withReset(0.U)
  val NoTrapAddr        = RO(1).withReset(0.U)
  val EnSiJump          = RO(2).withReset(0.U)
  val EnImRet           = RO(3).withReset(0.U)
  val EnBranchPred      = RO(4).withReset(0.U)
  val EnJumpTargetCache = RO(5).withReset(0.U)
  val ImRetMode         = RO(7, 6).withReset(0.U)
  val EnRepHistoty      = RO(8).withReset(0.U)
  val EnAllJumps        = RO(9).withReset(0.U)
  val ExtendAddrMSB     = RO(10).withReset(0.U)
  val SrcID             = RO(27, 16)
  val SrcBits           = SrcBitsROField(31, 28).withReset(SrcBitsROField.Default)
}

object ImpRetModeField extends CSREnum with WARLApply { // https://github.com/riscv-non-isa/riscv-trace-spec/issues/240
  val None     = Value(0.U)
  val Simple   = Value(1.U)
  val Partial  = Value(2.U)
  val FullAddr = Value(3.U)
}

object SrcBitsROField extends CSREnum with ROApply with TraceParameters {
  val Default = Value(src_bits_p.U)
}

class TeInstFiltersBundle extends CSRBundle with TraceParameters {
  override val len: Int = filter_num
  val InstFilters =
    FiltersIndexField(len - 1, 0, wNoFilter).withReset(FiltersIndexField.None) // 1hot: bit_i -> filter_i
}

object FiltersIndexField extends CSREnum with WARLApply {
  val None = Value(0.U)
  override def isLegal(enumeration: CSREnumType): Bool = true.B // filter selection is a bit mask
}

/**
 * data trace bundle
 */

class TeDataControlBundle extends CSRBundle {
  val DataImplemented  = RO(0).withReset(0.U)
  val DataTracing      = RO(1).withReset(0.U)
  val DataTrigEnable   = RO(2).withReset(0.U)
  val DataStallOrOf    = RO(3).withReset(0.U)
  val DataStallEna     = RO(4).withReset(0.U)
  val DataDrop         = RO(5).withReset(0.U)
  val DataDropEna      = RO(6).withReset(0.U)
  val DataNoValue      = RO(16).withReset(0.U)
  val DataNoAddr       = RO(17).withReset(0.U)
  val DataAddrCompress = RO(19, 18).withReset(0.U)
}

object DataAddrCompressField extends CSREnum with WARLApply {
  val FullAddr       = Value(0.U)
  val XOR            = Value(1.U)
  val Diff           = Value(2.U)
  val PtoCompression = Value(3.U)
}

class TeDataFiltersBundle extends CSRBundle {
  val DataFilters = RO(15, 0).withReset(0.U)
}

object TrigAction extends CSREnum with WARLApply {
  val NoAction    = Value(0.U)
  val TraceOn     = Value(2.U)
  val TraceOff    = Value(3.U)
  val TraceNotify = Value(4.U)

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(NoAction, TraceOn, TraceOff, TraceNotify)
}

object TrigEvent extends CSREnum with WARLApply {
  val Start = Value(0.U)
  val Stop  = Value(2.U)

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(Start, Stop)
}

/**
 * filter bundle
 */

class TeFilterControlBundle extends CSRBundle {
  val Enable         = RW(0).withReset(0.U)  // set 1 after all is ready.
  val MatchPrivilege = RW(1).withReset(0.U)  // TeFilterMatchInst.MatchChoicePrivilege; set 1 after all is ready.
  val MatchEcause    = RW(2).withReset(0.U)  // TeFilterMatchChoiceEcause
  val MatchInterrupt = RW(3).withReset(0.U)  // TeFilterMatchInst.MatchValueInterrupt
  val MatchComp1     = RW(4).withReset(0.U)
  val Comp1          = CompField(7, 5, wNoFilter).withReset(CompField.Zero)
  val MatchComp2     = RW(8).withReset(0.U)
  val Comp2          = CompField(11, 9, wNoFilter).withReset(CompField.Zero)
  val MatchComp3     = RW(12).withReset(0.U)
  val Comp3          = CompField(15, 13, wNoFilter).withReset(CompField.Zero)
  val MatchImpdef    = RW(16).withReset(0.U) // trTeFilterMatchValueImpdef & trTeFilterMatchMaskImpdef
  val MatchDtype     = RO(24).withReset(0.U) // TeFilterMatchData.MatchChoiceDtype
  val MatchDSize     = RO(25).withReset(0.U) // TeFilterMatchData.MatchChoiceDsize
}

class TeFilterMatchInstBundle extends CSRBundle {
  val MatchChoicePrivilege = MatchPriv(7, 0, wNoFilter).withReset(MatchPriv.HU)
  val MatchValueInterrupt  = MatchExpInt(8, wNoFilter).withReset(MatchExpInt.Exception)
}

object MatchPriv extends CSREnum with WARLApply { // any combination of implemented privilege bits
  val HU = Value(1.U)
  val HS = Value(2.U)
  val M  = Value(8.U)
  val D  = Value(16.U)
  val VU = Value(32.U)
  val VS = Value(64.U)
  override def isLegal(enumeration: CSREnumType): Bool = !(enumeration.asUInt & "h84".U).orR
}

object MatchExpInt extends CSREnum with WARLApply {
  val Exception = Value(0.U)
  val Interrupt = Value(1.U)
}

object CompField extends CSREnum with WARLApply with TraceParameters {
  val Zero = Value(0.U)
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.asUInt < comparator_num.U
}

class TeFilterMatchDataBundle extends CSRBundle {
  val MatchChoiceDtype = RO(15, 0).withReset(0.U)
  val MatchChoiceDsize = RO(23, 16).withReset(0.U)
}

object MatchDtype extends CSREnum with WARLApply { // 1h, F**king riscv trace spec
  val Load  = Value(1.U)
  val Store = Value(2.U)

  val CSRRW = Value(16.U)
  val CSRRS = Value(32.U)
  val CSRRC = Value(64.U)

  val AtomicSwap = Value(256.U)
  val AtomicAdd  = Value(512.U)
  val AtomicAND  = Value(1024.U)
  val AtomicOR   = Value(2048.U)
  val AtomicXOR  = Value(4096.U)
  val AtomicMax  = Value(8192.U)
  val AtomicMin  = Value(16384.U)
  val CondStFail = Value(32768.U)
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(
    Load,
    Store,
    CSRRW,
    CSRRS,
    CSRRC,
    AtomicSwap,
    AtomicAdd,
    AtomicAND,
    AtomicOR,
    AtomicXOR,
    AtomicMax,
    AtomicMin,
    CondStFail
  )
}

object MatchDsize extends CSREnum with WARLApply {
  val B_1 = Value(1.U)
  override def isLegal(enumeration: CSREnumType): Bool = PopCount(enumeration.asUInt) === 1.U
}

class TeCompControlBundle extends CSRBundle {
  val PInput    = CompInputField(1, 0, wNoFilter).withReset(CompInputField.Iaddr)
  val SInput    = CompInputField(3, 2, wNoFilter).withReset(CompInputField.Iaddr)
  val PFunc     = CompPFuncField(6, 4, wNoFilter).withReset(CompPFuncField.Equal)
  val SFunc     = CompSFuncField(10, 8, wNoFilter).withReset(CompSFuncField.Equal)
  val MatchMode = MatchModeField(13, 12, wNoFilter).withReset(MatchModeField.P)
  val PNotify   = RW(14).withReset(0.U)
  val SNotify   = RW(15).withReset(0.U)
}

object CompInputField extends CSREnum with WARLApply {
  val Iaddr   = Value(0.U)
  val Context = Value(1.U)
  val Tval    = Value(2.U)
  val Daddr   = Value(3.U)

  def isIaddr(enumeration:          CSREnumType): Bool = enumeration.isOneOf(Iaddr)
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(Iaddr, Context, Tval)
}

object CompPFuncField extends CSREnum with WARLApply {
  val Equal            = Value(0.U)
  val NotEqual         = Value(1.U)
  val LessThan         = Value(2.U)
  val LessEqual        = Value(3.U)
  val GreaterThan      = Value(4.U)
  val GreaterEqual     = Value(5.U)
  val AlwaysFalseLatch = Value(6.U) // TODO
  val AlwaysTrue       = Value(7.U)
}

object CompSFuncField extends CSREnum with WARLApply {
  val Equal          = Value(0.U)
  val NotEqual       = Value(1.U)
  val LessThan       = Value(2.U)
  val LessEqual      = Value(3.U)
  val GreaterThan    = Value(4.U)
  val GreaterEqual   = Value(5.U)
  val AlwaysTrueMask = Value(6.U) // TODO
  val AlwaysTrue     = Value(7.U)
}

object MatchModeField extends CSREnum with WARLApply {
  val P       = Value(0.U)
  val PS      = Value(1.U)
  val NonPS   = Value(2.U)
  val PUntilS = Value(3.U)
}

class HpmAddrBundle extends CSRBundle {
  val Addr = HpmAddrField(4, 0, wNoFilter).withReset(HpmAddrField.Disable)
}

object HpmAddrField extends CSREnum with WARLApply {
  val Disable = Value(0.U)

  override def isLegal(enumeration: CSREnumType): Bool = (enumeration.asUInt >= 3.U) && (enumeration.asUInt <= 31.U)
}

// from xs mhpmevent
class HpmeventBundle extends CSRBundle {
  val OF      = RW(63).withReset(0.U)
  val MINH    = RW(62).withReset(0.U)
  val SINH    = RW(61).withReset(0.U)
  val UINH    = RW(60).withReset(0.U)
  val VSINH   = RW(59).withReset(0.U)
  val VUINH   = RW(58).withReset(0.U)
  val OPTYPE2 = OPTYPE(54, 50, wNoFilter).withReset(OPTYPE.OR)
  val OPTYPE1 = OPTYPE(49, 45, wNoFilter).withReset(OPTYPE.OR)
  val OPTYPE0 = OPTYPE(44, 40, wNoFilter).withReset(OPTYPE.OR)
  val EVENT3  = RW(39, 30).withReset(0.U)
  val EVENT2  = RW(29, 20).withReset(0.U)
  val EVENT1  = RW(19, 10).withReset(0.U)
  val EVENT0  = RW(9, 0).withReset(0.U)
}

class HpmEventLowBundle extends CSRBundle {
  val EVENT3Low = RW(31, 30).withReset(0.U)
  val EVENT2    = RW(29, 20).withReset(0.U)
  val EVENT1    = RW(19, 10).withReset(0.U)
  val EVENT0    = RW(9, 0).withReset(0.U)
}

class HpmEventHighBundle extends CSRBundle {
  val OPTYPE2    = OPTYPE(22, 18, wNoFilter).withReset(OPTYPE.OR)
  val OPTYPE1    = OPTYPE(17, 13, wNoFilter).withReset(OPTYPE.OR)
  val OPTYPE0    = OPTYPE(12, 8, wNoFilter).withReset(OPTYPE.OR)
  val EVENT3High = RW(7, 0).withReset(0.U)
}

object OPTYPE extends CSREnum with WARLApply {
  val OR  = Value(0.U)
  val AND = Value(1.U)
  val XOR = Value(2.U)
  val ADD = Value(4.U)

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(OR, AND, XOR, ADD)
}

class HpmEventDataBundle extends CSRBundle with TraceParameters {
  val ALL = RO(5, 0).withReset(0.U)
}

/**
 * external io for reg module
 */

trait HasTeCtrlBundle { self: CSRModule[_] =>
  val traceOn  = IO(Input(Bool()))
  val traceOff = IO(Input(Bool()))
}

trait HasTeInstFeaturesBundle { self: CSRModule[_] =>
  val id = IO(Input(UInt(12.W)))
}

trait HasTsCountBundle { self: CSRModule[_] =>
  val timeCount = IO(Input(UInt(64.W)))
}

trait HasHpmEventDataBundle { self: CSRModule[_] =>
  val inc = IO(Input(UInt(6.W)))
}
