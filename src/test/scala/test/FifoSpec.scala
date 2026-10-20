package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FifoSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Fifo"

  it should "preserve complete packets through non-power-of-two wrap and stalls" in {
    test(new Fifo(FifoParam(true, 13, 8, 4))) { dut =>
      val random = new scala.util.Random(91)
      val packets = Vector.fill(80)(Vector.fill(1 + random.nextInt(8))(random.nextInt(256)))
      val pending = scala.collection.mutable.ArrayDeque[Vector[Int]]()
      var sent = 0
      var cycles = 0
      var stalled = Option.empty[(BigInt, BigInt, Boolean)]
      dut.io.packetEnq.valid.poke(false.B)
      dut.io.packetEnq.bits.len.poke(1.U)
      dut.io.packetEnq.bits.data.foreach(_.poke(0.U))
      dut.io.atbm.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      while (sent < packets.size || pending.nonEmpty) {
        assert(cycles < 2000)
        cycles += 1
        dut.io.atbm.ready.poke((random.nextInt(4) != 0).B)
        dut.io.packetEnq.valid.poke((sent < packets.size).B)
        if (sent < packets.size) {
          val packet = packets(sent)
          dut.io.packetEnq.bits.len.poke(packet.size.U)
          dut.io.packetEnq.bits.data.zipWithIndex.foreach { case (byte, i) =>
            byte.poke(packet.lift(i).getOrElse(0xff).U)
          }
        }
        val valid = dut.io.atbm.valid.peek().litToBoolean
        val ready = dut.io.atbm.ready.peek().litToBoolean
        val observed = (dut.io.atbm.bits.data.peek().litValue,
          dut.io.atbm.bits.bytes.peek().litValue, dut.io.atbm.bits.last.peek().litToBoolean)
        stalled.foreach { previous =>
          valid shouldBe true
          observed shouldBe previous
        }
        stalled = if (valid && !ready) Some(observed) else None
        if (valid && ready) {
          pending.nonEmpty shouldBe true
          val remaining = pending.removeHead()
          val count = math.min(4, remaining.size)
          observed._2.toInt shouldBe count - 1
          observed._3 shouldBe (remaining.size <= 4)
          (0 until count).foreach(i => ((observed._1 >> (8 * i)) & 255).toInt shouldBe remaining(i))
          if (remaining.size > count) pending.prepend(remaining.drop(count))
        }
        if (sent < packets.size && dut.io.packetEnq.ready.peek().litToBoolean) {
          pending.append(packets(sent))
          sent += 1
        }
        dut.clock.step()
      }
      dut.io.packetEnq.valid.poke(false.B)
      dut.io.atbm.valid.expect(false.B)
    }
  }

  it should "start each back-to-back packet at the packet boundary" in {
    test(new Fifo(FifoParam(isLeaf = true, fifoDepth = 32, enqDataBytes = 8, deqDataBytes = 4))) { dut =>
      dut.io.atbm.ready.poke(false.B)
      dut.io.packetEnq.valid.poke(false.B)
      dut.io.packetEnq.bits.len.poke(0.U)
      dut.io.packetEnq.bits.data.foreach(_.poke(0.U))

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def sendPacket(payload: Seq[Int]): Unit = {
        val packetLength = payload.length + 2
        require(packetLength <= 8)
        dut.io.packetEnq.bits.data.foreach(_.poke(0.U))
        dut.io.packetEnq.bits.data(0).poke(payload.length.U)
        dut.io.packetEnq.bits.data(1).poke(0x12.U)
        payload.zipWithIndex.foreach { case (value, index) =>
          dut.io.packetEnq.bits.data(index + 2).poke(value.U)
        }
        dut.io.packetEnq.bits.len.poke(packetLength.U)
        dut.io.packetEnq.valid.poke(true.B)
        while (!dut.io.packetEnq.ready.peek().litToBoolean) {
          dut.clock.step()
        }
        dut.clock.step()
        dut.io.packetEnq.valid.poke(false.B)
      }

      // A 6-byte packet occupies two 4-byte ATB beats. Sending a second
      // packet without an idle ATB cycle exercises the packet-start state.
      sendPacket(Seq(0xa0, 0xa1, 0xa2, 0xa3))
      sendPacket(Seq(0xb0, 0xb1, 0xb2, 0xb3))

      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.ready.poke(true.B)
      dut.io.atbm.bits.bytes.expect(3.U) // first beat of packet A
      dut.clock.step()

      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.bits.bytes.expect(1.U) // final beat of packet A
      dut.clock.step()

      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.bits.bytes.expect(3.U) // first beat of packet B
      dut.clock.step()

      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.bits.bytes.expect(1.U) // final beat of packet B
      dut.clock.step()

      dut.io.atbm.valid.expect(false.B)
    }
  }

  it should "hold a short packet byte count while ATB is stalled" in {
    test(new Fifo(FifoParam(isLeaf = true, fifoDepth = 16, enqDataBytes = 8, deqDataBytes = 4))) { dut =>
      dut.io.atbm.ready.poke(false.B)
      dut.io.packetEnq.valid.poke(false.B)
      dut.io.packetEnq.bits.len.poke(0.U)
      dut.io.packetEnq.bits.data.foreach(_.poke(0.U))

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.packetEnq.bits.data(0).poke(1.U) // one payload byte
      dut.io.packetEnq.bits.data(1).poke(0x12.U)
      dut.io.packetEnq.bits.data(2).poke(0xa0.U)
      dut.io.packetEnq.bits.len.poke(3.U)
      dut.io.packetEnq.valid.poke(true.B)
      dut.clock.step()
      dut.io.packetEnq.valid.poke(false.B)

      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.bits.bytes.expect(2.U) // 3-byte packet, valid bytes = 3
      dut.clock.step(2)
      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.bits.bytes.expect(2.U)

      dut.io.atbm.ready.poke(true.B)
      dut.io.atbm.bits.bytes.expect(2.U)
      dut.clock.step()
      dut.io.atbm.valid.expect(false.B)
    }
  }

  it should "use enqueue metadata for a one-byte packet" in {
    test(new Fifo(FifoParam(isLeaf = true, fifoDepth = 16, enqDataBytes = 8, deqDataBytes = 4))) { dut =>
      dut.io.atbm.ready.poke(false.B)
      dut.io.packetEnq.valid.poke(false.B)
      dut.io.packetEnq.bits.len.poke(0.U)
      dut.io.packetEnq.bits.data.foreach(_.poke(0.U))

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // The byte is intentionally zero. A header parser would treat it as a
      // null packet of length zero; the metadata queue still transmits it as
      // the one-byte packet accepted at enqueue.
      dut.io.packetEnq.bits.data(0).poke(0.U)
      dut.io.packetEnq.bits.len.poke(1.U)
      dut.io.packetEnq.valid.poke(true.B)
      dut.clock.step()
      dut.io.packetEnq.valid.poke(false.B)

      dut.io.atbm.valid.expect(true.B)
      dut.io.atbm.bits.bytes.expect(0.U)
      dut.io.atbm.bits.last.expect(true.B)
      dut.io.atbm.ready.poke(true.B)
      dut.clock.step()
      dut.io.atbm.valid.expect(false.B)
    }
  }

  it should "backpressure a packet that does not fit in the remaining byte space" in {
    test(new Fifo(FifoParam(isLeaf = true, fifoDepth = 8, enqDataBytes = 8, deqDataBytes = 4))) { dut =>
      dut.io.atbm.ready.poke(false.B)
      dut.io.packetEnq.valid.poke(false.B)
      dut.io.packetEnq.bits.data.foreach(_.poke(0.U))

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.packetEnq.bits.len.poke(6.U)
      dut.io.packetEnq.valid.poke(true.B)
      dut.io.packetEnq.ready.expect(true.B)
      dut.clock.step()

      dut.io.packetEnq.bits.len.poke(3.U)
      dut.io.packetEnq.ready.expect(false.B) // only two byte slots remain
      dut.io.packetEnq.valid.poke(false.B)
    }
  }
}
