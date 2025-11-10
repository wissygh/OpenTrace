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
import trace.component.FunnelImp

trait FunnelControl extends ControlBase { self: FunnelImp =>

  val funnelControl = Module(new CSRModule("FunnelControl", new FunnelControlBundle) with HasCtrlBundle {
    regOut.Active := !reset.asBool
    reg.Emptry := fifoIsEmpty
  })
    .setAddr(CtrlReg.FunnelControl)

  val funnelImpl = Module(new CSRModule("FunnelImpl", new FunnelImplBundle) {})
    .setAddr(CtrlReg.FunnelImpl)

  val funnelDisInput = Module(new CSRModule("FunnelDisInput", new FunnelDisInputBundle) {})
    .setAddr(CtrlReg.FunnelDisInput)

  /**
   * timestamp todo: fix name
   */
  val trTsControl = Module(new CSRModule("trTsControl", new OneROFieldBundle) {})
    .setAddr(CtrlReg.TsControl)

  val trTsCounterLow = Module(new CSRModule("trTsCounterLow", new OneROFieldBundle) {})
    .setAddr(CtrlReg.TsCounterLow)

  val trTsCounterHigh = Module(new CSRModule("trTsCounterHigh", new OneROFieldBundle) {})
    .setAddr(CtrlReg.TsCounterHigh)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    funnelControl,
    funnelImpl,
    funnelDisInput,
    trTsControl,
    trTsCounterLow,
    trTsCounterHigh
  )

}

class FunnelControlBundle extends CSRBundle {
  val Active = RW(0).withReset(0.U)
  val Enable = RW(1).withReset(0.U)
  val Emptry = RO(3)
}

class FunnelImplBundle extends CSRBundle {
  val VerMajor = MajorVersion(3, 0).withReset(MajorVersion.Default)
  val VerMinor = MinorVersion(7, 0).withReset(MinorVersion.Default)
  val CompType = TrcompTypeField(11, 8).withReset(TrcompTypeField.Funnel)
}

class FunnelDisInputBundle extends CSRBundle {
  val DisInput = RW(15, 0)
}
