package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.control.TraceControlEndpoint
import trace.interface.TraceBeat

private class FunnelResetHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val bus = TLBundleParameters(32, 32, 3, 1, 3, Nil, Nil, Nil, false)
  val io = IO(new Bundle {
    val a = Flipped(Decoupled(new TLBundleA(bus)))
    val d = Decoupled(new TLBundleD(bus))
    val in = Vec(2, Flipped(Decoupled(new TraceBeat(4))))
    val out = Decoupled(new TraceBeat(4))
    val flush = Input(Bool())
    val active = Output(Bool())
    val quiesce = Output(Bool())
  })
  val endpoint = Module(new TraceControlEndpoint(0x20020000, bus, deferReset = true))
  val funnel = withReset(endpoint.io.componentReset) { Module(LazyModule(new Funnel(0x20020000, 2)).module) }
  endpoint.io.a <> io.a; io.d <> endpoint.io.d
  endpoint.io.bus <> funnel.bus
  endpoint.io.resetReady.get := funnel.io.resetReady
  funnel.io.quiesce := endpoint.io.quiesce.get
  io.active := endpoint.io.active; io.quiesce := endpoint.io.quiesce.get
  funnel.io.in <> io.in; io.out <> funnel.io.out
  funnel.io.inputPending := false.B
  funnel.io.control.flushReq := io.flush; funnel.io.control.syncReq := false.B
  funnel.io.sourceControl.foreach(_.flushAck := false.B)
}

class FunnelResetSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Funnel software reset at packet boundaries"
  for (cancel <- Seq(false, true)) {
    it should s"retain offered packet ownership under backpressure and reset cancellation=$cancel" in {
      test(new FunnelResetHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
        d.io.a.valid.poke(false.B); d.io.d.ready.poke(true.B)
        d.io.a.bits.param.poke(0.U); d.io.a.bits.size.poke(2.U); d.io.a.bits.source.poke(0.U)
        d.io.a.bits.address.poke(0x20020000.U); d.io.a.bits.data.poke(0.U)
        d.io.a.bits.mask.poke(15.U); d.io.a.bits.corrupt.poke(false.B)
        d.io.a.bits.opcode.poke(TLMessages.Get)
        d.io.flush.poke(false.B); d.io.out.ready.poke(false.B)
        d.io.in.foreach { in =>
          in.valid.poke(false.B); in.bits.data.poke(0.U); in.bits.bytes.poke(3.U)
          in.bits.packetBytes.poke(8.U); in.bits.last.poke(false.B)
        }
        d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
        def access(write: Boolean, value: Int = 0): BigInt = {
          d.io.a.valid.poke(true.B); d.io.a.bits.data.poke(value.U)
          d.io.a.bits.opcode.poke(if (write) TLMessages.PutFullData else TLMessages.Get)
          d.io.a.ready.expect(true.B); d.clock.step(); d.io.a.valid.poke(false.B)
          d.io.d.valid.expect(true.B); d.io.d.bits.denied.expect(false.B)
          val result = d.io.d.bits.data.peek().litValue
          d.clock.step(); result
        }
        access(true, 1); d.clock.step(5); access(true, 3)
        d.io.active.expect(true.B)
        d.io.in(0).valid.poke(true.B); d.io.in(0).bits.data.poke(0x11111111L.U)
        d.io.out.valid.expect(true.B)
        // Cover both an accepted prefix and a first offered beat held by stall.
        if (!cancel) { d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B) }
        else d.clock.step()
        d.io.in(0).bits.data.poke((if (cancel) 0x11111111L else 0x22222222L).U)
        d.io.in(0).bits.last.poke((!cancel).B)
        d.io.in(1).valid.poke(true.B); d.io.in(1).bits.data.poke(0x33333333L.U)
        d.io.in(1).bits.last.poke(true.B); d.io.in(1).bits.packetBytes.poke(4.U)
        println(s"[Funnel-reset][1/4] 已offer旧packet，反压下请求Active=0；cancel=$cancel")
        access(true, 0)
        d.io.active.expect(true.B); d.io.quiesce.expect(true.B)
        (access(false) & 3) shouldBe BigInt(1) // Active remains up, Enable already off
        for (_ <- 0 until 8) {
          d.io.out.valid.expect(true.B)
          d.io.out.bits.data.expect((if (cancel) 0x11111111L else 0x22222222L).U)
          d.io.in(1).ready.expect(false.B); d.clock.step()
        }
        println("[Funnel-reset][2/4] 等待期间Active仍为1、Enable为0，旧beat稳定、另一源未被接纳")
        if (cancel) {
          access(true, 3); d.io.quiesce.expect(false.B); d.io.active.expect(true.B)
          d.io.out.ready.poke(true.B); d.clock.step()
          d.io.in(0).bits.data.poke(0x22222222L.U); d.io.in(0).bits.last.poke(true.B)
        } else {
          // AF may ask for a drain, but must not start another packet while closing.
          d.io.flush.poke(true.B); d.io.out.ready.poke(true.B)
        }
        d.io.out.bits.data.expect(0x22222222L.U); d.io.out.bits.last.expect(true.B)
        d.clock.step(); d.io.in(0).valid.poke(false.B)
        if (!cancel) {
          d.io.in(1).ready.expect(false.B); d.io.out.valid.expect(false.B)
          d.clock.step(4); d.io.active.expect(false.B); d.io.quiesce.expect(false.B)
          (access(false) & 3) shouldBe BigInt(0)
          d.io.flush.poke(false.B)
          access(true, 1); d.clock.step(5); access(true, 3)
        }
        println("[Funnel-reset][3/4] 旧packet尾部完整结束后才复位；重写Active=1可取消尚未完成的关闭")
        d.io.active.expect(true.B); d.io.out.valid.expect(true.B)
        d.io.out.bits.data.expect(0x33333333L.U); d.clock.step()
        d.io.in(1).valid.poke(false.B); d.io.out.valid.expect(false.B)
        println("[Funnel-reset][4/4] 新packet单独交付，源字节未交织；AF也未绕过关闭边界")
      }
    }
  }
}
