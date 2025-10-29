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
    enqDataBeats: Int,
    deqDataBeats: Int
) {}

class PacketEnq(enqDataBeats: Int) extends Bundle {
  val data = Vec(enqDataBeats, UInt(8.W))
  val len  = UInt(log2Up(enqDataBeats + 1).W) // max 41, true length
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
    val packetEnq      = Flipped(Decoupled(new PacketEnq(param.enqDataBeats)))
    val ctrl           = new CtrlInterface
    val sourceFlushAll = Option.when(!param.isLeaf)(Input(Bool()))
    val atbm           = new ATBMaster(param.deqDataBeats)
  })

  /**
    * parameters
    */
  private val fifoDepth     = param.fifoDepth
  private val enqBeats      = param.enqDataBeats
  private val enqLg2Beats   = log2Up(enqBeats)        // bytes - 1
  private val deqBeats      = param.deqDataBeats
  private val deqLg2Beats   = log2Up(deqBeats)        // bytes - 1
  private val maxTransNum   = maxPackets / deqBeats + (if (maxPackets % deqLg2Beats == 0) 0 else 1)
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

  /**
   * enq
   */
  enqPtrNext := enqPtr + Mux(packetEnq.fire, packetEnq.bits.len, 0.U)
  (0 until enqBeats).foreach(i => fifo((enqPtr + i.U).value) := packetEnq.bits.data(i))

  /**
    * flush
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
  val deqData         = Wire(Vec(deqBeats, UInt(8.W)))
  val firstBeatsValid = RegNext(trans.valid && !trans.ready) && trans.fire

  val header      = deqData(0).asTypeOf(new PacketHeader)
  val payloadLen  = header.length
  val timeStampEn = header.extend

  val deqPacketLen =
    Mux(payloadLen === 0.U, 1.U, 2.U +& payloadLen +& (timeStampEn << log2Ceil(time_width_p / 8)).asUInt)
  val packetLenTail = deqPacketLen(deqLg2Beats - 1, 0)
  val align         = !packetLenTail.orR

  val lastBeatsNext = Mux(align, deqBeats.U, packetLenTail) // trans.fire cycle
  val lastBeats     = RegEnable(lastBeatsNext, firstBeatsValid)
  val counter       = RegInit(0.U(transCntWidth.W))         // count deqBeats in a packet
  // - Mux(align, 1.U, 0.U), first deqBeats in trans.fire cycle
  val counterNext = Mux(firstBeatsValid, (deqPacketLen >> deqLg2Beats) - Mux(align, 1.U, 0.U), counter - 1.U)
  counter := counterNext

  val deqNum = WireInit(0.U((deqLg2Beats + 1).W))
  deqPtrNext := deqPtr + Mux(trans.fire, deqNum, 0.U)
  deqNum := Mux(
    firstBeatsValid && counterNext === 0.U,
    lastBeatsNext,
    Mux(trans.fire && counter === 0.U, lastBeats, deqBeats.U)
  )

  (0 until deqBeats).foreach(i =>
    deqData(i) := fifo(deqPtr.value + i.U)
  )

  val fifoIsEmpty = isEmpty(enqPtr, deqPtr)
  val fifoIsFull  = isFull(enqPtr, deqPtr)

  /**
    * atb
    */
  // wait a cycle after a packet is transmitted, which is reasonable because funnel needs to arbitrate packets from different src
  io.atbm.at.valid      := Mux(RegNext(counter === 0.U), false.B, !fifoIsEmpty)
  io.atbm.at.bits.data  := deqData.asUInt
  io.atbm.at.bits.bytes := deqNum - 1.U
  io.atbm.at.bits.id    := (io.ctrl.srcid + 1.U) & 0x7f.U // id 0 is reserved
  io.atbm.af.ready      := flushComplete                  // flush finish, slave will deassert flush.valid next cycle
  io.packetEnq.ready    := !fifoIsFull && io.atbm.at.ready
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
