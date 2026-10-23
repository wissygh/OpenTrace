package trace.component

import chisel3._
import chisel3.util._
import trace.Control2BusRegBundle
import trace.TraceParameters

/** E-Trace 2.0 Table 42 attributes, using the specification's encoded values.
  * Control Interface 0xe00..0xfff is explicitly reserved for vendor registers.
  * This read-only block occupies 0xe00..0xe4f, separate from statistics at 0xf00.
  */
case class EncoderDiscoveryValues(attributes: Seq[Int], sourceBits: Int, headerTimeBytes: Int, modeWidth: Int) {
  require(attributes.length == 15 && attributes.forall(_ >= 0))
  require(sourceBits >= 0 && sourceBits <= 16 && headerTimeBytes >= 0 && headerTimeBytes <= 8)
  require(modeWidth >= 1 && modeWidth <= 8)
  def words: Seq[BigInt] = Seq(
    BigInt("4352544f", 16),
    BigInt(1),
    BigInt(20),
    BigInt(15),
    BigInt(sourceBits | (headerTimeBytes << 8) | (modeWidth << 16))
  ) ++ attributes.map(BigInt(_))
}

object EncoderDiscoveryValues {
  def from(p: TraceParameters): EncoderDiscoveryValues = EncoderDiscoveryValues(
    Seq(
      0, // arch_p: initial E-Trace architecture version
      if (p.supportBranchPred) p.bpred_size_p else 0,
      if (p.supportJumpCache) p.cache_size_p else 0,
      p.call_counter_size_p,
      p.contextWidth - 1,
      p.time_width_p - 1,
      p.ecause_width_p - 1,
      p.f0s_width_p,
      p.iaddress_lsb_p - 1,
      p.iaddress_width_p - 1,
      if (p.nocontext_p) 1 else 0,
      if (p.notime_p) 1 else 0,
      p.privilege_width_p - 1,
      if (p.supportImRet) p.return_stack_size_p else 0,
      if (p.supportSijump) 1 else 0
    ),
    p.src_bits_p,
    p.packet_header_time_p / 8,
    p.encodermode_width_p
  )
}

class EncoderDiscovery(baseAddr: Int, values: EncoderDiscoveryValues) extends Module with TraceParameters {
  val io = IO(new Bundle {
    val bus      = new Control2BusRegBundle
    val selected = Output(Bool())
  })
  val local = io.bus.b2c.address - (baseAddr & ctrlMask).U
  val reads = values.words.zipWithIndex.map { case (word, index) => (0xe00 + index * 4).U -> word.U(32.W) }
  io.selected         := reads.map { case (address, _) => local === address }.reduce(_ || _)
  io.bus.c2b.dataRead := Mux(io.bus.b2c.readEn, MuxLookup(local, 0.U)(reads), 0.U)
  io.bus.c2b.error    := false.B
}
