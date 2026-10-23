package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class ImplicitReturnCodecHarness extends Module {
  val io = IO(new Bundle {
    val count = Input(UInt(5.W))
    val map = Input(UInt(31.W))
    val address = Input(UInt(49.W))
    val index = Input(UInt(8.W))
    val report = Input(Bool())
    val depth = Input(UInt(5.W))
    val notification = Input(Bool())
    val updiscon = Input(Bool())
    val failed = Input(Bool())
    val packet = Output(Vec(3, new PacketEnq(33)))
  })
  val normal = ETracePacketCodec.address(io.count, io.map, io.address, io.notification, io.updiscon, 248, Some(io.depth), io.report)
  val cached = ETracePacketCodec.cachedTarget(io.index, io.count, io.map, 1, 248, Some(io.depth), io.report)
  val predicted = ETracePacketCodec.predictedAddress(32.U(32.W), io.failed, io.address, io.notification, io.updiscon, 1, 248, Some(io.depth), io.report)
  for ((raw, i) <- Seq(normal, cached, predicted).zipWithIndex)
    io.packet(i) := ETracePacketCodec.encapsulate(raw, 3.U(8.W), 33)
}

class ImplicitReturnCodecSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "implicit return packet bit layout"
  it should "match independent Format1 2 00 01 fields at every map width address sign and flag combination" in {
    test(new ImplicitReturnCodecHarness) { d =>
      for (count <- 0 to 31; negative <- Seq(false, true); flags <- 0 until 4; depth <- Seq(-1, 0, 1, 16)) {
        val outcomes = (0 until count).map(i => (i + count) % 3 == 0)
        val map = outcomes.zipWithIndex.foldLeft(BigInt(0)) { case (v, (taken, i)) => if (taken) v else v.setBit(i) }
        val delta = BigInt(if (negative) -0x106 else 0x106)
        val notify = (flags & 1) != 0; val updiscon = (flags & 2) != 0
        val report = if (depth < 0) None else Some(depth)
        d.io.count.poke(count.U); d.io.map.poke(map.U)
        d.io.address.poke(((delta & ETraceReference.Mask) >> 1).U); d.io.index.poke(0x81.U)
        d.io.report.poke(report.nonEmpty.B); d.io.depth.poke(math.max(0, depth).U)
        d.io.notification.poke(notify.B); d.io.updiscon.poke(updiscon.B); d.io.failed.poke(negative.B)
        val expected = Seq(
          ETraceReference.address(3, delta, 0, outcomes, notify, updiscon, irDepth = report),
          ETraceReference.cachedTarget(3, 0x102, outcomes, irDepth = report),
          ETraceReference.prediction(3, 63, Some(delta), failed = negative, notifyRequested = notify,
            beforeSync = updiscon, irDepth = report))
        for (i <- 0 until 3) {
          val n = d.io.packet(i).len.peek().litValue.toInt
          withClue(s"format=$i count=$count sign=$negative flags=$flags depth=$depth: ") {
            d.io.packet(i).data.take(n).map(_.peek().litValue.toInt).toSeq shouldBe expected(i)
          }
        }
      }
      println("[implicit-return][wire] 1024组×3格式：前位异或/5-bit深度/填充符号裁剪/1..31分支图全部逐byte一致")
    }
  }
}
