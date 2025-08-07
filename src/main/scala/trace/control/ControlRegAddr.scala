package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import scala.collection.immutable.SeqMap
import trace.component.ComponentBase

object CtrlReg {

  /**
   * Encoder Reg Address
   */
  val TeControl      = 0x000
  val TeImpl         = 0x004
  val TeInstFeatures = 0x008
  val TeInstFilters  = 0x00c

  // data trace control
  val TeDataControl = 0x010
  val TeDataFilters = 0x01c

  // Timestamp control
  val TsControl     = 0x040
  val TsCounterLow  = 0x048
  val TsCounterHigh = 0x04c

  // trigger control
  val TeTrigDbgControl    = 0x050
  val TeTrigExtInControl  = 0x054 // don't support
  val TeTrigExtOutControl = 0x058 // don't support

  // Filters & comparators
  // filters 0x400-0x5ff
  // comparator 0x600-0x7ff
  val TeFilter0Control          = 0x400
  val TeFilter0MatchInst        = 0x404
  val TeFilter0MatchEcauseLow   = 0x408
  val TeFilter0MatchEcauseHigh  = 0x40c
  val TeFilter0MatchValueImpdef = 0x410
  val TeFilter0MatchMaskImpdef  = 0x414
  val TeFilter0MatchData        = 0x418

  val TeComp0Control    = 0x600
  val TeComp0PMatchLow  = 0x610
  val TeComp0PMatchHigh = 0x614
  val TeComp0SMatchLow  = 0x618
  val TeComp0SMatchHigh = 0x61c

  def filterCompAddrGen(base: Int, num: Int): Int = base + 0x20 * num // todo：int or bigInt

  // hpm
  val TeHpm0Addr      = 0x900
  val TeHpm0EventLow  = 0x904
  val TeHpm0EventHigh = 0x908
  val TeHpm0EventData = 0x90c
  def hpmAddrGen(base: Int, num: Int): Int = base + 0x20 * num

  /**
   * Funnel Reg Address
   */
  val FunnelControl  = 0x000
  val FunnelImpl     = 0x004
  val FunnelDisInput = 0x008

  /**
   * AtbBridge Reg Address
   */
  val ATBBridgeControl = 0x000
  val ATBBridgeImpl    = 0x004

  /**
   * PIBSink Reg Address
   */
  val PIBControl = 0x000
  val PIBImpl    = 0x004

  /**
   * RAMSink Reg Address
   */
  val RAMControl = 0x000
  val RAMImpl    = 0x004

  val RAMStartLow  = 0x010
  val RAMStartHigh = 0x014
  val RAMLimitLow  = 0x018
  val RAMLimitHigh = 0x01c

  val RAMWPLow  = 0x020
  val RAMWPHigh = 0x024
  val RAMRPLow  = 0x028
  val RAMRPHigh = 0x02c

  val RAMData = 0x040
}
