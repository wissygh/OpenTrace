package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}
import trace.interface._

class HartStopSramSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ordered hart halt and reset"
  it should "retain every accepted retirement ahead of halt and reset under downstream backpressure" in {
    test(new SingleHartAcceptanceHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val base = BigInt("80001000", 16)
      def write(address: Int, value: BigInt): Unit = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.dataWrite.poke(value.U)
        d.io.bus.b2c.writeEn.poke(true.B); d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
      }
      def read(address: Int): BigInt = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.readEn.poke(true.B)
        val value = d.io.bus.c2b.dataRead.peek().litValue
        d.clock.step(); d.io.bus.b2c.readEn.poke(false.B); value
      }
      for (isReset <- Seq(false, true); pulse <- Seq(false, true); mode <- Seq(0, 8)) {
        d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
        d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B); d.io.transfer.poke(true.B)
        val c = d.io.core
        c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
        c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
        c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
        c.blocks.foreach { b =>
          b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
          b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
          b.bits.trigNotify.poke(true.B); b.bits.sijump.foreach(_.poke(false.B))
        }
        d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B); d.clock.step(20)
        write(0x40000, 0x103); write(0x10000, 3); write(8, mode)
        write(0xf04, 1); write(0, 0x2007 | (15 << 20))
        d.clock.step(20); d.io.transfer.poke(false.B)
        val accepted = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        val b = c.blocks(0)
        var cycles = 0
        while (!d.io.toCore.stall.peek().litToBoolean && cycles < 100) {
          val pc = base + accepted.size * 4
          b.valid.poke(true.B); b.bits.iaddr.poke(pc.U)
          accepted += pc; d.clock.step(); cycles += 1
        }
        b.valid.poke(false.B)
        accepted.size should be > 16
        d.io.toCore.stall.expect(true.B)
        c.hartIsReset.poke(isReset.B); c.priv.poke((if (isReset) Priv.M else Priv.D))
        // Even valid-looking Debug/reset instructions must not enter this trace.
        b.valid.poke(true.B); b.bits.iaddr.poke(BigInt("38020800", 16).U)
        d.clock.step(); b.valid.poke(false.B)
        if (pulse) { c.hartIsReset.poke(false.B); c.priv.poke(Priv.M) }
        d.clock.step(36); d.io.transfer.poke(true.B); d.clock.step(400)
        c.hartIsReset.poke(false.B); c.priv.poke(Priv.M); d.clock.step(4)
        for (pc <- Seq(base + 0x1000, base + 0x1004)) {
          b.valid.poke(true.B); b.bits.iaddr.poke(pc.U); d.clock.step(); b.valid.poke(false.B); d.clock.step(20)
        }
        write(0, 1 | (15 << 20)); d.clock.step(200); (read(0) & 11) shouldBe BigInt(9)
        write(0x10000, 1); d.clock.step(100); (read(0x10000) & 11) shouldBe BigInt(9)
        write(0x40000, 1); d.clock.step(30); (read(0x40000) & 11) shouldBe BigInt(9)
        read(0xf10) shouldBe BigInt(0); read(0xf18) shouldBe BigInt(0)
        val wp = read(0x40020).toInt
        wp should be < 1024; (wp & 3) shouldBe 0
        write(0x40028, 0)
        val words = (0 until wp / 4).map(_ => read(0x40040))
        read(0x40028) shouldBe BigInt(wp)
        val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
          .resolve(s"build/validation/hart-stop/${if (isReset) "reset" else "debug"}/${if (pulse) "pulse" else "hold"}-$mode")
        for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
        val expected = accepted.toSeq ++ Seq(base + 0x1000, base + 0x1004)
        val code = Array.fill[Byte](0x1008)(0)
        for (pc <- expected) code((pc - base).toInt) = 0x13
        Files.write(root.resolve("workload/code.bin"), code)
        Files.write(root.resolve("dut/trace.bin"), words.flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 255).toByte)).toArray)
        Files.writeString(root.resolve("dut/trace.json"),
          """{"profile":{"return_stack_size":4},"sources":{"0":{"hart_id":0,"raw":"../workload/code.bin","base":"0x80001000"}}}""")
        Files.writeString(root.resolve("ref/expected.json"), expected.mkString("[", ",", "]"))
        println(s"[hart-stop] reset=$isReset pulse=$pulse mode=$mode：积压${accepted.size}条退休→停止→排空→resume2条，SRAM $wp bytes交decoder")
      }
    }
  }
}
