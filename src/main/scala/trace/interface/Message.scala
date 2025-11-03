package trace.interface

import chisel3._
import chisel3.util._
import utils.NamedUInt

/**
 * N-trace
 */

trait NType {
  val ntrace_max_src     = 12
  val ntrace_max_icnt    = 22
  val ntrace_max_addr    = 63
  val ntrace_max_hist    = 32
  val ntrace_max_tstamp  = 34
  val ntrace_max_hrepeat = 18
  val ntrace_max_bcnt    = 18
}

class Message extends Bundle {
  val tcode = Vec(1, UInt(8.W)) // 6
  val src   = Vec(2, UInt(8.W))
  //  The SYNC field is always sent together with the F-ADDR field, so decoding may
  //  start from a message containing the SYNC field
  val sync   = Vec(1, UInt(8.W)) // 4
  val btype  = Vec(1, UInt(8.W)) // 2
  val icnt   = Vec(5, UInt(8.W))
  val xaddr  = Vec(11, UInt(8.W))
  val hist   = Vec(6, UInt(8.W))
  val tstamp = Vec(11, UInt(8.W))
}

/**
 * data   ## meso
 * [7: 2] ## [1: 0]
 *
 * 11    -> 00: message start
 * 00/01 -> 00: field middle
 * 00/01 -> 01: field var end
 * 00    -> 11: message end
 * 11    -> 11: Idle
 */

class ByteNTrace extends Bundle {
  val meso = MESO
  val data = UInt(6.W)
}

object MESO extends NamedUInt(2) {
  def start  = 0.U
  def middle = 1.U
  def end    = 3.U
}

object TCODE extends NamedUInt(6) {
  def Ownership              = 2.U  // priv, context
  def DirectBranch           = 3.U
  def IndirectBranch         = 4.U
  def Error                  = 8.U  // fifo of
  def ProgTraceSync          = 9.U  // start or restart
  def DirectBranchSync       = 11.U
  def IndirectBranchSync     = 12.U
  def ResourceFull           = 27.U // icnt or hist is full
  def IndirectBranchHist     = 28.U
  def IndirectBranchHistSync = 29.U
  def RepeatBranch           = 30.U
  def ProgTraceCorrelation   = 33.U // disable or stop
}

object SYNC extends NamedUInt(4) {
  def ExtTrigger             = 0.U
  val ExitFromReset          = 1.U
  val PeriodicSync           = 2.U
  val ExitFromDmode          = 3.U
  val SeqInstCounter         = 4.U
  val TraceEnable            = 5.U
  val TraceEvent             = 6.U // trace Notify
  val RestartFromFfioOverrun = 7.U
  val ExitFromPowerdown      = 9.U
}

object BTYPE extends NamedUInt(2) {
  def Indirect  = 0.U
  val Exception = 2.U
  val Interrupt = 3.U
}

/**
 * Ownership Message
 */
class Process extends Bundle {
  val format  = Format
  val prv     = PRIV
  val v       = V
  val context = UInt(32.W)
}

object Format extends NamedUInt(2) {
  val PrivChange     = 0.U
  val ScontextChange = 2.U
  val HcontextChange = 3.U
}

object PRIV extends NamedUInt(2) {
  val U = 0.U
  val S = 1.U
  val M = 3.U
}

object V extends NamedUInt(1) {
  val UnVirt = 0.U
  val Virt   = 1.U
}

/**
 * DirectBranch Message
 * IndirectBranch Message
 */

/**
 * Error Message
 */
object ETYPE extends NamedUInt(4) {
  def FIFOisOF = 0.U // RDATA[0]: ICNT
}

//object ECODE extends NamedUInt(4) {
//}

/**
 * ResourceFull Message
 */
object RCODE extends NamedUInt(4) {
  def ICNTFull  = 0.U // RDATA[0]: ICNT
  val HISTFull  = 1.U // RDATA[0]: HIST
  val Extension = 3.U // RDATA[0]: HIST, RDATA[1]: HREPEAT
}

/**
 * IndirectBranchHist Message
 * IndirectBranchHistSync Message
 * RepeatBranch Message
 */

/**
 * ProgTraceCorrelation Message
 * This message is emitted when the trace is disabled or stopped.
 */

object EVCODE extends NamedUInt(4) {
  def EntryDmode          = 0.U
  val EntryLowerPowermode = 1.U
}

object CDF extends NamedUInt(4) {
  def ICNT      = 0.U
  val ICNT_HIST = 1.U
}
