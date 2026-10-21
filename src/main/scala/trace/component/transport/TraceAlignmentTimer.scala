package trace.component

import chisel3._
import chisel3.util._

/** Frequency 0 disables automatic alignment. Values 1..7 request a sequence
  * after 2^(frequency+7) accepted normal bytes. Alignment bytes never count.
  */
class TraceAlignmentTimer extends Module {
  val io = IO(new Bundle {
    val enable    = Input(Bool())
    val frequency = Input(UInt(3.W))
    val bytes     = Input(UInt(6.W))
    val serviced  = Input(Bool())
    val request   = Output(Bool())
    val pending   = Output(Bool())
  })
  val count             = RegInit(0.U(15.W))
  val pending           = RegInit(false.B)
  val previousFrequency = RegNext(io.frequency, 0.U)
  val changed           = previousFrequency =/= io.frequency
  val threshold         = (1.U(15.W) << (io.frequency +& 7.U))(14, 0)
  val sum               = count +& io.bytes
  io.request := io.enable && io.frequency =/= 0.U && !changed && !pending &&
    io.bytes =/= 0.U && sum >= threshold
  io.pending := pending
  when(!io.enable || io.frequency === 0.U) {
    count   := 0.U
    pending := false.B
  }.elsewhen(changed) {
    count   := io.bytes
    pending := false.B
  }.elsewhen(io.serviced) {
    count   := 0.U
    pending := false.B
  }.elsewhen(!pending) {
    count                    := Mux(io.request, threshold, sum(14, 0))
    when(io.request)(pending := true.B)
  }
}
