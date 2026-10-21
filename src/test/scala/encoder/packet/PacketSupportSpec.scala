package trace.component

import chisel3._
import chiseltest._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

class PacketSupportSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Entry(pc: Int, kind: Int = 0, lost: Boolean = false, notification: Boolean = false)

  private class Stream(d: PacketGenHarness) {
    val frames = ArrayBuffer.empty[Seq[Int]]
    private var held = Option.empty[(BigInt, Seq[BigInt])]
    var stalledCycles = 0

    def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
      val valid = d.io.packet.valid.peek().litToBoolean
      val ready = d.io.packet.ready.peek().litToBoolean
      val bits = (d.io.packet.bits.len.peek().litValue, d.io.packet.bits.data.map(_.peek().litValue).toSeq)
      held.foreach { previous => valid shouldBe true; bits shouldBe previous }
      held = if (valid && !ready) Some(bits) else None
      if (valid && !ready) stalledCycles += 1
      if (valid && ready) frames += bits._2.take(bits._1.toInt).map(_.toInt)
      d.clock.step()
    }

    def drive(e: Entry): Unit = {
      d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(e.pc.U); d.io.block.itype.poke(e.kind.U)
      d.io.block.iretire.poke((if (e.lost) 0 else 2).U); d.io.block.ilastsize.poke(Ilastsize.Word)
      d.io.block.traceLost.poke(e.lost.B); d.io.block.notifyRequested.poke(e.notification.B)
    }

    def drain(n: Int = 16): Unit = {
      d.io.block.valid.poke(false.B); d.io.packet.ready.poke(true.B); d.io.fifoIsFull.poke(false.B)
      tick(n)
    }

    def pause(n: Int): Unit = {
      d.io.block.valid.poke(false.B); d.io.packet.ready.poke(false.B); d.io.fifoIsFull.poke(true.B)
      tick(n)
    }

    def initialize(stallStartup: Boolean = false): Unit = {
      frames.clear(); held = None; stalledCycles = 0
      d.io.teCtrl.poke((6 | (15 << 20)).U); d.io.teInstFeat.poke((3 << 16).U)
      d.io.teDataCtrl.poke(0.U); d.io.filterRes.poke(true.B); d.io.fifoIsFull.poke(false.B)
      d.io.syncReq.poke(false.B); d.io.flushReq.poke(false.B); d.io.stopReq.poke(false.B)
      d.io.block.priv.poke(Priv.M); d.io.block.time.poke(0.U); d.io.block.context.poke(0.U)
      d.io.block.ctype.poke(Ctype.None); d.io.block.cause.poke(0.U); d.io.block.tval.poke(0.U)
      d.io.block.impdef.poke(0.U); d.io.block.hartIsReset.poke(false.B)
      d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
      drive(Entry(0)); d.io.block.valid.poke(false.B); d.io.packet.ready.poke((!stallStartup).B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); tick(8)
      if (stallStartup) {
        frames shouldBe empty
        d.io.packet.valid.expect(true.B)
      } else frames.toSeq shouldBe Seq(ETraceReference.support(3))
    }

    def send(entries: Seq[Entry]): Unit = {
      d.io.packet.ready.poke(true.B); d.io.fifoIsFull.poke(false.B)
      entries.foreach { e =>
        drive(e)
        var accepted = false
        var remaining = 100
        while (!accepted && remaining > 0) {
          accepted = d.io.blockReady.peek().litToBoolean
          tick(); remaining -= 1
        }
        assert(accepted, s"support input timeout at 0x${e.pc.toHexString}")
      }
      d.io.block.valid.poke(false.B)
    }

    def stop(): Unit = {
      d.io.block.valid.poke(false.B); d.io.stopReq.poke(true.B); tick(); d.io.stopReq.poke(false.B)
      drain(); d.io.empty.expect(true.B)
    }
  }

  private def capture(name: String, frames: Seq[Seq[Int]]): Unit = {
    val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
      Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
        .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
    val out = workspace.resolve(Paths.get("build", "validation")); Files.createDirectories(out)
    val stem = s"packetgen-support-$name"
    Files.write(out.resolve(s"$stem.bin"), frames.flatten.map(_.toByte).toArray)
    val code = Array.fill[Byte](0x100c)(0)
    for (pc <- Seq(0x1000, 0x2000, 0x2004, 0x2008)) code(pc - 0x1000) = 0x13 // addi x0,x0,0
    code(4) = 0x63; code(5) = 2 // beq x0,x0,+4; the target belongs to the lost interval
    Files.write(out.resolve(s"$stem-code.bin"), code)
    val config = s"""{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"$stem-code.bin","base":"0x1000","xlen":64}}}"""
    Files.write(out.resolve(s"$stem.json"), config.getBytes(StandardCharsets.UTF_8))
    Files.write(out.resolve(s"$stem-expected.json"), "[4096,4100,8192,8196,8200]".getBytes(StandardCharsets.UTF_8))
  }

  behavior of "Format33 event ordering"

  it should "retain startup support and stalled packets without inventing trace loss" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      println("[support][1/2] 阻塞启动support→核输入等待→排出support及首次同步→阻塞分支地址包→停止")
      val stream = new Stream(d)
      for (stall <- Seq(1, 7, 19)) {
        stream.initialize(stallStartup = true)
        stream.drive(Entry(0x1000))
        d.io.blockReady.expect(false.B)
        stream.tick(3)
        stream.send(Seq(Entry(0x1000), Entry(0x1004, kind = 5), Entry(0x1008, notification = true), Entry(0x100c)))
        d.io.packet.valid.expect(true.B)
        stream.pause(stall); d.io.empty.expect(false.B)
        stream.drain(); stream.stop()
        stream.stalledCycles should be >= stall + 3
        withClue(s"backpressure $stall cycles: ") {
          stream.frames.toSeq shouldBe Seq(ETraceReference.support(3), ETraceReference.synchronization(3, 0x1000),
            ETraceReference.address(3, 0x1008, 0x1000, Seq(true), notifyRequested = true),
            ETraceReference.address(3, 0x100c, 0x1008), ETraceReference.support(3, status = 1))
        }
      }
      println("[support][1/2] 3组通过：启动support先于Format30，FIFO满/反压保持原包，不生成trace_lost")
    }
  }

  it should "report loss with old options before pending configuration and restart from synchronization" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      println("[support][2/2] 旧分支地址包反压→loss与配置/stop重合→旧配置loss→新配置support→新段同步")
      val stream = new Stream(d)
      val changes = Seq(("unchanged", 6, false), ("full-address", 6, true),
        ("instruction-off", 2, false), ("encoder-off", 0, false))
      for ((name, control, fullAddress) <- changes) {
        for (stop <- Seq(false, true); stall <- Seq(1, 11)) {
          stream.initialize()
          stream.send(Seq(Entry(0x1000), Entry(0x1004, kind = 5), Entry(0, lost = true)))
          d.io.packet.valid.expect(true.B)
          stream.pause(1)
          d.io.teCtrl.poke((control | (15 << 20)).U)
          d.io.teInstFeat.poke(((3 << 16) | (if (fullAddress) 1 else 0)).U)
          d.io.stopReq.poke(stop.B)
          stream.pause(stall); d.io.stopReq.poke(false.B); stream.drain()
          d.io.empty.expect(true.B)
          val tracing = control == 6
          val old = Seq(ETraceReference.support(3), ETraceReference.synchronization(3, 0x1000),
            ETraceReference.address(3, 0x1004, 0x1000, Seq(true)), ETraceReference.support(3, status = 2))
          val changed = if (name == "unchanged") Seq.empty else
            Seq(ETraceReference.support(3, fullAddress = fullAddress, tracing = tracing))
          withClue(s"loss/config=$name stop=$stop stall=$stall: ") { stream.frames.toSeq shouldBe old ++ changed }
          stream.drain(10)
          stream.frames.toSeq shouldBe old ++ changed
          val restarted = if (tracing) Seq.empty else {
            d.io.teCtrl.poke((6 | (15 << 20)).U); stream.drain()
            Seq(ETraceReference.support(3, fullAddress = fullAddress))
          }
          stream.send(Seq(Entry(0x2000), Entry(0x2004, notification = true), Entry(0x2008)))
          stream.stop()
          withClue(s"restart after config=$name stop=$stop stall=$stall: ") {
            stream.frames.toSeq shouldBe old ++ changed ++ restarted ++ Seq(
              ETraceReference.synchronization(3, 0x2000),
              ETraceReference.address(3, 0x2004, 0x2000, notifyRequested = true, fullAddress = fullAddress),
              ETraceReference.address(3, 0x2008, 0x2004, fullAddress = fullAddress),
              ETraceReference.support(3, fullAddress = fullAddress, status = 1))
          }
        }
        capture(name, stream.frames.toSeq)
        println(s"[support][2/2] $name 的4组通过：loss保留旧options，stop不伪造尾PC，新段不继承旧分支/reference")
      }
    }
  }
}
