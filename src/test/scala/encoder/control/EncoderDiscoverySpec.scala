package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer
import trace.{Control2BusRegBundle, TraceParameters}
import trace.interface._

private class EncoderDiscoveryHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val io = IO(new Bundle {
    val bus = new Control2BusRegBundle
    val core = Input(new FromCore)
    val out = Decoupled(new TraceBeat(4))
  })
  val encoder = withReset(reset.asAsyncReset) { Module(LazyModule(new Encoder(0x20000000)).module) }
  encoder.bus <> io.bus
  encoder.io.fromCore := io.core
  encoder.io.control.syncReq := false.B; encoder.io.control.flushReq := false.B
  io.out <> encoder.io.out
}

class EncoderDiscoverySpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "E-Trace Table 42 register discovery"
  it should "read all attributes from the actual encoder and export discovery with its complete trace" in {
    test(new EncoderDiscoveryHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val captured = ArrayBuffer.empty[Byte]
      def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
        if (d.io.out.valid.peek().litToBoolean) {
          val word = d.io.out.bits.data.peek().litValue
          for (i <- 0 to d.io.out.bits.bytes.peek().litValue.toInt) captured += ((word >> (i * 8)) & 255).toByte
        }
        d.clock.step()
      }
      d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.readEn.poke(false.B)
      d.io.bus.b2c.writeEn.poke(false.B); d.io.bus.b2c.dataWrite.poke(0.U); d.io.out.ready.poke(true.B)
      val c = d.io.core
      c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
      c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
      c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
      c.blocks.foreach { b =>
        b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
        b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
        b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
      }
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B); tick(20)
      def read(address: Int): BigInt = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.readEn.poke(true.B)
        d.io.bus.c2b.error.expect(false.B)
        val result = d.io.bus.c2b.dataRead.peek().litValue
        d.io.bus.b2c.readEn.poke(false.B); result
      }
      def write(address: Int, value: BigInt): Unit = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.dataWrite.poke(value.U)
        d.io.bus.b2c.writeEn.poke(true.B); tick(); d.io.bus.b2c.writeEn.poke(false.B)
      }
      // Expected words are specified independently of EncoderDiscoveryValues.from.
      val expected = Seq(BigInt("4352544f", 16), BigInt(1), BigInt(20), BigInt(15), BigInt(0x10008)) ++
        Seq(0, 8, 8, 0, 31, 63, 7, 1, 0, 49, 0, 0, 2, 0, 0).map(BigInt(_))
      val words = expected.indices.map(i => read(0xe00 + i * 4))
      words shouldBe expected
      for (i <- words.indices; value <- Seq(BigInt(0), BigInt("ffffffff", 16))) {
        write(0xe00 + i * 4, value); read(0xe00 + i * 4) shouldBe expected(i)
      }
      for (hole <- Seq(0xdfc, 0xe01, 0xe4d, 0xe50, 0xefc)) {
        write(hole, BigInt("ffffffff", 16)); read(hole) shouldBe BigInt(0)
      }
      d.io.bus.b2c.address.poke(0xe00.U); d.io.bus.c2b.dataRead.expect(0.U)
      println("[Table42][1/3] 实际Encoder：20words/15属性、减1编码、只读和未对齐/相邻空洞全部通过")
      write(0, 7 | (15 << 20)); tick(20)
      for (pc <- Seq(0x1000, 0x1004)) {
        c.blocks(0).valid.poke(true.B); c.blocks(0).bits.iaddr.poke(pc.U)
        tick(); c.blocks(0).valid.poke(false.B); tick(12)
      }
      write(0, 1 | (15 << 20)); tick(120)
      (read(0) & 11) shouldBe BigInt(9)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      for (i <- words.indices) read(0xe00 + i * 4) shouldBe words(i)
      val out = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve("build/validation/encoder-discovery")
      Files.createDirectories(out)
      Files.write(out.resolve("discovery.bin"), words.flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 255).toByte)).toArray)
      Files.write(out.resolve("trace.bin"), captured.toArray)
      Files.write(out.resolve("code.bin"), Array[Byte](0x13, 0, 0, 0, 0x13, 0, 0, 0))
      Files.writeString(out.resolve("trace.json"),
        """{"discovery":"discovery.bin","sources":{"0":{"hart_id":0,"raw":"code.bin","base":"0x1000"}}}""")
      println(s"[Table42][2/3] 真实CSR快照和完整2条退休流已导出：${out.toAbsolutePath.normalize()}")
    }
  }

  it should "encode changed parameters and report optional formats as absent when unavailable" in {
    val parameters = new TraceParameters {
      override def supportBranchPred = false
      override def supportJumpCache = false
      override def nocontext_p = true
      override def notime_p = true
      override def iaddress_width_p = 32
      override def iaddress_lsb_p = 2
      override def privilege_width_p = 2
      override def ecause_width_p = 4
      override def f0s_width_p = 0
    }
    val values = EncoderDiscoveryValues.from(parameters)
    values.attributes shouldBe Seq(0, 0, 0, 0, 31, 63, 3, 0, 1, 31, 1, 1, 1, 0, 0)
    test(new EncoderDiscovery(0x20030000, values)) { d =>
      d.io.bus.b2c.readEn.poke(true.B); d.io.bus.b2c.writeEn.poke(false.B)
      d.io.bus.b2c.dataWrite.poke(0.U)
      for ((word, i) <- values.words.zipWithIndex) {
        d.io.bus.b2c.address.poke((0x30000 + 0xe00 + i * 4).U)
        d.io.selected.expect(true.B); d.io.bus.c2b.dataRead.expect(word.U)
      }
      d.io.bus.b2c.address.poke(0xe00.U)
      d.io.selected.expect(false.B); d.io.bus.c2b.dataRead.expect(0.U)
      println("[Table42][3/3] 变体参数/禁用context与time/可选模式关闭，以及非零组件base均正确")
    }
  }
}
