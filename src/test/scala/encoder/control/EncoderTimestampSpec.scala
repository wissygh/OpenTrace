package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.control.EncoderTimestampControlRegister
import trace.interface._

private class EncoderTimestampHarness extends Module {
  val io = IO(new Bundle {
    val write = Flipped(Valid(UInt(32.W)))
    val debug = Input(Bool())
    val value = Output(UInt(8.W))
    val time = Output(UInt(64.W))
    val control = Output(UInt(32.W))
    val core = Input(new FromCore)
    val consume = Input(Bool())
    val sampled = Output(new BlockBufferEntry)
  })
  val control = withReset(reset.asAsyncReset) { Module(new EncoderTimestampControlRegister) }
  val counter = Module(new EncoderTimestampCounter(8))
  control.w.wen := io.write.valid; control.w.wdata := io.write.bits
  counter.io.control := control.regOut
  counter.io.resetCount := io.write.valid && control.w.wdataFields.Reset.asBool
  counter.io.debug := io.debug
  io.value := counter.io.value; io.time := counter.io.traceTime; io.control := control.rdata
  val raw = Module(new BlockBuffer)
  raw.io.fromCore := io.core; raw.io.fromCore.time := counter.io.traceTime
  raw.io.deqReady := io.consume; io.sampled := raw.io.deqEntry
}

class EncoderTimestampSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Internal Core timestamp"
  it should "divide by 1/4/16/64 and preserve sampled ingress time through backpressure" in {
    test(new EncoderTimestampHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.write.valid.poke(false.B); d.io.write.bits.poke(0.U); d.io.debug.poke(false.B)
      d.io.consume.poke(false.B)
      d.io.core.priv.poke(Priv.M); d.io.core.time.poke(0xbeef.U); d.io.core.context.poke(0.U)
      d.io.core.ctype.poke(Ctype.None); d.io.core.cause.poke(0.U); d.io.core.tval.poke(0.U)
      d.io.core.impdef.poke(0.U); d.io.core.hartIsReset.poke(false.B)
      d.io.core.trigger.traceOn.poke(false.B); d.io.core.trigger.traceOff.poke(false.B)
      d.io.core.blocks.foreach { b =>
        b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
        b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
        b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
      }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.value.expect(0.U); d.io.time.expect(0.U)
      val caps = d.io.control.peek().litValue
      ((caps >> 4) & 7) shouldBe BigInt(3)
      (caps & 1) shouldBe BigInt(1)
      def configure(prescale: Int, run: Boolean = true, debug: Boolean = false,
          enable: Boolean = true, clear: Boolean = false): Unit = {
        val value = (prescale << 8) | (if (run) 2 else 0) | (if (debug) 8 else 0) |
          (if (enable) 1 << 15 else 0) | (if (clear) 4 else 0)
        d.io.write.valid.poke(true.B); d.io.write.bits.poke(value.U)
        d.clock.step(); d.io.write.valid.poke(false.B)
      }
      for (prescale <- 0 to 3) {
        configure(prescale, run = false)
        d.clock.step(2) // apply the new divider before resetting phase
        configure(prescale, clear = true)
        d.io.value.expect(0.U)
        ((d.io.control.peek().litValue >> 2) & 1) shouldBe BigInt(0)
        val period = 1 << (2 * prescale)
        for (i <- 1 to period * 3) {
          d.clock.step(); d.io.value.expect((i / period).U)
        }
        println(s"[timestamp] prescale=$prescale，除以 $period：3 次周期边界全部准确")
      }
      configure(0, run = false); d.clock.step(2); configure(0, clear = true)
      d.clock.step(255); d.io.value.expect(255.U); d.clock.step(); d.io.value.expect(0.U)
      println("[timestamp] 缩短计数宽度的溢出回绕通过；W1 reset 清计数和相位")

      configure(1, run = false); d.clock.step(2); configure(1, clear = true)
      d.clock.step(2) // half a /4 period
      d.io.debug.poke(true.B)
      d.clock.step(9); d.io.value.expect(0.U)
      d.io.debug.poke(false.B); d.clock.step(2); d.io.value.expect(1.U)
      d.io.debug.poke(true.B); configure(1, debug = true)
      val before = d.io.value.peek().litValue
      d.clock.step(8); d.io.value.expect((before + 2).U)
      configure(1, run = false, debug = true)
      val stopped = d.io.value.peek().litValue
      d.clock.step(12); d.io.value.expect(stopped.U)
      println("[timestamp] debug 停止保留相位；RunInDebug 不覆盖 Count=0")

      d.io.debug.poke(false.B)
      configure(0, run = false, enable = false); d.clock.step(2)
      configure(0, enable = false, clear = true); d.clock.step(7)
      d.io.value.expect(7.U); d.io.time.expect(0.U)
      configure(0)
      val sampled = d.io.time.peek().litValue
      d.io.core.blocks(0).valid.poke(true.B); d.io.core.blocks(0).bits.iaddr.poke(0x1000.U)
      d.clock.step(); d.io.core.blocks(0).valid.poke(false.B)
      d.io.sampled.valid.expect(true.B); d.io.sampled.time.expect(sampled.U)
      d.clock.step(20); d.io.sampled.time.expect(sampled.U)
      d.io.time.peek().litValue should not be sampled
      d.io.consume.poke(true.B); d.clock.step(); d.io.sampled.valid.expect(false.B)
      println("[timestamp] TsEnable=0 时间字段为 0 但可计数；入队采样在 raw backpressure 时保持不变")
    }
  }
}
