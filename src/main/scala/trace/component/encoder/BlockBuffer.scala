package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceIngressConfig
import trace.TraceParameters
import trace.control._
import trace.interface._
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper
import utils.Compress
import utils.Lzc

class BlockBuffer(stallResponseCycles: Int = 1, entries: Int = 16)
    extends Module with TraceParameters with HasCircularQueuePtrHelper {
  override def block_buffer_depth_p = entries
  require(entries > 0)
  require(stallResponseCycles >= 0)
  private val stallReserve = TraceIngressConfig(entries, stallResponseCycles).reservedEntries(BlockNum)

  val io = IO(new Bundle {
    val fromCore       = Input(new FromCore)
    val deqReady       = Input(Bool())
    val bufferIsFull   = Output(Bool())
    val overflow       = Output(Bool())
    val droppedEntries = Output(UInt(log2Ceil(BlockNum + 1).W))
    val empty          = Output(Bool())
    val deqEntry       = Output(new BlockBufferEntry)
  })

  // trace compress: Nxt -> Cur
  val inValidVec = VecInit(io.fromCore.blocks.map(block =>
    block.valid &&
      (block.bits.iretire =/= 0.U || Itype.isTrap(block.bits.itype))
  ))
  val inTypeIsNotNoneVec = VecInit(io.fromCore.blocks.map(block => Itype.isNotNone(block.bits.itype)))
  for (i <- 0 until BlockNum) {
    val block = io.fromCore.blocks(i)
    when(block.valid) {
      if (i > 0) assert(io.fromCore.blocks(i - 1).valid, "core blocks must be contiguous from index zero")
      when(block.bits.iretire =/= 0.U) {
        assert(block.bits.iretire >= (1.U << block.bits.ilastsize), "iretire must include the last instruction")
      }
      when(block.bits.iretire =/= 0.U || Itype.isExp(block.bits.itype)) {
        assert(!block.bits.iaddr(0), "instruction addresses must be halfword aligned")
      }
      if (i < BlockNum - 1) {
        when(Itype.isTrap(block.bits.itype)) {
          assert(!inValidVec.asUInt(BlockNum - 1, i + 1).orR, "trap must terminate the newest valid block")
        }
      }
    }
  }
  val needPcVec = Wire(Vec(BlockNum, Bool()))
  for (i <- 0 until BlockNum) { // todo: tail of fifo
    val rightHasValid = if (i == BlockNum - 1) false.B else inValidVec.asUInt(BlockNum - 1, i + 1).orR
    needPcVec(i) := inValidVec(i) & (inTypeIsNotNoneVec(i) || io.fromCore.blocks(i).bits.trigNotify || !rightHasValid)
  }

  val blocksCompressed = WireInit(io.fromCore.blocks)
  blocksCompressed.zip(inValidVec).foreach { case (block, valid) => block.valid := valid }
  val enq = WireInit(io.fromCore)
  for (i <- 1 until BlockNum) {
    when(inValidVec(i - 1) && !needPcVec(i - 1)) {
      // itype, ilastsize, sijump is belong to block which need pc.
      val retired = blocksCompressed(i - 1).bits.iretire +& io.fromCore.blocks(i).bits.iretire
      assert(!retired(IretireWidthCompressed), "compacted iretire exceeds ingress field width")
      blocksCompressed(i).bits.iretire := retired
      blocksCompressed(i).bits.trigNotify := blocksCompressed(i - 1).bits.trigNotify | io.fromCore.blocks(
        i
      ).bits.trigNotify
      blocksCompressed(i).bits.iaddr := blocksCompressed(i - 1).bits.iaddr
    }
  }
  enq.priv        := io.fromCore.priv
  enq.time        := io.fromCore.time
  enq.context     := io.fromCore.context
  enq.ctype       := io.fromCore.ctype
  enq.cause       := io.fromCore.cause
  enq.tval        := io.fromCore.tval
  enq.impdef      := io.fromCore.impdef
  enq.hartIsReset := io.fromCore.hartIsReset
  enq.trigger     := io.fromCore.trigger
  val compacted = WireInit(0.U.asTypeOf(chiselTypeOf(blocksCompressed)))
  Compress.compress(needPcVec, blocksCompressed, compacted)
  enq.blocks := compacted

  dontTouch(needPcVec)
  dontTouch(enq.blocks)

  val fifo = Reg(Vec(block_buffer_depth_p, new BlockBufferEntry))
  require(block_buffer_depth_p > 0, "Fifo depth must be > 0")
  val enqPtr     = RegInit(BlockPtr(block_buffer_depth_p, false.B, 0.U))
  val deqPtr     = RegInit(BlockPtr(block_buffer_depth_p, false.B, 0.U))
  val enqPtrNext = WireInit(enqPtr)
  val deqPtrNext = WireInit(deqPtr)
  enqPtr := enqPtrNext
  deqPtr := deqPtrNext

  val fifoIsEmpty  = isEmpty(enqPtr, deqPtr)
  val metadataOnly = !needPcVec.asUInt.orR && (io.fromCore.trigger.traceOn || io.fromCore.trigger.traceOff)
  val enqCount     = Mux(metadataOnly, 1.U, PopCount(needPcVec.asUInt))
  val freeEntries  = hasFreeEntries(enqPtr, deqPtr)
  val dequeue      = io.deqReady && !fifoIsEmpty
  // A register FIFO may replace the entry read on this edge even when full.
  val available = freeEntries +& dequeue.asUInt
  val canEnq    = enqCount <= available
  // Stop admitting post-gap data until an ordered marker has been written at
  // the old tail. Keep draining all previously accepted entries normally.
  val lossPending = RegInit(false.B)
  val lostTrigger = RegInit(TriggerAction.None)
  val incomingTrigger = Mux(
    io.fromCore.trigger.traceOff,
    TriggerAction.TraceOff,
    Mux(io.fromCore.trigger.traceOn, TriggerAction.TraceOn, TriggerAction.None)
  )
  val markerTrigger = Mux(incomingTrigger =/= TriggerAction.None, incomingTrigger, lostTrigger)
  val insertMarker  = lossPending && available =/= 0.U
  val admit         = !lossPending && canEnq
  val dropped       = enqCount =/= 0.U && !admit
  when(dropped && !lossPending)(lossPending := true.B)
  when(dropped)(lostTrigger                 := markerTrigger)
  when(insertMarker) {
    val marker = WireInit(0.U.asTypeOf(new BlockBufferEntry))
    marker.valid       := true.B
    marker.traceLost   := true.B
    marker.trigger     := markerTrigger
    fifo(enqPtr.value) := marker
    lossPending        := false.B
    lostTrigger        := TriggerAction.None
  }
  enqPtrNext := Mux(insertMarker, enqPtr + 1.U, Mux(admit, enqPtr + enqCount, enqPtr))
  deqPtrNext := deqPtr + dequeue.asUInt

  // enqueue
  for (i <- 0 until BlockNum) {
    when(admit && i.U < enqCount) {
      fifo((enqPtr + i.U).value) := {
        val entry = Wire(new BlockBufferEntry)
        entry.priv        := enq.priv
        entry.time        := enq.time
        entry.context     := enq.context
        entry.ctype       := Mux((i == 0).B, enq.ctype, Ctype.None)
        entry.cause       := enq.cause
        entry.tval        := enq.tval
        entry.impdef      := enq.impdef
        entry.hartIsReset := enq.hartIsReset
        entry.trigger := Mux(
          enq.trigger.traceOn && (i == 0).B,
          1.U,
          Mux(
            enq.trigger.traceOff && i.U === enqCount - 1.U,
            2.U,
            Mux(!metadataOnly && enq.blocks(i).bits.trigNotify, 3.U, 0.U)
          )
        )
        entry.traceOffAfter   := enq.trigger.traceOn && enq.trigger.traceOff && enqCount === 1.U
        entry.notifyRequested := !metadataOnly && enq.blocks(i).bits.trigNotify
        entry.traceLost       := false.B
        entry.valid           := true.B
        entry.iaddr           := Mux(metadataOnly, 0.U, enq.blocks(i).bits.iaddr)
        entry.itype           := Mux(metadataOnly, Itype.None, enq.blocks(i).bits.itype)
        entry.iretire         := Mux(metadataOnly, 0.U, enq.blocks(i).bits.iretire)
        entry.ilastsize       := Mux(metadataOnly, Ilastsize.HalfWord, enq.blocks(i).bits.ilastsize)
        entry
      }
    }
  }

  // dequeue
  io.deqEntry       := Mux(fifoIsEmpty, 0.U.asTypeOf(new BlockBufferEntry), fifo(deqPtr.value))
  io.bufferIsFull   := lossPending || freeEntries < stallReserve.U
  io.droppedEntries := Mux(dropped, enqCount, 0.U)
  io.overflow       := dropped
  io.empty          := fifoIsEmpty && !lossPending
}

class BlockPtr(entries: Int) extends CircularQueuePtr[BlockPtr](entries) {}

object BlockPtr {
  def apply(entries: Int, f: Bool, v: UInt): BlockPtr = {
    val ptr = Wire(new BlockPtr(entries))
    ptr.flag  := f
    ptr.value := v
    ptr
  }
}
