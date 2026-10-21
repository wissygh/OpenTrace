package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import scala.collection.immutable.SeqMap
import trace.Trace
import trace.component.PIBImp

trait PIBControl extends ControlBase { self: PIBImp =>

  val PIBControl = Module(new PIBControlRegister(wrapper.asInstanceOf[trace.component.PIB].hasSerializer))
    .setAddr(CtrlReg.PIBControl)

  val PIBImpl = Module(new CSRModule("PIBImpl", new PIBImplBundle) {})
    .setAddr(CtrlReg.PIBImpl)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    PIBControl,
    PIBImpl
  )
}

class PIBControlRegister(implemented: Boolean = false)
    extends CSRModule("PIBControl", new PIBControlBundle(implemented)) {
  val empty = Option.when(implemented)(IO(Input(Bool())))
  regOut.Active := !reset.asBool
  if (implemented) {
    regOut.Emptry := empty.get
    // Configuration cannot alter a queued or actively transmitted packet.
    reg.Mode      := reg.Mode
    reg.Divider   := reg.Divider
    reg.ClkCenter := reg.ClkCenter
    reg.Calibrate := reg.Calibrate
    reg.AsyncFreq := reg.AsyncFreq
    when(wen && !reg.Enable.asBool && empty.get) {
      when(wdata.Mode.isLegal)(reg.Mode := wdata.Mode)
      reg.Divider                       := wdata.Divider
      reg.ClkCenter                     := PIBModeField.isParallel(wdata.Mode) && wdata.ClkCenter.asBool
      reg.Calibrate                     := wdata.Calibrate
      reg.AsyncFreq                     := wdata.AsyncFreq
    }
  }
}

class PIBControlBundle(implemented: Boolean = false) extends CSRBundle {
  val Active = RW(0).withReset(0.U)
  val Enable = (if (implemented) RW(1) else RO(1)).withReset(0.U)
  val Emptry = RO(3).withReset(1.U)
  val Mode = if (implemented) PIBModeField(7, 4, wNoFilter).withReset(PIBModeField.Off)
  else RO(7, 4).withReset(0.U)
  val ClkCenter = (if (implemented) RW(8) else RO(8)).withReset(0.U)
  val Calibrate = (if (implemented) RW(9) else RO(9)).withReset(0.U)
  val AsyncFreq = (if (implemented) RW(14, 12) else RO(14, 12)).withReset(0.U)
  val Divider = if (implemented) PIBDividerField(31, 16, wNoFilter).withReset(PIBDividerField.Default)
  else RO(31, 16).withReset(0.U)
}

object PIBDividerField extends CSREnum with WARLApply {
  val Default = Value(31.U)
}

object PIBModeField extends CSREnum with WARLApply {
  val Off           = Value(0.U)
  val SWTManchester = Value(4.U)
  val SWTUART       = Value(5.U)
  val CLK_DATA1     = Value(8.U)
  val CLK_DATA2     = Value(9.U)
  val CLK_DATA4     = Value(10.U)
  val CLK_DATA8     = Value(11.U)
  val CLK_DATA16    = Value(12.U)

  def isParallel(enumeration: CSREnumType): Bool = enumeration.isOneOf(
    CLK_DATA1,
    CLK_DATA2,
    CLK_DATA4,
    CLK_DATA8,
    CLK_DATA16
  )

  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(Off, SWTManchester, SWTUART) |
    isParallel(enumeration)
}

class PIBImplBundle extends CSRBundle {
  val VerMajor = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor = MinorVersion(7, 4).withReset(MinorVersion.Default)
  val CompType = TrcompTypeField(11, 8).withReset(TrcompTypeField.PIB)
}

object CalibrationPaterns {
  val Data1  = 0xff_00_55_aa.U(32.W)                  // alternating 1/0, then all 0, then all 1
  val Data2  = 0x33_cc_66_66.U(32.W)                  // 2, 1, 2, 1, 2, 1, 2, 1, 0, 3, 0, 3, 3, 0, 3, 0
  val Data4  = 0x0f_f0_5a_5a.U(32.W)                  // A, 5, A, 5, 0, F, F, 0
  val Data8  = 0xff_00_55_aa.U(32.W)                  // AA, 55, 00, FF
  val Data16 = BigInt(0xffff_0000_5555_aaaaL).U(64.W) // AAAA, 5555, 0000, FFFF
}
