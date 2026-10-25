package trace

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class TraceControlClockDesign(implicit p: Parameters) extends LazyModule {
  val source = LazyModule(new CtrlAsyncSource)
  val sink = LazyModule(new CtrlAsyncSink(0x20010000))
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1("control-clock-test", sourceId = IdRange(0, 4))
  ))))
  sink.sinkNode := source.sourceNode := client
  val control = InModuleBody(client.makeIOs())
  class Imp extends LazyModuleImp(this) with RequireAsyncReset {
    val io = IO(new Bundle {
      val sinkClock = Input(Clock())
      val sinkReset = Input(AsyncReset())
      val active = Output(Bool())
      val scratch = Output(UInt(32.W))
      val writes = Output(UInt(16.W))
    })
    source.module.clock := clock; source.module.reset := reset.asAsyncReset
    sink.module.clock := io.sinkClock; sink.module.reset := io.sinkReset
    io.active := sink.module.io.active
    withClockAndReset(io.sinkClock, io.sinkReset) {
      val request = sink.module.io.bus.b2c
      val scratch = withReset(sink.module.io.componentReset)(RegInit(0.U(32.W)))
      val writes = RegInit(0.U(16.W))
      when(request.writeEn && request.address === 0x10100.U && sink.module.io.active) {
        scratch := request.dataWrite; writes := writes + 1.U
      }
      io.scratch := scratch; io.writes := writes
      sink.module.io.bus.c2b.dataRead := Mux(request.address === 0x10000.U, 0.U, scratch)
      sink.module.io.bus.c2b.error := false.B
    }
  }
  lazy val module = new Imp
}

private class TraceControlClockHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val design = LazyModule(new TraceControlClockDesign)
  val top = withReset(reset.asAsyncReset)(Module(design.module))
  val control = design.control.getWrappedValue.head
  val io = IO(new Bundle {
    val bus = Flipped(new TLBundle(TraceControlClockHarness.this.control.params))
    val pause = Input(Bool())
    val sinkReset = Input(Bool())
    val active = Output(Bool())
    val scratch = Output(UInt(32.W))
    val writes = Output(UInt(16.W))
  })
  val phase = RegInit(71.U(8.W))
  when(!io.pause) { phase := phase + 53.U }
  top.io.sinkClock := phase(7).asClock
  top.io.sinkReset := (reset.asBool || io.sinkReset).asAsyncReset
  control <> io.bus
  io.active := top.io.active; io.scratch := top.io.scratch; io.writes := top.io.writes
}

class TraceControlClockSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "actual asynchronous trace control route"
  it should "retain stopped-clock requests and expose destructive independent reset before coordinated recovery" in {
    test(new TraceControlClockHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(20000)
      val a = d.io.bus.a; val response = d.io.bus.d
      a.valid.poke(false.B); response.ready.poke(false.B)
      a.bits.opcode.poke(TLMessages.Get); a.bits.param.poke(0.U)
      a.bits.size.poke(2.U); a.bits.source.poke(0.U); a.bits.address.poke(0x20010000.U)
      a.bits.mask.poke(15.U); a.bits.data.poke(0.U); a.bits.corrupt.poke(false.B)
      d.io.pause.poke(false.B); d.io.sinkReset.poke(false.B)
      d.reset.poke(true.B); d.clock.step(30); d.reset.poke(false.B); d.clock.step(100)
      def until(description: String)(condition: => Boolean): Unit = {
        var left = 2000
        while (!condition && left > 0) { d.clock.step(); left -= 1 }
        assert(left > 0, s"timeout: $description")
      }
      def offer(write: Boolean, offset: Int, value: BigInt = 0, id: Int = 0): Unit = {
        a.bits.opcode.poke(if (write) TLMessages.PutFullData else TLMessages.Get)
        a.bits.source.poke(id.U); a.bits.address.poke((0x20010000L + offset).U)
        a.bits.data.poke(value.U); a.valid.poke(true.B)
        until("source A acceptance") { a.ready.peek().litToBoolean }
        d.clock.step(); a.valid.poke(false.B)
      }
      def reply(write: Boolean, id: Int = 0): BigInt = {
        until("source D response") { response.valid.peek().litToBoolean }
        response.bits.source.expect(id.U); response.bits.denied.expect(false.B)
        response.bits.opcode.expect(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
        val data = response.bits.data.peek().litValue
        response.ready.poke(true.B); d.clock.step(); response.ready.poke(false.B); data
      }
      offer(true, 0, 1); reply(true)
      until("component reset release") { d.io.active.peek().litToBoolean }
      println("[control-clock][1/3] 停组件clock后接受A；D等待clock恢复，写副作用仅一次")
      d.io.pause.poke(true.B); d.clock.step(10)
      val writes = d.io.writes.peek().litValue
      offer(true, 0x100, 0x12345678, id = 2)
      for (_ <- 0 until 80) {
        response.valid.expect(false.B); d.io.writes.expect(writes.U); d.clock.step()
      }
      d.io.pause.poke(false.B); reply(true, id = 2)
      d.io.writes.expect((writes + 1).U); d.io.scratch.expect(0x12345678.U)
      println("[control-clock][2/3] Q083负例：独立sink系统reset会丢失尚未握手D，原失败证据已保留")
      offer(false, 0x100, id = 3)
      until("read data buffered in source domain") { response.valid.peek().litToBoolean }
      response.bits.data.expect(0x12345678.U)
      d.io.sinkReset.poke(true.B); d.clock.step(40)
      for (_ <- 0 until 20) {
        response.valid.expect(false.B); d.clock.step()
      }
      // The master transaction remains unresolved. Reset the master/monitor
      // and both crossing endpoints before reuse; this is not lossless reset.
      d.reset.poke(true.B); d.clock.step(30)
      d.io.sinkReset.poke(false.B); d.reset.poke(false.B); d.clock.step(150)
      d.io.active.expect(false.B); d.io.writes.expect(0.U)
      println("[control-clock][3/3] 协调复位主机与两端后重新Active/CSR读写，无旧请求重复")
      offer(true, 0, 1); reply(true)
      until("reactivation") { d.io.active.peek().litToBoolean }
      offer(true, 0x100, 0x42); reply(true)
      offer(false, 0x100); reply(false) shouldBe BigInt(0x42)
      d.clock.step(100); d.io.writes.expect(1.U)
    }
  }
}
