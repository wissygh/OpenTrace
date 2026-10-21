package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random
import trace.TraceParameters

private class ETraceCodecHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val mode = Input(UInt(2.W))
    val count = Input(UInt(5.W))
    val map = Input(UInt(31.W))
    val address = Input(UInt(49.W))
    val trigger = Input(Bool())
    val updiscon = Input(Bool())
    val source = Input(UInt(8.W))
    val raw = Input(UInt(248.W))
    val packet = Output(new PacketEnq(maxPackets))
  })
  val addressed = ETracePacketCodec.address(io.count, io.map, io.address,
    io.trigger, io.updiscon, payload_max_width)
  val raw = MuxLookup(io.mode, io.raw)(Seq(
    1.U -> addressed,
    2.U -> ETracePacketCodec.branchOnly(io.map, payload_max_width)))
  io.packet := ETracePacketCodec.encapsulate(raw, io.source, maxPackets)
}

class ETraceCodecSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "E-Trace packet wire codec"
  it should "match normative field packing at every branch width and signed byte boundary" in {
    test(new ETraceCodecHarness) { d =>
      println("[encoder-golden] 校验 Format 1 的 1/3/7/15/31 位分支图和正负地址/标志")
      d.io.mode.poke(1.U)
      d.io.source.poke(7.U)
      d.io.raw.poke(0.U)
      d.io.count.poke(0.U)
      d.io.map.poke(0.U)
      d.io.address.poke(0.U)
      d.io.trigger.poke(false.B)
      d.io.updiscon.poke(false.B)
      def actual: Seq[Int] = {
        val size = d.io.packet.len.peek().litValue.toInt
        val bytes = d.io.packet.data.take(size).map(_.peek().litValue.toInt)
        d.io.packet.data.drop(size).foreach(_.expect(0.U))
        bytes
      }
      val random = new Random(0x45545243)
      for (count <- 0 to 31; negative <- Seq(false, true); flags <- 0 until 4) {
        val outcomes = Seq.fill(count)(random.nextBoolean())
        val map = outcomes.zipWithIndex.foldLeft(BigInt(0)) { case (value, (taken, i)) =>
          if (taken) value else value.setBit(i)
        }
        val delta = BigInt(if (negative) -0x106 else 0x106)
        val notify = (flags & 1) != 0
        val updiscon = (flags & 2) != 0
        d.io.count.poke(count.U)
        d.io.map.poke(map.U)
        d.io.address.poke((((delta & ETraceReference.Mask) >> 1)).U)
        d.io.trigger.poke(notify.B)
        d.io.updiscon.poke(updiscon.B)
        actual shouldBe ETraceReference.address(7, delta, 0, outcomes, notify, updiscon)
      }
      d.io.mode.poke(2.U)
      for (outcomes <- Seq(Seq.fill(31)(true), Seq.fill(31)(false), Seq.tabulate(31)(_ % 2 == 0))) {
        val map = outcomes.zipWithIndex.foldLeft(BigInt(0)) { case (value, (taken, i)) =>
          if (taken) value else value.setBit(i)
        }
        d.io.map.poke(map.U)
        actual shouldBe ETraceReference.branchOnly(7, outcomes)
      }
      println("[encoder-golden] 校验符号字节裁剪：0/-1、每个字节符号边界、248 位极值")
      d.io.mode.poke(0.U)
      val boundaries = (1 to 30).flatMap { bytes =>
        val sign = BigInt(1) << (8 * bytes - 1)
        Seq(sign - 1, sign, -sign, -sign - 1)
      }
      for (value <- Seq(BigInt(0), BigInt(-1), (BigInt(1) << 247) - 1, -(BigInt(1) << 247)) ++ boundaries) {
        d.io.raw.poke((value & ((BigInt(1) << 248) - 1)).U)
        actual shouldBe ETraceReference.frame(7, ETraceReference.payload(Seq((value, 248))))
      }
    }
  }
}
