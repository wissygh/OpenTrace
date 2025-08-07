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

  val PIBControl = Module(new CSRModule("PIBControl", new PIBControlBundle) {
    when(wen && wdata.ClkCenter.asBool && PIBModeField.isParallel(wdata.Mode)) {
      reg.ClkCenter := true.B
    }
  })
    .setAddr(CtrlReg.PIBControl)

  val PIBImpl = Module(new CSRModule("PIBImpl", new PIBImplBundle) {})
    .setAddr(CtrlReg.PIBImpl)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    PIBControl,
    PIBImpl
  )
}

class PIBControlBundle extends CSRBundle {
  val Active    = RW(0)
  val Enable    = RW(1)
  val Emptry    = RO(3)
  val Mode      = PIBModeField(7, 4, wNoFilter).withReset(PIBModeField.Off)
  val ClkCenter = RW(8)
  val Calibrate = RW(9)
  val AsyncFreq = RW(14, 12)
  val Divider   = RW(31, 16)
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
  val VerMinor = MinorVersion(7, 0).withReset(MinorVersion.Default)
  val CompType = TrcompTypeField(11, 8).withReset(TrcompTypeField.PIB)
}

object CalibrationPaterns {
  val Data1  = 0xff_00_55_aa.U(32.W)                  // alternating 1/0, then all 0, then all 1
  val Data2  = 0x33_cc_66_66.U(32.W)                  // 2, 1, 2, 1, 2, 1, 2, 1, 0, 3, 0, 3, 3, 0, 3, 0
  val Data4  = 0x0f_f0_5a_5a.U(32.W)                  // A, 5, A, 5, 0, F, F, 0
  val Data8  = 0xff_00_55_aa.U(32.W)                  // AA, 55, 00, FF
  val Data16 = BigInt(0xffff_0000_5555_aaaaL).U(64.W) // AAAA, 5555, 0000, FFFF
}
