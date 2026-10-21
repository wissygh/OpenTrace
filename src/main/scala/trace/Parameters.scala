package trace

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._
import trace.component.FifoParam

trait TraceParameters {

  /** Fail before RTL generation if a configuration exceeds the implemented
    * instruction profile or contradicts a public CSR/transport field.
    */
  def validateImplementationParameters(): Unit = {
    require(hart_num >= 1 && hart_num <= 16, "implemented funnel supports 1..16 harts")
    require(filter_num >= 1 && comparator_num >= 1)
    require(src_bits_p == 8, "hardware profile uses one in-band source byte and trTeSrcBits=8")
    require(packet_header_time_p == 0 && trace_type_width == 0, "instruction packets use a fixed base header")
    require(iaddress_width_p == IaddrWidth && IaddrWidth >= 2 && IaddrWidth <= 64 && iaddress_lsb_p == 1)
    require(privilege_width_p == PrivWidth && PrivWidth == 3 && ItypeWidth == 4)
    require(contextWidth == 32 && time_width_p == 64 && ts_width_p > 0 && ts_width_p <= 63)
    val trapBits = 7 + privilege_width_p + ecause_width_p + 2 * IaddrWidth - iaddress_lsb_p +
      (if (notime_p) 0 else time_width_p) + (if (nocontext_p) 0 else contextWidth)
    require(payload_max_width >= trapBits && payload_max_width <= 248 && payload_max_width % 8 == 0)
    require(maxPackets >= 1 + src_bits_p / 8 + payload_max_width / 8)
    def powerOfTwo(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
    require(atbIdWidth == 7)
    require(Seq(encoderAtbDataWidth, funnelAtbDataWidth).forall(w => w >= 8 && powerOfTwo(w)))
    require(funnelAtbDataWidth == 32, "implemented SRAM and control data words are 32 bits")
    require(buffer_num > 1 && buffer_ptr_width == log2Up(buffer_num))
    require(core_stall_response_cycles_p >= 0 && BlockNum > 0 &&
      block_buffer_depth_p >= BlockNum * (core_stall_response_cycles_p + 1))
    require(filter_trap_depth_bits_p > 0 && filter_trap_depth_bits_p <= 16)
    require(f0s_width_p == 1 && cache_size_p >= 1 && cache_size_p <= 10)
  }

  /**
    * soc
    */
  def test_xsEmu = true

  /**
   * Core's Parameters (from XS)
   */
  def RenameWidth = 6
  def CommitWidth = 8
  def Xlen        = 64

  def BlockNum               = 3
  def CauseWidth             = 8  // todo: fix in harts, because int/exp in itype
  def TvalWidth              = 50
  def PrivWidth              = 3
  def contextWidth           = 32
  def ImpdefWidth            = 32
  def IaddrWidth             = 50 // todo: ingore lsb from core
  def ItypeWidth             = 4
  def IretireWidthInPipe     = log2Up(RenameWidth * 2 + 1)
  def IretireWidthCompressed = log2Up(RenameWidth * CommitWidth * 2 + 1)
  def IlastsizeWidth         = 1

  /**
   * hpm custom design
   */
  def supportHPM     = false
  def hpmevent_num   = 2 // max is 8
  def hpmevent_width = 55
  def hpminc_width   = 6

  /**
   * Trace's Parameters
   */
  def privilege_width_p    = 3
  def notime_p             = false
  def time_width_p         = Xlen
  def packet_header_time_p = 0
  def nocontext_p          = false
  def ecause_width_p       = 8
  def iaddress_width_p     = IaddrWidth
  def iaddress_lsb_p       = 1
  def encodermode_width_p  = 1 // Branch trace is the only mode.

  def return_stack_size_p = 8 // todo: fix
  def call_counter_size_p = 8 // todo: fix

  def f0s_width_p  = 1
  def cache_size_p = 8

  // support packet
  def ioptions_width_p = 6

  /**
   * Trace's Parameters Data trace
   */
  def nodatatrace_p    = true
  def doptions_width_p = 2

  /**
   * encoder parameters
   */
  def hart_num   = 1
  def filter_num = 1
  require(
    filter_num <= 16,
    "filter_num can not be greater 16, because " +
      "the addres of trTeFilter is [0x400 ... 0x5ff].\n"
  )
  def filter_trap_depth_bits_p = 4
  def comparator_num           = 1
  require(
    comparator_num <= 8,
    "comparator_num can not be greater 8, " +
      "because width of trTeFilterControl.trTeFilterComp is 3 and " +
      "the addres of trTeComp is [0x600 ... 0x6ff].\n"
  )
  // ext trigger num
  def extTrig_num = 4
  def src_bits_p  = 8

  // block buffer parameters
  def block_buffer_depth_p = 16 // in entries
  // Maximum additional core input cycles after it observes toCore.stall.
  def core_stall_response_cycles_p = 1

  /**
   * timestamp parameters
   */

  def ts_width_p    = 63
  def ts_prescale_p = 2 // 0,1,2,3
  def clock_domain  = 3 // Internal Core: the counter is clocked in the encoder domain

  /**
   * RAM parameters
   * only support sram & fixed smem
   */
  def buffer_num       = 256 // 256 * 32bit, for SRAM
  def buffer_ptr_width = log2Up(buffer_num)
  def supportSRAM      = true
  def fixedStartAddr   = 0x0
  def fixedLimitAddr   = (buffer_num - 1) << 2
  def ramLimitAddr     = (buffer_num - 1) << 2

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

  def traceBaseAddr      = 0x00_2000_0000
  def ctrlMemStep        = 0x1_0000
  def ctrlMask           = 0xff_ffff
  def ctrlAddressSet     = AddressSet(traceBaseAddr, ctrlMask)
  def ctrlAddrWidth      = 64
  def ctrlDataSize       = 32  // Data-width which control slave support
  def ctrlBeatBytes      = ctrlDataSize / 8
  def ctrlDataSizeMax    = 256 // Max data-width which bus req support.
  def ctrlTLSizeMin      = ctrlDataSize / 8
  def ctrlTLSizeMax      = ctrlDataSizeMax / 8
  def ctrlAddrWidthInner = ctrlMask.U.getWidth

  def encoderBaseAddr   = traceBaseAddr
  def funnelBaseAddr    = encoderBaseAddr + ctrlMemStep * encoder_num
  def atbbridgeBaseAddr = funnelBaseAddr + ctrlMemStep * funnel_num
  def pibBaseAddr       = atbbridgeBaseAddr + ctrlMemStep * atbbridge_num
  def ramSinkAddr       = pibBaseAddr + ctrlMemStep * pib_num

  def srcId(addr: Int): Int = (addr & ctrlMask) >> log2Up(ctrlMemStep)

  /**
   * static run-time config
   */
  def supportEtrace = true
  def supportNtrace = !supportEtrace

  def supportFullAddr   = true
  def supportJumpCache  = true
  def supportBranchPred = false
  def supportImExp      = false
  def supportImRet      = false
  def supportSijump     = false

  /**
   * etrace interface parameter
   */
  def payload_max_width = 248
  def trace_type_width  = 0              // only support instruction trace now
  def S                 = src_bits_p / 8
  def T                 = packet_header_time_p / 8
  def P                 = payload_max_width / 8
  def maxPackets        = 1 + S + T + 31 // 1 + 1 + 8 + 31
  def maxNullSeq        = maxPackets + 1

  // atb
  def atbIdWidth          = 7
  def encoderAtbDataWidth = 32
  def funnelAtbDataWidth  = 32
  def encoderAtbDataBeats = encoderAtbDataWidth / 8
  def funnelAtbDataBeats  = funnelAtbDataWidth / 8

  // asyncBridge
  val ctrlAsyncBridge = AsyncQueueParams.singleton(safe = true)
  val compAsyncBridge = AsyncQueueParams(depth = 16, sync = 3, safe = true)

}

case class BusType(
    bustype: Int = 0
) {
  def isTL    = bustype == 0
  def isAXI   = bustype == 1
  def isAPB   = bustype == 2
  def isOther = bustype >= 3
  def busName: String = bustype match {
    case 0 => "tl"
    case 1 => "axi"
    case 2 => "apb"
    case _: Int => "other"
  }
}

object BusType {
  def TL:  BusType = new BusType(0)
  def AXI: BusType = new BusType(1)
  def APB: BusType = new BusType(2)
}

case class NodeType(
    nodetype: Int = 0
) {
  def isSource = nodetype == 0
  def isFunnel = nodetype == 1
  def isSink   = nodetype == 2
  def isOther  = nodetype >= 3
  def NodeName: String = nodetype match {
    case 0 => "Source"
    case 1 => "Funnel"
    case 2 => "Sink"
    case _: Int => "other"
  }
}

object NodeType {
  def source: NodeType = new NodeType(0)
  def funnel: NodeType = new NodeType(1)
  def sink:   NodeType = new NodeType(2)
}
