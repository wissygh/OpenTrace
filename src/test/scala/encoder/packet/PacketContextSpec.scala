package trace.component

import chisel3._
import chiseltest._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

class PacketContextSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Block(pc: Int, kind: Int = 0, ctype: Int = 0, context: Int = 0,
      notification: Boolean = false, time: Int = 0)
  behavior of "context event ordering"
  it should "preserve imprecise events and close asynchronous execution segments" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val frames = ArrayBuffer[Seq[Int]]()
      def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
        if (d.io.packet.valid.peek().litToBoolean && d.io.packet.ready.peek().litToBoolean)
          frames += d.io.packet.bits.data.take(d.io.packet.bits.len.peek().litValue.toInt).map(_.peek().litValue.toInt)
        d.clock.step()
      }
      def initialize(): Unit = {
        frames.clear()
        d.io.teCtrl.poke((6 | (15 << 20)).U); d.io.teInstFeat.poke((3 << 16).U)
        d.io.teDataCtrl.poke(0.U); d.io.filterRes.poke(true.B); d.io.fifoIsFull.poke(false.B)
        d.io.syncReq.poke(false.B); d.io.flushReq.poke(false.B); d.io.stopReq.poke(false.B)
        d.io.packet.ready.poke(true.B)
        d.io.block.valid.poke(false.B); d.io.block.iaddr.poke(0.U); d.io.block.itype.poke(Itype.None)
        d.io.block.iretire.poke(2.U); d.io.block.ilastsize.poke(Ilastsize.Word)
        d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
        d.io.block.notifyRequested.poke(false.B); d.io.block.traceLost.poke(false.B)
        d.io.block.priv.poke(Priv.M); d.io.block.time.poke(0.U); d.io.block.context.poke(0.U)
        d.io.block.ctype.poke(Ctype.None); d.io.block.cause.poke(0.U); d.io.block.tval.poke(0.U)
        d.io.block.impdef.poke(0.U); d.io.block.hartIsReset.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); tick(5)
      }
      def send(blocks: Seq[Block]): Unit = blocks.foreach { b =>
        d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(b.pc.U)
        d.io.block.itype.poke(b.kind.U); d.io.block.ctype.poke(b.ctype.U)
        d.io.block.context.poke(b.context.U); d.io.block.time.poke(b.time.U)
        d.io.block.notifyRequested.poke(b.notification.B)
        var accepted = false
        var remaining = 100
        while (!accepted && remaining > 0) { accepted = d.io.blockReady.peek().litToBoolean; tick(); remaining -= 1 }
        assert(accepted, s"context input timeout: $b")
      }
      def stop(): Unit = {
        d.io.block.valid.poke(false.B); d.io.stopReq.poke(true.B); tick()
        d.io.stopReq.poke(false.B); tick(12); d.io.empty.expect(true.B)
      }
      val support = ETraceReference.support(3)
      val end = ETraceReference.support(3, status = 1)
      initialize()
      send(Seq(Block(0x1000), Block(0x1004, ctype = 1, context = 1, notification = true, time = 9),
        Block(0x1008, context = 1)))
      stop()
      frames.toSeq shouldBe Seq(support, ETraceReference.synchronization(3, 0x1000),
        ETraceReference.context(3, 1, time = 9), ETraceReference.address(3, 0x1004, 0x1000, notifyRequested = true),
        ETraceReference.address(3, 0x1008, 0x1004), end)
      println("[context-golden] imprecise+notify：先 Format 32，再原地址包，事件未丢失")

      initialize()
      send(Seq(Block(0x1000)) ++ (1 to 31).map(i => Block(0x1000 + 4 * i, kind = 4,
        ctype = if (i == 31) 1 else 0, context = if (i == 31) 1 else 0, notification = i == 31)) ++
        Seq(Block(0x1080, context = 1)))
      stop()
      frames.toSeq shouldBe Seq(support, ETraceReference.synchronization(3, 0x1000), ETraceReference.context(3, 1),
        ETraceReference.address(3, 0x107c, 0x1000, Seq.fill(31)(false), notifyRequested = true),
        ETraceReference.address(3, 0x1080, 0x107c), end)
      println("[context-golden] 第 31 个 branch+imprecise+notify：分支只消费一次，31-bit map 完整")

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, kind = 5), Block(0x1008, ctype = 2, context = 1),
        Block(0x100c, ctype = 1, context = 1, notification = true, time = 0x1234),
        Block(0x1010, context = 1), Block(0x2000, ctype = 3, context = 2), Block(0x2004, context = 2)))
      stop()
      frames.toSeq shouldBe Seq(support, ETraceReference.synchronization(3, 0x1000),
        ETraceReference.address(3, 0x1004, 0x1000, Seq(true)),
        ETraceReference.synchronization(3, 0x1008, context = 1),
        ETraceReference.context(3, 1, time = 0x1234),
        ETraceReference.address(3, 0x100c, 0x1008, notifyRequested = true),
        ETraceReference.address(3, 0x1010, 0x100c), end,
        ETraceReference.synchronization(3, 0x2000, context = 2),
        ETraceReference.address(3, 0x2004, 0x2000), end)
      println("[context-golden] precise 先排分支，async 先结束旧段；随后新 context 同步，不生成间隙 PC")
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val out = workspace.resolve(Paths.get("build", "validation")); Files.createDirectories(out)
      Files.write(out.resolve("packetgen-context.bin"), frames.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x1010)(0)
      for (pc <- Seq(0x1000, 0x1008, 0x100c, 0x1010, 0x2000, 0x2004)) code(pc - 0x1000) = 0x13
      code(4) = 0x63; code(5) = 2 // beq x0,x0,+4
      Files.write(out.resolve("packetgen-context-code.bin"), code)
      val config = """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-context-code.bin","base":"0x1000","xlen":64}}}"""
      Files.write(out.resolve("packetgen-context.json"), config.getBytes(StandardCharsets.UTF_8))
      Files.write(out.resolve("packetgen-context-expected.json"), "[4096,4100,4104,4108,4112,8192,8196]".getBytes(StandardCharsets.UTF_8))
    }
  }
}
