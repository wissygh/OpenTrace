package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface.TraceBeat
import scala.collection.mutable.ArrayBuffer

private class FunnelDrainHarness extends Module {
  val io = IO(new Bundle {
    val in = Vec(2, Flipped(Decoupled(new PacketEnq(8))))
    val out = Decoupled(new TraceBeat(4))
    val enable = Input(Bool())
    val flush = Input(Bool())
    val ack = Output(Bool())
    val empty = Output(Bool())
    val sourceReq = Output(Vec(2, Bool()))
    val sourceActive = Input(Vec(2, Bool()))
  })
  val core = Module(new TraceFunnelCore(2))
  core.io.enable := io.enable; core.io.quiesce := false.B
  core.io.disable.foreach(_ := false.B)
  core.io.control.flushReq := io.flush; core.io.control.syncReq := false.B
  io.ack := core.io.control.flushAck; io.empty := core.io.empty
  io.out <> core.io.out
  for (i <- 0 until 2) {
    val sourceReset = (reset.asBool || !io.sourceActive(i)).asAsyncReset
    val fifo = withReset(sourceReset) { Module(new Fifo(FifoParam(true, 64, 8, 4))) }
    val link = Module(new TraceSourceLink(i))
    fifo.io.packetEnq <> io.in(i)
    link.io.sourceClock := clock; link.io.sinkClock := clock
    link.io.sourceReset := sourceReset; link.io.sinkReset := reset.asAsyncReset
    link.io.systemSinkReset := reset.asAsyncReset
    link.io.in <> fifo.io.atbm; fifo.io.control <> link.io.sourceControl
    core.io.in(i) <> link.io.out; link.io.control <> core.io.sourceControl(i)
    io.sourceReq(i) := fifo.io.control.flushReq
  }
}

class FunnelDrainSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Funnel software Disable flush"
  for (external <- Seq(false, true)) {
    it should s"drain all old source frontiers and serialize overlapping external flush=$external" in {
      test(new FunnelDrainHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
        d.io.in.foreach { in =>
          in.valid.poke(false.B); in.bits.len.poke(1.U); in.bits.data.foreach(_.poke(0.U))
        }
        d.io.enable.poke(true.B); d.io.flush.poke(false.B); d.io.out.ready.poke(false.B)
        d.io.sourceActive.foreach(_.poke(true.B))
        d.reset.poke(true.B); d.clock.step(4); d.reset.poke(false.B); d.clock.step(70)
        val received = ArrayBuffer.empty[Int]
        def tick(): Unit = {
          if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
            val data = d.io.out.bits.data.peek().litValue
            for (i <- 0 to d.io.out.bits.bytes.peek().litValue.toInt) received += ((data >> (8 * i)) & 255).toInt
          }
          d.clock.step()
        }
        def until(description: String)(condition: => Boolean): Unit = {
          var timeout = 0
          while (!condition && timeout < 400) { tick(); timeout += 1 }
          assert(timeout < 400, description)
        }
        def send(source: Int, packet: Seq[Int]): Unit = {
          val in = d.io.in(source)
          in.bits.len.poke(packet.size.U)
          in.bits.data.zipWithIndex.foreach { case (b, i) => b.poke(packet.lift(i).getOrElse(0).U) }
          in.valid.poke(true.B); until("packet enqueue") { in.ready.peek().litToBoolean }; tick()
          in.valid.poke(false.B)
        }
        val old0 = (0xa0 until 0xa6)
        val old1 = (0xb0 until 0xb5)
        val post0 = (0xc0 until 0xc4)
        val post1 = (0xd0 until 0xd4)
        send(0, old0); send(1, old1); for (_ <- 0 until 30) tick()
        d.io.enable.poke(false.B)
        until("Disable propagates to every real FIFO") { d.io.sourceReq.forall(_.peek().litToBoolean) }
        println(s"[Funnel-drain][1/4] Enable=0自动请求两源旧前沿，仍受sink反压；external=$external")
        for (_ <- 0 until 20) tick() // snapshot and ordered markers precede the following enqueues
        send(0, post0); send(1, post1)
        if (external) d.io.flush.poke(true.B)
        for (_ <- 0 until 15) { d.io.empty.expect(false.B); d.io.ack.expect(false.B); tick() }
        d.io.out.ready.poke(true.B)
        if (external) {
          until("external AF gets its fresh frontier after automatic release") { d.io.ack.peek().litToBoolean }
          received.size shouldBe 19
          d.io.flush.poke(false.B)
        }
        until("software Disable is Empty") { d.io.empty.peek().litToBoolean }
        val old = received.take(11).toSeq
        Set(old0 ++ old1, old1 ++ old0) should contain(old)
        if (!external) received.size shouldBe 11
        println("[Funnel-drain][2/4] Enable已清0也能完整排空两个旧packet；外部AF不复用旧屏障ack")
        for (_ <- 0 until 50) tick()
        d.io.empty.expect(true.B)
        d.io.enable.poke(true.B)
        until("post-frontier data resumes") { received.size == 19 }
        Set(post0 ++ post1, post1 ++ post0) should contain(received.drop(11).toSeq)
        println("[Funnel-drain][3/4] 后前沿packet在Disable期间保留，重新Enable后逐byte一致")
        // Close once more with empty sources; no external flush is needed.
        for (_ <- 0 until 5) tick()
        d.io.enable.poke(false.B)
        until("second automatic drain") { d.io.sourceReq.forall(_.peek().litToBoolean) }
        until("second four-phase release") { d.io.empty.peek().litToBoolean }
        println("[Funnel-drain][4/4] 第二次Disable重新建立marker并回零，不依赖外部AF")
      }
    }
  }

  it should "finish automatic drain when an unused encoder remains in Active reset" in {
    test(new FunnelDrainHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.in.foreach { in =>
        in.valid.poke(false.B); in.bits.len.poke(1.U); in.bits.data.foreach(_.poke(0.U))
      }
      d.io.enable.poke(true.B); d.io.flush.poke(false.B); d.io.out.ready.poke(true.B)
      d.io.sourceActive(0).poke(true.B); d.io.sourceActive(1).poke(false.B)
      d.reset.poke(true.B); d.clock.step(4); d.reset.poke(false.B); d.clock.step(70)
      d.io.in(0).bits.data(0).poke(0x55.U); d.io.in(0).valid.poke(true.B)
      d.io.in(0).ready.expect(true.B); d.clock.step(); d.io.in(0).valid.poke(false.B)
      d.clock.step(35); d.io.enable.poke(false.B)
      var remaining = 200
      while (!d.io.empty.peek().litToBoolean && remaining > 0) { d.clock.step(); remaining -= 1 }
      assert(remaining > 0, "inactive source acknowledgment failed to return low")
      d.io.sourceReq.foreach(_.expect(false.B))
      d.io.ack.expect(false.B)
      println("[Funnel-drain] 未启用encoder一直Active=0，也按req返回空前沿ack并在req撤销后回零，Disable能完成")
    }
  }

  it should "queue a new Disable during an existing external barrier until its acknowledgment returns low" in {
    test(new FunnelFlushController) { d =>
      d.io.disableEvent.poke(false.B); d.io.request.poke(false.B)
      d.io.complete.poke(false.B); d.io.released.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.request.poke(true.B); d.clock.step(); d.io.upstreamReq.expect(true.B)
      d.io.released.poke(false.B); d.io.disableEvent.poke(true.B); d.clock.step()
      d.io.disableEvent.poke(false.B); d.io.complete.poke(true.B)
      d.io.ack.expect(true.B); d.clock.step(3)
      d.io.request.poke(false.B); d.clock.step()
      d.io.upstreamReq.expect(false.B); d.io.ack.expect(false.B); d.io.busy.expect(true.B)
      d.clock.step(4); d.io.upstreamReq.expect(false.B)
      d.io.complete.poke(false.B); d.io.released.poke(true.B); d.clock.step(2)
      d.io.upstreamReq.expect(true.B); d.io.ack.expect(false.B)
      d.io.released.poke(false.B); d.io.complete.poke(true.B); d.clock.step()
      d.io.upstreamReq.expect(false.B); d.io.busy.expect(true.B)
      d.io.complete.poke(false.B); d.io.released.poke(true.B); d.clock.step()
      d.io.busy.expect(false.B)
      println("[Funnel-drain] 外部AF进行中到达的Disable必须等ack回零后新发marker，未提前宣称Empty")
    }
  }
}
