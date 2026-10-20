package trace.component

import chisel3._
import chiseltest._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

class PacketEncodingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Block(pc: Int, kind: Int = 0, halfwords: Int = 2, lastSize: Int = 1,
      notifyRequested: Boolean = false)
  behavior of "PacketGen normative packet golden"

  it should "encode real branch history, differential anchors, block tails and resynchronization" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val packets = ArrayBuffer[Seq[Int]]()
      var cycles = 0
      var held = Option.empty[Seq[Int]]
      val source = 3
      def drive(block: Block): Unit = {
        d.io.block.valid.poke(true.B)
        d.io.block.iaddr.poke(block.pc.U)
        d.io.block.itype.poke(block.kind.U)
        d.io.block.iretire.poke(block.halfwords.U)
        d.io.block.ilastsize.poke(block.lastSize.U)
        d.io.block.trigger.poke((if (block.notifyRequested) 3 else 0).U)
      }
      def setReady(): Unit = d.io.packet.ready.poke((cycles % 7 != 2 && cycles % 7 != 3).B)
      def tick(): Unit = {
        val valid = d.io.packet.valid.peek().litToBoolean
        val ready = d.io.packet.ready.peek().litToBoolean
        val frame = if (valid) {
          val length = d.io.packet.bits.len.peek().litValue.toInt
          length should be >= 3
          d.io.packet.bits.data.take(length).map(_.peek().litValue.toInt)
        } else Seq.empty
        held.foreach { expected => valid shouldBe true; frame shouldBe expected }
        held = if (valid && !ready) Some(frame) else None
        if (valid && ready) packets += frame
        d.clock.step()
        cycles += 1
      }
      def idle(count: Int, requestSync: Boolean = false): Unit = {
        d.io.block.valid.poke(false.B)
        for (i <- 0 until count) {
          d.io.syncReq.poke((requestSync && i == 0).B)
          setReady(); tick()
        }
        d.io.syncReq.poke(false.B)
        d.io.flushReq.poke(false.B)
        d.io.stopReq.poke(false.B)
        d.io.block.traceOffAfter.poke(false.B)
      d.io.block.notifyRequested.poke(false.B)
      }
      def send(blocks: Seq[Block]): Unit = blocks.foreach { block =>
        drive(block)
        var accepted = false
        var budget = 100
        while (!accepted && budget > 0) {
          setReady()
          accepted = d.io.blockReady.peek().litToBoolean
          tick()
          budget -= 1
        }
        assert(accepted, s"input did not advance at PC 0x${block.pc.toHexString}")
      }
      def initialize(fullAddress: Boolean = false, syncMax: Int = 15): Unit = {
        packets.clear(); held = None; cycles = 0
        d.io.syncReq.poke(false.B)
        d.io.filterRes.poke(true.B)
        d.io.blockBufferIsFull.poke(false.B)
        d.io.fifoIsFull.poke(false.B)
        d.io.teCtrl.poke(0.U)
        d.io.teInstFeat.poke(((source << 16) | (if (fullAddress) 1 else 0)).U)
        d.io.teDataCtrl.poke(0.U)
        d.io.packet.ready.poke(true.B)
        d.io.block.valid.poke(false.B)
        d.io.block.priv.poke(Priv.M)
        d.io.block.time.poke(0.U)
        d.io.block.context.poke(0.U)
        d.io.block.ctype.poke(Ctype.None)
        d.io.block.cause.poke(0.U)
        d.io.block.tval.poke(0.U)
        d.io.block.impdef.poke(0.U)
        d.io.block.hartIsReset.poke(false.B)
        drive(Block(0)); d.io.block.valid.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        d.io.teCtrl.poke((6 | (syncMax << 20)).U)
        idle(6)
        packets.toSeq shouldBe Seq(ETraceReference.support(source, fullAddress))
      }
      def check(name: String, expected: Seq[Seq[Int]]): Unit = {
        idle(8)
        withClue(s"$name packets: ") { packets.toSeq shouldBe expected }
        println(s"[encoder-golden] $name：${packets.size} 个 packet 逐字节一致，包含输出反压")
      }

      for (branches <- Seq(1, 2, 3, 4, 7, 8, 15, 16, 30, 31, 32, 62)) {
        initialize()
        val outcomes = Seq.tabulate(branches)(_ % 3 != 0)
        val history = outcomes.zipWithIndex.map { case (taken, i) =>
          Block(0x1004 + 4 * i, if (taken) 5 else 4)
        }
        val anchor = 0x1004 + 4 * branches
        send(Seq(Block(0x1000)) ++ history ++ Seq(Block(anchor, notifyRequested = true), Block(anchor + 4)))
        val complete = outcomes.grouped(31).filter(_.size == 31).toSeq.map(ETraceReference.branchOnly(source, _))
        val remaining = outcomes.drop(complete.size * 31)
        check(s"$branches 个 taken/non-taken 分支", Seq(ETraceReference.support(source),
          ETraceReference.synchronization(source, 0x1000)) ++ complete ++
          Seq(ETraceReference.address(source, anchor, 0x1000, remaining, notifyRequested = true)))
      }

      initialize()
      send(Seq(Block(0x1000, 5), Block(0x1004, 4), Block(0x1008, notifyRequested = true), Block(0x100c)))
      check("同步包首指令为 taken branch，不重复加入 map", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000, taken = true),
        ETraceReference.address(source, 0x1008, 0x1000, Seq(false), notifyRequested = true)))

      initialize()
      send(Seq(Block(0x1000, 5, halfwords = 3, lastSize = 0),
        Block(0x1100, notifyRequested = true), Block(0x1104)))
      check("block 首指令同步，尾部 taken branch 留待后续报告", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1100, 0x1000, Seq(true), notifyRequested = true)))

      initialize()
      val fullMap = Seq.tabulate(31)(_ % 2 == 0)
      send(Seq(Block(0x1000)) ++ fullMap.zipWithIndex.map { case (taken, i) =>
        Block(0x1004 + i * 4, if (taken) 5 else 4, notifyRequested = i == 30)
      } ++ Seq(Block(0x1080)))
      check("第 31 个分支同时 notify，使用带地址 branches=31", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x107c, 0x1000, fullMap, notifyRequested = true)))

      initialize()
      send(Seq(Block(0x1000, halfwords = 5, lastSize = 0, notifyRequested = true),
        Block(0x100a, halfwords = 3, lastSize = 1, notifyRequested = true), Block(0x1010)))
      check("压缩/普通指令 block 的 notify 尾地址和参考更新", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1008, 0x1000, notifyRequested = true),
        ETraceReference.address(source, 0x100c, 0x1008, notifyRequested = true)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 5), Block(0x1008, 4), Block(0x100c, 5)))
      idle(6, requestSync = true)
      send(Seq(Block(0x1010), Block(0x1014, 4), Block(0x1018, notifyRequested = true), Block(0x101c)))
      check("SYNCREQ 先清空未报告分支，再发 Format 30", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x100c, 0x1000, Seq(true, false, true)),
        ETraceReference.synchronization(source, 0x1010),
        ETraceReference.address(source, 0x1018, 0x1010, Seq(false), notifyRequested = true)))

      initialize()
      send(Seq(Block(0x1000), Block(0x1004, 5), Block(0x1008, 8), Block(0x2000)))
      idle(6, requestSync = true)
      send(Seq(Block(0x2004), Block(0x2008)))
      check("间接跳转目标后立即重同步，updiscon 与 notify 相反", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x2000, 0x1000, Seq(true), beforeSync = true),
        ETraceReference.synchronization(source, 0x2004)))

      for (limit <- Seq(0, 15)) {
        initialize(syncMax = limit)
        send(Seq(Block(0x1000)) ++ (1 to 20).map { i =>
          Block(0x1000 + 4 * i, notifyRequested = true)
        } ++ Seq(Block(0x1054)))
        var reference = 0x1000
        val anchors = (1 to 20).map { i =>
          val pc = 0x1000 + 4 * i
          val packet = if (limit == 0 && i == 17) ETraceReference.synchronization(source, pc)
            else ETraceReference.address(source, pc, reference, notifyRequested = true)
          reference = pc
          packet
        }
        check(s"InstSyncMax=$limit 的 2^(value+4) 阈值与重新建立地址参考",
          Seq(ETraceReference.support(source), ETraceReference.synchronization(source, 0x1000)) ++ anchors)
      }

      initialize(fullAddress = true)
      send(Seq(Block(0x1000), Block(0x1004, notifyRequested = true),
        Block(0x1008, 8), Block(0x0ff0), Block(0x0ff4)))
      check("FullAddr 模式和负向跳转目标", Seq(ETraceReference.support(source, fullAddress = true),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x1004, 0x1000, notifyRequested = true, fullAddress = true),
        ETraceReference.address(source, 0x0ff0, 0x1004, fullAddress = true)))

      initialize()
      val execution = Seq(Block(0x1000), Block(0x1004, 5), Block(0x1000), Block(0x1004, 5),
        Block(0x1000), Block(0x1004, 4), Block(0x1008, 8, halfwords = 4),
        Block(0x2000, halfwords = 3, lastSize = 0, notifyRequested = true),
        Block(0x2006, 13), Block(0x0ff0, notifyRequested = true), Block(0x0ff4))
      send(execution)
      check("执行流：循环/间接调用/压缩尾指令/返回负向目标", Seq(ETraceReference.support(source),
        ETraceReference.synchronization(source, 0x1000),
        ETraceReference.address(source, 0x2000, 0x1000, Seq(true, true, false)),
        ETraceReference.address(source, 0x2004, 0x2000, notifyRequested = true),
        ETraceReference.address(source, 0x0ff0, 0x2004)))

      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val directory = workspace.resolve(Paths.get("build", "validation"))
      Files.createDirectories(directory)
      Files.write(directory.resolve("packetgen-execution.bin"), packets.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x2020 - 0x0ff0)(0)
      def put(pc: Int, instruction: Long, size: Int = 4): Unit =
        for (i <- 0 until size) code(pc - 0x0ff0 + i) = ((instruction >> (8 * i)) & 255).toByte
      put(0x0ff0, 0x13); put(0x0ff4, 0x13)
      // Initial x1=3, x5=0x2000: decrement loop, call, LUI/C.ADDI changes ra,
      // then return to 0xff0. 0xff4 is lookahead beyond the last trace anchor.
      put(0x1000, 0xfff08093L); put(0x1004, 0xfe009ee3L); put(0x1008, 0x13); put(0x100c, 0x000280e7)
      put(0x2000, 0x000010b7); put(0x2004, 0x10c1, 2); put(0x2006, 0x00008067)
      Files.write(directory.resolve("packetgen-execution-code.bin"), code)
      val config = """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-execution-code.bin","base":"0xff0","xlen":64}}}"""
      Files.write(directory.resolve("packetgen-execution.json"), config.getBytes(StandardCharsets.UTF_8))
      val expected = """[4096,4100,4096,4100,4096,4100,4104,4108,8192,8196,8198,4080]"""
      Files.write(directory.resolve("packetgen-execution-expected.json"), expected.getBytes(StandardCharsets.UTF_8))
      println(s"[encoder-golden] 真实 RTL 执行流采集已写入 $directory/packetgen-execution.bin，下一步交给 decoder")
    }
  }
}
