package trace.control

import bundles.CSRModule
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class ComponentDiscoveryHarness extends Module {
  val io = IO(new Bundle {
    val write = Input(Bool())
    val select = Input(UInt(3.W))
    val data = Input(UInt(32.W))
    val values = Output(Vec(7, UInt(32.W)))
  })
  val encoder = withReset(reset.asAsyncReset) { Module(new CSRModule("EncoderImpl", new TeImplBundle)) }
  val funnel = withReset(reset.asAsyncReset) { Module(new CSRModule("FunnelImpl", new FunnelImplBundle)) }
  val ram = withReset(reset.asAsyncReset) { Module(new CSRModule("RamImpl", new RAMImplBundle)) }
  val pib = withReset(reset.asAsyncReset) { Module(new CSRModule("PibImpl", new PIBImplBundle)) }
  val atb = withReset(reset.asAsyncReset) { Module(new CSRModule("AtbImpl", new ATBImplBundle)) }
  val pibControl = withReset(reset.asAsyncReset) { Module(new PIBControlRegister) }
  val ramControl = withReset(reset.asAsyncReset) { Module(new CSRModule("RamControl", new RAMControlBundle)) }
  Seq(encoder, funnel, ram, pib, atb, pibControl, ramControl).zipWithIndex.foreach { case (csr, i) =>
    csr.w.wen := io.write && io.select === i.U
    csr.w.wdata := io.data
    io.values(i) := csr.rdata
  }
}

class ComponentDiscoverySpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "trace component discovery"
  it should "place version and component fields correctly and keep unimplemented PIB modes disabled" in {
    test(new ComponentDiscoveryHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.write.poke(false.B); d.io.select.poke(0.U); d.io.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      var words = Seq(0x101, 0x801, 0x1901, 0xa01, 0xe01)
      def check(): Unit = {
        for ((word, i) <- words.zipWithIndex) d.io.values(i).expect(word.U)
        (d.io.values(6).peek().litValue & ((3 << 9) | (7 << 12))) shouldBe BigInt(0)
        d.io.values(5).expect(9.U) // Active alias=1, Empty=1, no serializer features
      }
      check()
      for (index <- 0 until 7; word <- Seq(BigInt(0), BigInt("ffffffff", 16))) {
        d.io.select.poke(index.U); d.io.data.poke(word.U); d.io.write.poke(true.B)
        d.clock.step(); d.io.write.poke(false.B)
        if (index == 4) words = words.updated(4, 0xe01 | (((word >> 12).toInt & 7) << 12))
        check()
      }
      println("[discovery] Encoder/Funnel/RAM/PIB/ATB 完整 Impl 位布局与只读值通过")
      println("[discovery] PIB 占位组件的 Enable/Mode/时钟/校准均读回 0，不宣称未实现输出")
    }
  }
}
