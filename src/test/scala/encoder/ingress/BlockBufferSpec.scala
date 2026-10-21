package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface._

class BlockBufferSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "BlockBuffer"

  it should "hold its head entry until the consumer is ready" in {
    test(new BlockBuffer) { dut =>
      dut.io.fromCore.priv.poke(Priv.M)
      dut.io.fromCore.time.poke(0.U)
      dut.io.fromCore.context.poke(0.U)
      dut.io.fromCore.ctype.poke(Ctype.None)
      dut.io.fromCore.cause.poke(0.U)
      dut.io.fromCore.tval.poke(0.U)
      dut.io.fromCore.impdef.poke(0.U)
      dut.io.fromCore.hartIsReset.poke(false.B)
      dut.io.fromCore.trigger.traceOn.poke(false.B)
      dut.io.fromCore.trigger.traceOff.poke(false.B)
      dut.io.fromCore.blocks.foreach { block =>
        block.valid.poke(false.B)
        block.bits.iaddr.poke(0.U)
        block.bits.itype.poke(Itype.None)
        block.bits.iretire.poke(0.U)
        block.bits.ilastsize.poke(Ilastsize.HalfWord)
        block.bits.sijump.foreach(_.poke(false.B))
        block.bits.trigNotify.poke(false.B)
      }
      dut.io.deqReady.poke(false.B)

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.fromCore.blocks(0).valid.poke(true.B)
      dut.io.fromCore.blocks(0).bits.iaddr.poke("h1000".U)
      dut.io.fromCore.blocks(0).bits.itype.poke(Itype.None)
      dut.io.fromCore.blocks(0).bits.iretire.poke(2.U)
      dut.io.fromCore.blocks(0).bits.ilastsize.poke(Ilastsize.Word)
      dut.clock.step()

      dut.io.fromCore.blocks(0).valid.poke(false.B)
      dut.io.deqEntry.valid.expect(true.B)
      dut.io.deqEntry.iaddr.expect("h1000".U)
      dut.clock.step()

      // The entry must remain at the head while the consumer is stalled.
      dut.io.deqEntry.valid.expect(true.B)
      dut.io.deqEntry.iaddr.expect("h1000".U)

      dut.io.deqReady.poke(true.B)
      dut.clock.step()
      dut.io.deqEntry.valid.expect(false.B)
    }
  }

  it should "preserve empty trigger cycles, end actions on the newest group, and exact notify boundaries" in {
    test(new BlockBuffer) { d =>
      println("[lifecycle-ingress] 校验无退休触发、同周期多 group 的首/尾动作及 notify 边界")
      d.io.fromCore.priv.poke(Priv.M)
      d.io.fromCore.time.poke(0.U); d.io.fromCore.context.poke(0.U)
      d.io.fromCore.ctype.poke(Ctype.None); d.io.fromCore.cause.poke(0.U)
      d.io.fromCore.tval.poke(0.U); d.io.fromCore.impdef.poke(0.U)
      d.io.fromCore.hartIsReset.poke(false.B)
      d.io.fromCore.trigger.traceOn.poke(false.B)
      d.io.fromCore.trigger.traceOff.poke(false.B)
      d.io.fromCore.blocks.foreach { b =>
        b.valid.poke(false.B); b.bits.iaddr.poke(0.U)
        b.bits.itype.poke(Itype.None); b.bits.iretire.poke(2.U)
        b.bits.ilastsize.poke(Ilastsize.Word); b.bits.trigNotify.poke(false.B)
        b.bits.sijump.foreach(_.poke(false.B))
      }
      d.io.deqReady.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.fromCore.trigger.traceOn.poke(true.B)
      d.io.fromCore.ctype.poke(Ctype.Precisely)
      d.io.fromCore.trigger.traceOff.poke(true.B); d.clock.step()
      d.io.fromCore.trigger.traceOn.poke(false.B)
      d.io.fromCore.trigger.traceOff.poke(false.B)
      d.io.deqEntry.valid.expect(true.B)
      d.io.deqEntry.iretire.expect(0.U)
      d.io.deqEntry.trigger.expect(TriggerAction.TraceOn)
      d.io.deqEntry.traceOffAfter.expect(true.B)
      d.io.deqEntry.notifyRequested.expect(false.B)
      d.io.deqReady.poke(true.B); d.clock.step()
      d.io.deqEntry.valid.expect(false.B)

      d.io.deqReady.poke(false.B)
      d.io.fromCore.blocks.zipWithIndex.foreach { case (b, i) =>
        b.valid.poke(true.B); b.bits.iaddr.poke((0x1000 + i * 4).U)
        b.bits.itype.poke(Itype.NonTaken)
      }
      d.io.fromCore.trigger.traceOn.poke(true.B)
      d.io.fromCore.ctype.poke(Ctype.Precisely)
      d.io.fromCore.trigger.traceOff.poke(true.B); d.clock.step()
      d.io.fromCore.blocks.foreach(_.valid.poke(false.B))
      d.io.fromCore.trigger.traceOn.poke(false.B)
      d.io.fromCore.trigger.traceOff.poke(false.B)
      for (i <- 0 until 3) {
        d.io.deqEntry.iaddr.expect((0x1000 + 4 * i).U)
        d.io.deqEntry.trigger.expect((if (i == 0) 1 else if (i == 2) 2 else 0).U)
        d.io.deqEntry.traceOffAfter.expect(false.B)
        d.io.deqEntry.ctype.expect((if (i == 0) Ctype.Precisely else Ctype.None))
        d.io.deqReady.poke(true.B); d.clock.step()
      }
      d.io.deqEntry.valid.expect(false.B)

      d.io.deqReady.poke(false.B)
      d.io.fromCore.blocks.zipWithIndex.foreach { case (b, i) =>
        b.valid.poke(true.B); b.bits.iaddr.poke((0x2000 + i * 4).U)
        b.bits.itype.poke(Itype.None); b.bits.trigNotify.poke((i == 0).B)
      }
      d.io.fromCore.trigger.traceOn.poke(true.B); d.clock.step()
      d.io.fromCore.trigger.traceOn.poke(false.B)
      d.io.fromCore.blocks.foreach(_.valid.poke(false.B))
      d.io.deqEntry.iaddr.expect(0x2000.U)
      d.io.deqEntry.iretire.expect(2.U)
      d.io.deqEntry.trigger.expect(TriggerAction.TraceOn)
      d.io.deqEntry.notifyRequested.expect(true.B)
      d.io.deqReady.poke(true.B); d.clock.step()
      d.io.deqEntry.iaddr.expect(0x2004.U)
      d.io.deqEntry.iretire.expect(4.U)
      d.io.deqEntry.notifyRequested.expect(false.B)
      d.clock.step(); d.io.deqEntry.valid.expect(false.B)
    }
  }
}
