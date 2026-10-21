package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import bundles.CSRModule
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.control.FunnelDisInputBundle

private class FunnelMaskHarness extends Module {
  val io = IO(new Bundle {
    val write = Input(Bool())
    val data = Input(UInt(32.W))
    val mask = Output(Vec(4, UInt(32.W)))
  })
  for ((inputs, i) <- Seq(1, 2, 7, 16).zipWithIndex) {
    val csr = withReset(reset.asAsyncReset) { Module(new CSRModule("FunnelDisInput", new FunnelDisInputBundle(inputs))) }
    csr.w.wen := io.write; csr.w.wdata := io.data; io.mask(i) := csr.rdata
  }
}

class FunnelInputSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Funnel disabled-input packet routing"
  it should "discover only implemented mask bits and ignore reserved writes" in {
    test(new FunnelMaskHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.write.poke(false.B); d.io.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.mask.foreach(_.expect(0.U))
      for (word <- Seq(BigInt("ffffffff", 16), BigInt("a55a5aa5", 16), BigInt(0))) {
        d.io.write.poke(true.B); d.io.data.poke(word.U); d.clock.step(); d.io.write.poke(false.B)
        for ((n, i) <- Seq(1, 2, 7, 16).zipWithIndex) d.io.mask(i).expect((word & ((BigInt(1) << n) - 1)).U)
      }
      println("[Funnel-mask] 1/2/7/16输入配置仅返回实际端口mask，保留位写入无效，reset为0")
    }
  }

  it should "discard masked packets independently of downstream stalls without leaking tails during unmask" in {
    test(new TraceFunnelCore(2)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.enable.poke(true.B); d.io.quiesce.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.control.flushReq.poke(false.B); d.io.control.syncReq.poke(false.B)
      d.io.sourceControl.foreach(_.flushAck.poke(false.B))
      d.io.disable.foreach(_.poke(false.B))
      d.io.in.foreach { in =>
        in.valid.poke(false.B); in.bits.data.poke(0.U); in.bits.bytes.poke(3.U)
        in.bits.packetBytes.poke(8.U); in.bits.last.poke(false.B)
      }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.in(0).valid.poke(true.B); d.io.in(0).bits.data.poke(0x10.U)
      d.io.out.valid.expect(true.B); d.clock.step() // first offer locked while stalled
      d.io.disable(0).poke(true.B)
      d.io.disable(1).poke(true.B); d.io.in(1).valid.poke(true.B); d.io.in(1).bits.data.poke(0x20.U)
      d.io.in(0).ready.expect(false.B); d.io.in(1).ready.expect(true.B)
      d.io.out.bits.data.expect(0x10.U); d.clock.step() // source1 first beat discarded
      println("[Funnel-mask][1/4] 下游反压下disabled源独立消费；已offer源即使被屏蔽仍稳定")
      d.io.disable(1).poke(false.B); d.io.in(1).valid.poke(false.B)
      d.clock.step(3); d.io.idle.expect(false.B)
      d.io.in(1).valid.poke(true.B); d.io.in(1).bits.data.poke(0x21.U); d.io.in(1).bits.last.poke(true.B)
      d.io.in(1).ready.expect(true.B); d.io.out.bits.data.expect(0x10.U); d.clock.step()
      d.io.in(1).valid.poke(false.B)
      println("[Funnel-mask][2/4] 包中途unmask仍只丢旧tail，不借用新输出packet边界")
      d.io.out.ready.poke(true.B); d.clock.step()
      d.io.in(0).bits.data.poke(0x11.U); d.io.in(0).bits.last.poke(true.B)
      d.io.out.bits.data.expect(0x11.U); d.clock.step()
      d.io.in(0).bits.data.poke(0x12.U); d.io.in(0).bits.last.poke(false.B)
      d.io.out.valid.expect(false.B); d.io.in(0).ready.expect(true.B); d.clock.step()
      d.io.in(0).valid.poke(false.B)
      d.io.quiesce.poke(true.B); d.io.idle.expect(false.B); d.clock.step(2)
      d.io.in(0).valid.poke(true.B); d.io.in(0).bits.last.poke(true.B)
      d.io.in(0).ready.expect(true.B); d.clock.step()
      d.io.in(0).valid.poke(false.B); d.io.idle.expect(true.B)
      println("[Funnel-mask][3/4] 中途disable在原输出包结束后才生效；Active关闭也等已开始discard完整结束")
      d.io.quiesce.poke(false.B); d.io.control.flushReq.poke(true.B)
      d.io.in(0).valid.poke(true.B); d.io.in(0).bits.data.poke(0x13.U)
      d.io.in(1).valid.poke(true.B); d.io.in(1).bits.packetBytes.poke(4.U)
      d.io.in(1).bits.data.poke(0x22.U)
      d.io.in(0).ready.expect(true.B); d.io.in(1).ready.expect(true.B)
      d.io.out.valid.expect(true.B); d.io.out.bits.data.expect(0x22.U); d.clock.step()
      d.io.in.foreach(_.valid.poke(false.B)); d.io.sourceControl.foreach(_.flushAck.poke(true.B))
      d.io.control.flushAck.expect(true.B); d.io.empty.expect(false.B)
      d.io.control.flushReq.poke(false.B); d.clock.step()
      d.io.sourceControl.foreach(_.flushAck.poke(false.B)); d.clock.step()
      d.io.empty.expect(true.B)
      println("[Funnel-mask][4/4] AF flush仍丢弃disabled数据，只输出可用源，确认两源旧前沿后Empty")
    }
  }
}
