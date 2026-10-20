package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters

/** Internal packet stream. Source identity is in the encapsulated packet bytes.
  * packetBytes describes the complete packet (including header and srcID).
  * These boundary fields are not signals on a standard ATB port.
  */
class TraceBeat(dataBytes: Int) extends Bundle with TraceParameters {
  require(dataBytes > 0 && isPow2(dataBytes))
  val data = UInt((dataBytes * 8).W)
  val bytes = UInt(math.max(1, log2Ceil(dataBytes)).W) // valid byte count minus one
  val last = Bool()
  val packetBytes = UInt(log2Ceil(maxPackets + 1).W)
}
