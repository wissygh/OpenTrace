package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters
import trace.interface._
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper

case class FifoParam(
    isLeaf:       Boolean,
    fifoDepth:    Int,
    enqDataBytes: Int,
    deqDataBytes: Int
) {}

class PacketEnq(enqDataBytes: Int) extends Bundle {
  val data = Vec(enqDataBytes, UInt(8.W))
  val len  = UInt(log2Up(enqDataBytes + 1).W) // max 41, true length
}

class CtrlInterface extends Bundle with TraceParameters {
  val fifoIsEmpty = Output(Bool())
  val fifoIsFull  = Output(Bool())
}

/** Stores complete packets and serializes them without interpreting their contents. */
class Fifo(param: FifoParam) extends Module with HasCircularQueuePtrHelper with TraceParameters {
  private val depth    = param.fifoDepth
  private val inBytes  = param.enqDataBytes
  private val outBytes = param.deqDataBytes
  require(depth >= inBytes && inBytes > 0)
  require(outBytes > 0 && isPow2(outBytes))

  val io = IO(new Bundle {
    val packetEnq      = Flipped(Decoupled(new PacketEnq(inBytes)))
    val ctrl           = new CtrlInterface
    val sourceFlushAll = Option.when(!param.isLeaf)(Input(Bool()))
    val atbm           = Decoupled(new TraceBeat(outBytes))
    val control        = new TraceControl
  })

  val data        = Reg(Vec(depth, UInt(8.W)))
  val lengths     = Module(new Queue(UInt(log2Ceil(inBytes + 1).W), depth))
  val writePtr    = RegInit(FifoPtr(depth, false.B, 0.U))
  val readPtr     = RegInit(FifoPtr(depth, false.B, 0.U))
  val used        = RegInit(0.U(log2Ceil(depth + 1).W))
  val remaining   = RegInit(0.U(log2Ceil(inBytes + 1).W))
  val packetSize  = lengths.io.deq.bits
  val available   = Mux(remaining === 0.U, packetSize, remaining)
  val count       = Mux(available > outBytes.U, outBytes.U, available)
  val last        = available <= outBytes.U
  val legalLength = io.packetEnq.bits.len > 0.U && io.packetEnq.bits.len <= inBytes.U

  io.packetEnq.ready   := lengths.io.enq.ready && legalLength && io.packetEnq.bits.len <= depth.U - used
  lengths.io.enq.valid := io.packetEnq.fire
  lengths.io.enq.bits  := io.packetEnq.bits.len
  when(io.packetEnq.valid) {
    assert(legalLength, "PacketEnq.len must describe a nonempty complete packet")
  }
  when(io.packetEnq.fire) {
    for (i <- 0 until inBytes) {
      when(i.U < io.packetEnq.bits.len) {
        data((writePtr + i.U).value) := io.packetEnq.bits.data(i)
      }
    }
    writePtr := writePtr + io.packetEnq.bits.len
  }

  val beat = Wire(Vec(outBytes, UInt(8.W)))
  for (i <- 0 until outBytes) {
    // Zero unused lanes so a concurrent enqueue cannot change stalled bits.
    beat(i) := Mux(i.U < count, data((readPtr + i.U).value), 0.U)
  }
  val flushing = RegInit(false.B)
  val oldBytes = RegInit(0.U(log2Ceil(depth + inBytes + 1).W))
  // Include the packet already offered at the request, even if FIFO is full.
  when(io.control.flushReq && !flushing) {
    flushing := true.B
    oldBytes := used +& Mux(io.packetEnq.valid, io.packetEnq.bits.len, 0.U) -
      Mux(io.atbm.fire, count, 0.U)
  }.elsewhen(flushing && io.atbm.fire) {
    assert(count <= oldBytes)
    oldBytes := oldBytes - count
  }
  when(!io.control.flushReq)(flushing := false.B)
  io.control.flushAck      := reset.asBool || (flushing && oldBytes === 0.U)
  io.atbm.valid            := lengths.io.deq.valid && !(flushing && oldBytes === 0.U)
  io.atbm.bits.data        := beat.asUInt
  io.atbm.bits.bytes       := count - 1.U
  io.atbm.bits.packetBytes := packetSize
  io.atbm.bits.last        := last
  lengths.io.deq.ready     := io.atbm.fire && last
  when(io.atbm.fire) {
    readPtr   := readPtr + count
    remaining := Mux(last, 0.U, available - count)
  }
  used := used + Mux(io.packetEnq.fire, io.packetEnq.bits.len, 0.U) - Mux(io.atbm.fire, count, 0.U)
  assert(used <= depth.U)
  io.ctrl.fifoIsEmpty := !lengths.io.deq.valid
  io.ctrl.fifoIsFull  := used === depth.U || !lengths.io.enq.ready
}

class FifoPtr(entries: Int) extends CircularQueuePtr[FifoPtr](entries) {}

object FifoPtr {
  def apply(entries: Int, f: Bool, v: UInt): FifoPtr = {
    val ptr = Wire(new FifoPtr(entries))
    ptr.flag  := f
    ptr.value := v
    ptr
  }
}
