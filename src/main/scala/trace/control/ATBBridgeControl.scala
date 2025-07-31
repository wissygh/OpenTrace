package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.Trace
import trace.component.ATBBridgeImp

trait ATBBridgeControl extends ControlBase { self: ATBBridgeImp =>

  val ATBControl = Module(new CSRModule("ATBBridgeControl", new ATBControlBundle))
    .setAddr(CtrlReg.ATBBridgeControl)

  val ATBImpl = Module(new CSRModule("ATBBridgeImpl", new ATBImplBundle){})
    .setAddr(CtrlReg.ATBBridgeImpl)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    ATBControl,
    ATBImpl,
  )
}


class ATBControlBundle extends CSRBundle {
  val Active              = RW(0)
  val Enable              = RW(1)
  val Emptry              = RO(3)
  val ID                  = IDField(14, 8, wNoFilter).withReset(IDField.Default)
}

object IDField extends CSREnum with WARLApply {
  val Default          = Value(1.U)

  override def isLegal(enumeration: CSREnumType): Bool = {
    val id = enumeration.asUInt
    val notLegal = (id === 0.U) || (id >= 0x70.U && id < 0x80.U)
    !notLegal
  }
}

class ATBImplBundle extends CSRBundle {
  val VerMajor      = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor      = MinorVersion(7, 0).withReset(MinorVersion.Default)
  val CompType      = TrcompTypeField(11, 8).withReset(TrcompTypeField.ATBBridge)
  val AsyncFreq     = RW(14, 12).withReset(0.U)
}