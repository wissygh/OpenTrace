package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.control.TsControlBundle

/** Internal Core time, sampled into BlockBuffer at ingress rather than when
  * a queued instruction eventually reaches PacketGen.
  */
class EncoderTimestampCounter(width: Int = 63) extends Module with TraceParameters {
  require(width > 0 && width <= 63)
  val io = IO(new Bundle {
    val control = Input(new TsControlBundle)
    val resetCount = Input(Bool())
    val debug = Input(Bool())
    val value = Output(UInt(width.W))
    val traceTime = Output(UInt(time_width_p.W))
  })
  val count = RegInit(0.U(width.W))
  val phase = RegInit(0.U(6.W))
  val prescale = io.control.Prescale.asUInt
  val priorPrescale = RegNext(prescale, ts_prescale_p.U(2.W))
  val period = (1.U(7.W) << (prescale << 1)) - 1.U
  val running = io.control.Count.asBool && (!io.debug || io.control.RunInDebug.asBool)
  when(io.resetCount) {
    count := 0.U
    phase := 0.U
  }.elsewhen(prescale =/= priorPrescale) {
    phase := 0.U
  }.elsewhen(running) {
    when(phase === period(5, 0)) {
      phase := 0.U
      count := count + 1.U
    }.otherwise {
      phase := phase + 1.U
    }
  }
  io.value := count
  // Packet time width is fixed by the profile; disabled time is encoded as 0.
  io.traceTime := Mux(io.control.Enable.asBool, count, 0.U)
}
