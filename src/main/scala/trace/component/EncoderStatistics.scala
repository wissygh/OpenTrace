package trace.component

import chisel3._
import chisel3.util._
import trace.Control2BusRegBundle
import trace.TraceParameters

/** Per-encoder diagnostics; these counters are independent of optional core HPM.
  * Full packet FIFOs apply backpressure, so they never claim a packet drop.
  */
class EncoderStatistics(baseAddr: Int, width: Int = 64) extends Module with TraceParameters {
  require(width >= 8 && width <= 64)
  val io = IO(new Bundle {
    val bus            = new Control2BusRegBundle
    val selected       = Output(Bool())
    val droppedEntries = Input(UInt(log2Ceil(BlockNum + 1).W))
    val lossMarker     = Input(Bool())
    val coreStall      = Input(Bool())
    val packetWait     = Input(Bool())
    val downstreamWait = Input(Bool())
    val packetAccepted = Input(Bool())
  })
  val local = io.bus.b2c.address - (baseAddr & ctrlMask).U
  val clear = io.bus.b2c.writeEn && local === "hf04".U && io.bus.b2c.dataWrite(0)
  val increments = Seq(
    io.droppedEntries,
    io.lossMarker.asUInt,
    io.coreStall.asUInt,
    io.packetWait.asUInt,
    io.downstreamWait.asUInt,
    io.packetAccepted.asUInt
  )
  val counts = RegInit(VecInit(Seq.fill(6)(0.U(width.W))))
  for ((increment, index) <- increments.zipWithIndex) {
    val sum = Mux(clear, 0.U, counts(index)) +& increment
    counts(index) := Mux(sum(width), Fill(width, 1.U(1.W)), sum(width - 1, 0))
  }
  val current = Cat(io.downstreamWait, io.packetWait, io.coreStall, io.droppedEntries.orR)
  val seen    = RegInit(0.U(4.W))
  seen := Mux(clear, 0.U, seen) | current
  val reads = Seq(
    "hf00".U -> Cat(0.U(20.W), seen, 0.U(4.W), current),
    "hf04".U -> 0.U,
    "hf08".U -> 3.U
  ) ++ counts.zipWithIndex.flatMap { case (count, index) =>
    val extended = Wire(UInt(64.W)); extended := count
    Seq((0xf10 + index * 8).U -> extended(31, 0), (0xf14 + index * 8).U -> extended(63, 32))
  }
  io.selected         := reads.map { case (address, _) => local === address }.reduce(_ || _)
  io.bus.c2b.dataRead := Mux(io.bus.b2c.readEn, MuxLookup(local, 0.U)(reads), 0.U)
  io.bus.c2b.error    := false.B
}
