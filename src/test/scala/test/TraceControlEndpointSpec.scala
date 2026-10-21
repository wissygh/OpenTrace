package trace.control

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.tilelink._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.TraceParameters

private object ControlEndpointTestParameters {
  val bus = TLBundleParameters(32, 32, 3, 1, 3, Nil, Nil, Nil, false)
  val base = 0x20010000
}

private class TraceControlEndpointHarness extends Module with TraceParameters {
  import ControlEndpointTestParameters._
  val io = IO(new Bundle {
    val a = Flipped(Decoupled(new TLBundleA(bus)))
    val d = Decoupled(new TLBundleD(bus))
    val active = Output(Bool())
    val control = Output(UInt(32.W))
    val scratch = Output(UInt(32.W))
    val reads = Output(UInt(32.W))
    val writes = Output(UInt(32.W))
  })
  val endpoint = Module(new TraceControlEndpoint(base, bus))
  endpoint.io.a <> io.a; io.d <> endpoint.io.d; io.active := endpoint.io.active
  val csr = withReset(endpoint.io.componentReset) { Module(new EncoderTraceControlRegister) }
  val request = endpoint.io.bus.b2c
  csr.w.wen := request.writeEn && request.address === (base & ctrlMask).U
  csr.w.wdata := request.dataWrite
  csr.traceOn := false.B; csr.traceOff := false.B; csr.fifoIsEmpty := true.B; csr.fifoIsFull := false.B
  val scratch = withReset(endpoint.io.componentReset) { RegInit(0.U(32.W)) }
  when(request.writeEn && request.address === ((base & ctrlMask) + 0x100).U) { scratch := request.dataWrite }
  endpoint.io.bus.c2b.error := false.B
  endpoint.io.bus.c2b.dataRead := Mux(request.address === (base & ctrlMask).U, csr.rdata, scratch)
  val reads = RegInit(0.U(32.W)); val writes = RegInit(0.U(32.W))
  when(request.readEn) { reads := reads + 1.U }
  when(request.writeEn) { writes := writes + 1.U }
  io.control := csr.rdata; io.scratch := scratch; io.reads := reads; io.writes := writes
}

class TraceControlEndpointSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  import ControlEndpointTestParameters._
  behavior of "always-on trace control endpoint"
  it should "hold responses and execute side effects once across Active reset and invalid accesses" in {
    test(new TraceControlEndpointHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.a.valid.poke(false.B); d.io.d.ready.poke(true.B)
      d.io.a.bits.opcode.poke(TLMessages.Get); d.io.a.bits.param.poke(0.U)
      d.io.a.bits.size.poke(2.U); d.io.a.bits.source.poke(0.U)
      d.io.a.bits.address.poke(base.U); d.io.a.bits.mask.poke(15.U)
      d.io.a.bits.data.poke(0.U); d.io.a.bits.corrupt.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.active.expect(false.B)
      def offer(write: Boolean, offset: Int = 0, value: BigInt = 0, source: Int = 0,
          size: Int = 2, mask: Int = 15, corrupt: Boolean = false, partialOpcode: Boolean = false): Unit = {
        d.io.a.valid.poke(true.B)
        d.io.a.bits.opcode.poke(if (!write) TLMessages.Get else
          if (partialOpcode) TLMessages.PutPartialData else TLMessages.PutFullData)
        d.io.a.bits.source.poke(source.U); d.io.a.bits.size.poke(size.U)
        d.io.a.bits.address.poke((base + offset).U); d.io.a.bits.mask.poke(mask.U)
        d.io.a.bits.data.poke(value.U); d.io.a.bits.corrupt.poke(corrupt.B)
      }
      def accepted(): Unit = { d.io.a.ready.expect(true.B); d.clock.step(); d.io.a.valid.poke(false.B) }
      def response(write: Boolean, source: Int = 0, denied: Boolean = false, size: Int = 2): BigInt = {
        d.io.d.valid.expect(true.B); d.io.d.bits.source.expect(source.U)
        d.io.d.bits.opcode.expect(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
        d.io.d.bits.size.expect(size.U); d.io.d.bits.denied.expect(denied.B)
        val data = d.io.d.bits.data.peek().litValue
        d.clock.step(); data
      }
      def write(offset: Int, value: BigInt): Unit = { offer(true, offset, value); accepted(); response(true) }
      def read(offset: Int = 0): BigInt = { offer(false, offset); accepted(); response(false) }
      (read() & 1) shouldBe BigInt(0)
      write(0, 1); d.clock.step(4); d.io.active.expect(true.B)
      (read() & 1) shouldBe BigInt(1)
      write(0, 7); ((d.io.control.peek().litValue >> 1) & 1) shouldBe BigInt(1)
      for (partial <- Seq(false, true)) {
        val before = d.io.writes.peek().litValue
        offer(true, 0x100, BigInt("87654321", 16), partialOpcode = partial)
        accepted(); response(true)
        d.io.writes.expect((before + 1).U)
        read(0x100) shouldBe BigInt("87654321", 16)
      }
      write(0x100, BigInt("12345678", 16))
      println("[control-bus] 1/4 Active同步释放；PutFullData/PutPartialData整word写入均恰好一次")

      d.io.d.ready.poke(false.B)
      offer(false, 0x100, source = 5); accepted()
      val count = d.io.reads.peek().litValue
      offer(true, 0x100, BigInt("deadbeef", 16), source = 6)
      for (_ <- 0 until 6) {
        d.io.a.ready.expect(false.B); d.io.d.valid.expect(true.B)
        d.io.d.bits.source.expect(5.U); d.io.d.bits.data.expect("h12345678".U)
        d.io.reads.expect(count.U); d.io.scratch.expect("h12345678".U)
        d.clock.step()
      }
      // Complete the held response and accept the next request on one edge.
      d.io.d.ready.poke(true.B); accepted()
      response(true, source = 6)
      d.io.scratch.expect("hdeadbeef".U)
      read(0x100) shouldBe BigInt("deadbeef", 16)
      println("[control-bus] 2/4 D 反压保持数据/source，受阻 A 无副作用，同拍应答与新请求握手通过")

      val writes = d.io.writes.peek().litValue
      val reads = d.io.reads.peek().litValue
      for ((wr, offset, size, mask, corrupt) <- Seq(
          (true, 0, 0, 1, false), (true, 1, 2, 15, false),
          (true, 0x100, 2, 3, false), (true, 0, 2, 15, true),
          (false, 0x101, 2, 15, false))) {
        offer(wr, offset, 0, source = 3, size = size, mask = mask, corrupt = corrupt)
        accepted(); response(wr, source = 3, denied = true, size = size)
        d.io.active.expect(true.B); d.io.writes.expect(writes.U); d.io.reads.expect(reads.U)
        d.io.scratch.expect("hdeadbeef".U)
      }
      for (partial <- Seq(false, true); mask <- 0 until 15) {
        offer(true, 0x100, 0, mask = mask, partialOpcode = partial)
        accepted(); response(true, denied = true)
        d.io.writes.expect(writes.U); d.io.scratch.expect("hdeadbeef".U)
      }
      println("[control-bus] 3/4 窄访问/未对齐/部分 mask/corrupt 请求被拒绝，不触发 Active 或 CSR 副作用")

      d.io.d.ready.poke(false.B)
      offer(true, value = 0, source = 7, partialOpcode = true); accepted()
      d.io.active.expect(false.B); d.io.scratch.expect(0.U)
      d.clock.step(4); d.io.d.valid.expect(true.B); d.io.d.bits.source.expect(7.U)
      ((d.io.control.peek().litValue >> 1) & 1) shouldBe BigInt(0)
      d.io.d.ready.poke(true.B); response(true, source = 7)
      (read() & 1) shouldBe BigInt(0)
      write(0, 1); d.clock.step(4); d.io.active.expect(true.B)
      write(0x100, 0x42); read(0x100) shouldBe BigInt(0x42)
      println("[control-bus] 4/4 Active=0 复位数据路径仍保留写应答，重新激活与新事务通过")
    }
  }
}
