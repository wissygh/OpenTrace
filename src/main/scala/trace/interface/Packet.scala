package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters

/**
 * E-Trace
 */

class PacketHeader extends Bundle {
  val extend = Bool() // timestamp enable
  val flow   = UInt(2.W)
  val length = UInt(5.W)
}

class NormalPacket extends Bundle with TraceParameters {
  val payload = new Bundle {
    val traceType    = UInt(trace_type_width.W) // 0: Instruction 1: Data; only support instruction trace now
    val tracePayload = UInt((payload_max_width - (src_bits_p % 8) - trace_type_width).W)
  }
  val timestamp = UInt(packet_header_time_p.W) // todo: fix
  val srcid     = UInt(src_bits_p.W)
  // number of bytes: 1 + src_bits_p/8 + T*header.extend + header.length((payload_width + src_bits_p%8)/8)
  // number of bytes: 1 + 1 + 8 + [1, 31]
  val header = new PacketHeader
}

class NullPacket extends PacketHeader {

  /**
   * length: 0
   * extend: 0 -> null.idle, 1 -> null.alignment
   * sync:
   *    unframed: N null.idle + 1 null.alignment
   *    framed  : N null.idle + 1 null.idle/null.alignment -> (1 + 1 + 8 + 31 + 1) * 0.U(8.W)
   */
  override val extend = false.B
  override val flow   = 0.U(2.W)
  override val length = 0.U(5.W)
}
