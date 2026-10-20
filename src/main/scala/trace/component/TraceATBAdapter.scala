package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.interface._

/** IHI0032C transmitter with flush, synchronization and registered wakeup.
  * All harts share the configured Bridge ID; their srcID stays in the bytes.
  */
class TraceATBAdapter(dataBytes: Int = 4) extends Module with TraceParameters {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new TraceBeat(dataBytes)))
    val enable   = Input(Bool())
    val id       = Input(UInt(7.W))
    val atbm     = new ATBMaster(dataBytes)
    val upstream = Flipped(new TraceControl)
    val empty    = Output(Bool())
  })
  val packetActive = RegInit(false.B)
  val packetId     = Reg(UInt(7.W))
  val queue        = Module(new Queue(new ATData(dataBytes), 2))
  val syncBytes    = RegInit(0.U(log2Ceil(maxNullSeq + 1).W))
  val flush        = Module(new ATBFlushController)
  flush.io.request     := io.atbm.af.valid
  flush.io.clockEnable := io.atbm.atclken
  flush.io.available   := io.enable || packetActive || queue.io.deq.valid || syncBytes =/= 0.U
  flush.io.upstreamAck := io.upstream.flushAck
  flush.io.localDone   := !queue.io.deq.valid && syncBytes === 0.U
  io.upstream.flushReq := flush.io.upstreamReq
  io.upstream.syncReq  := io.atbm.syncReq && io.atbm.atclken && !reset.asBool
  io.atbm.af.ready     := flush.io.ready
  // Encapsulation synchronization is inserted only between complete packets.
  // It precedes the per-source Format 30 anchors requested through SYNCREQ.
  when(io.atbm.syncReq && io.atbm.atclken && io.enable && syncBytes === 0.U) {
    syncBytes := maxNullSeq.U
  }
  val align = syncBytes =/= 0.U && !packetActive
  val count = Mux(syncBytes > dataBytes.U, dataBytes.U, syncBytes)
  val admit = io.enable || packetActive || flush.io.upstreamReq
  queue.io.enq.valid := !reset.asBool && io.atbm.atclken && (align || (io.in.valid && admit))
  queue.io.enq.bits.data := Mux(
    align,
    Mux(syncBytes <= dataBytes.U, 0x80.U << ((count - 1.U) << 3), 0.U),
    io.in.bits.data
  )
  queue.io.enq.bits.bytes := Mux(align, count - 1.U, io.in.bits.bytes)
  queue.io.enq.bits.id    := Mux(packetActive, packetId, io.id)
  io.in.ready             := !reset.asBool && queue.io.enq.ready && admit && !align && io.atbm.atclken
  when(queue.io.enq.fire && align)(syncBytes := syncBytes - count)
  when(io.in.fire) {
    when(!packetActive)(assert(io.id > 0.U && io.id < 0x70.U))
    when(!packetActive)(packetId := io.id)
    packetActive := !io.in.bits.last
  }
  io.atbm.at.valid   := queue.io.deq.valid
  io.atbm.at.bits    := queue.io.deq.bits
  queue.io.deq.ready := io.atbm.at.ready && io.atbm.atclken
  val wakeup = RegNext(io.in.valid || queue.io.deq.valid || flush.io.active || syncBytes =/= 0.U, false.B)
  io.atbm.wakeup := wakeup
  io.empty       := !packetActive && !queue.io.deq.valid && syncBytes === 0.U
}
