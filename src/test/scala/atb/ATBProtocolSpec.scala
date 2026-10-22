package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

class ATBProtocolSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ATB flush and synchronization"

  it should "acknowledge the old FIFO frontier while newer packets remain buffered" in {
    test(new Fifo(FifoParam(true, 16, 8, 4))) { d =>
      println("[ATB-test] FIFO flush：旧 packet 排空后应确认，后生成 packet 保留")
      d.io.control.flushReq.poke(false.B)
      d.io.control.syncReq.poke(false.B)
      d.io.atbm.ready.poke(false.B)
      d.io.packetEnq.valid.poke(false.B)
      d.io.packetEnq.bits.len.poke(6.U)
      d.io.packetEnq.bits.data.zipWithIndex.foreach { case (b, i) => b.poke((i + 1).U) }
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.packetEnq.valid.poke(true.B); d.clock.step()
      d.io.packetEnq.valid.poke(false.B)
      d.io.control.flushReq.poke(true.B); d.clock.step()
      d.io.packetEnq.bits.len.poke(5.U)
      d.io.packetEnq.valid.poke(true.B); d.clock.step()
      d.io.packetEnq.valid.poke(false.B)
      d.io.control.flushAck.expect(false.B)
      d.io.atbm.ready.poke(true.B)
      d.io.atbm.bits.bytes.expect(3.U); d.clock.step()
      d.io.control.flushAck.expect(false.B)
      d.io.atbm.bits.bytes.expect(1.U); d.clock.step()
      d.io.control.flushAck.expect(true.B)
      d.io.ctrl.fifoIsEmpty.expect(false.B)
      d.io.atbm.valid.expect(false.B)
      d.clock.step(3)
      d.io.control.flushAck.expect(true.B)
      d.io.control.flushReq.poke(false.B); d.clock.step()
      d.io.atbm.valid.expect(true.B)
      d.io.atbm.bits.packetBytes.expect(5.U)
    }
  }

  it should "include an offered packet blocked by a full FIFO in the flush frontier" in {
    test(new Fifo(FifoParam(true, 8, 8, 4))) { d =>
      d.io.control.flushReq.poke(false.B)
      d.io.control.syncReq.poke(false.B)
      d.io.atbm.ready.poke(false.B)
      d.io.packetEnq.valid.poke(false.B)
      d.io.packetEnq.bits.len.poke(8.U)
      d.io.packetEnq.bits.data.foreach(_.poke(0x55.U))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.packetEnq.valid.poke(true.B); d.clock.step()
      d.io.packetEnq.bits.len.poke(6.U)
      d.io.packetEnq.ready.expect(false.B)
      d.io.control.flushReq.poke(true.B); d.clock.step()
      d.io.atbm.ready.poke(true.B)
      var admitted = false
      val sizes = ArrayBuffer[Int]()
      for (_ <- 0 until 12) {
        if (d.io.atbm.valid.peek().litToBoolean) sizes += d.io.atbm.bits.bytes.peek().litValue.toInt + 1
        val accepted = !admitted && d.io.packetEnq.ready.peek().litToBoolean
        d.clock.step()
        if (accepted) { admitted = true; d.io.packetEnq.valid.poke(false.B) }
      }
      admitted shouldBe true
      sizes.toSeq shouldBe Seq(4, 4, 4, 2)
      d.io.control.flushAck.expect(true.B)
    }
  }

  private def initialize(d: TraceATBAdapter): Unit = {
    d.io.enable.poke(true.B)
    d.io.id.poke(0x20.U)
    d.io.atbm.atclken.poke(true.B)
    d.io.atbm.at.ready.poke(false.B)
    d.io.atbm.af.valid.poke(false.B)
    d.io.atbm.syncReq.poke(false.B)
    d.io.upstream.flushAck.poke(false.B)
    d.io.in.valid.poke(false.B)
    d.io.in.bits.data.poke(0.U)
    d.io.in.bits.bytes.poke(3.U)
    d.io.in.bits.packetBytes.poke(6.U)
    d.io.in.bits.last.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }

  it should "flush through disable and stall, honor ATCLKEN, and assert registered wakeup" in {
    test(new TraceATBAdapter) { d =>
      d.io.asyncFreq.poke(0.U)
      initialize(d)
      println("[ATB-test] Bridge：缓存两拍，禁用并发起 AF，检查 stall/ATCLKEN/ATWAKEUP")
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke("h04030201".U); d.clock.step()
      d.io.in.bits.data.poke("h0605".U)
      d.io.in.bits.bytes.poke(1.U)
      d.io.in.bits.last.poke(true.B); d.clock.step()
      d.io.in.valid.poke(false.B)
      d.io.enable.poke(false.B)
      d.io.atbm.wakeup.expect(true.B)
      d.io.atbm.af.valid.poke(true.B); d.clock.step()
      d.io.upstream.flushReq.expect(true.B)
      d.io.upstream.flushAck.poke(true.B)
      d.io.atbm.af.ready.expect(false.B)
      d.io.atbm.atclken.poke(false.B)
      d.io.atbm.at.ready.poke(true.B); d.clock.step(3)
      d.io.atbm.at.bits.data.expect("h04030201".U)
      d.io.atbm.at.valid.expect(true.B)
      d.io.atbm.wakeup.expect(true.B)
      d.io.atbm.af.ready.expect(false.B)
      d.io.atbm.atclken.poke(true.B); d.clock.step()
      d.io.atbm.af.ready.expect(false.B)
      d.io.atbm.at.bits.data.expect("h0605".U); d.clock.step()
      d.io.atbm.af.ready.expect(true.B)
      d.clock.step()
      d.io.atbm.af.valid.poke(false.B)
      d.io.upstream.flushAck.poke(false.B); d.clock.step(2)
      d.io.empty.expect(true.B)
      d.io.atbm.af.ready.expect(true.B) // inactive, empty transmitter clamp
      d.io.atbm.wakeup.expect(false.B)
    }
  }

  it should "insert synchronization bytes after the current packet without splitting its payload" in {
    test(new TraceATBAdapter) { d =>
      d.io.asyncFreq.poke(0.U)
      initialize(d)
      val bytes = ArrayBuffer[Int]()
      def tick(): Unit = {
        if (d.io.atbm.at.valid.peek().litToBoolean && d.io.atbm.at.ready.peek().litToBoolean) {
          val data = d.io.atbm.at.bits.data.peek().litValue
          for (i <- 0 to d.io.atbm.at.bits.bytes.peek().litValue.toInt)
            bytes += ((data >> (8 * i)) & 255).toInt
        }
        d.clock.step()
      }
      d.io.atbm.at.ready.poke(true.B)
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke("h04030201".U); tick()
      d.io.in.valid.poke(false.B)
      d.io.atbm.syncReq.poke(true.B)
      d.io.upstream.syncReq.expect(true.B); tick()
      d.io.atbm.syncReq.poke(false.B)
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke("h0605".U)
      d.io.in.bits.bytes.poke(1.U)
      d.io.in.bits.last.poke(true.B); tick()
      d.io.in.valid.poke(false.B)
      for (_ <- 0 until 15) tick()
      bytes.toSeq shouldBe Seq(1, 2, 3, 4, 5, 6) ++ Seq.fill(33)(0) ++ Seq(0x80)
      d.io.empty.expect(true.B)
    }
  }

  it should "clamp an empty disabled transmitter and hold new packets until reenabled" in {
    test(new TraceATBAdapter) { d =>
      d.io.asyncFreq.poke(0.U); initialize(d)
      d.io.enable.poke(false.B); d.clock.step(3)
      d.io.atbm.af.ready.expect(true.B); d.io.upstream.flushReq.expect(false.B)
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke(0x1234.U)
      d.io.in.bits.bytes.poke(1.U); d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(2.U)
      d.io.atbm.at.ready.poke(true.B); d.io.atbm.af.valid.poke(true.B)
      for (_ <- 0 until 5) {
        d.io.in.ready.expect(false.B); d.io.atbm.at.valid.expect(false.B)
        d.io.atbm.af.ready.expect(true.B); d.io.upstream.flushReq.expect(false.B); d.clock.step()
      }
      d.io.atbm.af.valid.poke(false.B); d.clock.step(2)
      d.io.enable.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
      d.io.in.valid.poke(false.B)
      d.io.atbm.at.valid.expect(true.B); d.io.atbm.at.bits.data.expect(0x1234.U)
      d.io.atbm.at.bits.bytes.expect(1.U); d.io.atbm.at.bits.id.expect(0x20.U)
      d.clock.step(); d.io.atbm.at.valid.expect(false.B); d.io.empty.expect(true.B)
      println("[ATB-disable] 禁用空transmitter按IHI0032C确认AF，不接新packet；重新Enable只传一次")
    }
  }
}
