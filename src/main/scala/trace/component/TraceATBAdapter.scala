package trace.component

import chisel3._
import chisel3.util._
import trace.interface._

/** Converts the merged packet stream to a standard AT data channel.
  * All harts share the configured Bridge ID; their srcID stays in the bytes.
  */
class TraceATBAdapter(dataBytes: Int = 4) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TraceBeat(dataBytes)))
    val enable = Input(Bool())
    val id = Input(UInt(7.W))
    val atbm = new ATBMaster(dataBytes)
    val empty = Output(Bool())
  })
  val packetActive = RegInit(false.B)
  val packetId = Reg(UInt(7.W))
  val queue = Module(new Queue(new ATData(dataBytes), 2))
  val admit = io.enable || packetActive
  queue.io.enq.valid := io.in.valid && admit
  queue.io.enq.bits.data := io.in.bits.data
  queue.io.enq.bits.bytes := io.in.bits.bytes
  queue.io.enq.bits.id := Mux(packetActive, packetId, io.id)
  io.in.ready := queue.io.enq.ready && admit
  when(io.in.fire) {
    assert(io.id > 0.U && io.id < 0x70.U)
    when(!packetActive) { packetId := io.id }
    packetActive := !io.in.bits.last
  }
  io.atbm.at <> queue.io.deq
  io.empty := !packetActive && !queue.io.deq.valid
}
