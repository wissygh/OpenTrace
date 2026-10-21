package trace.control

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.component.{Mictor38TraceMapper, PIBSerializer}
import trace.interface.{PIBTracePins, TraceBeat}

private class PIBControlHarness extends Module {
  val io = IO(new Bundle {
    val write = Flipped(Valid(UInt(32.W)))
    val control = Output(UInt(32.W))
    val in = Flipped(Decoupled(new TraceBeat(4)))
    val pins = new PIBTracePins
  })
  val csr = withReset(reset.asAsyncReset) { Module(new PIBControlRegister(implemented = true)) }
  val serializer = Module(new PIBSerializer(startupAlignment = false))
  csr.w.wen := io.write.valid; csr.w.wdata := io.write.bits
  serializer.io.in <> io.in
  serializer.io.enable := csr.regOut.Enable.asBool
  serializer.io.mode := csr.regOut.Mode.asUInt
  serializer.io.divider := csr.regOut.Divider.asUInt
  serializer.io.center := csr.regOut.ClkCenter.asBool
  serializer.io.calibrate := csr.regOut.Calibrate.asBool
  csr.empty.get := serializer.io.empty
  io.control := csr.rdata; io.pins <> serializer.io.pins
}

class PIBControlSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "PIB configuration and board trace mapping"
  it should "hold mode and timing until disabled and drained and expose only supported configuration" in {
    test(new PIBControlHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.write.valid.poke(false.B); d.io.write.bits.poke(0.U)
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(0.U)
      d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(1.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def control: BigInt = d.io.control.peek().litValue
      (control >> 16) shouldBe BigInt(31)
      def write(mode: Int, divider: Int = 0, enable: Boolean = false, center: Boolean = false,
          calibrate: Boolean = false): Unit = {
        val word = 1 | (mode << 4) | (divider << 16) | (if (enable) 2 else 0) |
          (if (center) 256 else 0) | (if (calibrate) 512 else 0)
        d.io.write.valid.poke(true.B); d.io.write.bits.poke(word.U)
        d.clock.step(); d.io.write.valid.poke(false.B)
      }
      for (mode <- Seq(4, 5, 8, 9, 10, 11, 12)) {
        write(mode, 3, center = true)
        ((control >> 4) & 15) shouldBe BigInt(mode)
        ((control >> 8) & 1) shouldBe BigInt(if (mode >= 8) 1 else 0)
        (control >> 16) shouldBe BigInt(3)
      }
      write(7); ((control >> 4) & 15) shouldBe BigInt(12)
      write(11, enable = true); d.clock.step(2)
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h04030201".U)
      d.io.in.bits.bytes.poke(3.U); d.io.in.bits.last.poke(false.B); d.io.in.bits.packetBytes.poke(7.U)
      d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      (control & 8) shouldBe BigInt(0)
      write(5, 20, enable = true, center = true)
      ((control >> 4) & 15) shouldBe BigInt(11); (control >> 16) shouldBe BigInt(0)
      write(5, 20)
      ((control >> 4) & 15) shouldBe BigInt(11)
      write(4, 25) // still collecting, even though Enable is now zero
      ((control >> 4) & 15) shouldBe BigInt(11)
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h070605".U)
      d.io.in.bits.bytes.poke(2.U); d.io.in.bits.last.poke(true.B); d.io.in.ready.expect(true.B)
      d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(20)
      (control & 8) shouldBe BigInt(8)
      write(5, 3); ((control >> 4) & 15) shouldBe BigInt(5); (control >> 16) shouldBe BigInt(3)
      println("[PIB-control] WARL模式/clock-center、运行和Disable未排空时配置保持、完整排空后切换通过")
      write(11, calibrate = true); write(11, enable = true, calibrate = true)
      d.clock.step(12); d.io.in.ready.expect(false.B)
      write(11); d.clock.step(3); (control & 8) shouldBe BigInt(8)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      ((control >> 4) & 15) shouldBe BigInt(0); (control >> 16) shouldBe BigInt(31)
      println("[PIB-control] 校准不接纳trace，Disable后quiescent，Active式复位能力读回正确")
    }
  }

  it should "map the target trace clock and each data bit to the documented Mictor pins" in {
    test(new Mictor38TraceMapper) { d =>
      val pins = Seq(38, 28, 26, 24, 22, 20, 18, 16, 37, 35, 33, 31, 29, 27, 25, 23)
      d.io.trace.clock.poke(false.B); d.io.trace.control.poke(true.B); d.io.externalTrigger.poke(false.B)
      for (bit <- 0 until 16) {
        d.io.trace.data.poke((1 << bit).U)
        for ((pin, index) <- pins.zipWithIndex) d.io.pins(pin - 1).expect((index == bit).B)
        d.io.pins(29).expect(false.B); d.io.pins(31).expect(false.B)
        d.io.pins(33).expect(true.B); d.io.pins(35).expect(false.B)
      }
      d.io.trace.clock.poke(true.B); d.io.pins(5).expect(true.B)
      d.io.externalTrigger.poke(true.B); d.io.pins(9).expect(true.B)
      val mask = (pins ++ Seq(6, 10, 30, 32, 34, 36)).foldLeft(BigInt(0))((m, pin) => m.setBit(pin - 1))
      d.io.driven.expect(mask.U)
      println("[PIB-Mictor] pin6 clock、16路数据、EXTTRIG、固定30/32/34/36与板级驱动mask通过")
    }
  }
}
