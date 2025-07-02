 package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.Trace
import trace.component.Funnel

import scala.collection.immutable.SeqMap
 
trait FunnelControl extends ControlBase { self: Funnel =>

  val trFunnelControl = Module(new CSRModule("TrFunnelControl", new TrFunnelControlBundle))
    .setAddr(CtrlReg.trFunnelControl)

  val trFunnelImpl = Module(new CSRModule("TrFunnelImpl", new TrFunnelImplBundle){})
    .setAddr(CtrlReg.trFunnelImpl)

  val trFunnelDisInput = Module(new CSRModule("trFunnelDisInput", new TrFunnelDisInputBundle){})
    .setAddr(CtrlReg.trFunnelDisInput)

  /**
   * timestamp todo: fix name
   */
  val trTsControl = Module(new CSRModule("trTsControl", new OneROFieldBundle){})
    .setAddr(CtrlReg.trTsControl)

  val trTsCounterLow = Module(new CSRModule("trTsCounterLow", new OneROFieldBundle){})
    .setAddr(CtrlReg.trTsCounterLow)

  val trTsCounterHigh = Module(new CSRModule("trTsCounterHigh", new OneROFieldBundle){})
    .setAddr(CtrlReg.trTsCounterHigh)


  override val controlMods: Seq[CSRModule[_]] = Seq(
    trFunnelControl,
    trFunnelImpl,
    trFunnelDisInput,
    trTsControl,
    trTsCounterLow,
    trTsCounterHigh,
  )


}


class TrFunnelControlBundle extends CSRBundle {
  val Active              = RW(0)
  val Enable              = RW(1)
  val Emptry              = RO(3)
}

class TrFunnelImplBundle extends CSRBundle {
  val VerMajor      = RO(3, 0)
  val VerMinor      = RO(7, 4)
  val CompType      = TrcompTypeFiled(11, 8, wNoFilter).withReset(TrcompTypeFiled.Funnel)
}

class TrFunnelDisInputBundle extends CSRBundle {
  val DisInput      = RW(15, 0)
}
