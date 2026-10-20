package trace.component

import chisel3._
import chisel3.util._
import trace.interface.TraceBeat

/** Dedicated plain-byte SRAM sink; bufferWords and CSR pointers are in 32-bit
  * words. Packet admission reserves the space to the end when StopOnWrap is set.
  */
class SRAMSinkCore(bufferWords: Int, wordBytes: Int = 4) extends Module {
  require(bufferWords > 1 && wordBytes == 4)
  private val ptrWidth = log2Ceil(bufferWords)
  val io = IO(new Bundle {
    val in           = Flipped(Decoupled(new TraceBeat(wordBytes)))
    val enable       = Input(Bool())
    val stopOnWrap   = Input(Bool())
    val writePointer = Flipped(Valid(UInt(ptrWidth.W)))
    val readPointer  = Flipped(Valid(UInt(ptrWidth.W)))
    val read         = Input(Bool())
    val readData     = Output(UInt(32.W))
    val wp           = Output(UInt(ptrWidth.W))
    val rp           = Output(UInt(ptrWidth.W))
    val wrap         = Output(Bool())
    val stop         = Output(Bool())
    val empty        = Output(Bool())
  })
  val memory          = Mem(bufferWords, UInt(32.W))
  val wp              = RegInit(0.U(ptrWidth.W))
  val rp              = RegInit(0.U(ptrWidth.W))
  val inPacket        = RegInit(false.B)
  val packetRemaining = RegInit(0.U(io.in.bits.packetBytes.getWidth.W))
  val stopping        = RegInit(false.B)
  val packer          = Module(new TraceBytePacker(wordBytes))
  val untilWrap       = (bufferWords.U - wp) << 2
  val available       = Mux(packer.io.pendingBytes <= untilWrap, untilWrap - packer.io.pendingBytes, 0.U)
  val fits            = !io.stopOnWrap || io.in.bits.packetBytes <= available
  val rejectPacket    = io.enable && !inPacket && io.in.valid && !fits
  val admission       = !stopping && (inPacket || (io.enable && fits))
  packer.io.in.valid  := io.in.valid && admission
  packer.io.in.bits   := io.in.bits
  io.in.ready         := packer.io.in.ready && admission
  packer.io.flush     := (stopping || rejectPacket || !io.enable) && !inPacket
  packer.io.out.ready := true.B
  when(io.in.fire) {
    val expected = Mux(inPacket, packetRemaining, io.in.bits.packetBytes)
    val accepted = io.in.bits.bytes +& 1.U
    assert(expected > 0.U && accepted <= expected)
    assert(io.in.bits.last === (accepted === expected), "packet length and last disagree")
    packetRemaining := expected - accepted
    inPacket        := !io.in.bits.last
  }
  when(!io.enable)(stopping   := false.B)
  when(rejectPacket)(stopping := true.B)
  io.wrap                     := false.B
  when(packer.io.out.fire) {
    memory.write(wp, packer.io.out.bits)
    when(wp === (bufferWords - 1).U) {
      wp                           := 0.U
      io.wrap                      := true.B
      when(io.stopOnWrap)(stopping := true.B)
    }.otherwise(wp := wp + 1.U)
  }
  when(io.writePointer.valid && !io.enable && packer.io.empty && !inPacket) {
    assert(io.writePointer.bits < bufferWords.U)
    wp := io.writePointer.bits
  }
  when(io.read)(rp := Mux(rp === (bufferWords - 1).U, 0.U, rp + 1.U))
  when(io.readPointer.valid) {
    assert(io.readPointer.bits < bufferWords.U)
    rp := io.readPointer.bits
  }
  io.readData := memory.read(rp)
  io.wp       := wp
  io.rp       := rp
  io.stop     := stopping && packer.io.empty && !inPacket
  io.empty    := packer.io.empty && !inPacket
}
