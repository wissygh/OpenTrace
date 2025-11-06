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
  val srcid       = Input(UInt(src_bits_p.W))
  val fifoIsEmpty = Output(Bool())
  val fifoIsFull  = Output(Bool())
}

class Fifo(param: FifoParam) extends Module
    with HasCircularQueuePtrHelper
    with TraceParameters {

  val io = IO(new Bundle {
    val packetEnq      = Flipped(Decoupled(new PacketEnq(param.enqDataBytes)))
    val ctrl           = new CtrlInterface
    val sourceFlushAll = Option.when(!param.isLeaf)(Input(Bool()))
    val atbm           = new ATBMaster(param.deqDataBytes)
  })

  /**
    * parameters
    */
  private val fifoDepth     = param.fifoDepth
  private val enqBytes      = param.enqDataBytes
  private val enqLg2Bytes   = log2Up(enqBytes)        // bytes - 1
  private val deqBytes      = param.deqDataBytes
  private val deqLg2Bytes   = log2Up(deqBytes)        // bytes - 1
  private val maxTransNum   = maxPackets / deqBytes + (if (maxPackets % deqBytes == 0) 0 else 1)
  private val transCntWidth = log2Up(maxTransNum + 1) // 6

  /**
    * io
    */
  val packetEnq = io.packetEnq
  val flush     = io.atbm.af
  val trans     = io.atbm.at

  /**
   * fifo
   * todo: think of fifo is full
   */
  val fifo = Reg(Vec(fifoDepth, UInt(8.W)))
  require(fifoDepth > 0, "Fifo depth must be > 0")
  val enqPtr     = RegInit(FifoPtr(fifoDepth, false.B, 0.U))
  val deqPtr     = RegInit(FifoPtr(fifoDepth, false.B, 0.U))
  val enqPtrNext = WireInit(enqPtr)
  val deqPtrNext = WireInit(deqPtr)
  enqPtr := enqPtrNext
  deqPtr := deqPtrNext

  dontTouch(enqPtr)
  dontTouch(enqPtrNext)
  dontTouch(deqPtr)
  dontTouch(deqPtrNext)

  /**
   * enq
   */
  enqPtrNext := enqPtr + Mux(packetEnq.fire, packetEnq.bits.len, 0.U)
  (0 until enqBytes).foreach(i => fifo((enqPtr + i.U).value) := packetEnq.bits.data(i))

  /**
    * Flush
    * Encoder is leaf. Funnel is root.
    */
  val flushStart = if (param.isLeaf) flush.valid && !RegNext(flush.valid) else io.sourceFlushAll.get
  val flushPtr =
    RegEnable(enqPtrNext, flushStart) // flush data in buffer before flush request(including flush req cycle)
  val flushState    = RegInit(false.B)
  val flushComplete = flushState && (deqPtr === flushPtr)
  when(flushStart) {
    flushState := true.B
  }.elsewhen(flushComplete) {
    flushState := false.B
  }

  /**
   * deq
   */
  // todo: think about the case that a packet is finished and the next packet starts immediately
  val deqData        = Wire(Vec(deqBytes, UInt(8.W)))
  val firstBeatValid = RegNext(!trans.fire) && trans.fire

  val header      = deqData(0).asTypeOf(new PacketHeader)
  val payloadLen  = header.length
  val timeStampEn = header.extend

  // todo: think null packet
  val deqPacketLen =
    Mux(payloadLen === 0.U, 0.U, 2.U +& payloadLen +& (timeStampEn << log2Ceil(time_width_p / 8)).asUInt)
  val packetLenTail = deqPacketLen(deqLg2Bytes - 1, 0)
  val align         = !packetLenTail.orR

  val lastBytesNext = Mux(align, deqBytes.U, packetLenTail) // trans.fire cycle
  val lastBytes     = RegEnable(lastBytesNext, firstBeatValid)
  val counter       = RegInit(0.U(transCntWidth.W))         // count deqBytes in a packet
  val counterNext   = WireInit(counter)                     // Trans is last cycle when counterNext === 0.U
  counter := counterNext
  // - Mux(align, 1.U, 0.U), first deq in trans.fire cycle
  when(firstBeatValid && counter === 0.U) { // todo: fix control
    counterNext := (deqPacketLen >> deqLg2Bytes) - Mux(align, 1.U, 0.U)
  }.elsewhen(!firstBeatValid && counter > 0.U && trans.fire) {
    counterNext := counter - 1.U
  }.otherwise {
    counterNext := counter
  }

  val deqNum = WireInit(0.U((deqLg2Bytes + 1).W))
  deqPtrNext := deqPtr + Mux(trans.fire, deqNum, 0.U)
  deqNum := Mux(
    firstBeatValid && counterNext === 0.U, // only one beat
    lastBytesNext,
    Mux(!firstBeatValid && trans.fire && counterNext === 0.U, lastBytes, deqBytes.U)
  )

  (0 until deqBytes).foreach(i =>
    deqData(i) := fifo(deqPtr.value + i.U)
  )

  val fifoValidLen = distanceBetween(enqPtr, deqPtr)
  val fifoIsEmpty = isEmpty(enqPtr, deqPtr)
  val fifoIsFull  = isFull(enqPtr, deqPtr)

  /**
    * atb
    */
  // wait a cycle after a packet is transmitted, which is reasonable because funnel needs to arbitrate packets from different src
  io.atbm.at.valid      := !fifoIsEmpty
  io.atbm.at.bits.data  := deqData.asUInt
  io.atbm.at.bits.bytes := deqNum - 1.U
  io.atbm.at.bits.id    := (io.ctrl.srcid + 1.U) & 0x7f.U // id 0 is reserved
  io.atbm.af.ready      := flushComplete                  // flush finish, slave will deassert flush.valid next cycle
  io.packetEnq.ready    := !fifoIsFull                    // todo: Full is not only encough.
  io.ctrl.fifoIsEmpty   := fifoIsEmpty
  io.ctrl.fifoIsFull    := fifoIsFull
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
