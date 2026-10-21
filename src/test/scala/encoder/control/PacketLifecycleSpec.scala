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
import trace.control._
import trace.interface._

/** Actual CSR logic and PacketGen; triggers update only on block acceptance. */
private class PacketLifecycleHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val block = Input(new BlockBufferEntry)
    val filter = Input(Bool())
    val stop = Input(Bool())
    val features = Input(UInt(32.W))
    val write = Flipped(Valid(UInt(32.W)))
    val packet = Decoupled(new PacketEnq(maxPackets))
    val ready = Output(Bool())
    val empty = Output(Bool())
    val control = Output(UInt(32.W))
  })
  val control = withReset(reset.asAsyncReset) { Module(new EncoderTraceControlRegister) }
  val encoder = Module(new PacketGen)
  control.w.wen := io.write.valid
  control.w.wdata := io.write.bits
  control.traceOn := io.block.valid && encoder.io.blockReady && io.block.trigger === TriggerAction.TraceOn
  control.traceOff := io.block.valid && encoder.io.blockReady &&
    (io.block.trigger === TriggerAction.TraceOff || io.block.traceOffAfter)
  control.fifoIsFull := false.B
  control.fifoIsEmpty := encoder.io.empty
  encoder.io.block := io.block
  encoder.io.stopReq := io.stop
  encoder.io.syncReq := false.B
  encoder.io.flushReq := false.B
  encoder.io.fromCtrl.filterRes := io.filter
  encoder.io.fromCtrl.fifoIsFull := false.B
  encoder.io.fromCtrl.teCtrl := control.regOut
  encoder.io.fromCtrl.teInstFeat := io.features
  encoder.io.fromCtrl.teDataCtrl := 0.U
  io.packet <> encoder.io.packet
  io.ready := encoder.io.blockReady
  io.empty := encoder.io.empty
  io.control := control.rdata
}

class PacketLifecycleSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Block(pc: Int, kind: Int = 0, halfwords: Int = 2, lastSize: Int = 1,
      trigger: Int = 0, both: Boolean = false, qualified: Boolean = true, notifyRequested: Boolean = false)
  behavior of "instruction trace lifecycle"
  it should "drain software stops, filter gaps, mode changes and trigger boundaries without invented lookahead" in {
    test(new PacketLifecycleHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val frames = ArrayBuffer[Seq[Int]]()
      val source = 3
      var held = Option.empty[Seq[Int]]
      def tick(): Unit = {
        val valid = d.io.packet.valid.peek().litToBoolean
        val ready = d.io.packet.ready.peek().litToBoolean
        val frame = if (valid) d.io.packet.bits.data.take(d.io.packet.bits.len.peek().litValue.toInt)
          .map(_.peek().litValue.toInt) else Seq.empty
        held.foreach { before => valid shouldBe true; frame shouldBe before }
        held = if (valid && !ready) Some(frame) else None
        if (valid && ready) frames += frame
        d.clock.step()
      }
      def idle(n: Int): Unit = {
        d.io.block.valid.poke(false.B)
        for (_ <- 0 until n) tick()
      }
      def write(value: Int): Unit = {
        d.io.write.valid.poke(true.B); d.io.write.bits.poke(value.U)
        tick(); d.io.write.valid.poke(false.B)
      }
      def send(blocks: Seq[Block]): Unit = blocks.foreach { block =>
        d.io.block.valid.poke(true.B)
        d.io.block.iaddr.poke(block.pc.U)
        d.io.block.itype.poke(block.kind.U)
        d.io.block.iretire.poke(block.halfwords.U)
        d.io.block.ilastsize.poke(block.lastSize.U)
        d.io.block.trigger.poke(block.trigger.U)
        d.io.block.traceOffAfter.poke(block.both.B)
        d.io.block.notifyRequested.poke(block.notifyRequested.B)
        d.io.filter.poke(block.qualified.B)
        var accepted = false
        var remaining = 120
        while (!accepted && remaining > 0) {
          accepted = d.io.ready.peek().litToBoolean
          tick(); remaining -= 1
        }
        assert(accepted, s"lifecycle input timeout at 0x${block.pc.toHexString}")
      }
      def initialize(tracing: Boolean = true, triggers: Boolean = false): Unit = {
        frames.clear(); held = None
        d.io.write.valid.poke(false.B); d.io.write.bits.poke(0.U)
        d.io.features.poke((source << 16).U)
        d.io.filter.poke(true.B); d.io.stop.poke(false.B)
        d.io.packet.ready.poke(true.B)
        d.io.block.valid.poke(false.B)
        d.io.block.iaddr.poke(0.U); d.io.block.itype.poke(Itype.None)
        d.io.block.iretire.poke(0.U); d.io.block.ilastsize.poke(Ilastsize.HalfWord)
        d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
        d.io.block.notifyRequested.poke(false.B)
      d.io.block.traceLost.poke(false.B)
        d.io.block.priv.poke(Priv.M); d.io.block.time.poke(0.U); d.io.block.context.poke(0.U)
        d.io.block.ctype.poke(Ctype.None); d.io.block.cause.poke(0.U); d.io.block.tval.poke(0.U)
        d.io.block.impdef.poke(0.U); d.io.block.hartIsReset.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        write(3 | (if (tracing) 4 else 0) | (if (triggers) 1 << 11 else 0))
        idle(6)
        frames.toSeq shouldBe Seq(ETraceReference.support(source, tracing = tracing))
      }
      def check(name: String, expected: Seq[Seq[Int]]): Unit = {
        idle(10)
        withClue(name + ": ") { frames.toSeq shouldBe expected }
        println(s"[lifecycle-golden] $name：${frames.size} 个 packet 顺序/字节一致")
      }

      initialize()
      send(Seq(Block(0x1000), Block(0x1004)))
      idle(6)
      frames.size shouldBe 2 // mere idle is not trace-end
      write(1)
      check("Enable=0，无后继 block，最终地址先于 ended_rep", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1004, 0x1000),
        ETraceReference.support(source, status = 1, tracing = false)))
      d.io.empty.expect(true.B)
      ((d.io.control.peek().litValue >> 3) & 1) shouldBe BigInt(1)
      idle(10); frames.size shouldBe 4
      write(7); idle(5)
      send(Seq(Block(0x2000), Block(0x2004)))
      d.io.block.valid.poke(false.B); write(3)
      check("重新启用后独立同步，InstTracing=0 也能收尾", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000), ETraceReference.address(source, 0x1004, 0x1000),
        ETraceReference.support(source, status = 1, tracing = false), ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x2000), ETraceReference.address(source, 0x2004, 0x2000),
        ETraceReference.support(source, status = 1, tracing = false)))

      initialize()
      send(Seq(Block(0x1000, halfwords = 5, lastSize = 0)))
      d.io.block.valid.poke(false.B); write(1)
      check("多指令 block 停止时完整覆盖压缩尾指令", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000), ETraceReference.address(source, 0x1008, 0x1000),
        ETraceReference.support(source, status = 1, tracing = false)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 5), Block(0x1008, 4),
        Block(0x100c, qualified = false), Block(0x2000), Block(0x2004)))
      check("过滤间隙：清空分支、只发一次 support、恢复后重新同步", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1008, 0x1000, Seq(true, false)),
        ETraceReference.support(source, status = 1), ETraceReference.synchronization(source, 0x2000)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 8), Block(0x1000), Block(0x1004, 8, qualified = false)))
      check("跳转回先前锚点后结束：updiscon 消歧，使用 ended_rep", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1000, 0x1000, beforeSync = true),
        ETraceReference.support(source, status = 1)))

      initialize()
      send(Seq(Block(0x1000)))
      d.io.block.valid.poke(false.B); d.io.packet.ready.poke(false.B)
      d.io.stop.poke(true.B); tick(); d.io.stop.poke(false.B)
      idle(4)
      d.io.empty.expect(false.B)
      d.io.packet.ready.poke(true.B)
      check("单周期 halt/reset 请求在反压期间保留且不伪造后继 PC", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000), ETraceReference.support(source, status = 1)))
      d.io.empty.expect(true.B)

      initialize()
      send(Seq(Block(0x1000), Block(0x1004)))
      d.io.block.valid.poke(false.B)
      d.io.features.poke(((source << 16) | 1).U)
      idle(10)
      send(Seq(Block(0x2000), Block(0x2004, trigger = 3), Block(0x2008)))
      check("配置切换：旧差分先结束，再报告新模式并重新同步", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000), ETraceReference.address(source, 0x1004, 0x1000),
        ETraceReference.support(source, fullAddress = true, status = 1),
        ETraceReference.synchronization(source, 0x2000),
        ETraceReference.address(source, 0x2004, 0x2000, notifyRequested = true, fullAddress = true)))

      initialize(tracing = false, triggers = true)
      send(Seq(Block(0x4000, halfwords = 3, lastSize = 0, trigger = 1, both = true)))
      check("trace-on/off 同周期：只跟踪该 block 的首尾", Seq(ETraceReference.support(source, tracing = false),
        ETraceReference.support(source), ETraceReference.synchronization(source, 0x4000),
        ETraceReference.address(source, 0x4004, 0x4000), ETraceReference.support(source, status = 1, tracing = false)))
      ((d.io.control.peek().litValue >> 2) & 1) shouldBe BigInt(0)

      initialize(tracing = false, triggers = true)
      send(Seq(Block(0x5000, halfwords = 5, lastSize = 0, trigger = 1, notifyRequested = true), Block(0x500a)))
      check("trace-on 与 notify 同时发生，尾部通知仍被报告", Seq(ETraceReference.support(source, tracing = false),
        ETraceReference.support(source), ETraceReference.synchronization(source, 0x5000),
        ETraceReference.address(source, 0x5008, 0x5000, notifyRequested = true)))

      initialize(triggers = true)
      send(Seq(Block(0x1000), Block(0x1004, trigger = 2), Block(0x1008, trigger = 1),
        Block(0x100c, trigger = 3), Block(0x1010)))
      check("trace-off 后紧接 trace-on：连续跟踪不中断", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x100c, 0x1000, notifyRequested = true)))

      initialize(tracing = false, triggers = true)
      send(Seq(Block(0, halfwords = 0, lastSize = 0, trigger = 1), Block(0x1000),
        Block(0x1004), Block(0, halfwords = 0, lastSize = 0, trigger = 2)))
      check("无退休指令的触发事件不产生地址 0 的假指令", Seq(ETraceReference.support(source, tracing = false),
        ETraceReference.support(source), ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1004, 0x1000), ETraceReference.support(source, status = 1, tracing = false)))

      initialize(tracing = false, triggers = true)
      send(Seq(Block(0, halfwords = 0, lastSize = 0, trigger = 1, both = true)))
      check("空周期 trace-on/off 重合：不伪造一次启用的指令", Seq(ETraceReference.support(source, tracing = false)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 5), Block(0x1000), Block(0x1004, 5),
        Block(0x1000), Block(0x1004, 4), Block(0x1008, 8, halfwords = 4),
        Block(0x2000, halfwords = 3, lastSize = 0, trigger = 3), Block(0x2006, 13),
        Block(0x0ff0, trigger = 3), Block(0x0ff4)))
      d.io.block.valid.poke(false.B); write(1); idle(12)
      write(7); idle(6)
      send(Seq(Block(0x3000), Block(0x3004)))
      d.io.block.valid.poke(false.B); write(1); idle(12)
      d.io.empty.expect(true.B)
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val directory = workspace.resolve(Paths.get("build", "validation"))
      Files.createDirectories(directory)
      Files.write(directory.resolve("packetgen-lifecycle.bin"), frames.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x3020 - 0xff0)(0)
      def put(pc: Int, instruction: Long, size: Int = 4): Unit =
        for (i <- 0 until size) code(pc - 0xff0 + i) = ((instruction >> (8 * i)) & 255).toByte
      put(0xff0, 0x13); put(0xff4, 0x13)
      put(0x1000, 0xfff08093L); put(0x1004, 0xfe009ee3L); put(0x1008, 0x13); put(0x100c, 0x280e7)
      put(0x2000, 0x10b7); put(0x2004, 0x10c1, 2); put(0x2006, 0x8067)
      put(0x3000, 0x13); put(0x3004, 0x13)
      Files.write(directory.resolve("packetgen-lifecycle-code.bin"), code)
      val config = """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-lifecycle-code.bin","base":"0xff0","xlen":64}}}"""
      Files.write(directory.resolve("packetgen-lifecycle.json"), config.getBytes(StandardCharsets.UTF_8))
      val expected = """[4096,4100,4096,4100,4096,4100,4104,4108,8192,8196,8198,4080,4084,12288,12292]"""
      Files.write(directory.resolve("packetgen-lifecycle-expected.json"), expected.getBytes(StandardCharsets.UTF_8))
      println("[lifecycle-golden] 已导出包含两次完整停止/重启的实际 RTL capture，供 decoder 校验全部 15 条退休指令")
    }
  }
}
