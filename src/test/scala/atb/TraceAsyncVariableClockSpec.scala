package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Digital changing-clock protocol stress; not analog CDC sign-off. */
private class TraceAsyncVariableClockHarness extends Module {
  val io = IO(new Bundle {
    val sourceStep = Input(UInt(7.W))
    val sinkStep = Input(UInt(7.W))
    val sourceReset = Input(Bool())
    val sinkReset = Input(Bool())
    val limit = Input(UInt(8.W))
    val epoch = Input(UInt(8.W))
    val ready = Input(Bool())
    val flush = Input(Bool())
    val sync = Input(Bool())
    val accepted = Output(UInt(8.W))
    val received = Output(UInt(8.W))
    val captured = Output(Vec(128, UInt(32.W)))
    val sourceFlushes = Output(UInt(8.W))
    val sourceSyncs = Output(UInt(8.W))
    val sinkCycles = Output(UInt(16.W))
    val acknowledged = Output(Bool())
  })
  val sourcePhase = RegInit(31.U(8.W))
  val sinkPhase = RegInit(97.U(8.W))
  sourcePhase := sourcePhase + io.sourceStep
  sinkPhase := sinkPhase + io.sinkStep
  val sourceClock = sourcePhase(7).asClock
  val sinkClock = sinkPhase(7).asClock
  val commonReset = reset.asBool || io.sourceReset || io.sinkReset
  val bridge = Module(new TraceAsyncBridge(UInt(32.W), depth = 8))
  bridge.io.sourceClock := sourceClock
  bridge.io.sinkClock := sinkClock
  bridge.io.sourceReset := (reset.asBool || io.sourceReset).asAsyncReset
  bridge.io.sinkReset := (reset.asBool || io.sinkReset).asAsyncReset
  bridge.io.out.ready := io.ready
  bridge.io.control.flushReq := io.flush
  bridge.io.control.syncReq := io.sync
  io.acknowledged := bridge.io.control.flushAck
  withClockAndReset(sourceClock, commonReset.asAsyncReset) {
    val accepted = RegInit(0.U(8.W))
    bridge.io.in.valid := accepted < io.limit
    bridge.io.in.bits := Cat(io.epoch, 0.U(16.W), accepted)
    when(bridge.io.in.fire) { accepted := accepted + 1.U }
    io.accepted := accepted
    val seen = RegInit(false.B)
    val frontier = RegInit(0.U(8.W))
    val flushes = RegInit(0.U(8.W))
    val syncs = RegInit(0.U(8.W))
    when(bridge.io.sourceControl.flushReq && !seen) {
      seen := true.B; frontier := io.limit; flushes := flushes + 1.U
    }
    when(!bridge.io.sourceControl.flushReq) { seen := false.B }
    bridge.io.sourceControl.flushAck := seen && accepted >= frontier
    when(bridge.io.sourceControl.syncReq) { syncs := syncs + 1.U }
    io.sourceFlushes := flushes; io.sourceSyncs := syncs
  }
  // Survive endpoint reset so stale epoch deliveries remain visible.
  withClockAndReset(sinkClock, reset.asAsyncReset) {
    val received = RegInit(0.U(8.W))
    val captured = Reg(Vec(128, UInt(32.W)))
    val cycles = RegInit(0.U(16.W))
    cycles := cycles + 1.U
    when(bridge.io.out.fire && !commonReset && !bridge.io.sinkResetActive) {
      assert(received < 128.U)
      captured(received(6, 0)) := bridge.io.out.bits; received := received + 1.U
    }
    io.received := received; io.captured := captured; io.sinkCycles := cycles
  }
}

class TraceAsyncVariableClockSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "TraceAsyncBridge with varying phase and clock ratios"
  it should "preserve ordered data, flush frontiers and sync events across either endpoint reset" in {
    test(new TraceAsyncVariableClockHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(30000)
      d.io.sourceStep.poke(37.U); d.io.sinkStep.poke(53.U)
      d.io.sourceReset.poke(false.B); d.io.sinkReset.poke(false.B)
      d.io.limit.poke(0.U); d.io.epoch.poke(1.U)
      d.io.ready.poke(false.B); d.io.flush.poke(false.B); d.io.sync.poke(false.B)
      d.reset.poke(true.B); d.clock.step(30); d.reset.poke(false.B); d.clock.step(80)
      var time = 0
      def tick(): Unit = {
        if (time % 19 == 0) {
          d.io.sourceStep.poke((17 + (time * 7 % 91)).U)
          d.io.sinkStep.poke((13 + (time * 11 % 101)).U)
        }
        d.clock.step(); time += 1
      }
      def until(description: String)(condition: => Boolean): Unit = {
        var left = 5000
        while (!condition && left > 0) { tick(); left -= 1 }
        assert(left > 0, s"timeout: $description")
      }
      def words(begin: Int, end: Int): Seq[BigInt] =
        (begin until end).map(i => d.io.captured(i).peek().litValue)
      println("[CDC-variable][1/4] 改变相位增量；反压下发起旧数据flush边界")
      d.io.limit.poke(8.U)
      until("queue contains old source data") { d.io.accepted.peek().litValue >= 4 }
      d.io.flush.poke(true.B)
      until("flush reaches source") { d.io.sourceFlushes.peek().litValue == 1 }
      d.io.limit.poke(24.U)
      for (_ <- 0 until 80) { tick(); d.io.acknowledged.expect(false.B) }
      d.io.ready.poke(true.B)
      until("old frontier acknowledgment") { d.io.acknowledged.peek().litToBoolean }
      d.io.received.expect(8.U)
      d.io.flush.poke(false.B)
      until("post-flush data") { d.io.received.peek().litValue == 24 }
      words(0, 24) shouldBe (0 until 24).map(i => (BigInt(1) << 24) + i)
      println("[CDC-variable][2/4] 单sink周期sync请求，覆盖应答返回阶段")
      for (expected <- 1 to 2) {
        val cycle = d.io.sinkCycles.peek().litValue
        d.io.sync.poke(true.B)
        until("one sink sync sample") { d.io.sinkCycles.peek().litValue > cycle }
        d.io.sync.poke(false.B)
        until("source synchronization delivery") { d.io.sourceSyncs.peek().litValue == expected }
      }
      for (_ <- 0 until 120) tick()
      d.io.sourceSyncs.expect(2.U)
      for ((endpoint, epoch) <- Seq(("source", 2), ("sink", 3))) {
        println(s"[CDC-variable] $endpoint 独立reset中止积压，恢复只输出epoch=$epoch")
        d.io.ready.poke(false.B)
        val before = d.io.received.peek().litValue.toInt
        d.io.limit.poke((if (epoch == 2) 32 else 16).U)
        val old = d.io.accepted.peek().litValue
        until("new data held under backpressure") { d.io.accepted.peek().litValue > old }
        val previousFlushes = d.io.sourceFlushes.peek().litValue
        d.io.flush.poke(true.B)
        until("in-flight flush reaches source before reset") {
          d.io.sourceFlushes.peek().litValue > previousFlushes
        }
        d.io.acknowledged.expect(false.B)
        d.io.limit.poke(0.U)
        if (endpoint == "source") d.io.sourceReset.poke(true.B) else d.io.sinkReset.poke(true.B)
        d.io.flush.poke(false.B)
        for (_ <- 0 until 50) tick()
        d.io.epoch.poke(epoch.U)
        if (endpoint == "source") d.io.sourceReset.poke(false.B) else d.io.sinkReset.poke(false.B)
        for (_ <- 0 until 100) tick()
        d.io.accepted.expect(0.U); d.io.received.expect(before.U)
        d.io.limit.poke(8.U); d.io.ready.poke(true.B)
        until("new epoch data") { d.io.received.peek().litValue == before + 8 }
        words(before, before + 8) shouldBe (0 until 8).map(i => (BigInt(epoch) << 24) + i)
        d.io.flush.poke(true.B)
        until("new flush completes after endpoint reset") { d.io.acknowledged.peek().litToBoolean }
        d.io.sourceFlushes.expect(1.U)
        d.io.flush.poke(false.B)
        for (_ <- 0 until 80) tick()
        val cycle = d.io.sinkCycles.peek().litValue
        d.io.sync.poke(true.B)
        until("new single-cycle sync after reset") { d.io.sinkCycles.peek().litValue > cycle }
        d.io.sync.poke(false.B)
        until("new source sync after reset") { d.io.sourceSyncs.peek().litValue == 1 }
        for (_ <- 0 until 80) tick()
        d.io.received.expect((before + 8).U)
      }
    }
  }
}
