package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.TraceParameters
import trace.interface._

private class TraceLossHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val core = Input(new FromCore)
    val features = Input(UInt(32.W))
    val control = Input(UInt(32.W))
    val run = Input(Bool())
    val stop = Input(Bool())
    val overflow = Output(Bool())
    val rawEmpty = Output(Bool())
    val packet = Decoupled(new PacketEnq(maxPackets))
  })
  val raw = Module(new BlockBuffer)
  val gen = Module(new PacketGen)
  raw.io.fromCore := io.core
  raw.io.deqReady := gen.io.blockReady && io.run
  gen.io.block := raw.io.deqEntry
  gen.io.block.valid := raw.io.deqEntry.valid && io.run
  gen.io.stopReq := io.stop
  gen.io.flushReq := false.B
  gen.io.syncReq := false.B
  gen.io.fromCtrl.filterRes := true.B
  gen.io.fromCtrl.fifoIsFull := false.B
  gen.io.fromCtrl.teCtrl := io.control
  gen.io.fromCtrl.teInstFeat := io.features
  gen.io.fromCtrl.teDataCtrl := 0.U
  io.overflow := raw.io.overflow
  io.rawEmpty := raw.io.empty
  io.packet <> gen.io.packet
}

class TraceLossSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ordered trace loss recovery"

  private def init(core: FromCore): Unit = {
    core.priv.poke(Priv.M); core.time.poke(0.U); core.context.poke(0.U)
    core.ctype.poke(Ctype.None); core.cause.poke(0.U); core.tval.poke(0.U)
    core.impdef.poke(0.U); core.hartIsReset.poke(false.B)
    core.trigger.traceOn.poke(false.B); core.trigger.traceOff.poke(false.B)
    core.blocks.foreach { b =>
      b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
      b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
      b.bits.trigNotify.poke(true.B); b.bits.sijump.foreach(_.poke(false.B))
    }
  }

  it should "preserve old entries and order repeated loss markers and dropped trigger actions" in {
    test(new BlockBuffer) { d =>
      init(d.io.fromCore); d.io.deqReady.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def fill(base: Int): Unit = {
        d.io.fromCore.blocks(0).valid.poke(true.B)
        for (i <- 0 until 16) {
          d.io.fromCore.blocks(0).bits.iaddr.poke((base + i * 4).U)
          d.io.overflow.expect(false.B); d.clock.step()
        }
        d.io.fromCore.blocks(0).bits.iaddr.poke((base + 0x100).U)
        d.io.fromCore.trigger.traceOn.poke(true.B)
        d.io.overflow.expect(true.B); d.clock.step(2)
        d.io.fromCore.trigger.traceOn.poke(false.B)
        d.io.fromCore.trigger.traceOff.poke(true.B)
        d.io.overflow.expect(true.B); d.clock.step()
        d.io.fromCore.trigger.traceOff.poke(false.B)
        d.io.fromCore.blocks(0).valid.poke(false.B)
      }
      for (base <- Seq(0x1000, 0x2000)) {
        println(f"[trace-loss] 填满 raw buffer，制造连续丢失；依次排出旧数据和 marker，base=0x$base%x")
        fill(base)
        d.io.empty.expect(false.B)
        d.io.deqReady.poke(true.B)
        for (i <- 0 until 16) {
          d.io.deqEntry.iaddr.expect((base + i * 4).U)
          d.io.deqEntry.traceLost.expect(false.B)
          d.clock.step()
        }
        d.io.deqEntry.valid.expect(true.B)
        d.io.deqEntry.traceLost.expect(true.B)
        d.io.deqEntry.iretire.expect(0.U)
        d.io.deqEntry.trigger.expect(TriggerAction.TraceOff)
        d.clock.step(); d.io.empty.expect(true.B)
        d.io.deqReady.poke(false.B)
      }
    }
  }

  it should "emit trace_lost after all old packets and resynchronize before new instruction bytes" in {
    test(new TraceLossHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.features.poke((3 << 16).U); d.io.control.poke(7.U)
      init(d.io.core); d.io.run.poke(false.B); d.io.stop.poke(false.B)
      d.io.packet.ready.poke(true.B)
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
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); tick(6)
      frames.toSeq shouldBe Seq(ETraceReference.support(3))
      println("[trace-loss] 1/4 停止 raw 消费，保留 16 条输入并制造真实 overflow")
      d.io.core.blocks(0).valid.poke(true.B)
      for (i <- 0 until 20) {
        d.io.core.blocks(0).bits.iaddr.poke((0x1000 + i * 4).U)
        d.io.overflow.expect((i >= 16).B); tick()
      }
      d.io.core.blocks(0).valid.poke(false.B)
      d.io.run.poke(true.B); d.io.packet.ready.poke(false.B); tick(5)
      d.io.packet.ready.poke(true.B); tick(80)
      val old = Seq(ETraceReference.support(3), ETraceReference.synchronization(3, 0x1000)) ++
        (1 until 16).map(i => ETraceReference.address(3, 0x1000 + 4 * i, 0x1000 + 4 * (i - 1), notifyRequested = true)) ++
        Seq(ETraceReference.support(3, status = 2))
      frames.toSeq shouldBe old
      d.io.rawEmpty.expect(true.B)
      println("[trace-loss] 2/4 旧 PC 全部按序报告，连续丢失合并为一个 trace_lost；已检查输出反压保持")
      for (pc <- Seq(0x3000, 0x3004)) {
        d.io.core.blocks(0).valid.poke(true.B)
        d.io.core.blocks(0).bits.iaddr.poke(pc.U); tick()
        d.io.core.blocks(0).valid.poke(false.B); tick(8)
      }
      d.io.stop.poke(true.B); tick(); d.io.stop.poke(false.B); tick(10)
      frames.toSeq shouldBe old ++ Seq(ETraceReference.synchronization(3, 0x3000),
        ETraceReference.address(3, 0x3004, 0x3000, notifyRequested = true), ETraceReference.support(3, status = 1))
      println("[trace-loss] 3/4 新片段以完整同步起始，无差分状态或分支图跨越丢失区间")
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val out = workspace.resolve(Paths.get("build", "validation")); Files.createDirectories(out)
      Files.write(out.resolve("packetgen-loss.bin"), frames.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x2010)(0)
      for (pc <- (0 until 16).map(i => 0x1000 + i * 4) ++ Seq(0x3000, 0x3004)) code(pc - 0x1000) = 0x13
      Files.write(out.resolve("packetgen-loss-code.bin"), code)
      val config = """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-loss-code.bin","base":"0x1000","xlen":64}}}"""
      Files.write(out.resolve("packetgen-loss.json"), config.getBytes(StandardCharsets.UTF_8))
      val expected = ((0 until 16).map(i => 0x1000 + i * 4) ++ Seq(0x3000, 0x3004)).mkString("[", ",", "]")
      Files.write(out.resolve("packetgen-loss-expected.json"), expected.getBytes(StandardCharsets.UTF_8))
      println("[trace-loss] 4/4 导出真实 raw-buffer/PacketGen 捕获；decoder 应仅恢复 18 条已保留指令")
    }
  }
}
