package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.interface.PIBTracePins
import trace.interface.TraceBeat

/** Complete packets prevent Manchester starvation and preserve packet boundaries
  * during Disable. Output timing is in trace-clock cycles; physical DDR/pad and
  * clock-forwarding implementation must be constrained by the SoC integration.
  */
class PIBSerializer(startupAlignment: Boolean = true) extends Module with TraceParameters {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new TraceBeat(4)))
    val enable    = Input(Bool())
    val asyncFreq = Input(UInt(3.W))
    val mode      = Input(UInt(4.W))
    val divider   = Input(UInt(16.W))
    val center    = Input(Bool())
    val calibrate = Input(Bool())
    val pins      = new PIBTracePins
    val empty     = Output(Bool())
    val syncReq   = Output(Bool())
  })
  val previousEnable   = RegNext(io.enable, false.B)
  val newSession       = io.enable && !previousEnable
  val alignmentPending = RegInit(false.B)
  when(newSession && !io.calibrate && startupAlignment.B)(alignmentPending := true.B)
  val timer = Module(new TraceAlignmentTimer)
  timer.io.enable                         := io.enable && !io.calibrate
  timer.io.frequency                      := io.asyncFreq
  timer.io.bytes                          := Mux(io.in.fire && io.in.bits.last, io.in.bits.packetBytes, 0.U)
  timer.io.serviced                       := false.B
  when(timer.io.request)(alignmentPending := true.B)
  io.syncReq                              := (newSession || timer.io.request) && !io.calibrate
  val idle :: collecting :: sending :: finishing :: Nil = Enum(4)
  val state                                             = RegInit(idle)
  val buffer                                            = Reg(Vec(maxNullSeq, UInt(8.W)))
  val length                                            = Reg(UInt(log2Ceil(maxNullSeq + 1).W))
  val collected                                         = RegInit(0.U(log2Ceil(maxNullSeq + 1).W))
  val bitIndex                                          = RegInit(0.U(log2Ceil(maxNullSeq * 8 + 1).W))
  val calibrating                                       = RegInit(false.B)
  val parallel                                          = io.mode >= 8.U && io.mode <= 12.U
  val uart                                              = io.mode === 5.U
  val manchester                                        = io.mode === 4.U
  val supported                                         = parallel || uart || manchester
  val config                                            = Cat(io.mode, io.divider, io.center)
  val previousConfig                                    = RegNext(config, 0.U)
  val changed                                           = config =/= previousConfig
  val phaseCount                                        = RegInit(0.U(16.W))
  val centerPhase                                       = RegInit(false.B)
  val traceClock                                        = RegInit(false.B)
  val pins                                              = RegInit(0.U(16.W))
  val frame                                             = RegInit(0.U(4.W))
  val half                                              = RegInit(false.B)
  val manStart                                          = RegInit(true.B)
  val manStop                                           = RegInit(false.B)
  val tick                                              = phaseCount === io.divider && supported && !changed
  val dataTick                                          = tick && (!parallel || !io.center || !centerPhase)
  when(changed) {
    phaseCount  := 0.U
    centerPhase := false.B
    traceClock  := false.B
    pins        := Mux(uart, 1.U, 0.U)
  }.elsewhen(supported) {
    when(tick) {
      phaseCount := 0.U
      when(parallel) {
        when(!io.center || centerPhase)(traceClock := !traceClock)
        when(io.center)(centerPhase                := !centerPhase)
      }
    }.otherwise(phaseCount := phaseCount + 1.U)
  }
  val parallelWidth = (1.U(5.W) << (io.mode - 8.U))(4, 0)
  // The clock also runs through idle/collection. Account for those symbols so
  // narrow parallel modes insert whole null bytes between encoded packets.
  val bytePhase = RegInit(0.U(3.W))
  when(changed) {
    bytePhase := 0.U
  }.elsewhen(dataTick && parallel) {
    bytePhase := bytePhase + parallelWidth
  }
  val totalBits     = length << 3
  val bits          = buffer.asUInt >> bitIndex
  val idleData      = Mux(uart, 1.U(16.W), 0.U(16.W))
  io.pins.clock   := traceClock && parallel
  io.pins.data    := pins
  io.pins.control := false.B
  io.empty        := (state === idle && !alignmentPending && !(newSession && startupAlignment.B)) || calibrating
  io.in.ready := supported && !calibrating &&
    ((state === idle && io.enable && !io.calibrate && !alignmentPending && !(newSession && startupAlignment.B)) || state === collecting)

  def finishPacket(): Unit =
    when(calibrating && io.enable && io.calibrate) {
      bitIndex := 0.U
      frame    := 0.U
      half     := false.B
      manStart := true.B
      manStop  := false.B
    }.otherwise(state := finishing)
  when(io.in.fire) {
    val start    = Mux(state === collecting, collected, 0.U)
    val count    = io.in.bits.bytes +& 1.U
    val expected = Mux(state === collecting, length, io.in.bits.packetBytes)
    val next     = start +& count
    assert(expected =/= 0.U && expected <= maxNullSeq.U && next <= expected)
    assert(io.in.bits.last === (next === expected), "PIB input packet boundary is inconsistent")
    when(state === collecting)(assert(io.in.bits.packetBytes === length))
    for (lane <- 0 until 4) {
      when(lane.U < count)(buffer(start + lane.U) := io.in.bits.data(8 * lane + 7, 8 * lane))
    }
    when(state === idle)(length := io.in.bits.packetBytes)
    collected                   := next
    calibrating                 := false.B
    bitIndex                    := 0.U
    frame                       := 0.U
    half                        := false.B
    manStart                    := true.B
    manStop                     := false.B
    state                       := Mux(io.in.bits.last, sending, collecting)
  }
  when(state === idle && io.enable && alignmentPending && !io.calibrate) {
    timer.io.serviced := true.B
    for (i <- 0 until maxNullSeq) { buffer(i) := (if (i == maxNullSeq - 1) 0x80 else 0).U }
    length           := maxNullSeq.U
    bitIndex         := 0.U
    frame            := 0.U
    half             := false.B
    manStart         := true.B
    manStop          := false.B
    calibrating      := false.B
    alignmentPending := false.B
    state            := sending
  }
  when(state === idle && !io.enable)(alignmentPending := false.B)
  when(state === idle && io.enable && io.calibrate && supported) {
    val pattern = MuxLookup(io.mode, "hff0055aa".U(64.W))(Seq(
      9.U  -> "h33cc6666".U(64.W),
      10.U -> "h0ff05a5a".U(64.W),
      12.U -> "hffff00005555aaaa".U(64.W)
    ))
    for (i <- 0 until maxNullSeq) { buffer(i) := (pattern >> ((i % 8) * 8))(7, 0) }
    length      := Mux(io.mode === 12.U, 8.U, 4.U)
    bitIndex    := 0.U
    frame       := 0.U
    half        := false.B
    manStart    := true.B
    manStop     := false.B
    calibrating := true.B
    state       := sending
  }
  when(dataTick) {
    when(state === sending) {
      when(parallel) {
        when(bitIndex === 0.U && bytePhase =/= 0.U) {
          pins := 0.U
        }.otherwise {
          val widthMask = (1.U(17.W) << parallelWidth) - 1.U
          pins := bits(15, 0) & widthMask(15, 0)
          // Only a 16-bit packet's odd final byte requires zero padding.
          when(bitIndex + parallelWidth >= totalBits) {
            when(bitIndex + parallelWidth > totalBits)(pins := Cat(0.U(8.W), bits(7, 0)))
            finishPacket()
          }.otherwise(bitIndex := bitIndex + parallelWidth)
        }
      }.elsewhen(uart) {
        when(frame === 0.U) { pins := 0.U; frame := 1.U }
          .elsewhen(frame <= 8.U) {
            pins     := bits(0)
            bitIndex := bitIndex + 1.U
            frame    := frame + 1.U
          }.otherwise {
            pins  := 1.U
            frame := 0.U
            when(bitIndex === totalBits)(finishPacket())
          }
      }.elsewhen(manchester) {
        when(manStart) {
          pins                := !half
          half                := !half
          when(half)(manStart := false.B)
        }.elsewhen(manStop) {
          pins := 0.U
          half := !half
          when(half) { manStop := false.B; finishPacket() }
        }.otherwise {
          pins := Mux(half, !bits(0), bits(0))
          half := !half
          when(half) {
            bitIndex                                   := bitIndex + 1.U
            when(bitIndex + 1.U === totalBits)(manStop := true.B)
          }
        }
      }
    }.otherwise {
      pins := idleData
      when(state === finishing) { state := idle; calibrating := false.B }
    }
  }
  when(calibrating && (!io.enable || !io.calibrate)) {
    state       := idle
    calibrating := false.B
    pins        := idleData
    frame       := 0.U
    half        := false.B
  }
}
