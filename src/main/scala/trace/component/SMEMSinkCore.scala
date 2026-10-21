package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.interface.TraceBeat

/** Inclusive word-addressable memory window; supplied by the SoC integrator. */
case class SMEMWindow(base: BigInt, size: BigInt) {
  require(base >= 0 && base % 4 == 0 && size >= 4 && size % 4 == 0)
  require(base + size < (BigInt(1) << 64), "SMEM window must leave a representable exclusive end")
  val limit: BigInt = base + size - 4
}

class SMEMConfiguration extends Bundle {
  val start   = UInt(64.W)
  val limit   = UInt(64.W) // inclusive address of final 32-bit word
  val pointer = UInt(64.W)
}

class SMEMWordWrite extends Bundle {
  val address = UInt(64.W)
  val data    = UInt(32.W)
  val mask    = UInt(4.W)
}

/** Whole-packet staging and publication. The word transport must retain accepted
  * transactions across software aborts, and return one response per request.
  * System reset cancels the transport and core together.
  */
class SMEMSinkCore(window: SMEMWindow) extends Module with TraceParameters {
  val io = IO(new Bundle {
    val in            = Flipped(Decoupled(new TraceBeat(4)))
    val enable        = Input(Bool())
    val stopOnWrap    = Input(Bool())
    val abort         = Input(Bool())
    val configuration = Flipped(Decoupled(new SMEMConfiguration))
    val request       = Decoupled(new SMEMWordWrite)
    val response      = Flipped(Decoupled(Bool())) // true means write failed
    val start         = Output(UInt(64.W))
    val limit         = Output(UInt(64.W))
    val committed     = Output(UInt(64.W))         // next byte after last completely acknowledged packet
    val empty         = Output(Bool())
    val stop          = Output(Bool())
    val wrap          = Output(Bool())
    val fault         = Output(UInt(2.W))          // 0=none, 1=invalid config/oversize, 2=memory error
    val faultAddress  = Output(UInt(64.W))
  })
  val idle :: collecting :: offering :: waiting :: Nil = Enum(4)
  val state                                            = RegInit(idle)
  val start                                            = RegInit(window.base.U(64.W))
  val limit                                            = RegInit(window.limit.U(64.W))
  val committed                                        = RegInit(window.base.U(64.W))
  val cursor                                           = RegInit(window.base.U(64.W))
  val data                                             = Reg(Vec(maxPackets, UInt(8.W)))
  val length                                           = Reg(UInt(log2Ceil(maxPackets + 1).W))
  val collected                                        = RegInit(0.U(log2Ceil(maxPackets + 1).W))
  val offset                                           = RegInit(0.U(log2Ceil(maxPackets + 1).W))
  val issued                                           = Reg(UInt(3.W))
  val padding                                          = RegInit(false.B)
  val stopping                                         = RegInit(false.B)
  val packetStopOnWrap                                 = Reg(Bool())
  val aborted                                          = RegInit(false.B)
  val wrappedPacket                                    = RegInit(false.B)
  val fault                                            = RegInit(0.U(2.W))
  val faultAddress                                     = RegInit(0.U(64.W))
  val aborting                                         = io.abort || aborted
  when(io.abort)(aborted := true.B)
  val padNeeded   = !io.enable || stopping
  val partialWord = committed(1, 0) =/= 0.U
  io.configuration.ready := state === idle && !io.enable && !aborting && (!partialWord || fault =/= 0.U)
  val config = io.configuration.bits
  val configLegal = config.start(1, 0) === 0.U && config.limit(1, 0) === 0.U && config.pointer(1, 0) === 0.U &&
    config.start >= window.base.U && config.limit <= window.limit.U && config.limit >= config.start &&
    config.pointer >= config.start && config.pointer <= config.limit
  val end       = limit + 4.U
  val available = end - committed
  val capacity  = end - start
  val fits      = io.in.bits.packetBytes <= available
  val packetLegal = io.in.bits.packetBytes =/= 0.U && io.in.bits.packetBytes <= maxPackets.U &&
    io.in.bits.packetBytes <= capacity
  val admitted = state === idle && io.enable && !stopping && fault === 0.U && !aborting
  io.in.ready := (admitted && packetLegal && (!io.stopOnWrap || fits)) ||
    (state === collecting && !aborting)
  io.empty        := state === idle && !aborting && !(padNeeded && partialWord && fault === 0.U)
  io.stop         := stopping || fault =/= 0.U
  io.wrap         := false.B
  io.start        := start
  io.limit        := limit
  io.committed    := committed
  io.fault        := fault
  io.faultAddress := faultAddress

