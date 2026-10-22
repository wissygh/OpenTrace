package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SRAMSinkCoreSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "SRAMSinkCore"

  private def initialize(d: SRAMSinkCore, stop: Boolean): Unit = {
    d.io.enable.poke(true.B)
    d.io.stopOnWrap.poke(stop.B)
    d.io.in.valid.poke(false.B)
    d.io.in.bits.data.poke(0.U)
    d.io.in.bits.bytes.poke(0.U)
    d.io.in.bits.last.poke(true.B)
    d.io.in.bits.packetBytes.poke(1.U)
    d.io.writePointer.valid.poke(false.B)
    d.io.writePointer.bits.poke(0.U)
    d.io.readPointer.valid.poke(false.B)
    d.io.readPointer.bits.poke(0.U)
    d.io.read.poke(false.B)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }

  private def send(d: SRAMSinkCore, packet: Seq[Int]): Unit = {
    packet.grouped(4).zipWithIndex.foreach { case (bytes, i) =>
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke(bytes.zipWithIndex.map { case (b, n) => BigInt(b) << (n * 8) }.sum.U)
      d.io.in.bits.bytes.poke((bytes.size - 1).U)
      d.io.in.bits.last.poke((i * 4 + bytes.size == packet.size).B)
      d.io.in.bits.packetBytes.poke(packet.size.U)
      var timeout = 0
      while (!d.io.in.ready.peek().litToBoolean) { d.clock.step(); timeout += 1; assert(timeout < 40) }
      d.clock.step()
    }
    d.io.in.valid.poke(false.B)
  }

  private def word(d: SRAMSinkCore, index: Int): BigInt = {
    d.io.readPointer.valid.poke(true.B)
    d.io.readPointer.bits.poke(index.U)
    d.clock.step()
    d.io.readPointer.valid.poke(false.B)
    d.io.readData.peek().litValue
  }

  it should "pack short packets across words and pad only the final drain" in {
    test(new SRAMSinkCore(16)) { d =>
      initialize(d, false)
      send(d, Seq(4, 0, 0x10, 0x11, 0x12, 0x13))
      send(d, Seq(3, 1, 0x20, 0x21, 0x22))
      d.clock.step(3)
      d.io.empty.expect(false.B)
      d.io.enable.poke(false.B)
      d.clock.step(4)
      d.io.empty.expect(true.B)
      d.io.wp.expect(3.U)
      word(d, 0) shouldBe BigInt("11100004", 16)
      word(d, 1) shouldBe BigInt("01031312", 16)
      word(d, 2) shouldBe BigInt("00222120", 16)
    }
  }

  it should "reject a whole packet that cannot fit before StopOnWrap" in {
    test(new SRAMSinkCore(3)) { d =>
      initialize(d, true)
      send(d, Seq(1, 2, 3, 4, 5, 6, 7))
      d.io.in.valid.poke(true.B)
      d.io.in.bits.packetBytes.poke(7.U)
      d.io.in.ready.expect(false.B)
      d.clock.step(5)
      d.io.stop.expect(true.B)
      d.io.empty.expect(true.B)
      d.io.wp.expect(2.U)
      d.io.in.ready.expect(false.B)
      word(d, 0) shouldBe BigInt("04030201", 16)
      word(d, 1) shouldBe BigInt("00070605", 16)
    }
  }

  it should "finish an admitted packet when enable is cleared mid-packet" in {
    test(new SRAMSinkCore(8)) { d =>
      initialize(d, false)
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke("h04030201".U)
      d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.last.poke(false.B)
      d.io.in.bits.packetBytes.poke(6.U)
      d.clock.step()
      d.io.enable.poke(false.B)
      d.io.in.bits.data.poke("h0605".U)
      d.io.in.bits.bytes.poke(1.U)
      d.io.in.bits.last.poke(true.B)
      d.io.in.ready.expect(true.B)
      d.clock.step()
      d.io.in.valid.poke(false.B)
      d.clock.step(4)
      d.io.empty.expect(true.B)
      word(d, 1) shouldBe BigInt("00000605", 16)
    }
  }

  it should "stop after an exact fit and wrap normally when StopOnWrap is off" in {
    test(new SRAMSinkCore(3)) { d =>
      initialize(d, true)
      send(d, (1 to 12))
      d.clock.step(4)
      d.io.stop.expect(true.B)
      d.io.wp.expect(0.U)
      word(d, 2) shouldBe BigInt("0c0b0a09", 16)
      d.io.enable.poke(false.B)
      d.clock.step()
      d.io.stopOnWrap.poke(false.B)
      d.io.enable.poke(true.B)
      send(d, Seq(13, 14, 15, 16))
      d.clock.step(3)
      d.io.wp.expect(1.U)
      word(d, 0) shouldBe BigInt("100f0e0d", 16)
    }
  }

  it should "block new packets while disabled drain only an admitted tail and resume the held next packet once" in {
    test(new SRAMSinkCore(8)) { d =>
      initialize(d, false)
      d.io.enable.poke(false.B)
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h04030201".U)
      d.io.in.bits.bytes.poke(3.U); d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(4.U)
      for (_ <- 0 until 8) {
        d.io.in.ready.expect(false.B); d.io.empty.expect(true.B); d.io.wp.expect(0.U)
        d.clock.step()
      }
      println("[SRAM-disable][1/3] disabled空闲即使valid持续为1也不接新packet、不写SRAM、不推进WP")
      d.io.enable.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
      d.io.in.bits.data.poke("h08070605".U); d.io.in.bits.last.poke(false.B); d.io.in.bits.packetBytes.poke(6.U)
      d.io.in.ready.expect(true.B); d.clock.step()
      d.io.enable.poke(false.B)
      d.io.in.bits.data.poke("hffff0a09".U); d.io.in.bits.bytes.poke(1.U); d.io.in.bits.last.poke(true.B)
      d.io.in.ready.expect(true.B); d.io.empty.expect(false.B); d.clock.step()
      // Offer the following packet immediately and hold it through disabled.
      d.io.in.bits.data.poke("h0e0d0c0b".U); d.io.in.bits.bytes.poke(3.U); d.io.in.bits.packetBytes.poke(4.U)
      for (_ <- 0 until 8) { d.io.in.ready.expect(false.B); d.clock.step() }
      d.io.empty.expect(true.B); d.io.wp.expect(3.U)
      word(d, 0) shouldBe BigInt("04030201", 16)
      word(d, 1) shouldBe BigInt("08070605", 16)
      word(d, 2) shouldBe BigInt("00000a09", 16)
      println("[SRAM-disable][2/3] 中途Disable只收旧包尾，屏蔽无效lane并补null；紧随的新包持续被反压")
      d.io.enable.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
      d.io.in.valid.poke(false.B); d.io.enable.poke(false.B); d.clock.step(5)
      d.io.wp.expect(4.U); d.io.empty.expect(true.B)
      word(d, 3) shouldBe BigInt("0e0d0c0b", 16)
      d.clock.step(5); d.io.wp.expect(4.U)
      println("[SRAM-disable][3/3] 重新Enable后恰好接收一次被保持的新包，旧null与新packet边界正确")
    }
  }
}
