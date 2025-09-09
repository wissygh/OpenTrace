package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters
import utils.NamedUInt

class Payload extends Bundle with TraceParameters {
  val format = UInt(2.W)
}

/**
 * Format3 Payload
 * 30: Synchronisation
 * 31: Trap
 * 32: Context
 * 33: Support
 */
class Format3 extends Payload {
  override val format = 3.U(2.W)
  val subformat       = UInt(2.W)
}

class Format30 extends Format3 {
  override val subformat = 0.U(2.W)
  val branch             = Bool()
  val privilege          = UInt(privilege_width_p.W)
  val time               = Option.when(!notime_p)(UInt(time_width_p.W))
  val context            = Option.when(!nocontext_p)(UInt(context_width_p.W))
  val address            = UInt((iaddress_width_p - iaddress_lsb_p).W)
}

class Format31 extends Format3 {
  override val subformat = 1.U(2.W)
  val branch             = Bool()
  val privilege          = UInt(privilege_width_p.W)
  val time               = Option.when(!notime_p)(UInt(time_width_p.W))
  val context            = Option.when(!nocontext_p)(UInt(context_width_p.W))
  val ecause             = UInt(ecause_width_p.W)
  val interrupt          = Bool()
  val thaddr             = Bool()
  val address            = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val tavl               = UInt(iaddress_width_p.W)
}

class Format32 extends Format3 {
  override val subformat = 2.U(2.W)
  val privilege          = UInt(privilege_width_p.W)
  val time               = Option.when(!notime_p)(UInt(time_width_p.W))
  val context            = Option.when(!nocontext_p)(UInt(context_width_p.W))
}

class Format33 extends Format3 {
  override val subformat = 3.U(2.W)
  val ienable            = Bool()
  val encoder_mode       = UInt(encodermode_width_p.W) // should be zero.
  val qual_status        = QualStatus
  val ioption            = ConfigMode
  val denable            = Bool()
  val dloss              = Bool()
  val doptions           = Doptions
}

class Format2 extends Payload {
  override val format = 2.U(2.W)
  val address         = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val notifyTrigger   = Bool()
  val updiscon        = Bool()
  val irreport        = Bool()
  val irdepth         = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
}

class Format1 extends Payload {
  override val format = 1.U(2.W)
  val branches        = UInt(5.W)

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
  val branch_map    = UInt(31.W)
  val address       = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val notifyTrigger = Bool()
  val updiscon      = Bool()
  val irreport      = Bool()
  val irdepth       = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
}

class Format1NoAddress extends Payload {
  override val format = 1.U(2.W)
  val branches        = UInt(5.W)

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
}

/**
 * format0 packets including:
 *  subformat0:  no address, branch count    ; address, branch count
 *  subformat1: jump target index, branch map; jump target index, no branch map
 */

class Format0 extends Payload {
  override val format = 0.U(2.W)
  val subformat       = UInt(1.W)
}
class Format00NoAddress extends Format0 {
  override val subformat = 0.U(1.W)
  val branch_count       = UInt(32.W)
  val branch_fmt         = UInt(2.W) // 00 ; 01-11: cannot occur
}

class Format00Address extends Format0 {
  override val subformat = 0.U(1.W)
  val branch_count       = UInt(32.W)
  val branch_fmt         = UInt(2.W) // 10: address; 11 address-fail; 00-01: cannot occur
  val address            = UInt((iaddress_width_p - iaddress_lsb_p).W)
  val notifyTrigger      = Bool()
  val updiscon           = Bool()
  val irreport           = Bool()
  val irdepth            = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
}

class Format01Branchmap extends Format0 {
  override val subformat = 1.U(1.W)
  val index              = UInt(cache_size_p.W)
  val branches           = UInt(5.W)

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
  val irreport   = Bool()
  val irdepth    = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
}

class Format01Nobranchmap extends Format0 {
  override val subformat = 1.U(1.W)
  val index              = UInt(cache_size_p.W)
  val branches           = UInt(5.W)
  val irreport           = Bool()
  val irdepth            = UInt((return_stack_size_p + call_counter_size_p + (if (return_stack_size_p > 0) 1 else 0)).W)
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
  def DeltaAddr  = 1.U
  def FullAddr   = 2.U
  def ImExp      = 4.U
  def Sijump     = 8.U
  def BranchPred = 16.U
  def JumpCache  = 32.U
}

/**
 *  doptions:
 *    00 -> is not defined
 *    01 -> only report address
 *    10 -> only report data
 *    11 -> report address and data
 */

object Doptions extends NamedUInt(2) {
  def None    = 0.U
  def Address = 1.U
  def Data    = 2.U
  def All     = 3.U
}
