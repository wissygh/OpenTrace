package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}
import trace.interface._

class ImplicitReturnSramSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "implicit return through actual encoder funnel and SRAM"
  it should "read real SRAM bytes and discovered parameters for all return and optional mode scenarios" in {
    test(new SingleHartAcceptanceHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      var cycles = 0
      def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
        // Real ready propagation through funnel; keep four consecutive blocked cycles.
        d.io.transfer.poke((cycles % 13 >= 4).B)
        d.clock.step(); cycles += 1
      }
      def write(address: Int, value: BigInt): Unit = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.dataWrite.poke(value.U)
        d.io.bus.b2c.writeEn.poke(true.B); tick(); d.io.bus.b2c.writeEn.poke(false.B)
      }
      def read(address: Int): BigInt = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.readEn.poke(true.B)
        d.io.bus.c2b.error.expect(false.B)
        val word = d.io.bus.c2b.dataRead.peek().litValue
        tick(); d.io.bus.b2c.readEn.poke(false.B); word
      }
      d.clock.setTimeout(1000000)
      for (mode <- Seq(0, 8, 9, 24, 40, 56); scenario <- ImplicitReturnScenarios.all) {
        d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
        d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B); d.io.transfer.poke(true.B)
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
        write(0x40000, 0x103); write(0x10000, 3); write(8, mode)
        val features = read(8)
        (features & 0xff) shouldBe BigInt(mode)
        write(0xf04, 1); write(0, 7 | (15 << 20)); tick(30)
        for (e <- scenario.entries) {
          var wait = 0
          while (d.io.toCore.stall.peek().litToBoolean && wait < 500) { tick(); wait += 1 }
          assert(wait < 500, "core stall did not release")
          c.context.poke(e.context.U); c.ctype.poke(e.ctype.U); c.cause.poke(e.cause.U)
          c.tval.poke(e.tval.U); c.priv.poke(e.privilege.U)
          val b = c.blocks(0)
          b.valid.poke(true.B); b.bits.iaddr.poke(e.pc.U); b.bits.itype.poke(e.kind.U)
          b.bits.iretire.poke(e.retired.U); b.bits.ilastsize.poke((if (e.halfword) 0 else 1).U)
          b.bits.trigNotify.poke(e.notification.B)
          tick(); b.valid.poke(false.B); c.ctype.poke(0.U)
          tick(4)
        }
        tick(300)
        write(0, 1 | (15 << 20)); tick(250); (read(0) & 11) shouldBe BigInt(9)
        write(0x10000, 1); tick(80); (read(0x10000) & 11) shouldBe BigInt(9)
        write(0x40000, 1); tick(30); (read(0x40000) & 11) shouldBe BigInt(9)
        read(0xf10) shouldBe BigInt(0); read(0xf18) shouldBe BigInt(0)
        val wp = read(0x40020).toInt
        wp should be > 0; wp should be < 1024; (wp & 3) shouldBe 0
        write(0x40028, 0)
        val words = (0 until wp / 4).map(_ => read(0x40040))
        read(0x40028) shouldBe BigInt(wp)
        val discovery = (0 until 20).map(i => read(0xe00 + i * 4))
        val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
          .resolve(s"build/validation/implicit-return/sram/$mode/${scenario.name}")
        for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
        def bytes(values: Seq[BigInt]): Array[Byte] = values.flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 255).toByte)).toArray
        Files.write(root.resolve("workload/code.bin"), scenario.code)
        Files.write(root.resolve("dut/trace.bin"), bytes(words))
        Files.write(root.resolve("dut/discovery.bin"), bytes(discovery))
        Files.writeString(root.resolve("dut/trace.json"),
          s"""{"discovery":"discovery.bin","sources":{"0":{"hart_id":0,"raw":"../workload/code.bin","base":"0x${ImplicitReturnScenarios.base.toString(16)}","xlen":${scenario.xlen}}}}""")
        Files.writeString(root.resolve("dut/metadata.json"),
          s"""{"features_requested":$mode,"features_readback":$features,"dropped":0,"loss_markers":0,"wp":$wp,"rp":$wp,"trace_path":"actual Encoder/Funnel/SRAM CSR readback"}""")
        Files.writeString(root.resolve("ref/expected.json"), scenario.pcs.mkString("[", ",", "]"))
        println(s"[implicit-return][SRAM] mode=$mode ${scenario.name}: ${scenario.pcs.size}条、$wp bytes，CSR/Empty/drop/loss/RP全部通过")
      }
    }
  }
}
