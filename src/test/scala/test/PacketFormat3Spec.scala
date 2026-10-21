package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

class PacketFormat3Spec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Entry(pc: BigInt, kind: Int = 0, retired: Int = 2, size: Int = 1,
      privilege: Int = 3, time: BigInt = 0, context: BigInt = 0, ctype: Int = 0,
      cause: Int = 0, tval: BigInt = 0, notifyRequested: Boolean = false)

  private class Stream(d: PacketGenHarness) {
    val frames = ArrayBuffer.empty[Seq[Int]]
    var cycles = 0
    var held = Option.empty[(BigInt, Seq[BigInt])]
    def tick(): Unit = {
      val valid = d.io.packet.valid.peek().litToBoolean
      val ready = d.io.packet.ready.peek().litToBoolean
      val bits = (d.io.packet.bits.len.peek().litValue, d.io.packet.bits.data.map(_.peek().litValue).toSeq)
      held.foreach { previous => valid shouldBe true; bits shouldBe previous }
      held = if (valid && !ready) Some(bits) else None
      if (valid && ready) frames += bits._2.take(bits._1.toInt).map(_.toInt)
      d.clock.step(); cycles += 1
    }
    def drive(e: Entry): Unit = {
      d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(e.pc.U)
      d.io.block.itype.poke(e.kind.U); d.io.block.iretire.poke(e.retired.U)
      d.io.block.ilastsize.poke(e.size.U); d.io.block.priv.poke(e.privilege.U)
      d.io.block.time.poke(e.time.U); d.io.block.context.poke(e.context.U); d.io.block.ctype.poke(e.ctype.U)
      d.io.block.cause.poke(e.cause.U); d.io.block.tval.poke(e.tval.U)
      d.io.block.notifyRequested.poke(e.notifyRequested.B)
    }
    def idle(n: Int): Unit = {
      d.io.block.valid.poke(false.B)
      for (_ <- 0 until n) { d.io.packet.ready.poke((cycles % 9 > 2).B); tick() }
    }
    def initialize(syncMax: Int = 15): Unit = {
      frames.clear(); held = None; cycles = 0
      d.io.teCtrl.poke((6 | (syncMax << 20)).U); d.io.teInstFeat.poke((3 << 16).U)
      d.io.teDataCtrl.poke(0.U); d.io.filterRes.poke(true.B); d.io.fifoIsFull.poke(false.B)
      d.io.syncReq.poke(false.B); d.io.flushReq.poke(false.B); d.io.stopReq.poke(false.B)
      d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
      d.io.block.traceLost.poke(false.B); d.io.block.hartIsReset.poke(false.B); d.io.block.impdef.poke(0.U)
      drive(Entry(0)); d.io.block.valid.poke(false.B); d.io.packet.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); idle(8)
      frames.toSeq shouldBe Seq(ETraceReference.support(3))
    }
    def send(entries: Seq[Entry]): Unit = entries.foreach { e =>
      drive(e)
      var accepted = false
      var remaining = 160
      while (!accepted && remaining > 0) {
        d.io.packet.ready.poke((cycles % 9 > 2).B)
        accepted = d.io.blockReady.peek().litToBoolean
        tick(); remaining -= 1
      }
      assert(accepted, s"Format3 input timeout at 0x${e.pc.toString(16)}")
    }
    def stop(): Unit = {
      d.io.block.valid.poke(false.B); d.io.stopReq.poke(true.B); tick(); d.io.stopReq.poke(false.B)
      idle(24); d.io.empty.expect(true.B)
    }
  }

  behavior of "Format3 normative field ownership"
  it should "preserve high address time context and the synchronization instruction's branch under stalls" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      println("[Format3][1/3] 复位/配置→输入同步指令及notify→输出反压→停止排空→逐字节golden比较")
      val stream = new Stream(d)
      val mask = ETraceReference.Mask
      val cases = Seq(
        (BigInt(0x1000), BigInt(0), BigInt(0)),
        ((BigInt(1) << 48) - 16, (BigInt(1) << 63) - 1, BigInt("7fffffff", 16)),
        ((BigInt(1) << 49) + 0x1000, BigInt(1) << 63, BigInt("80000000", 16)),
        (mask - 15, (BigInt(1) << 64) - 1, BigInt("ffffffff", 16)))
      for (((pc, time, context), index) <- cases.zipWithIndex; kind <- Seq(0, 4, 5)) {
        stream.initialize()
        val priv = Seq(0, 1, 5, 6)(index)
        stream.send(Seq(Entry(pc, kind = kind, privilege = priv, time = time, context = context),
          Entry(pc + 4, privilege = priv, time = time, context = context, notifyRequested = true),
          Entry(pc + 8, privilege = priv, time = time, context = context)))
        stream.stop()
        withClue(s"sync vector $index kind=$kind: ") {
          stream.frames.toSeq shouldBe Seq(ETraceReference.support(3),
            ETraceReference.synchronization(3, pc, taken = kind == 5, privilege = priv, time = time, context = context),
            ETraceReference.address(3, pc + 4, pc, notifyRequested = true),
            ETraceReference.address(3, pc + 8, pc + 4), ETraceReference.support(3, status = 1))
        }
      }
      println("[Format3][1/3] Format30的12组通过：高位地址/time/context/branch完整，首branch不重复入map")
    }
  }

  it should "report handler metadata and branch once while omitting interrupt tval at both address signs" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      println("[Format3][2/3] 输入旧执行段→exception/interrupt→handler首指令→notify→停止，检查trap字段归属")
      val stream = new Stream(d)
      val handlerTime = BigInt("fedcba9876543210", 16)
      val handlerContext = BigInt("deadbeef", 16)
      val trapValue = (BigInt(1) << 50) - 3
      for (negative <- Seq(false, true); interrupt <- Seq(false, true); kind <- Seq(0, 4, 5)) {
        stream.initialize()
        val handler = (if (negative) BigInt(1) << 49 else BigInt(0)) + 0x2000
        stream.send(Seq(Entry(0x1000, time = 7, context = 0x1234),
          Entry(0x1004, kind = if (interrupt) 2 else 1, retired = 0, cause = 255, tval = trapValue,
            time = 11, context = 0x1234),
          Entry(handler, kind = kind, privilege = 6, time = handlerTime, context = handlerContext),
          Entry(handler + 4, privilege = 6, time = handlerTime, context = handlerContext, notifyRequested = true),
          Entry(handler + 8, privilege = 6, time = handlerTime, context = handlerContext)))
        stream.stop()
        withClue(s"trap addressSign=$negative interrupt=$interrupt kind=$kind: ") {
          stream.frames.toSeq shouldBe Seq(ETraceReference.support(3),
            ETraceReference.synchronization(3, 0x1000, time = 7, context = 0x1234),
            ETraceReference.trap(3, handler, 255, trapValue, interrupt = interrupt, privilege = 6,
              time = handlerTime, context = handlerContext, taken = kind == 5),
            ETraceReference.address(3, handler + 4, handler, notifyRequested = true),
            ETraceReference.address(3, handler + 8, handler + 4), ETraceReference.support(3, status = 1))
        }
      }
      println("[Format3][2/3] Format31的12组通过：handler元数据/branch与原trap cause/tval独立，interrupt省略tval")
    }
  }

  it should "drain pending branches before a periodic sync triggered by an imprecise-context packet" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      println("[Format3][3/3] 15个地址包→积累分支→Format32达到周期阈值→先报告旧map→下一条Format30")
      val stream = new Stream(d)
      for (pending <- Seq(1, 3, 7, 15, 30)) {
        stream.initialize(syncMax = 0)
        val notified = (1 to 15).map(i => Entry(0x1000 + i * 4, notifyRequested = true))
        val branches = (0 until pending).map(i => Entry(0x1040 + i * 4, kind = if (i % 2 == 0) 5 else 4))
        val changed = BigInt(0x1040 + pending * 4)
        stream.send(Seq(Entry(0x1000)) ++ notified ++ branches ++ Seq(
          Entry(changed, ctype = 1, context = 0x12345678, time = 0x55),
          Entry(changed + 4, kind = 5, context = 0x12345678, time = 0x66),
          Entry(changed + 8, context = 0x12345678, notifyRequested = true),
          Entry(changed + 12, context = 0x12345678)))
        stream.stop()
        val prefix = Seq(ETraceReference.support(3), ETraceReference.synchronization(3, 0x1000)) ++
          (1 to 15).map(i => ETraceReference.address(3, 0x1000 + i * 4, 0x1000 + (i - 1) * 4, notifyRequested = true))
        withClue(s"periodic resync with $pending pending branches: ") {
          stream.frames.toSeq shouldBe prefix ++ Seq(
            ETraceReference.context(3, 0x12345678, time = 0x55),
            ETraceReference.address(3, changed, 0x103c, (0 until pending).map(_ % 2 == 0)),
            ETraceReference.synchronization(3, changed + 4, taken = true, context = 0x12345678, time = 0x66),
            ETraceReference.address(3, changed + 8, changed + 4, notifyRequested = true),
            ETraceReference.address(3, changed + 12, changed + 8), ETraceReference.support(3, status = 1))
        }
      }
      println("[Format3][3/3] 5组周期同步通过：1/3/7/15/30个旧分支均先排空，再报告新branch同步，反压字节稳定")
    }
  }
}
