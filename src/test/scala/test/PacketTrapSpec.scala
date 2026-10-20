package trace.component

import chisel3._
import chiseltest._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

class PacketTrapSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Block(pc: Int, kind: Int = 0, halfwords: Int = 2, cause: Int = 0,
      tval: Int = 0, privilege: Int = 3)
  behavior of "trap retirement and handler reporting"
  it should "preserve retired prefixes, consecutive trap causes, EPCs and stop boundaries" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val frames = ArrayBuffer[Seq[Int]]()
      var held = Option.empty[Seq[Int]]
      def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
        val valid = d.io.packet.valid.peek().litToBoolean
        val ready = d.io.packet.ready.peek().litToBoolean
        val bytes = if (valid) d.io.packet.bits.data.take(d.io.packet.bits.len.peek().litValue.toInt)
          .map(_.peek().litValue.toInt) else Seq.empty
        held.foreach { h => valid shouldBe true; bytes shouldBe h }
        held = if (valid && !ready) Some(bytes) else None
        if (valid && ready) frames += bytes
        d.clock.step()
      }
      def initialize(): Unit = {
        frames.clear(); held = None
        d.io.teCtrl.poke((6 | (15 << 20)).U); d.io.teInstFeat.poke((3 << 16).U)
        d.io.teDataCtrl.poke(0.U); d.io.filterRes.poke(true.B); d.io.fifoIsFull.poke(false.B)
        d.io.syncReq.poke(false.B); d.io.flushReq.poke(false.B); d.io.stopReq.poke(false.B)
        d.io.packet.ready.poke(true.B)
        d.io.block.valid.poke(false.B); d.io.block.iaddr.poke(0.U); d.io.block.itype.poke(Itype.None)
        d.io.block.iretire.poke(0.U); d.io.block.ilastsize.poke(Ilastsize.Word)
        d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
        d.io.block.notifyRequested.poke(false.B); d.io.block.traceLost.poke(false.B)
        d.io.block.priv.poke(Priv.M); d.io.block.time.poke(0.U); d.io.block.context.poke(0.U)
        d.io.block.ctype.poke(Ctype.None); d.io.block.cause.poke(0.U); d.io.block.tval.poke(0.U)
        d.io.block.impdef.poke(0.U); d.io.block.hartIsReset.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); tick(6)
      }
      def send(blocks: Seq[Block]): Unit = blocks.foreach { b =>
        d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(b.pc.U)
        d.io.block.itype.poke(b.kind.U); d.io.block.iretire.poke(b.halfwords.U)
        d.io.block.cause.poke(b.cause.U); d.io.block.tval.poke(b.tval.U)
        d.io.block.priv.poke(b.privilege.U)
        var accepted = false
        var remaining = 100
        while (!accepted && remaining > 0) {
          accepted = d.io.blockReady.peek().litToBoolean; tick(); remaining -= 1
        }
        assert(accepted, s"trap input timeout: $b")
      }
      def stop(): Unit = {
        d.io.block.valid.poke(false.B)
        d.io.stopReq.poke(true.B); tick(); d.io.stopReq.poke(false.B); tick(12)
        d.io.empty.expect(true.B)
      }
      val support = ETraceReference.support(3)
      val end = ETraceReference.support(3, status = 1)
      def check(name: String, body: Seq[Seq[Int]]): Unit = {
        frames.toSeq shouldBe Seq(support) ++ body ++ Seq(end)
        println(s"[trap-golden] $name：${frames.size} 个 packet 逐字节一致")
      }

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 1, 0, 2, 0x1004), Block(0x2000), Block(0x2004)))
      stop()
      check("普通 exception：最后退休指令与 handler，faulting PC 不退休", Seq(
        ETraceReference.synchronization(3, 0x1000), ETraceReference.trap(3, 0x2000, 2, 0x1004),
        ETraceReference.address(3, 0x2004, 0x2000)))

      initialize()
      send(Seq(Block(0x1000, 8), Block(0x5000, 1, 0, 12, 0x5000), Block(0x2000), Block(0x2004)))
      stop()
      check("间接跳转目标异常：先 EPC，再 handler sync", Seq(ETraceReference.synchronization(3, 0x1000),
        ETraceReference.trap(3, 0x5000, 12, 0x5000, handler = false),
        ETraceReference.synchronization(3, 0x2000), ETraceReference.address(3, 0x2004, 0x2000)))

      initialize()
      send(Seq(Block(0x5000, 1, 0, 12, 0x5000), Block(0x2000), Block(0x2004)))
      stop()
      check("首次输入就是异常，EPC 不得作为首条退休 PC", Seq(
        ETraceReference.trap(3, 0x5000, 12, 0x5000, handler = false),
        ETraceReference.synchronization(3, 0x2000), ETraceReference.address(3, 0x2004, 0x2000)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 1, 0, 2, 0x1004),
        Block(0x5000, 1, 0, 12, 0x5000, privilege = 1), Block(0x2000), Block(0x2004)))
      stop()
      check("连续零退休异常保留两个 cause/tval 和各自 privilege", Seq(
        ETraceReference.synchronization(3, 0x1000),
        ETraceReference.trap(3, 0x1004, 2, 0x1004, handler = false),
        ETraceReference.trap(3, 0x5000, 12, 0x5000, handler = false, privilege = 1),
        ETraceReference.synchronization(3, 0x2000), ETraceReference.address(3, 0x2004, 0x2000)))

      initialize()
      send(Seq(Block(0x1000), Block(0, 2, 0, 7), Block(0x2000), Block(0x2004)))
      stop()
      check("interrupt 不使用无效 iaddr，不携带 tval", Seq(
        ETraceReference.synchronization(3, 0x1000), ETraceReference.trap(3, 0x2000, 7, interrupt = true),
        ETraceReference.address(3, 0x2004, 0x2000)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 1, 0, 2, 0x1004)))
      d.io.block.valid.poke(false.B); d.io.packet.ready.poke(false.B)
      d.io.stopReq.poke(true.B); tick(); d.io.stopReq.poke(false.B); tick(5)
      d.io.empty.expect(false.B)
      d.io.packet.ready.poke(true.B); tick(12)
      d.io.empty.expect(true.B)
      check("零退休异常后停止且输出反压，不等待不存在的 handler", Seq(
        ETraceReference.synchronization(3, 0x1000),
        ETraceReference.trap(3, 0x1004, 2, 0x1004, handler = false)))

      initialize()
      send(Seq(Block(0x5000, 1, 0, 12, 0x5000))); stop()
      check("首次零退休异常立即停止也能完成", Seq(
        ETraceReference.trap(3, 0x5000, 12, 0x5000, handler = false)))

      initialize()
      send(Seq(Block(0x1000, 1, 6, 2, 0x100c), Block(0x2000, 1, 4, 12, 0x2008),
        Block(0x3000), Block(0x3004)))
      stop()
      check("连续退休+trap block 完整保留首尾退休指令及两个 handler", Seq(
        ETraceReference.synchronization(3, 0x1000), ETraceReference.address(3, 0x1008, 0x1000),
        ETraceReference.trap(3, 0x2000, 2, 0x100c), ETraceReference.address(3, 0x2004, 0x2000),
        ETraceReference.trap(3, 0x3000, 12, 0x2008), ETraceReference.address(3, 0x3004, 0x3000)))
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val out = workspace.resolve(Paths.get("build", "validation")); Files.createDirectories(out)
      Files.write(out.resolve("packetgen-traps.bin"), frames.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x2010)(0)
      for (pc <- Seq(0x1000, 0x1004, 0x1008, 0x2000, 0x2004, 0x3000, 0x3004)) code(pc - 0x1000) = 0x13
      Files.write(out.resolve("packetgen-traps-code.bin"), code)
      val config = """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-traps-code.bin","base":"0x1000","xlen":64}}}"""
      Files.write(out.resolve("packetgen-traps.json"), config.getBytes(StandardCharsets.UTF_8))
      Files.write(out.resolve("packetgen-traps-expected.json"), "[4096,4100,4104,8192,8196,12288,12292]".getBytes(StandardCharsets.UTF_8))
      println("[trap-golden] 导出双重退休+trap capture，decoder 比较 7 条退休 PC 和两个非退休 EPC")
    }
  }
}
