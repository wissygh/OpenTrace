package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.util.AsyncResetSynchronizerShiftReg
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface._

private class ATBClockRequestHarness extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ATData(4)))
    val ready = Input(Bool())
    val clockEnable = Input(Bool())
    val request = Output(Bool())
    val gateOpen = Output(Bool())
    val sinkCycles = Output(UInt(16.W))
    val received = Output(UInt(8.W))
    val data = Output(Vec(64, new ATData(4)))
    val wakeup = Output(Bool())
    val valid = Output(Bool())
    val resetSink = Input(Bool())
    val eagerWakeup = Input(Bool())
    val flush = Input(Bool())
    val flushReady = Output(Bool())
  })
  val bridge = Module(new ATBAsyncBridge(4, 8, exposeClockRequest = true))
  bridge.io.sourceClock := clock; bridge.io.sourceReset := reset.asAsyncReset
  bridge.io.sinkReset := (reset.asBool || io.resetSink).asAsyncReset
  bridge.io.slave.at <> io.in
  bridge.io.slave.atclken := true.B
  bridge.io.slave.af.ready := true.B
  bridge.io.slave.wakeup := io.in.valid || io.eagerWakeup
  val requested = AsyncResetSynchronizerShiftReg(bridge.io.sourceClockRequest.get, 3)
  val open = requested || reset.asBool || io.resetSink || io.flush
  // The test clock manager changes the divider only while its output is low
  // or at a completed high half-period, so it does not create narrow pulses.
  val sinkLevel = RegInit(false.B)
  val phase = RegInit(0.U(2.W))
  phase := Mux(phase === 2.U, 0.U, phase + 1.U)
  when(phase === 2.U) {
    when(open || sinkLevel) { sinkLevel := !sinkLevel }
  }
  val sinkClock = sinkLevel.asClock
  bridge.io.sinkClock := sinkClock
  bridge.io.master.at.ready := io.ready
  bridge.io.master.atclken := io.clockEnable
  bridge.io.master.af.valid := io.flush
  bridge.io.master.af.bits := 0.U.asTypeOf(bridge.io.master.af.bits)
  bridge.io.master.syncReq := false.B
  io.request := bridge.io.sourceClockRequest.get
  io.gateOpen := open
  io.wakeup := bridge.io.master.wakeup
  io.valid := bridge.io.master.at.valid
  io.flushReady := bridge.io.master.af.ready
  withClockAndReset(sinkClock, (reset.asBool || io.resetSink).asAsyncReset) {
    val cycles = RegInit(0.U(16.W)); cycles := cycles + 1.U
    val received = RegInit(0.U(8.W))
    val data = Reg(Vec(64, new ATData(4)))
    when(bridge.io.master.at.fire && io.clockEnable) {
      data(received(5,0)) := bridge.io.master.at.bits; received := received + 1.U
    }
    io.sinkCycles := cycles; io.received := received; io.data := data
  }
}

class ATBClockRequestSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "optional ATB source clock request"
  it should "restart a fully stopped destination clock, hold it for stalled bytes and allow it to stop after delivery" in {
    test(new ATBClockRequestHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(20000)
      d.io.in.valid.poke(false.B); d.io.in.bits.id.poke(0x42.U)
      d.io.in.bits.data.poke(0x44332211L.U); d.io.in.bits.bytes.poke(3.U)
      d.io.ready.poke(false.B); d.io.clockEnable.poke(true.B)
      d.io.resetSink.poke(false.B); d.io.eagerWakeup.poke(false.B)
      d.io.flush.poke(false.B)
      d.reset.poke(true.B); d.clock.step(30); d.reset.poke(false.B); d.clock.step(100)
      def until(description: String)(condition: => Boolean): Unit = {
        var left=2000
        while(!condition && left>0){ d.clock.step(); left-=1 }
        assert(left>0,s"timeout: $description")
      }
      def stopped(): Unit = {
        until("source request quiescent"){ !d.io.request.peek().litToBoolean }
        d.clock.step(80)
        val count=d.io.sinkCycles.peek().litValue
        d.clock.step(100); d.io.sinkCycles.expect(count.U); d.io.gateOpen.expect(false.B)
      }
      stopped()
      println("[ATB-clock-request][1/4] ATCLK全停后source单拍valid请求自主开钟，未异步驱动ATWAKEUP")
      d.io.in.valid.poke(true.B)
      until("source accepts word"){d.io.in.ready.peek().litToBoolean}
      d.clock.step(); d.io.in.valid.poke(false.B)
      until("registered ATVALID after automatic clock restart"){d.io.valid.peek().litToBoolean}
      d.io.request.expect(true.B)
      until("ATWAKEUP registered by the restarted destination clock"){d.io.wakeup.peek().litToBoolean}
      d.clock.step(100); d.io.received.expect(0.U); d.io.request.expect(true.B)
      println("[ATB-clock-request][2/4] ATCLKEN=0保持唤醒请求与数据；恢复后仅发一次")
      d.io.ready.poke(true.B); d.io.clockEnable.poke(false.B); d.clock.step(60)
      d.io.received.expect(0.U); d.io.request.expect(true.B)
      d.io.clockEnable.poke(true.B)
      until("one actual ATB transfer"){d.io.received.peek().litValue==1}
      d.io.data(0).data.expect(0x44332211L.U); d.io.data(0).id.expect(0x42.U); d.io.data(0).bytes.expect(3.U)
      stopped()
      println("[ATB-clock-request][3/4] 多次门控/重启和计数器wrap仍不丢不重")
      for(i <- 1 until 40){
        d.io.in.bits.data.poke((0x100+i).U);d.io.in.valid.poke(true.B)
        until("accept after clock stop"){d.io.in.ready.peek().litToBoolean}
        d.clock.step();d.io.in.valid.poke(false.B)
        until("delivery after restart"){d.io.received.peek().litValue==i+1}
        d.io.data(i).data.expect((0x100+i).U);stopped()
      }
      println("[ATB-clock-request][4/4] eager wakeup可先开钟；空闲sink reset后不遗留请求")
      d.io.eagerWakeup.poke(true.B);d.clock.step(50);d.io.request.expect(true.B)
      d.io.eagerWakeup.poke(false.B);stopped()
      d.io.resetSink.poke(true.B);d.clock.step(30);d.io.resetSink.poke(false.B);d.clock.step(100)
      d.io.received.expect(0.U);stopped()
      println("[ATB-clock-request] 外部clock manager也响应接收端flush，source request保持到marker/AF完整完成")
      d.io.flush.poke(true.B)
      until("empty-path flush marker acknowledgment"){d.io.flushReady.peek().litToBoolean}
      d.io.flush.poke(false.B);stopped()
    }
  }
}
