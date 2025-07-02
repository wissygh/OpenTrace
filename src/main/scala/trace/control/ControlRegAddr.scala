package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.component.ComponentBase

import scala.collection.immutable.SeqMap

object CtrlReg {

  /**
   * Encoder Reg Address
   */
  val trTeControl                  = 0x000
  val trTeImpl                     = 0x004
  val trTeInstFeatures             = 0x008
  val trTeInstFilters              = 0x00c

  // data trace control
  val trTeDataControl              = 0x010
  val trTeDataFilters              = 0x01c

  // Timestamp control
  val trTsControl                  = 0x040
  val trTsCounterLow               = 0x048
  val trTsCounterHigh              = 0x04c

  // trigger control
  val trTeTrigDbgControl           = 0x050
  val trTeTrigExtInControl         = 0x054 // don't support
  val trTeTrigExtOutControl        = 0x058 // don't support

  // Filters & comparators
  // filters 0x400-0x5ff
  // comparator 0x600-0x7ff
  val trTeFilter0Control          = 0x400
  val trTeFilter0MatchInst        = 0x404
  val trTeFilter0MatchEcauseLow   = 0x408
  val trTeFilter0MatchEcauseHigh  = 0x40C
  val trTeFilter0MatchValueImpdef = 0x410
  val trTeFilter0MatchMaskImpdef  = 0x414
  val trTeFilter0MatchData        = 0x418

  val trTeComp0Control            = 0x600
  val trTeComp0PMatchLow          = 0x610
  val trTeComp0PMatchHigh         = 0x614
  val trTeComp0SMatchLow          = 0x618
  val trTeComp0SMatchHigh         = 0x61C

  def filterCompAddrGen(base: Int, num: Int): Int = (base + 0x20 * num) // todo：int or bigInt

  /**
   * Funnel Reg Address
   */
  val trFunnelControl  = 0x000
  val trFunnelImpl     = 0x004
  val trFunnelDisInput = 0x008

  /**
   * AtbBridge Reg Address
   */
  val trATBBridgeControl = 0x000
  val trATBBridgeImpl    = 0x004

  /**
   * PibSink Reg Address
   */
  val trPibControl = 0x000
  val trPibImpl    = 0x004

  /**
   * RAMSink Reg Address
   */
  val trRamControl     = 0x000
  val trRamImpl        = 0x004

  val trRamStartLow    = 0x010
  val trRamStartHigh   = 0x014
  val trRamLimitLow    = 0x018
  val trRamLimitHigh   = 0x01C

  val trRamWPLow       = 0x020
  val trRamWPHigh      = 0x024
  val trRamRPLow       = 0x028
  val trRamRPHigh      = 0x02C

  val trRamData        = 0x040
}
