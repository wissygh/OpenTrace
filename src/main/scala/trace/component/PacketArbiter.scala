package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.TLArbiter
import trace.interface.TraceBeat

/** Locks a source from its first offered beat until its accepted last beat. */
class PacketArbiter(numSources: Int, dataBeats: Int) extends Module {
  require(numSources > 0)
  val io = IO(new Bundle {
    val in      = Vec(numSources, Flipped(Decoupled(new TraceBeat(dataBeats))))
    val enable  = Input(Bool())
    val disable = Input(Vec(numSources, Bool()))
    val idle    = Output(Bool())
    val out     = Decoupled(new TraceBeat(dataBeats))
  })
  val locked     = RegInit(false.B)
  val owner      = RegInit(0.U(math.max(1, log2Ceil(numSources)).W))
  val requests   = VecInit((0 until numSources).map(i => io.in(i).valid && !io.disable(i) && io.enable))
  val rrRequests = Mux(locked, UIntToOH(owner, numSources), requests.asUInt)
  val rr         = TLArbiter.roundRobin(numSources, rrRequests, io.out.fire && io.out.bits.last)
  val grant      = Mux(locked, UIntToOH(owner, numSources), rr & requests.asUInt)
  io.out.valid := Mux1H(grant.asBools, io.in.map(_.valid))
  io.out.bits  := Mux1H(grant.asBools, io.in.map(_.bits))
  for (i <- 0 until numSources) io.in(i).ready := grant(i) && io.out.ready
  // Lock even before the first handshake: new contenders and mask writes
  // must not change a valid beat while the receiver is stalled.
  when(io.out.valid) {
    owner  := OHToUInt(grant)
    locked := !(io.out.fire && io.out.bits.last)
  }
  io.idle := !locked && !io.out.valid
}
