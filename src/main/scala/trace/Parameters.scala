package trace

import chisel3._
import chisel3.util._

trait TraceParameters {

  /**
   * Core's Parameters (from XS)
   */
  def RenameWidth            = 6
  def CommitWidth            = 8
  def Xlen                   = 64

  def BlockNum               = 3
  def CauseWidth             = 64
  def TvalWidth              = 50
  def PrivWidth              = 3
  def IaddrWidth             = 50
  def ItypeWidth             = 4
  def IretireWidthInPipe     = log2Up(RenameWidth * 2 + 1)
  def IretireWidthCompressed = log2Up(RenameWidth * CommitWidth * 2 + 1)
  def IlastsizeWidth         = 1

  /**
   * Trace's Parameters
   */
  def privilege_width_p = 3
  def notime_p = true
  def time_width_p = Xlen
  def nocontext_p = true
  def context_width_p = Xlen
  def ecause_width_p = Xlen
  def iaddress_width_p = IaddrWidth
  def iaddress_lsb_p   = 1
  def encodermode_width_p = 1 // Branch trace is the only mode.
  def ioptions_width_p = 6

  def return_stack_size_p = 8 // todo: fix
  def call_counter_size_p = 8 // todo: fix

  def f0s_width_p = 1
  def cache_size_p = 8 // todo: fix

  /**
   * Trace's Parameters Data trace
   */
  def nodatatrace_p = true
  def doptions_width_p = 2

  /**
   * Trace components' memmory map base address
   */
  def encoder0_base_address = 0x0;
  def funnel0_base_address = 0x0;
  def atbbridge_base_address = 0x0;
  def pib_base_address = 0x0;
  def ramsink_base_address = 0x0;

  /**
   * the num of harts
   * the num of filter
   * the num of comparator
   */
  def hart_num = 1
  def filter_num = 1
  def comparator_num = 1

  /**
   * component type
   */
  def trcomp_encoder   = 0x1
  def trcomp_funnel    = 0x8
  def trcomp_ramsink   = 0x9
  def trcomp_pibsink   = 0xA
  def trcomp_atbbridge = 0xE
}

case class BusType(
  bustype: Int = 0)
{
  def isTL  = bustype == 0
  def isAXI = bustype == 1
  def isAPB = bustype == 2
  def isOther = bustype >= 3
}

object BusType {
  def TLbus:  BusType = new BusType(0)
  def AXIbus: BusType = new BusType(1)
  def APBbus: BusType = new BusType(2)
}
