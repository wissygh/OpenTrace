package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface._

/** Real FIFO -> CDC -> funnel -> ATB adapter -> CDC, with three clock rates. */
private class ATBCrossingHarness extends Module {
  val io = IO(new Bundle {
    val in = Vec(2, Flipped(Decoupled(new PacketEnq(8))))
    val ready = Input(Bool())
    val clockEnable = Input(Bool())
    val flush = Input(Bool())
    val sync = Input(Bool())
    val pauseClock = Input(Bool())
    val linkReset = Input(Bool())
    val enqueued = Output(Vec(2, UInt(16.W)))
    val sourceSyncs = Output(Vec(2, UInt(16.W)))
    val sourceFlushes = Output(Vec(2, UInt(16.W)))
    val received = Output(UInt(8.W))
    val captured = Output(Vec(64, new ATData(4)))
    val flushes = Output(UInt(8.W))
    val atFlush = Output(UInt(8.W))
    val sinkCycles = Output(UInt(16.W))
    val wakeup = Output(Bool())
    val valid = Output(Bool())
    val afready = Output(Bool())
  })
  val sourceLevel = RegInit(false.B)
  sourceLevel := !sourceLevel
  val sinkLevel = RegInit(false.B)
  val divider = RegInit(0.U(2.W))
  divider := Mux(divider === 2.U, 0.U, divider + 1.U)
  when(divider === 2.U && !io.pauseClock) { sinkLevel := !sinkLevel }
  val sinkClock = sinkLevel.asClock
  val funnel = Module(new TraceFunnelCore(2))
  funnel.io.enable := true.B
  funnel.io.disable.foreach(_ := false.B)
  for (i <- 0 until 2) {
    val sourceClock = if (i == 0) clock else sourceLevel.asClock
    val fifo = withClockAndReset(sourceClock, reset.asAsyncReset) {
      val f = Module(new Fifo(FifoParam(true, 32, 8, 4)))
      val count = RegInit(0.U(16.W))
      when(f.io.packetEnq.fire) { count := count + 1.U }
      io.enqueued(i) := count
      f
    }
    fifo.io.packetEnq <> io.in(i)
    val crossing = Module(new TraceAsyncBridge(new TraceBeat(4), 8))
    crossing.io.sourceClock := sourceClock
    crossing.io.sourceReset := reset.asAsyncReset
    crossing.io.sinkClock := clock
    crossing.io.sinkReset := reset.asAsyncReset
    crossing.io.in <> fifo.io.atbm
    fifo.io.control <> crossing.io.sourceControl
    crossing.io.control <> funnel.io.sourceControl(i)
    funnel.io.in(i) <> crossing.io.out
    withClockAndReset(sourceClock, reset.asAsyncReset) {
      val syncs = RegInit(0.U(16.W))
      val flushes = RegInit(0.U(16.W))
      when(fifo.io.control.syncReq) { syncs := syncs + 1.U }
      when(fifo.io.control.flushReq && !RegNext(fifo.io.control.flushReq, false.B)) {
        flushes := flushes + 1.U
      }
      io.sourceSyncs(i) := syncs
      io.sourceFlushes(i) := flushes
    }
  }
  val adapter = Module(new TraceATBAdapter)
  adapter.io.asyncFreq := 0.U
  adapter.io.in <> funnel.io.out
  adapter.io.upstream <> funnel.io.control
  adapter.io.enable := true.B
  adapter.io.id := 0x20.U
  val link = Module(new ATBAsyncBridge(4, 8))
  link.io.sourceClock := clock
  link.io.sourceReset := reset.asAsyncReset
  link.io.sinkClock := sinkClock
  link.io.sinkReset := (reset.asBool || io.linkReset).asAsyncReset
  link.io.slave.at <> adapter.io.atbm.at
  adapter.io.atbm.af <> link.io.slave.af
  adapter.io.atbm.syncReq := link.io.slave.syncReq
  link.io.slave.wakeup := adapter.io.atbm.wakeup
  link.io.slave.atclken := true.B
  adapter.io.atbm.atclken := true.B
  link.io.master.at.ready := io.ready
  link.io.master.atclken := io.clockEnable
  link.io.master.af.valid := io.flush
  link.io.master.af.bits := 0.U.asTypeOf(link.io.master.af.bits)
  link.io.master.syncReq := io.sync
  io.wakeup := link.io.master.wakeup
  io.valid := link.io.master.at.valid
  io.afready := link.io.master.af.ready
  withClockAndReset(sinkClock, (reset.asBool || io.linkReset).asAsyncReset) {
    val received = RegInit(0.U(8.W))
    val captured = Reg(Vec(64, new ATData(4)))
    val flushes = RegInit(0.U(8.W))
    val atFlush = RegInit(0.U(8.W))
    val cycles = RegInit(0.U(16.W))
    cycles := cycles + 1.U
    when(link.io.master.at.fire && io.clockEnable) {
      assert(received < 64.U)
      captured(received(5, 0)) := link.io.master.at.bits
      received := received + 1.U
    }
    when(link.io.master.af.fire && io.clockEnable) {
      flushes := flushes + 1.U
      atFlush := received
    }
    val stalled = link.io.master.at.valid && (!io.ready || !io.clockEnable)
    val held = RegNext(link.io.master.at.bits.asUInt)
    when(RegNext(stalled, false.B)) {
      assert(link.io.master.at.valid && link.io.master.at.bits.asUInt === held)
    }
    io.received := received
    io.captured := captured
    io.flushes := flushes
    io.atFlush := atFlush
    io.sinkCycles := cycles
  }
}

class ATBCrossingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ATB multi-clock path"
  it should "flush both old frontiers, preserve later data and cross single-cycle synchronization events" in {
    test(new ATBCrossingHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      println("[ATB-CDC][1/6] 启动三种频率的时钟，复位实际 FIFO/CDC/Funnel/Bridge 链路")
      d.io.in.foreach { in =>
        in.valid.poke(false.B); in.bits.len.poke(1.U); in.bits.data.foreach(_.poke(0.U))
      }
      d.io.ready.poke(false.B)
      d.io.clockEnable.poke(true.B)
      d.io.flush.poke(false.B)
      d.io.sync.poke(false.B)
      d.io.pauseClock.poke(false.B)
      d.io.linkReset.poke(false.B)
      d.reset.poke(true.B); d.clock.step(4); d.reset.poke(false.B); d.clock.step(80)
      def waitFor(description: String)(condition: => Boolean): Unit = {
        var remaining = 1500
        while (!condition && remaining > 0) { d.clock.step(); remaining -= 1 }
        assert(remaining > 0, s"timeout: $description")
      }
      def enqueue(source: Int, bytes: Seq[Int]): Unit = {
        val previous = d.io.enqueued(source).peek().litValue
        d.io.in(source).bits.len.poke(bytes.size.U)
        d.io.in(source).bits.data.zipWithIndex.foreach { case (b, i) => b.poke(bytes.lift(i).getOrElse(0).U) }
        d.io.in(source).valid.poke(true.B)
        waitFor(s"source $source enqueue") { d.io.enqueued(source).peek().litValue > previous }
        d.io.in(source).valid.poke(false.B)
      }
      def captured(begin: Int, end: Int): Seq[Int] = (begin until end).flatMap { n =>
        val beat = d.io.captured(n)
        beat.id.expect(0x20.U)
        val data = beat.data.peek().litValue
        (0 to beat.bytes.peek().litValue.toInt).map(i => ((data >> (8 * i)) & 255).toInt)
      }
      val old0 = Seq(4, 0, 0x13, 0, 1, 2)
      val old1 = Seq(3, 1, 0x13, 4, 5)
      val post0 = Seq(0xa0, 0xa1, 0xa2, 0xa3)
      val post1 = Seq(0xb0, 0xb1, 0xb2, 0xb3)
      enqueue(0, old0); enqueue(1, old1)
      d.clock.step(50)
      d.io.wakeup.expect(true.B)
      println("[ATB-CDC][2/6] 外部 AFVALID 发起 flush，等待请求跨域到两个 source")
      d.io.flush.poke(true.B)
      waitFor("flush reaches both sources") { d.io.sourceFlushes.forall(_.peek().litValue == 1) }
      println("[ATB-CDC][3/6] flush 请求后继续产生新 packet；接收端保持 backpressure")
      enqueue(0, post0); enqueue(1, post1)
      d.io.flushes.expect(0.U)
      d.io.received.expect(0.U)
      d.io.clockEnable.poke(false.B)
      d.io.ready.poke(true.B); d.clock.step(30)
      d.io.received.expect(0.U)
      d.io.clockEnable.poke(true.B)
      waitFor("old data flush acknowledgment") { d.io.flushes.peek().litValue == 1 }
      d.io.flush.poke(false.B)
      d.io.atFlush.expect(4.U)
      val old = captured(0, 4)
      Set(old0 ++ old1, old1 ++ old0) should contain(old)
      println("[ATB-CDC][4/6] AFREADY 已确认旧数据边界；恢复后续 packet，校验 ID/字节/顺序")
      waitFor("post-flush packets") { d.io.received.peek().litValue == 6 }
      val post = captured(4, 6)
      Set(post0 ++ post1, post1 ++ post0) should contain(post)
      println("[ATB-CDC][5/6] 仅拉高一个外部 ATCLK 周期的 SYNCREQ，检查两个源各收到一次")
      val cycle = d.io.sinkCycles.peek().litValue
      d.io.sync.poke(true.B)
      waitFor("one sink clock") { d.io.sinkCycles.peek().litValue > cycle }
      d.io.sync.poke(false.B)
      waitFor("synchronization reaches both sources") { d.io.sourceSyncs.forall(_.peek().litValue == 1) }
      // A second event must survive while acknowledgment is returning across CDC.
      val nextCycle = d.io.sinkCycles.peek().litValue
      d.io.sync.poke(true.B)
      waitFor("second single-cycle request") { d.io.sinkCycles.peek().litValue > nextCycle }
      d.io.sync.poke(false.B)
      waitFor("second synchronization reaches both sources") { d.io.sourceSyncs.forall(_.peek().litValue == 2) }
      d.clock.step(150)
      d.io.sourceSyncs.foreach(_.expect(2.U))
      println("[ATB-CDC][6/6] 暂停目的时钟，继续入队；恢复时钟后检查数据保留和空闲复位")
      d.io.pauseClock.poke(true.B)
      val before = d.io.received.peek().litValue
      enqueue(0, Seq(0xc0, 0xc1, 0xc2))
      d.clock.step(40)
      d.io.received.expect(before.U)
      d.io.pauseClock.poke(false.B)
      waitFor("data after clock resume") { d.io.received.peek().litValue == before + 1 }
      captured(before.toInt, before.toInt + 1) shouldBe Seq(0xc0, 0xc1, 0xc2)
      println("[ATB-CDC] 连续保持 AFVALID，检查额外 flush 请求和四阶段屏障释放")
      val previousFlushes = d.io.flushes.peek().litValue
      d.io.flush.poke(true.B)
      waitFor("two consecutive flushes") { d.io.flushes.peek().litValue == previousFlushes + 2 }
      d.io.flush.poke(false.B)
      d.clock.step(100)
      println("[ATB-CDC] flush 进行中复位目的端，检查复位后的新请求可重新完成")
      val sourceFlushes = d.io.sourceFlushes(0).peek().litValue
      d.io.flush.poke(true.B)
      waitFor("in-flight flush request") { d.io.sourceFlushes(0).peek().litValue > sourceFlushes }
      d.io.linkReset.poke(true.B); d.clock.step(12)
      d.io.flush.poke(false.B)
      d.io.valid.expect(false.B)
      d.io.afready.expect(true.B)
      d.io.linkReset.poke(false.B); d.clock.step(150)
      d.io.valid.expect(false.B)
      d.io.flush.poke(true.B)
      waitFor("fresh flush after reset") { d.io.flushes.peek().litValue == 1 }
      d.io.flush.poke(false.B)
    }
  }
}
