package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

class PIBSerializerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "PIB packet serializer"
  private val packet = Seq(0xa5, 0x31, 0xf7)
  private def parallelSymbols(bytes: Seq[Int], width: Int): Seq[Int] = {
    val bits = bytes.flatMap(b => (0 until 8).map(bit => (b >> bit) & 1))
    bits.grouped(width).map(group => group.zipWithIndex.map { case (bit, i) => bit << i }.sum).toSeq
  }
  private def setup(d: PIBSerializer, mode: Int, center: Boolean = false, divider: Int = 1): Unit = {
    d.io.asyncFreq.poke(0.U)
    d.io.enable.poke(false.B); d.io.mode.poke(mode.U); d.io.center.poke(center.B)
    d.io.divider.poke(divider.U); d.io.calibrate.poke(false.B)
    d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(0.U)
    d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(1.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(2)
  }
  private def send(d: PIBSerializer, bytes: Seq[Int], tick: () => Unit): Unit = {
    bytes.grouped(4).zipWithIndex.foreach { case (beat, index) =>
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke(beat.zipWithIndex.map { case (byte, lane) => BigInt(byte) << (8 * lane) }.sum.U)
      d.io.in.bits.bytes.poke((beat.size - 1).U)
      d.io.in.bits.last.poke((4 * index + beat.size == bytes.size).B)
      d.io.in.bits.packetBytes.poke(bytes.size.U)
      d.io.in.ready.expect(true.B); tick()
    }
    d.io.in.valid.poke(false.B)
  }

  it should "transmit LSB-first parallel data at the normal and centered clock edges" in {
    test(new PIBSerializer(startupAlignment = false)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      for (mode <- 8 to 12; center <- Seq(false, true)) {
        setup(d, mode, center)
        val width = 1 << (mode - 8)
        val symbols = ArrayBuffer.empty[Int]
        var before = d.io.pins.clock.peek().litToBoolean
        var cycles = 0
        var lastEdge = Option.empty[Int]
        def tick(): Unit = {
          d.clock.step(); cycles += 1
          val current = d.io.pins.clock.peek().litToBoolean
          if (current != before) {
            lastEdge.foreach(c => cycles - c shouldBe 2 * (if (center) 2 else 1))
            lastEdge = Some(cycles)
            symbols += d.io.pins.data.peek().litValue.toInt
          }
          before = current
          d.io.pins.control.expect(false.B)
        }
        for (_ <- 0 until 12) tick() // clock runs with PIB disabled; data is quiescent
        symbols.foreach(_ shouldBe 0)
        d.io.enable.poke(true.B); send(d, packet, () => tick())
        for (_ <- 0 until 160) tick()
        val actual = symbols.dropWhile(_ == 0)
        val expected = parallelSymbols(packet, width)
        actual.take(expected.size).toSeq shouldBe expected
        actual.drop(expected.size).foreach(_ shouldBe 0)
        d.io.empty.expect(true.B)
        println(s"[PIB-parallel] width=$width center=$center：采样两侧clock边沿，LSB序/奇数尾byte/padding/idle全部通过")
      }
      setup(d, 11)
      d.io.enable.poke(true.B)
      val bytes = (1 to 7)
      val symbols = ArrayBuffer.empty[Int]
      var priorClock = d.io.pins.clock.peek().litToBoolean
      def tick(): Unit = {
        d.clock.step()
        val current = d.io.pins.clock.peek().litToBoolean
        if (current != priorClock) symbols += d.io.pins.data.peek().litValue.toInt
        priorClock = current
      }
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h04030201".U)
      d.io.in.bits.bytes.poke(3.U); d.io.in.bits.last.poke(false.B); d.io.in.bits.packetBytes.poke(7.U)
      tick(); d.io.in.valid.poke(false.B); d.io.empty.expect(false.B)
      d.io.enable.poke(false.B)
      for (_ <- 0 until 8) tick()
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h070605".U)
      d.io.in.bits.bytes.poke(2.U); d.io.in.bits.last.poke(true.B); d.io.in.ready.expect(true.B)
      tick(); d.io.in.valid.poke(false.B)
      for (_ <- 0 until 40) tick()
      symbols.dropWhile(_ == 0).take(7).toSeq shouldBe bytes
      d.io.empty.expect(true.B); d.io.in.ready.expect(false.B)
      println("[PIB-parallel] Disable完成已接纳的整个packet，未在partial collection或最后symbol提前Empty")
    }
  }

  it should "frame UART bytes and Manchester packets without inserting idle inside a packet" in {
    test(new PIBSerializer(startupAlignment = false)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      for (mode <- Seq(4, 5)) {
        val divider = 2
        setup(d, mode, divider = divider)
        val samples = ArrayBuffer.empty[Int]
        var started = false
        var cycle = 0
        var firstCycle = 0
        val idle = if (mode == 5) 1 else 0
        def tick(): Unit = {
          d.clock.step(); cycle += 1
          val value = d.io.pins.data.peek().litValue.toInt
          d.io.pins.clock.expect(false.B)
          if (!started && value != idle) { started = true; firstCycle = cycle }
          if (started && (cycle - firstCycle) % (divider + 1) == 0) samples += value
        }
        d.io.pins.data.expect(idle.U)
        d.io.enable.poke(true.B)
        send(d, packet, () => tick())
        for (_ <- 0 until 240) tick()
        val bits = packet.flatMap(byte => (0 until 8).map(bit => (byte >> bit) & 1))
        val expected = if (mode == 5) packet.flatMap(byte =>
          Seq(0) ++ (0 until 8).map(bit => (byte >> bit) & 1) ++ Seq(1))
        else Seq(1, 0) ++ bits.flatMap(bit => Seq(bit, 1 - bit)) ++ Seq(0, 0)
        samples.take(expected.size).toSeq shouldBe expected
        samples.drop(expected.size).foreach(_ shouldBe idle)
        d.io.empty.expect(true.B)
        println(s"[PIB-serial] mode=$mode：${expected.size} 个bit/half-phase匹配，整包连续且尾部idle正确")
      }
    }
  }

  it should "repeat the normative calibration patterns without consuming input" in {
    test(new PIBSerializer(startupAlignment = false)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val patterns = Map(8 -> Seq(0xaa, 0x55, 0, 0xff), 9 -> Seq(0x66, 0x66, 0xcc, 0x33),
        10 -> Seq(0x5a, 0x5a, 0xf0, 0x0f), 11 -> Seq(0xaa, 0x55, 0, 0xff),
        12 -> Seq(0xaa, 0xaa, 0x55, 0x55, 0, 0, 0xff, 0xff))
      for (mode <- 8 to 12) {
        setup(d, mode)
        val result = ArrayBuffer.empty[Int]
        var before = d.io.pins.clock.peek().litToBoolean
        d.io.calibrate.poke(true.B); d.io.enable.poke(true.B)
        for (_ <- 0 until 180) {
          d.io.in.ready.expect(false.B); d.clock.step()
          val current = d.io.pins.clock.peek().litToBoolean
          if (current != before) result += d.io.pins.data.peek().litValue.toInt
          before = current
        }
        // In 1-bit calibration the first bit of 0xaa is itself zero,
        // indistinguishable from preceding quiescent zeros; restore that bit.
        val actual = (if (mode == 8) Seq(0) else Seq.empty) ++ result.dropWhile(_ == 0)
        val expected = parallelSymbols(patterns(mode), 1 << (mode - 8))
        actual.take(expected.size * 2).toSeq shouldBe expected ++ expected
        d.io.enable.poke(false.B); d.clock.step(5); d.io.empty.expect(true.B)
        println(s"[PIB-calibration] mode=$mode：连续两轮规范pattern，无输入消费")
      }
    }
  }

  it should "insert encapsulation alignment before accepting the first real packet" in {
    test(new PIBSerializer).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      setup(d, 11, divider = 1)
      val symbols = ArrayBuffer.empty[Int]
      var previous = d.io.pins.clock.peek().litToBoolean
      def tick(): Unit = {
        d.clock.step()
        val now = d.io.pins.clock.peek().litToBoolean
        if (now != previous) symbols += d.io.pins.data.peek().litValue.toInt
        previous = now
      }
      d.io.enable.poke(true.B); d.io.syncReq.expect(true.B)
      d.io.in.ready.expect(false.B)
      var waiting = 0
      while (!d.io.in.ready.peek().litToBoolean && waiting < 150) { tick(); waiting += 1 }
      assert(waiting < 150)
      send(d, packet, () => tick())
      for (_ <- 0 until 20) tick()
      val alignment = symbols.indexOf(0x80)
      alignment should be >= 33
      symbols.take(alignment).foreach(_ shouldBe 0)
      symbols.drop(alignment + 1).dropWhile(_ == 0).take(3).toSeq shouldBe packet
      d.io.empty.expect(true.B)
      println("[PIB-alignment] 至少33个null.idle和null.alignment先于完整packet，启动sync请求通过")
    }
  }

}
