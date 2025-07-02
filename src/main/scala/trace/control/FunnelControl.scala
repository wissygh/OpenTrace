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

object FunnelAddr {
  val trFunnelControl = 0x000
  val trFunnelImpl = 0x004
  val trFunnelDisInput = 0x008

  // Timestamp control
  val trTsControl = 0x040
  val trTsCounterLow = 0x048
  val trTsCounterHigh = 0x04c
}

trait FunnelControl extends ControlBase { self: Funnel =>

  val trFunnelContrl = Module(new CSRModule("TrFunnelControl", new TrFunnelContrlBundle))
    .setAddr(FunnelAddr.trFunnelControl)

  val trFunnelImpl = Module(new CSRModule("TrFunnelImpl", new TrFunnelImplBundle){})
    .setAddr(FunnelAddr.trFunnelImpl)

  val trFunnelDisInput = Module(new CSRModule("trFunnelDisInput", new TrFunnelDisInputBundle){})
    .setAddr(FunnelAddr.trFunnelDisInput)
  
  /**
   * timestamp todo: fix name
   */
  val trTsControl = Module(new CSRModule("trTsControl", new OneROFieldBundle){})
    .setAddr(FunnelAddr.trTsControl)

  val trTsCounterLow = Module(new CSRModule("trTsCounterLow", new OneROFieldBundle){})
    .setAddr(FunnelAddr.trTsCounterLow)

  val trTsCounterHigh = Module(new CSRModule("trTsCounterHigh", new OneROFieldBundle){})
    .setAddr(FunnelAddr.trTsCounterHigh)
  

  override val controlMods: Seq[CSRModule[_]] = Seq(
    trFunnelContrl,
    trFunnelImpl,
    trFunnelDisInput,
    trTsControl,
    trTsCounterLow,
    trTsCounterHigh,
  )

  override val controlRWMap: SeqMap[Int, (CSRAddrWriteBundle[_], UInt)] = SeqMap.from(
    controlMods.map(reg => (reg.addr -> (reg.w -> reg.rdata))).iterator
  )

  override val controlOutMap: SeqMap[Int, UInt] = SeqMap.from(
    controlMods.map(reg => (reg.addr -> reg.regOut.asInstanceOf[CSRBundle].asUInt)).iterator
  )

}


class TrFunnelContrlBundle extends CSRBundle {
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
