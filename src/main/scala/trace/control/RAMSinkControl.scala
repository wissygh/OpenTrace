package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.Trace

import scala.collection.immutable.SeqMap

object RamAddr {
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
