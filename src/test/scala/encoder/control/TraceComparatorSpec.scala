package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.TraceParameters
import trace.interface._

private class TraceComparatorHarness extends Module {
  val io = IO(new Bundle {
    val block = Input(new BlockBufferEntry)
    val control = Input(UInt(32.W))
    val primary = Input(UInt(64.W))
    val secondary = Input(UInt(64.W))
    val configure = Input(Bool())
    val advance = Input(Bool())
    val matched = Output(Bool())
    val notification = Output(Bool())
  })
  val unit = Module(new TraceComparator)
  unit.io.block := io.block; unit.io.control := io.control
  unit.io.primary := io.primary; unit.io.secondary := io.secondary
  unit.io.configure := io.configure; unit.io.advance := io.advance
  io.matched := unit.io.matched; io.notification := unit.io.notification
}

class TraceComparatorSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "trace comparator"
  it should "compare unsigned masked input widths and advance range state only on acceptance" in {
    test(new TraceComparatorHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(0.U); d.io.block.itype.poke(Itype.None)
      d.io.block.iretire.poke(2.U); d.io.block.ilastsize.poke(Ilastsize.Word)
      d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
      d.io.block.notifyRequested.poke(false.B); d.io.block.traceLost.poke(false.B)
      d.io.block.priv.poke(Priv.M); d.io.block.time.poke(0.U); d.io.block.context.poke(0.U)
      d.io.block.ctype.poke(Ctype.None); d.io.block.cause.poke(0.U); d.io.block.tval.poke(0.U)
      d.io.block.impdef.poke(0.U); d.io.block.hartIsReset.poke(false.B)
      d.io.control.poke(0.U); d.io.primary.poke(0.U); d.io.secondary.poke(0.U)
      d.io.configure.poke(false.B); d.io.advance.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def control(p: Int = 0, s: Int = 7, mode: Int = 0, pi: Int = 0, si: Int = 0,
          pn: Boolean = false, sn: Boolean = false): Unit =
        d.io.control.poke((pi | (si << 2) | (p << 4) | (s << 8) | (mode << 12) |
          (if (pn) 1 << 14 else 0) | (if (sn) 1 << 15 else 0)).U)
      val pairs = Seq((BigInt(0), BigInt(0)), (BigInt(0), BigInt(1)), (BigInt(1), BigInt(0)),
        (BigInt(0x1234), BigInt(0x1234)), ((BigInt(1) << 49) + 4, BigInt(0x1000)))
      for ((data, value) <- pairs; f <- 0 to 7) {
        d.io.block.iaddr.poke(data.U); d.io.primary.poke(value.U); control(p = f)
        val expected = Seq(data == value, data != value, data < value, data <= value,
          data > value, data >= value, false, true)(f)
        d.io.matched.expect(expected.B)
      }
      for (f <- 0 to 7; data <- Seq(0x10, 0x20, 0x30); mode <- 0 to 2) {
        d.io.block.iaddr.poke(data.U); d.io.primary.poke(0x20.U); d.io.secondary.poke(0x20.U)
        control(p = 0, s = f, mode = mode)
        val p = if (f == 6) (data & 0x20) == 0x20 else data == 0x20
        val s = Seq(data == 0x20, data != 0x20, data < 0x20, data <= 0x20,
          data > 0x20, data >= 0x20, true, true)(f)
        d.io.matched.expect(Seq(p, p && s, !(p && s))(mode).B)
      }
      println("[comparator] 1/4 P/S unsigned 真值、P/PS/NonPS 组合通过")
      for ((select, width) <- Seq((0, 50), (1, 32), (2, 50))) {
        d.io.block.iaddr.poke(0x1234.U); d.io.block.context.poke(0x1234.U); d.io.block.tval.poke(0x1234.U)
        d.io.primary.poke(((BigInt(1) << width) | 0x1234).U)
        control(pi = select); d.io.matched.expect(true.B)
      }
      d.io.block.iaddr.poke(0xa5.U); d.io.primary.poke(0xaf.U); d.io.secondary.poke(0xf0.U)
      control(s = 6); d.io.matched.expect(true.B)
      println("[comparator] 2/4 iaddr/context/tval 高位忽略；mask 同时作用于输入和值")

      d.io.primary.poke(10.U); d.io.secondary.poke(20.U); control(s = 0, mode = 3)
      d.io.block.iaddr.poke(10.U); d.io.matched.expect(true.B); d.clock.step(5)
      d.io.block.iaddr.poke(15.U); d.io.matched.expect(false.B) // no accepted start
      d.io.block.iaddr.poke(10.U); d.io.advance.poke(true.B); d.clock.step(); d.io.advance.poke(false.B)
      d.io.block.iaddr.poke(15.U); d.io.matched.expect(true.B)
      d.io.block.iaddr.poke(20.U); d.io.matched.expect(true.B); d.clock.step(5)
      d.io.block.iaddr.poke(15.U); d.io.matched.expect(true.B) // stalled end cannot clear
      d.io.block.iaddr.poke(20.U); d.io.advance.poke(true.B); d.clock.step(); d.io.advance.poke(false.B)
      d.io.block.iaddr.poke(21.U); d.io.matched.expect(false.B)
      d.io.secondary.poke(10.U); d.io.block.iaddr.poke(10.U)
      d.io.advance.poke(true.B); d.clock.step(); d.io.advance.poke(false.B)
      d.io.block.iaddr.poke(11.U); d.io.matched.expect(false.B) // same P/S includes current only
      control(p = 6, s = 0, mode = 3); d.io.secondary.poke(20.U)
      d.io.configure.poke(true.B); d.clock.step(); d.io.configure.poke(false.B)
      d.io.block.iaddr.poke(15.U); d.io.matched.expect(true.B)
      d.io.block.iaddr.poke(20.U); d.io.advance.poke(true.B); d.clock.step(); d.io.advance.poke(false.B)
      d.io.block.iaddr.poke(21.U); d.io.matched.expect(false.B)
      println("[comparator] 3/4 PUntilS 起止均含当前条目，stall 不推进；PFunc=6 编程置位通过")

      control(pn = true); d.io.primary.poke(10.U); d.io.block.iaddr.poke(11.U)
      d.io.matched.expect(false.B); d.io.notification.expect(false.B)
      d.io.block.iaddr.poke(10.U); d.io.matched.expect(true.B); d.io.notification.expect(true.B)
      control(pi = 1, pn = true); d.io.block.context.poke(10.U)
      d.io.matched.expect(true.B); d.io.notification.expect(false.B)
      control(p = 6, s = 0, sn = true); d.io.secondary.poke(10.U)
      d.io.notification.expect(true.B)
      println("[comparator] 4/4 watchpoint 仅真实地址比较匹配时通知，不把 notify 当作匹配输入")
    }
  }
}
