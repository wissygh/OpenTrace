package trace.component

import chisel3._
import chisel3.util._
import trace.interface.TraceBeat

/** Packs only valid input lanes into a plain-byte stream. Flush is allowed at
  * a packet boundary; its only padding is zero (E-Trace null.idle).
  */
class TraceBytePacker(wordBytes: Int = 4) extends Module {
  require(wordBytes > 0 && isPow2(wordBytes))
  val io = IO(new Bundle {
    val in           = Flipped(Decoupled(new TraceBeat(wordBytes)))
    val flush        = Input(Bool())
    val out          = Decoupled(UInt((wordBytes * 8).W))
    val pendingBytes = Output(UInt(log2Ceil(2 * wordBytes + 1).W))
    val empty        = Output(Bool())
  })
  val data     = RegInit(0.U((wordBytes * 16).W))
  val count    = RegInit(0.U(log2Ceil(2 * wordBytes + 1).W))
  val incoming = io.in.bits.bytes +& 1.U
  io.out.valid := count >= wordBytes.U || (io.flush && count =/= 0.U)
  io.out.bits  := data(wordBytes * 8 - 1, 0)
  val removed  = Mux(io.out.fire, Mux(count >= wordBytes.U, wordBytes.U, count), 0.U)
  val retained = count - removed
  io.in.ready := !io.flush && incoming <= (2 * wordBytes).U - retained
  val masked = VecInit((0 until wordBytes).map(i =>
    Mux(i.U < incoming, io.in.bits.data(8 * i + 7, 8 * i), 0.U(8.W))
  )).asUInt
  when(io.in.fire || io.out.fire) {
    data  := (data >> (removed << 3)) | Mux(io.in.fire, masked.pad(wordBytes * 16) << (retained << 3), 0.U)
    count := retained + Mux(io.in.fire, incoming, 0.U)
  }
  when(io.in.valid)(assert(incoming <= wordBytes.U))
  io.pendingBytes := count
  io.empty        := count === 0.U
}
