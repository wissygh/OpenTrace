package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

class TraceAlignmentSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "periodic encapsulation alignment"
  it should "count accepted normal bytes once and retain one request until its sequence is serviced" in {
    test(new TraceAlignmentTimer) { d =>
      d.io.enable.poke(false.B); d.io.frequency.poke(0.U); d.io.bytes.poke(0.U); d.io.serviced.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (frequency <- 1 to 7) {
        d.io.frequency.poke(frequency.U); d.io.enable.poke(true.B); d.io.bytes.poke(0.U); d.clock.step()
        val limit = 1 << (frequency + 7)
        var total = 0
        var iteration = 0
        while (total < limit) {
          val bytes = Seq(0, 1, 4, 17, 33)(iteration % 5)
          d.io.bytes.poke(bytes.U)
          d.io.request.expect((bytes != 0 && total + bytes >= limit).B)
          d.clock.step(); total += bytes; iteration += 1
        }
        d.io.pending.expect(true.B)
        d.io.bytes.poke(33.U)
        for (_ <- 0 until 7) { d.io.request.expect(false.B); d.clock.step() }
        d.io.serviced.poke(true.B); d.clock.step(); d.io.serviced.poke(false.B)
        d.io.bytes.poke(0.U); d.io.pending.expect(false.B)
        d.io.enable.poke(false.B); d.clock.step()
        println(s"[alignment-timer] AsyncFreq=$frequency：$limit accepted bytes，单请求保持/服务后清除通过")
      }
      d.io.frequency.poke(0.U); d.io.enable.poke(true.B); d.io.bytes.poke(33.U)
      for (_ <- 0 until 10) { d.io.request.expect(false.B); d.clock.step() }
      d.io.pending.expect(false.B)
    }
  }

  it should "insert ATB alignment after the entire packet that crosses the threshold and drain a flush" in {
    test(new TraceATBAdapter) { d =>
      d.io.enable.poke(true.B); d.io.asyncFreq.poke(1.U); d.io.id.poke(0x20.U)
      d.io.atbm.atclken.poke(true.B); d.io.atbm.at.ready.poke(true.B)
      d.io.atbm.af.valid.poke(false.B); d.io.atbm.syncReq.poke(false.B)
      d.io.upstream.flushAck.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(9.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(2)
      val captured = ArrayBuffer.empty[Int]
      val tags = ArrayBuffer.empty[Int]
      var syncs = 0
      var held = Option.empty[(BigInt, BigInt, BigInt)]
      def tick(): Unit = {
        val valid = d.io.atbm.at.valid.peek().litToBoolean
        val ready = d.io.atbm.at.ready.peek().litToBoolean
        val bits = if (valid) Some((d.io.atbm.at.bits.data.peek().litValue,
          d.io.atbm.at.bits.bytes.peek().litValue, d.io.atbm.at.bits.id.peek().litValue)) else None
        held.foreach(h => bits shouldBe Some(h))
        held = if (valid && !ready) bits else None
        if (valid && ready) {
          val (data, count, id) = bits.get
          Set(BigInt(0x20), BigInt(0x21)) should contain(id)
          for (i <- 0 to count.toInt) { captured += ((data >> (8 * i)) & 255).toInt; tags += id.toInt }
        }
        if (d.io.upstream.syncReq.peek().litToBoolean) syncs += 1
        d.clock.step()
      }
      val packets = (0 until 30).map(i => Seq(7, i) ++ Seq.fill(7)(0x40 + i))
      for ((packet, index) <- packets.zipWithIndex) {
        packet.grouped(4).zipWithIndex.foreach { case (beat, word) =>
          d.io.in.valid.poke(true.B); d.io.in.bits.packetBytes.poke(packet.size.U)
          d.io.in.bits.data.poke(beat.zipWithIndex.map { case (b, lane) => BigInt(b) << (8 * lane) }.sum.U)
          d.io.in.bits.bytes.poke((beat.size - 1).U); d.io.in.bits.last.poke((word * 4 + beat.size == packet.size).B)
          if (index == 28 && word == 1) {
            d.io.id.poke(0x21.U) // change the live ID after the periodic sequence was requested
            d.io.in.valid.poke(false.B) // do not accidentally enqueue this beat during the pause
            d.io.atbm.at.ready.poke(false.B)
            for (_ <- 0 until 4) tick()
            d.io.atbm.at.ready.poke(true.B)
            d.io.in.valid.poke(true.B)
          }
          var timeout = 0
          while (!d.io.in.ready.peek().litToBoolean && timeout < 100) { tick(); timeout += 1 }
          assert(timeout < 100); tick()
        }
        d.io.in.valid.poke(false.B)
      }
      for (_ <- 0 until 15) tick()
      val expected = packets.take(29).flatten ++ Seq.fill(33)(0) ++ Seq(0x80) ++ packets.drop(29).flatten
      captured.toSeq shouldBe expected
      syncs shouldBe 1
      tags.toSeq shouldBe Seq.fill(29 * 9 + 34)(0x20) ++ Seq.fill(9)(0x21)
      println("[alignment-ATB] 256-byte阈值在9-byte包中途达到，完整尾包后才插33*00+80；反压保持通过")
      d.io.atbm.af.valid.poke(true.B)
      var cycles = 0
      while (!d.io.upstream.flushReq.peek().litToBoolean && cycles < 30) { tick(); cycles += 1 }
      assert(cycles < 30)
      d.io.upstream.flushAck.poke(true.B)
      cycles = 0
      while (!d.io.atbm.af.ready.peek().litToBoolean && cycles < 30) { tick(); cycles += 1 }
      assert(cycles < 30); tick()
      d.io.atbm.af.valid.poke(false.B); d.io.upstream.flushAck.poke(false.B)
      for (_ <- 0 until 8) tick()
      d.io.empty.expect(true.B)
      captured.toSeq shouldBe expected
      println("[alignment-ATB] 周期同步后AF握手完成，无重复自动请求拖延flush")
    }
  }

  it should "insert a complete PIB sequence between packets after enough accepted bytes" in {
    test(new PIBSerializer(startupAlignment = false)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.enable.poke(false.B); d.io.asyncFreq.poke(1.U); d.io.mode.poke(11.U)
      d.io.divider.poke(0.U); d.io.center.poke(false.B); d.io.calibrate.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.last.poke(false.B); d.io.in.bits.packetBytes.poke(33.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(2)
      var prior = d.io.pins.clock.peek().litToBoolean
      val samples = ArrayBuffer.empty[Int]
      var requests = 0
      def tick(): Unit = {
        if (d.io.syncReq.peek().litToBoolean) requests += 1
        d.clock.step()
        val clock = d.io.pins.clock.peek().litToBoolean
        if (clock != prior) samples += d.io.pins.data.peek().litValue.toInt
        prior = clock
      }
      d.io.enable.poke(true.B)
      for (packet <- 0 until 9) {
        for (beat <- 0 until 9) {
          d.io.in.valid.poke(true.B)
          val count = if (beat == 8) 1 else 4
          val byte = 0x20 + packet
          d.io.in.bits.data.poke((BigInt(byte) * BigInt("01010101", 16)).U)
          d.io.in.bits.bytes.poke((count - 1).U); d.io.in.bits.last.poke((beat == 8).B)
          var timeout = 0
          while (!d.io.in.ready.peek().litToBoolean && timeout < 150) { tick(); timeout += 1 }
          assert(timeout < 150); tick()
        }
        d.io.in.valid.poke(false.B)
      }
      for (_ <- 0 until 100) tick()
      val marker = samples.indices.find(i => samples(i) == 0x80 &&
        samples.take(i).reverse.takeWhile(_ == 0).size >= 33).getOrElse(-1)
      marker should be > 0
      samples.take(marker).filter(b => b != 0 && b != 0x80).toSeq shouldBe (0 until 8).flatMap(i => Seq.fill(33)(0x20 + i))
      samples.take(marker).reverse.takeWhile(_ == 0).size should be >= 33
      samples.drop(marker + 1).filter(b => b != 0 && b != 0x80).toSeq shouldBe Seq.fill(33)(0x28)
      samples.count(_ == 0x80) shouldBe 10 // nine packet prefixes plus the periodic sequence
      requests shouldBe 2 // enable request plus one periodic request
      d.io.empty.expect(true.B)
      println("[alignment-PIB] 8个33-byte包后跨256阈值，完整同步再发第9包，encoder同步事件准确")
    }
  }
}
