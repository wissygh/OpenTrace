package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.{Trace, TraceParameters}
import trace.component.RAM

/**
 * Single RAM Sink may support both SRAM and SMEM modes, but not both may be enabled
 * at the same time. It is also possible to have more than one RAM Sink in a system.
 *
 * Trace RAM Sink may be instantiated or configured to support storing trace into dedicated SRAM or system memory.
 * Dedicated SRAM memory must be read via dedicated trRamData register, while memory in SMEM
 * mode should be read as any other memory on system bus - for example using SBA (System Bus
 * Access) access mode as defined in the RISC-V Debug Specification.
 *
 */

trait RAMControl extends ControlBase { self: RAM =>

  val ramControl = Module(new CSRModule("RAMControl", new RAMControlBundle))
    .setAddr(CtrlReg.RAMControl)

  val ramImpl = Module(new CSRModule("RAMImpl", new RAMImplBundle))
    .setAddr(CtrlReg.RAMImpl)

  val ramStartLow = Module(new CSRModule("RAMStartLow",new RAMAddrLowBundle) with HasRAMTypeBundle {
    when(hasSRAM && (mode === RAMModeField.SRAM.asUInt)) {
      regOut := 0.U
    }.elsewhen(hasSMEM && (mode === RAMModeField.SMEM.asUInt)){
      regOut := fixedStartAddr.U(64.W).tail(32)
    }
  })
    .setAddr(CtrlReg.RAMStartLow)

  val ramStartHigh = Module(new CSRModule("RAMStartHigh",new OneFieldBundle) with HasRAMTypeBundle {
    when(hasSRAM && (mode === RAMModeField.SRAM.asUInt)) {
      regOut := 0.U
    }.elsewhen(hasSMEM && (mode === RAMModeField.SMEM.asUInt)){
      regOut := fixedStartAddr.U(64.W).head(32)
    }
  })
    .setAddr(CtrlReg.RAMStartHigh)

  val ramLimitLow = Module(new CSRModule("RAMLimitLow",new RAMAddrLowBundle) with HasRAMTypeBundle {
    when(hasSRAM && (mode === RAMModeField.SRAM.asUInt)) {
      regOut := ramLimitAddr.U(64.W).tail(32)
    }.elsewhen(hasSMEM && (mode === RAMModeField.SMEM.asUInt)){
      regOut := fixedLimitAddr.U(64.W).tail(32)
    }
  })
    .setAddr(CtrlReg.RAMLimitLow)

  val ramLimitHigh = Module(new CSRModule("RAMLimitHigh",new OneFieldBundle) with HasRAMTypeBundle {
    when(hasSRAM && (mode === RAMModeField.SRAM.asUInt)) {
      regOut := ramLimitAddr.U(64.W).head(32)
    }.elsewhen(hasSMEM && (mode === RAMModeField.SMEM.asUInt)){
      regOut := fixedLimitAddr.U(64.W).head(32)
    }
  })
    .setAddr(CtrlReg.RAMLimitHigh)

  val ramWPLow = Module(new CSRModule("RAMWPLow",new RAMPointerBundle)
    with HasWPBundle {
    when(wen && !wdata.Wrap.asBool){
      reg.Wrap := false.B
    }.elsewhen(wrap){ // wraps
      reg.Wrap := true.B
    }
    regOut.Low := wp.tail(32)
  })
    .setAddr(CtrlReg.RAMWPLow)

  val ramWPHigh = Module(new CSRModule("RAMWPHigh",new FieldInitBundle) with HasWPBundle {
    regOut.ALL := wp.head(32)
  })
    .setAddr(CtrlReg.RAMWPHigh)

  val ramRPLow = Module(new CSRModule("RAMRPLow",new RAMAddrLowBundle) with HasRPBundle {
    regOut.Low := rp.tail(32)
  })
    .setAddr(CtrlReg.RAMRPLow)

  val ramRPHigh = Module(new CSRModule("RAMRPHigh",new FieldInitBundle) with HasRPBundle {
    regOut.ALL := rp.head(32)
  })
    .setAddr(CtrlReg.RAMRPHigh)

  val ramData = Module(new CSRModule("RAMRPHigh",new OneROFieldBundle)
    with HasRAMTypeBundle
    with HasRAMRdataBundle {
    when(hasSRAM && mode === RAMModeField.SRAM.asUInt) {
      regOut := readData
    }.otherwise {
      regOut := 0.U
    }
  }) // Optional write is not supported
    .setAddr(CtrlReg.RAMData)


  override val controlMods: Seq[CSRModule[_]] = Seq(
    ramControl,
    ramImpl,
    ramStartLow,
    ramStartHigh,
    ramLimitLow,
    ramLimitHigh,
    ramWPLow,
    ramWPHigh,
    ramRPLow,
    ramRPHigh,
    ramData,
  )
}


class RAMControlBundle extends CSRBundle {
  val Active              = RW(0)
  val Enable              = RW(1)
  val Emptry              = RO(3)
  val Mode                = RAMModeField(4, wNoFilter).withReset(RAMModeField.SRAM)
  val StopOnWrap          = RW(8)
  val MemFormat           = MemFormatField(10, 9, wNoFilter).withReset(MemFormatField.PlainBytes)
  val AsyncFreq           = RW(14, 12)
}

object RAMModeField extends CSREnum with WARLApply {
  val SRAM    = Value(0.U)
  val SMEM    = Value(1.U)
}

object MemFormatField extends CSREnum with WARLApply {
  val PlainBytes    = Value(0.U)
  val Custom        = Value(3.U)

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(PlainBytes, Custom)
}

class RAMImplBundle extends CSRBundle with TraceParameters{
  val VerMajor      = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor      = MinorVersion(7, 0).withReset(MinorVersion.Default)
  val CompType      = TrcompTypeField(11, 8).withReset(TrcompTypeField.RAM)
  val HasSRAM       = RO(12).withReset(supportSRAM.B) // todo: sram or smem?
  val HasSMEM       = RO(13).withReset(supportSMEM.B)
}

class RAMAddrLowBundle extends CSRBundle {
  val Low = RW(31, 2)
}

class RAMPointerBundle extends RAMAddrLowBundle {
  val Wrap = RW(0).withReset(0.U)
}

trait HasRAMTypeBundle { self: CSRModule[_] =>
  val hasSRAM = IO(Input(Bool()))
  val hasSMEM = IO(Input(Bool()))
  val mode    = IO(Input(Bool()))
}

trait HasRAMRdataBundle { self: CSRModule[_] =>
  val readData = IO(Input(UInt(32.W)))
}

trait HasWPBundle { self: CSRModule[_] =>
  val wrap = IO(Input(Bool()))
  val wp = IO(Input(UInt(62.W)))
}

trait HasRPBundle { self: CSRModule[_] =>
  val rp = IO(Input(UInt(62.W)))
}