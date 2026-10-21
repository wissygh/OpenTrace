package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters
import utils.NamedUInt

/**
 * Format3 Payload
 * 30: Synchronisation
 * 31: Trap
 * 32: Context
 * 33: Support
 */
class Format30 extends Bundle with TraceParameters {
  val address   = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val context   = Option.when(!nocontext_p)(UInt(contextWidth.W))
  val time      = Option.when(!notime_p)(UInt(time_width_p.W))
  val privilege = UInt(privilege_width_p.W)
  val branch    = Bool()
  val subformat = UInt(2.W)
  val format    = UInt(2.W)
}

class Format31 extends Bundle with TraceParameters {
  val tval      = UInt(iaddress_width_p.W)
  val address   = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val thaddr    = Bool()
  val interrupt = Bool()
  val ecause    = UInt(ecause_width_p.W)
  val context   = Option.when(!nocontext_p)(UInt(contextWidth.W))
  val time      = Option.when(!notime_p)(UInt(time_width_p.W))
  val privilege = UInt(privilege_width_p.W)
  val branch    = Bool()
  val subformat = UInt(2.W)
  val format    = UInt(2.W)
}

class Format32 extends Bundle with TraceParameters {
  val context   = Option.when(!nocontext_p)(UInt(contextWidth.W))
  val time      = Option.when(!notime_p)(UInt(time_width_p.W))
  val privilege = UInt(privilege_width_p.W)
  val subformat = UInt(2.W)
  val format    = UInt(2.W)
}

class Format33 extends Bundle with TraceParameters {
  val doptions     = UInt(2.W)
  val dloss        = Bool()
  val denable      = Bool()
  val fullAddr     = Bool()
  val jumpCache    = Bool()
  val branchPred   = Bool()
  val imExp        = Bool()
  val imRet        = Bool()
  val sijump       = Bool()                      // ioption_width_p = 6
  val qual_status  = UInt(2.W)
  val encoder_mode = UInt(encodermode_width_p.W) // should be zero.
  val ienable      = Bool()
  val subformat    = UInt(2.W)
  val format       = UInt(2.W)
}

class Format2 extends Bundle with TraceParameters {
  // val irdepth       = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
  // val irreport      = Bool()
  val updiscon      = Bool()
  val notifyTrigger = Bool()
  val diffAddress   = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val format        = UInt(2.W)
}

/** Maximum-capacity logical fields. The wire map is 1/3/7/15/31 bits:
  * use ETracePacketCodec.address rather than serializing this Bundle.asUInt.
  */
class Format1WithAddr extends Bundle with TraceParameters {
  // val irdepth       = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
  // val irreport      = Bool()
  val updiscon      = Bool()
  val notifyTrigger = Bool()
  val diffAddress   = UInt((iaddress_width_p - iaddress_lsb_p).W)

  /**
   * The num of bits of branch_map is determined as followed:
   *  swicth(branches)
   *    0     : cannot occur
   *    1     : 1
   *    2-3   : 3
   *    4-7   : 7
   *    8-15  : 15
   *    16-31 : 31
   */
  val branch_map = UInt(31.W)
  val branches   = UInt(5.W)
  val format     = UInt(2.W)
}

class Format1NoAddr extends Bundle with TraceParameters {

  /**
   * The num of bits of branch_map is determined as followed:
   *  swicth(branches)
   *    0     : 31 bits, with no address
   *    1-31  : cannot occur in an address-free packet
   */
  val branch_map = UInt(31.W)
  val branches   = UInt(5.W)
  val format     = UInt(2.W)
}

/**
 * format0 packets including:
 *  subformat0:  no address, branch count    ; address, branch count
 *  subformat1: jump target index, branch map; jump target index, no branch map
 */
class Format00NoAddress extends Bundle with TraceParameters {
  val branch_fmt   = UInt(2.W) // 00 ; 01-11: cannot occur
  val branch_count = UInt(32.W)
  val subformat    = UInt(f0s_width_p.W)
  val format       = UInt(2.W)
}

class Format00Address extends Bundle with TraceParameters {
  val irdepth       = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
  val irreport      = Bool()
  val updiscon      = Bool()
  val notifyTrigger = Bool()
  val address       = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val branch_fmt    = UInt(2.W) // 10: address; 11 address-fail; 00-01: cannot occur
  val branch_count  = UInt(32.W)
  val subformat     = UInt(f0s_width_p.W)
  val format        = UInt(2.W)
}

class Format01Branchmap extends Bundle with TraceParameters {
  val irdepth  = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
  val irreport = Bool()

  /**
   * The num of bits of branch_map is determined as followed:
   *  swicth(branches)
   *    0     : cannot occur
   *    1     : 1
   *    2-3   : 3
   *    4-7   : 7
   *    8-15  : 15
   *    16-31 : 31
   */
  val branch_map = UInt(31.W)
  val branches   = UInt(5.W)
  val index      = UInt(cache_size_p.W)
  val subformat  = UInt(f0s_width_p.W)
  val format     = UInt(2.W)
}

class Format01Nobranchmap extends Bundle with TraceParameters {
  val irdepth   = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
  val irreport  = Bool()
  val branches  = UInt(5.W)
  val index     = UInt(cache_size_p.W)
  val subformat = UInt(f0s_width_p.W)
  val format    = UInt(2.W)
}

object QualStatus extends NamedUInt(2) {
  def No_change  = 0.U
  def Ended_req  = 1.U
  def Trace_lost = 2.U
  def Ended_ntr  = 3.U
}

/**
 * Optional and run-time configurable mode, oneHot:
 *   Delta address mode
 *   Full address mode
 *   Implicit exception mode
 *   Sequentially inferable jump mode
 *   Branch prediction mode
 *   Jump target cache mode
 */
object ConfigMode extends NamedUInt(6) {
  def Sijump     = 1.U
  def ImRet      = 2.U
  def ImExp      = 4.U
  def BranchPred = 8.U
  def JumpCache  = 16.U
  def FullAddr   = 32.U
}

/**
 *  doptions:
 *    00 -> is not defined
 *    01 -> only report address
 *    10 -> only report data
 *    11 -> report address and data
 */

object Doptions extends NamedUInt(2) {
  def ALL       = 0.U
  def NoAddress = 1.U
  def NoData    = 2.U
  def None      = 3.U
}
