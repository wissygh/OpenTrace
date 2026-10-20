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
      dut.io.fromCore.blocks(0).bits.iretire.poke(1.U)
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
}
