package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import trace.TraceParameters

class OneROFieldBundle extends OneFieldBundle {
  this.ALL.setRO().withReset(0.U)
}

object MajorVersion extends CSREnum with ROApply {
  val Default = Value(1.U)
}
object MinorVersion extends CSREnum with ROApply {
  val Default = Value(0.U)
}

object MajorProtocol extends CSREnum with ROApply {
  val EDefault = Value(0.U) // Must be 0 to indicate this version (2.0.x) of the E-Trace protocol
  val NDefault = Value(1.U)
}
object MinorProtocol extends CSREnum with ROApply {
  val Default = Value(0.U)
}

object TrcompTypeField extends CSREnum with ROApply {
  val Encoder   = Value(0x1.U)
  val Funnel    = Value(0x8.U)
  val RAM       = Value(0x9.U)
  val PIB       = Value(0xa.U)
  val ATBBridge = Value(0xe.U)
}

/**
  * RAM Sink
  */

class RAMControlBundle extends CSRBundle with TraceParameters {
  val Active     = RW(0)
  val Enable     = RW(1)
  val Emptry     = RO(3)
  val Mode       = RAMModeROField(4).withReset(if (supportSRAM) RAMModeROField.SRAM else RAMModeROField.SMEM)
  val StopOnWrap = RW(8)
  val MemFormat  = MemFormatField(10, 9, wNoFilter).withReset(MemFormatField.PlainBytes)
  val AsyncFreq  = RW(14, 12)
}

object RAMModeROField extends CSREnum with ROApply {
  val SRAM = Value(0.U)
  val SMEM = Value(1.U)
}

object MemFormatField extends CSREnum with WARLApply {
  val PlainBytes = Value(0.U)
  val Custom     = Value(3.U)

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(PlainBytes, Custom)
}

class RAMImplBundle extends CSRBundle with TraceParameters {
  val VerMajor = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor = MinorVersion(7, 0).withReset(MinorVersion.Default)
  val CompType = TrcompTypeField(11, 8).withReset(TrcompTypeField.RAM)
  val HasSRAM  = RO(12).withReset(supportSRAM.B)
  val HasSMEM  = RO(13).withReset(supportSMEM.B)
}

class RAMAddrLowBundle extends CSRBundle {
  val Low = RW(31, 2)
}

class RAMPointerBundle extends RAMAddrLowBundle {
  val Wrap = RW(0).withReset(0.U)
}

trait HasRAMRdataBundle { self: CSRModule[_] =>
  val readData = IO(Input(UInt(32.W)))
}

trait HasWPBundle { self: CSRModule[_] =>
  val wrap = IO(Input(Bool()))
  val wp   = IO(Input(UInt(62.W)))
}

trait HasRPBundle { self: CSRModule[_] =>
  val rp = IO(Input(UInt(62.W)))
}

trait HasRamCtrlBundle { self: CSRModule[_] =>
  val ramWrapStop = IO(Input(Bool()))
}

trait HasCtrlBundle { self: CSRModule[_] =>
  val fifoIsEmpty = IO(Input(Bool()))
  val fifoIsFull  = IO(Input(Bool()))
}
