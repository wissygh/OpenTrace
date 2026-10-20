package trace.component

import bundles.CSRModule
import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.TraceParameters
import trace.control._
import trace.interface._

private class EncoderCapabilitiesHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val write = Input(Bool())
    val select = Input(UInt(3.W))
    val data = Input(UInt(32.W))
    val values = Output(Vec(5, UInt(32.W)))
    val block = Input(new BlockBufferEntry)
    val ready = Output(Bool())
    val stop = Input(Bool())
    val packet = Decoupled(new PacketEnq(maxPackets))
  })
  val control = withReset(reset.asAsyncReset) { Module(new EncoderTraceControlRegister) }
  val features = withReset(reset.asAsyncReset) { Module(new EncoderInstructionFeaturesRegister) }
  val data = withReset(reset.asAsyncReset) { Module(new CSRModule("TeDataControl", new TeDataControlBundle)) }
  val filters = withReset(reset.asAsyncReset) { Module(new CSRModule("TeDataFilters", new TeDataFiltersBundle)) }
  val impl = withReset(reset.asAsyncReset) { Module(new CSRModule("TeImpl", new TeImplBundle)) }
  val registers = Seq(control, features, data, filters, impl)
  registers.zipWithIndex.foreach { case (csr, i) =>
    csr.w.wen := io.write && io.select === i.U
    csr.w.wdata := io.data
    io.values(i) := csr.rdata
  }
  features.id := 3.U
  val gen = Module(new PacketGen)
  control.traceOn := false.B; control.traceOff := false.B
  control.fifoIsFull := false.B; control.fifoIsEmpty := gen.io.empty
  gen.io.fromCtrl.teCtrl := control.regOut
  gen.io.fromCtrl.teInstFeat := features.regOut
  gen.io.fromCtrl.teDataCtrl := data.regOut
  gen.io.fromCtrl.fifoIsFull := false.B; gen.io.fromCtrl.filterRes := true.B
  gen.io.syncReq := false.B; gen.io.flushReq := false.B; gen.io.stopReq := io.stop
  gen.io.block := io.block; io.ready := gen.io.blockReady; io.packet <> gen.io.packet
}

class EncoderCapabilitiesSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "implemented encoder capabilities"
  it should "reject unsupported modes and data trace while encoding the supported full-address mode" in {
    test(new EncoderCapabilitiesHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val frames = ArrayBuffer[Seq[Int]]()
      def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
        if (d.io.packet.valid.peek().litToBoolean) frames += d.io.packet.bits.data
          .take(d.io.packet.bits.len.peek().litValue.toInt).map(_.peek().litValue.toInt)
        d.clock.step()
      }
      d.io.write.poke(false.B); d.io.select.poke(0.U); d.io.data.poke(0.U)
      d.io.packet.ready.poke(true.B); d.io.stop.poke(false.B)
      d.io.block.valid.poke(false.B); d.io.block.iaddr.poke(0.U); d.io.block.itype.poke(Itype.None)
      d.io.block.iretire.poke(2.U); d.io.block.ilastsize.poke(Ilastsize.Word)
      d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
      d.io.block.notifyRequested.poke(false.B); d.io.block.traceLost.poke(false.B)
      d.io.block.priv.poke(Priv.M); d.io.block.time.poke(0.U); d.io.block.context.poke(0.U)
      d.io.block.ctype.poke(Ctype.None); d.io.block.cause.poke(0.U); d.io.block.tval.poke(0.U)
      d.io.block.impdef.poke(0.U); d.io.block.hartIsReset.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); tick(3)
      val identity = (BigInt(8) << 28) | (3 << 16)
      d.io.values(1).expect(identity.U); d.io.values(2).expect(0.U); d.io.values(3).expect(0.U)
      val implementation = d.io.values(4).peek().litValue
      ((implementation >> 16) & 255) shouldBe BigInt(0) // E-Trace protocol 0/0
      def write(index: Int, value: BigInt): Unit = {
        d.io.select.poke(index.U); d.io.data.poke(value.U); d.io.write.poke(true.B)
        tick(); d.io.write.poke(false.B); tick(5)
      }
      println("[capabilities] 1/3 真实 CSR 复位与读回：E-Trace 0/0、8-bit 固定 srcID、无 data trace")
      write(0, 7)
      write(1, BigInt("ffffffff", 16))
      d.io.values(1).expect((identity | 1).U)
      for (index <- Seq(2, 3, 4)) write(index, BigInt("ffffffff", 16))
      d.io.values(2).expect(0.U); d.io.values(3).expect(0.U); d.io.values(4).expect(implementation.U)
      val control = d.io.values(0).peek().litValue
      ((control >> 24) & 7) shouldBe BigInt(0)
      ((control >> 4) & 7) shouldBe BigInt(6)
      println("[capabilities] 2/3 全 1 能力探测：只保留 NoAddrDiff，可选压缩模式/数据功能/来源改写均被拒绝")
      for (pc <- Seq(0x1000, 0x1004)) {
        d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(pc.U)
        var accepted = false
        var remaining = 100
        while (!accepted && remaining > 0) { accepted = d.io.ready.peek().litToBoolean; tick(); remaining -= 1 }
        assert(accepted)
      }
      d.io.block.valid.poke(false.B); d.io.stop.poke(true.B); tick(); d.io.stop.poke(false.B); tick(10)
      frames.toSeq shouldBe Seq(ETraceReference.support(3), ETraceReference.support(3, fullAddress = true),
        ETraceReference.synchronization(3, 0x1000), ETraceReference.address(3, 0x1004, 0x1000, fullAddress = true),
        ETraceReference.support(3, fullAddress = true, status = 1))
      println("[capabilities] 3/3 CSR -> PacketGen 字节对拍通过：support 仅声明 fullAddr，实际地址编码一致")
      write(1, 0); d.io.values(1).expect(identity.U)
    }
  }
}