  when(admitted && io.in.valid && !packetLegal) {
    fault        := 1.U
    faultAddress := committed
  }.elsewhen(admitted && io.in.valid && io.stopOnWrap && !fits) {
    stopping := true.B
  }
  when(!io.enable && state === idle && !partialWord)(stopping := false.B)
  when(io.configuration.fire) {
    when(configLegal) {
      start        := config.start
      limit        := config.limit
      committed    := config.pointer
      cursor       := config.pointer
      stopping     := false.B
      fault        := 0.U
      faultAddress := 0.U
    }.otherwise {
      fault        := 1.U
      faultAddress := config.pointer
    }
  }
  when(io.in.fire) {
    val index    = Mux(state === collecting, collected, 0.U)
    val count    = io.in.bits.bytes +& 1.U
    val total    = index +& count
    val expected = Mux(state === collecting, length, io.in.bits.packetBytes)
    assert(total <= expected && expected <= maxPackets.U, "SMEM input exceeds declared whole packet length")
    assert(io.in.bits.last === (total === expected), "SMEM last disagrees with packet length")
    when(state === collecting) {
      assert(io.in.bits.packetBytes === length, "SMEM packet length changed during collection")
    }.otherwise {
      length           := io.in.bits.packetBytes
      packetStopOnWrap := io.stopOnWrap
      cursor           := committed
      wrappedPacket    := false.B
    }
    for (lane <- 0 until 4) {
      when(lane.U < count)(data(index + lane.U) := io.in.bits.data(8 * lane + 7, 8 * lane))
    }
    collected := total
    offset    := 0.U
    padding   := false.B
    state     := Mux(io.in.bits.last, offering, collecting)
  }
  when(state === idle && padNeeded && partialWord && fault === 0.U && !aborting) {
    padding := true.B
    cursor  := committed
    state   := offering
  }
  val room      = 4.U(3.W) - cursor(1, 0)
  val remaining = length - offset
  val count     = Mux(padding, room, Mux(remaining < room, remaining, room))
  io.request.valid        := state === offering
  io.request.bits.address := Cat(cursor(63, 2), 0.U(2.W))
  val lanes = Wire(Vec(4, UInt(8.W)))
  val mask  = Wire(Vec(4, Bool()))
  for (lane <- 0 until 4) {
    val relative = lane.U - cursor(1, 0)
    mask(lane)  := lane.U >= cursor(1, 0) && relative < count
    lanes(lane) := Mux(mask(lane) && !padding, data(offset + relative), 0.U)
  }
  io.request.bits.data := lanes.asUInt
  io.request.bits.mask := mask.asUInt
  when(io.request.fire) { issued := count; state := waiting }
  io.response.ready := state === waiting
  when(io.response.fire) {
    val next       = cursor + issued
    val wrapped    = next === end
    val nextCursor = Mux(wrapped, start, next)
    val complete   = padding || offset + issued === length
    when(aborting) {
      state := idle
    }.elsewhen(io.response.bits) {
      fault        := 2.U
      faultAddress := Cat(cursor(63, 2), 0.U(2.W))
      state        := idle
    }.otherwise {
      cursor        := nextCursor
      offset        := offset + issued
      wrappedPacket := wrappedPacket || wrapped
      when(complete) {
        committed                                                     := nextCursor
        io.wrap                                                       := wrappedPacket || wrapped
        when((wrappedPacket || wrapped) && packetStopOnWrap)(stopping := true.B)
        state                                                         := idle
      }.otherwise {
        state := offering
      }
    }
  }
  // Offered writes remain valid through abort; finish that word, discard the
  // remaining staged packet, then reset. No new session can inherit a stale B.
  when(aborting && (state === idle || state === collecting)) {
    state                   := idle
    start                   := window.base.U
    limit                   := window.limit.U
    committed               := window.base.U
    cursor                  := window.base.U
    collected               := 0.U
    offset                  := 0.U
    padding                 := false.B
    stopping                := false.B
    wrappedPacket           := false.B
    fault                   := 0.U
    faultAddress            := 0.U
    when(!io.abort)(aborted := false.B)
  }
}
