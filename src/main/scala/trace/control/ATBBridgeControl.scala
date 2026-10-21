package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import trace.Trace
import trace.component.ATBBridgeImp

trait ATBBridgeControl extends ControlBase { self: ATBBridgeImp =>

  val ATBControl = Module(new CSRModule("ATBBridgeControl", new ATBControlBundle) with HasCtrlBundle {
    regOut.Active := !reset.asBool
    regOut.Emptry := fifoIsEmpty
  })
    .setAddr(CtrlReg.ATBBridgeControl)

  val ATBImpl = Module(new CSRModule("ATBBridgeImpl", new ATBImplBundle) {})
    .setAddr(CtrlReg.ATBBridgeImpl)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    ATBControl,
    ATBImpl
  )
}

class ATBControlBundle extends CSRBundle {
  val Active = RW(0).withReset(0.U)
  val Enable = RW(1).withReset(0.U)
  val Emptry = RO(3).withReset(1.U)
  val ID     = IDField(14, 8, wNoFilter).withReset(IDField.Default)
}

object IDField extends CSREnum with WARLApply {
  val Default = Value(1.U)

  override def isLegal(enumeration: CSREnumType): Bool = {
    val id       = enumeration.asUInt
    val notLegal = (id === 0.U) || (id >= 0x70.U && id < 0x80.U)
    !notLegal
  }
}

class ATBImplBundle extends CSRBundle {
  val VerMajor  = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor  = MinorVersion(7, 4).withReset(MinorVersion.Default)
  val CompType  = TrcompTypeField(11, 8).withReset(TrcompTypeField.ATBBridge)
  val AsyncFreq = RW(14, 12).withReset(0.U)
}
