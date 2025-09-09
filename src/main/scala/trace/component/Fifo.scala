package trace.component

import cats.syntax.validated
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters
import trace.interface._
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper

class PacketEnq extends Bundle with TraceParameters {
  val data = Vec(maxPackets, UInt(8.W))
  val len  = UInt(6.W) // max 41
}

class CtrlInterface extends Bundle with TraceParameters {
  val srcid = UInt(src_bits_p.W)
}

class Fifo extends Module
    with HasCircularQueuePtrHelper
    with TraceParameters {

  val io = IO(new Bundle {
    val packetEnq = Input(new PacketEnq)
    val fromCtrl  = Input(new CtrlInterface)
    val atbm      = new ATBMaster
  })

  val packetEnq = io.packetEnq
  val flush     = io.atbm.af
  val trans     = io.atbm.at

  /**
   * fifo
   */
  val fifo       = Reg(Vec(encoderFifoDepth, UInt(8.W)))
  val enqPtr     = RegInit(FifoPtr(false.B, 0.U))
  val deqPtr     = RegInit(FifoPtr(false.B, 0.U))
  val enqPtrNext = WireInit(enqPtr)
  val deqPtrNext = WireInit(deqPtr)
  enqPtr := enqPtrNext
  deqPtr := deqPtrNext

  /**
   * enq
   */
  enqPtrNext := enqPtr + packetEnq.len
  (0 until maxPackets).foreach(i =>
    fifo(enqPtr.value + i.U) := packetEnq.data(i)
  )

  /**
    * flush
    */
  val flushStart = flush.valid && !RegNext(flush.valid)
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
  val deqData         = Wire(Vec(atbBeats, UInt(8.W)))
  val firstBeatsValid = RegNext(trans.valid && !trans.ready) && trans.fire

  val header      = deqData(0).asTypeOf(new PacketHeader)
  val payloadLen  = header.length
  val timeStampEn = header.extend

  val deqPacketLen  = Mux(payloadLen === 0.U, 1.U, 2.U + payloadLen + (timeStampEn << log2Ceil(time_width_p)).asUInt)
  val packetLenTail = deqPacketLen(lg2Beats - 1, 0)
  val align         = !packetLenTail.orR

  val lastBeatsNext = Mux(align, atbBeats.U, packetLenTail) // trans.fire cycle
  val lastBeats     = RegEnable(lastBeatsNext, firstBeatsValid)
  val counter       = RegInit(0.U(transCntWidth.W))         // count beats in a packet
  // - Mux(align, 1.U, 0.U), first beats in trans.fire cycle
  val counterNext = Mux(firstBeatsValid, (deqPacketLen >> lg2Beats) - Mux(align, 1.U, 0.U), counter - 1.U)
  counter := counterNext

  val deqNum = WireInit(0.U((lg2Beats + 1).W))
  deqPtrNext := deqPtr + Mux(trans.fire, deqNum, 0.U)
  deqNum := Mux(
    firstBeatsValid && counterNext === 0.U,
    lastBeatsNext,
    Mux(trans.fire && counter === 0.U, lastBeats, atbBeats.U)
  )

  (0 until atbBeats).foreach(i =>
    deqData(i) := fifo(deqPtr.value + i.U)
  )

  val entriesValidNum = distanceBetween(enqPtr, deqPtr) // todo fix
  val fifoIsNotEmpty  = entriesValidNum > 0.U

  /**
    * atb
    */
  // wait a cycle after a packet is transmitted, which is reasonable because funnel needs to arbitrate packets from different src
  io.atbm.at.valid      := Mux(RegNext(counter === 0.U), false.B, fifoIsNotEmpty)
  io.atbm.at.bits.data  := deqData.asUInt
  io.atbm.at.bits.bytes := deqNum - 1.U
  io.atbm.at.bits.id    := (io.fromCtrl.srcid + 1.U) & 0x7f.U // id 0 is reserved
  io.atbm.af.ready := flushComplete // flush finish, slave will deassert flush.valid next cycle
}

class FifoPtr(entries: Int) extends CircularQueuePtr[FifoPtr](
      entries
    ) with HasCircularQueuePtrHelper with TraceParameters {

  def this() = this(41 * 3) // todo: fix

}

object FifoPtr {
  def apply(f: Bool, v: UInt): FifoPtr = {
    val ptr = Wire(new FifoPtr)
    ptr.flag  := f
    ptr.value := v
    ptr
  }
}
