package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

private class SourceLinkFlushHarness extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new PacketEnq(8)))
    val out = Decoupled(new TraceBeat(4))
    val flush = Input(Bool())
    val done = Output(Bool())
    val sourceDone = Output(Bool())
  })
  val fifo = Module(new Fifo(FifoParam(true, 32, 8, 4)))
  val link = Module(new TraceSourceLink(7))
  fifo.io.packetEnq <> io.in
  link.io.sourceClock := clock; link.io.sinkClock := clock
  link.io.sourceReset := reset.asAsyncReset; link.io.sinkReset := reset.asAsyncReset
  link.io.systemSinkReset := reset.asAsyncReset
  link.io.in <> fifo.io.atbm; link.io.sourceControl <> fifo.io.control
  link.io.control.flushReq := io.flush; link.io.control.syncReq := false.B
  io.out <> link.io.out
  io.done := link.io.control.flushAck; io.sourceDone := fifo.io.control.flushAck
}

class SourcePacketStagerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "source epoch packet staging"
  it should "retain old complete packets and publish source-specific loss with the preceding options" in {
    test(new SourcePacketStager(7)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(0.U)
      d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(1.U)
      d.io.epochReset.poke(false.B); d.io.out.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(2)
      val bytes = ArrayBuffer.empty[Int]
      var held = Option.empty[(BigInt, BigInt, BigInt, Boolean)]
      def tick(): Unit = {
        val valid = d.io.out.valid.peek().litToBoolean
        val ready = d.io.out.ready.peek().litToBoolean
        val beat = if (valid) Some((d.io.out.bits.data.peek().litValue, d.io.out.bits.bytes.peek().litValue,
          d.io.out.bits.packetBytes.peek().litValue, d.io.out.bits.last.peek().litToBoolean)) else None
        held.foreach(before => beat shouldBe Some(before))
        held = if (valid && !ready) beat else None
        if (valid && ready) {
          val (data, count, _, _) = beat.get
          for (i <- 0 to count.toInt) bytes += ((data >> (8 * i)) & 255).toInt
        }
        d.clock.step()
      }
      def send(packet: Seq[Int]): Unit = {
        for ((beat, index) <- packet.grouped(4).zipWithIndex) {
          d.io.in.valid.poke(true.B); d.io.in.bits.packetBytes.poke(packet.size.U)
          d.io.in.bits.bytes.poke((beat.size - 1).U)
          d.io.in.bits.last.poke((index * 4 + beat.size == packet.size).B)
          d.io.in.bits.data.poke(beat.zipWithIndex.map { case (b, lane) => BigInt(b) << (lane * 8) }.sum.U)
          var timeout = 0
          while (!d.io.in.ready.peek().litToBoolean && timeout < 80) { tick(); timeout += 1 }
          assert(timeout < 80); tick()
        }
        d.io.in.valid.poke(false.B)
      }
      val support = ETraceReference.support(7, fullAddress = true)
      send(support); for (_ <- 0 until 3) tick(); bytes.toSeq shouldBe support
      bytes.clear()
      val old = ETraceReference.synchronization(7, 0x1000)
      send(old); d.io.out.ready.poke(false.B); tick(); tick()
      d.io.epochReset.poke(true.B); tick(); tick()
      d.io.out.ready.poke(true.B)
      for (_ <- 0 until 2) tick()
      d.io.epochReset.poke(false.B); d.io.syncReq.expect(true.B); tick()
      for (_ <- 0 until 15) tick()
      bytes.toSeq shouldBe old ++ ETraceReference.support(7, fullAddress = true, status = 2)
      d.io.empty.expect(true.B)
      val fresh = ETraceReference.support(7) ++ ETraceReference.synchronization(7, 0x2000)
      send(ETraceReference.support(7)); send(ETraceReference.synchronization(7, 0x2000))
      for (_ <- 0 until 10) tick()
      bytes.toSeq shouldBe old ++ ETraceReference.support(7, fullAddress = true, status = 2) ++ fresh
      println("[source-reset] 完整旧包在源reset/反压中保持，之后带同srcID/FullAddr的trace_lost，再新support/sync")
      send(ETraceReference.support(7, status = 1, tracing = false))
      for (_ <- 0 until 5) tick()
      bytes.clear()
      d.io.epochReset.poke(true.B); tick(); tick()
      d.io.epochReset.poke(false.B); tick()
      for (_ <- 0 until 5) tick()
      bytes shouldBe empty
      d.io.empty.expect(true.B)
      println("[source-reset] 已有序停止且无partial input时reset不伪报trace_lost")

    }
  }

  it should "discard interrupted and length-inconsistent tails before offering any packet bytes" in {
    test(new SourcePacketStager(3)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0x11111111L.U)
      d.io.in.bits.bytes.poke(3.U); d.io.in.bits.packetBytes.poke(9.U); d.io.in.bits.last.poke(false.B)
      d.io.epochReset.poke(false.B); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.clock.step()
      d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.io.out.valid.expect(false.B)
      d.io.epochReset.poke(true.B); d.clock.step(); d.io.epochReset.poke(false.B)
      d.io.out.valid.expect(true.B); d.io.out.bits.data.expect("h008f0302".U)
      d.io.out.bits.packetBytes.expect(4.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.valid.expect(false.B)
      // The old FIFO may resume at a tail after a link/Funnel queue reset.
      d.io.in.valid.poke(true.B); d.io.in.bits.bytes.poke(3.U); d.clock.step()
      d.io.in.bits.bytes.poke(0.U); d.io.in.bits.last.poke(true.B)
      d.io.syncReq.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.io.out.valid.expect(true.B); d.io.out.bits.data.expect("h008f0302".U)
      d.clock.step(); d.io.empty.expect(true.B)
      d.io.in.valid.poke(true.B); d.io.in.bits.packetBytes.poke(1.U)
      d.io.in.bits.data.poke(0x55.U); d.clock.step(); d.io.in.valid.poke(false.B)
      d.io.out.valid.expect(true.B); d.io.out.bits.data.expect(0x55.U)
      d.io.out.bits.bytes.expect(0.U); d.io.out.bits.last.expect(true.B); d.clock.step()
      d.io.empty.expect(true.B)
      println("[source-reset] partial epoch和残余tail均先显式报loss，不向Funnel提供半包；新整包独立")
    }
  }

  it should "delay the actual source link flush acknowledgement until its staged old packet drains" in {
    test(new SourceLinkFlushHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.len.poke(6.U)
      d.io.in.bits.data.zipWithIndex.foreach { case (b, i) => b.poke((i + 1).U) }
      d.io.out.ready.poke(false.B); d.io.flush.poke(false.B)
      d.reset.poke(true.B); d.clock.step(4); d.reset.poke(false.B); d.clock.step(60)
      d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.clock.step(30); d.io.out.valid.expect(true.B)
      d.io.flush.poke(true.B); d.clock.step(40)
      d.io.sourceDone.expect(true.B)
      d.io.done.expect(false.B) // marker passed the async queue; staging still holds old data
      d.io.out.ready.poke(true.B); d.io.out.bits.data.expect("h04030201".U); d.clock.step()
      d.io.done.expect(false.B); d.io.out.bits.data.expect("h0605".U); d.clock.step()
      d.io.done.expect(true.B)
      d.io.flush.poke(false.B); d.clock.step(30); d.io.done.expect(false.B)
      println("[source-reset-flush] 真实FIFO/CDC/staging：旧packet尾beat被接收后才确认flush，不提前屏蔽该源")
    }
  }
}
