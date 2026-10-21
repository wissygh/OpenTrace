package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.interface.TraceBeat

/** Retains complete old packets across a source-domain reset. Partial input
  * epochs are discarded; a standard per-source trace_lost precedes new packets.
  * The staging/output state resets only with the trace system domain.
  */
class SourcePacketStager(sourceId: Int, dataBytes: Int = 4) extends Module with TraceParameters {
  require(sourceId >= 0 && sourceId < 256 && dataBytes == 4)
  val io = IO(new Bundle {
    val in         = Flipped(Decoupled(new TraceBeat(dataBytes)))
    val out        = Decoupled(new TraceBeat(dataBytes))
    val epochReset = Input(Bool()) // synchronous release in this trace domain
    val syncReq    = Output(Bool())
    val empty      = Output(Bool())
  })
  val memory        = Reg(Vec(maxPackets, UInt(8.W)))
  val received      = RegInit(0.U(log2Ceil(maxPackets + 1).W))
  val length        = Reg(UInt(log2Ceil(maxPackets + 1).W))
  val offset        = RegInit(0.U(log2Ceil(maxPackets + 1).W))
  val complete      = RegInit(false.B)
  val damaged       = RegInit(false.B)
  val loss          = RegInit(false.B)
  val options       = RegInit(0.U(6.W))
  val tracing       = RegInit(false.B)
  val previousReset = RegNext(io.epochReset, true.B)
  val resetting     = io.epochReset && !previousReset
  val resumed       = !io.epochReset && previousReset
  val accepted      = io.in.bits.bytes +& 1.U
  val expected      = Mux(received === 0.U, io.in.bits.packetBytes, length)
  val total         = received +& accepted
  val invalid = damaged || expected === 0.U || expected > maxPackets.U || total > expected ||
    (received =/= 0.U && io.in.bits.packetBytes =/= length)
  val discarded = io.in.fire && io.in.bits.last && (invalid || total =/= expected)
  io.in.ready := !complete && !loss && !io.epochReset
  io.syncReq  := resumed || discarded
  io.empty    := !complete && !loss && received === 0.U && !damaged
  when(resetting) {
    received                                          := 0.U
    damaged                                           := false.B
    when(tracing || received =/= 0.U || damaged)(loss := true.B)
  }
  when(io.in.fire) {
    when(received === 0.U)(length := io.in.bits.packetBytes)
    when(!invalid) {
      for (lane <- 0 until dataBytes) {
        when(lane.U < accepted)(memory(received + lane.U) := io.in.bits.data(8 * lane + 7, 8 * lane))
      }
    }
    // Damaged tails drain through last without extending a finite counter.
    when(!invalid)(received := total)
    damaged                 := invalid
    when(io.in.bits.last) {
      received := 0.U
      damaged  := false.B
      when(!invalid && total === expected) {
        complete := true.B
        offset   := 0.U
        // Implemented support packets fit in one 32-bit beat. Preserve their
        // options in the injected loss report until the next real support.
        when(received === 0.U && (expected === 3.U || expected === 4.U) &&
          io.in.bits.data(7, 0) === expected - 2.U && io.in.bits.data(15, 8) === sourceId.U &&
          io.in.bits.data(19, 16) === 15.U) {
          options := Mux(expected === 4.U, io.in.bits.data(29, 24), 0.U)
          tracing := io.in.bits.data(20)
        }
      }.otherwise(loss := true.B)
    }
  }
  val available = length - offset
  val count     = Mux(available < dataBytes.U, available, dataBytes.U)
  val lanes     = Wire(Vec(dataBytes, UInt(8.W)))
  for (lane <- 0 until dataBytes) {
    lanes(lane) := Mux(lane.U < count, memory(offset + lane.U), 0.U)
  }
  io.out.valid := complete || loss
  // qual_status=trace_lost, previous stream ienable/options preserved. A second payload
  // byte is necessary because the first payload byte has its sign bit set.
  val lostData = Cat(0.U(2.W), options, "h8f".U(8.W) | (tracing.asUInt << 4), sourceId.U(8.W), 2.U(8.W))
  io.out.bits.data        := Mux(complete, lanes.asUInt, lostData)
  io.out.bits.bytes       := Mux(complete, count - 1.U, 3.U)
  io.out.bits.last        := !complete || available <= dataBytes.U
  io.out.bits.packetBytes := Mux(complete, length, 4.U)
  when(io.out.fire) {
    when(complete) {
      when(io.out.bits.last) { complete := false.B; offset := 0.U }
        .otherwise(offset := offset + dataBytes.U)
    }.otherwise(loss := false.B)
  }
}
