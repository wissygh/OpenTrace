package trace

import chisel3._
import chisel3.util._
import firrtl2.Utils.True
import freechips.rocketchip.diplomacy.AddressSet
import shapeless.ops.nat.Pow

trait TraceParameters {

  /**
   * Core's Parameters (from XS)
   */
  def RenameWidth = 6
  def CommitWidth = 8
  def Xlen        = 64

  def BlockNum               = 3
  def CauseWidth             = 8 // todo: fix in harts, because int/exp in itype
  def TvalWidth              = 50
  def PrivWidth              = 3
  def ImpdefWidth            = 32
  def IaddrWidth             = 50
  def ItypeWidth             = 4
  def IretireWidthInPipe     = log2Up(RenameWidth * 2 + 1)
  def IretireWidthCompressed = log2Up(RenameWidth * CommitWidth * 2 + 1)
  def IlastsizeWidth         = 1

  def supportSijump = false

  /**
   * hpm custom design
   */
  def supportHPM     = true
  def hpmevent_num   = 2 // max is 8
  def hpmevent_width = 55
  def hpminc_width   = 6

  /**
   * Trace's Parameters
   */
  def privilege_width_p   = 3
  def notime_p            = true
  def time_width_p        = Xlen
  def nocontext_p         = true
  def context_width_p     = Xlen
  def ecause_width_p      = Xlen
  def iaddress_width_p    = IaddrWidth
  def iaddress_lsb_p      = 1
  def encodermode_width_p = 1 // Branch trace is the only mode.
  def ioptions_width_p    = 6

  def return_stack_size_p = 8 // todo: fix
  def call_counter_size_p = 8 // todo: fix

  def f0s_width_p  = 1
  def cache_size_p = 8 // todo: fix

  // sync packets
  def syncmax_p           = 2 // [0, 16)
  def synccounter_width_p = syncmax_p + 4
  def countmax_p          = math.pow(2, syncmax_p + 4).toInt

  /**
   * Trace's Parameters Data trace
   */
  def nodatatrace_p    = true
  def doptions_width_p = 2

  /**
   * encoder parameters
   */
  def hart_num   = 2
  def filter_num = 1
  require(
    filter_num <= 16,
    "filter_num can not be greater 16, because " +
      "the addres of trTeFilter is [0x400 ... 0x5ff].\n"
  )
  def comparator_num = 1
  require(
    comparator_num <= 8,
    "comparator_num can not be greater 8, " +
      "because width of trTeFilterControl.trTeFilterComp is 3 and " +
      "the addres of trTeComp is [0x600 ... 0x6ff].\n"
  )
  // ext trigger num
  def extTrig_num = 4
  def src_bits_p  = 8

  /**
   * timestamp parameters
   */

  def ts_width_p    = 63
  def ts_prescale_p = 2 // 0,1,2,3
  def prescale_p    = math.pow(2, 2 * ts_prescale_p).toInt
  def clock_domain  = 2 // 2: bus, 3: cpu

  /**
   * RAM parameters
   * only support sram & fixed smem
   */
  def supportSRAM    = true
  def supportSMEM    = true
  def ramIsSMEM      = true // true: SMEM false: SRAM
  def fixedSMEM      = true
  def fixedStartAddr = 0x0
  def fixedLimitAddr = 0x0
  def ramLimitAddr   = 0x0

  // trace buffer
  def buffer_num    = 2
  def ramBaseAddr   = 0x00_4000_0000
  def ramMask       = (buffer_num << 2) - 1
  def ramAddressSet = AddressSet(ramBaseAddr, ramMask)
  def ramBeatBytes  = 32 / 8

  /**
   * trace memory map address
   * Each Trace Component is controlled by a set of 32-bit registers occupying up to 4KB of an address
   * space. Base address of each trace component must be aligned on the 4KB boundary.
   *
   * 1. MemMap("h00_2000_0000", "h00_20FF_FFFF",   "h0", "Reserved",    "RW")
   *  => real Range: ("h00_2000_0000", "h00_2000_0000" + "h1_0000" * [[allComp_num]] -"h1")
   *  encoder, funnel, atbbridge, pib, ramsink
   */
  def encoder_num   = hart_num
  def funnel_num    = 1
  def atbbridge_num = 1
  def pib_num       = 1
  def ramsink_num   = 1
  def allComp_num   = encoder_num + funnel_num + atbbridge_num + pib_num + ramsink_num

  def traceBaseAddr  = 0x00_2000_0000
  def memStep        = 0x1_0000
  def mask           = 0xff_ffff
  def addressSet     = AddressSet(traceBaseAddr, mask)
  def addrWidth      = 64
  def dataSize       = 32  // Data-width which control slave support
  def dataSizeMax    = 256 // Max data-width which bus req support.
  def tlSizeMin      = dataSize / 8
  def tlSizeMax      = dataSizeMax / 8
  def addrWidthInner = mask.U.getWidth

  def encoderBaseAddr   = traceBaseAddr
  def funnelBaseAddr    = encoderBaseAddr + memStep * encoder_num
  def atbbridgeBaseAddr = funnelBaseAddr + memStep * funnel_num
  def pibBaseAddr       = atbbridgeBaseAddr + memStep * atbbridge_num
  def ramSinkAddr       = pibBaseAddr + memStep * pib_num

  def srcId(addr: Int): Int = (addr & mask) >> log2Up(memStep)

  /**
   *
   */
  def supportEtrace = true
  def supportNtrace = !supportEtrace

  /**
   * etrace interface parameter
   */
  def payload_max_width = 248
  def trace_type_width  = 1
  def S                 = src_bits_p / 8
  def T                 = (ts_width_p + 1) / 8
  def P                 = payload_max_width / 8
  def maxPackets        = 1 + S + T + 31 // 1 + 1 + 8 + 31
  def maxNullSeq        = maxPackets + 1

  val encoderFifoDepth = maxPackets * 3
  def atbBeats         = 4
  def lg2Beats         = log2Ceil(atbBeats)

  def maxTransNum   = maxPackets / atbBeats + (if (maxPackets % atbBeats == 0) 0 else 1)
  def transCntWidth = log2Up(maxTransNum + 1) // 6

  /**
   * n-trace
   */
  val ntrace_max_src     = 12
  val ntrace_max_icnt    = 22
  val ntrace_max_addr    = 63
  val ntrace_max_hist    = 32
  val ntrace_max_tstamp  = 34
  val ntrace_max_hrepeat = 18
  val ntrace_max_bcnt    = 18
}

case class BusType(
    bustype: Int = 0
) {
  def isTL    = bustype == 0
  def isAXI   = bustype == 1
  def isAPB   = bustype == 2
  def isOther = bustype >= 3
}

object BusType {
  def TLbus:  BusType = new BusType(0)
  def AXIbus: BusType = new BusType(1)
  def APBbus: BusType = new BusType(2)
}
