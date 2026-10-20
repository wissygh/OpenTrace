package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import trace.control.EncoderTraceControlRegister
import trace.interface._

private class StallStatusHarness extends Module {
  val io = IO(new Bundle {
    val event = Input(Bool())
    val write = Flipped(Valid(UInt(32.W)))
    val status = Output(UInt(32.W))
  })
  val csr = withReset(reset.asAsyncReset) { Module(new EncoderTraceControlRegister) }
  csr.w.wen := io.write.valid
  csr.w.wdata := io.write.bits
  csr.traceOn := false.B; csr.traceOff := false.B
  csr.fifoIsFull := io.event; csr.fifoIsEmpty := true.B
  io.status := csr.rdata
}

class BlockBackpressureSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "core stall and ingress capacity"
  for (latency <- 0 to 2) {
    it should s"preserve peak three-group input with $latency cycles of core stall response latency" in {
      test(new BlockBuffer(latency)) { d =>
        println(s"[core-stall] 响应延迟=$latency：峰值 3 group/拍，先阻塞 consumer，再交替入队/出队并回绕")
        d.io.fromCore.priv.poke(Priv.M); d.io.fromCore.time.poke(0.U)
        d.io.fromCore.context.poke(0.U); d.io.fromCore.ctype.poke(Ctype.None)
        d.io.fromCore.cause.poke(0.U); d.io.fromCore.tval.poke(0.U)
        d.io.fromCore.impdef.poke(0.U); d.io.fromCore.hartIsReset.poke(false.B)
        d.io.fromCore.trigger.traceOn.poke(false.B); d.io.fromCore.trigger.traceOff.poke(false.B)
        d.io.fromCore.blocks.foreach { b =>
          b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.NonTaken)
          b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
          b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
        }
        d.io.deqReady.poke(false.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val expected = mutable.Queue[Int]()
        val delayed = mutable.Queue.fill(latency)(false)
        var pc = 0x1000
        var stalled = 0
        for (cycle <- 0 until 180) {
          val stall = d.io.bufferIsFull.peek().litToBoolean
          if (stall) stalled += 1
          val effective = if (latency == 0) stall else { delayed.enqueue(stall); delayed.dequeue() }
          val send = cycle < 140 && !effective
          val read = cycle >= 25 && cycle % 5 != 0
          d.io.deqReady.poke(read.B)
          if (read && d.io.deqEntry.valid.peek().litToBoolean) {
            d.io.deqEntry.traceLost.expect(false.B)
            d.io.deqEntry.iaddr.expect(expected.dequeue().U)
          }
          for (b <- d.io.fromCore.blocks) {
            b.valid.poke(send.B)
            if (send) { b.bits.iaddr.poke(pc.U); expected.enqueue(pc); pc += 4 }
          }
          d.io.overflow.expect(false.B)
          d.clock.step()
        }
        expected shouldBe empty
        stalled should be > 0
        d.io.empty.expect(true.B)
        println(s"[core-stall] 延迟=$latency：${(pc - 0x1000) / 4} 条 group 全部顺序一致，无 trace loss")
      }
    }
  }

  it should "replace a dequeued entry in a full buffer without declaring overflow" in {
    test(new BlockBuffer(0)) { d =>
      d.io.fromCore.priv.poke(Priv.M); d.io.fromCore.time.poke(0.U); d.io.fromCore.context.poke(0.U)
      d.io.fromCore.ctype.poke(Ctype.None); d.io.fromCore.cause.poke(0.U); d.io.fromCore.tval.poke(0.U)
      d.io.fromCore.impdef.poke(0.U); d.io.fromCore.hartIsReset.poke(false.B)
      d.io.fromCore.trigger.traceOn.poke(false.B); d.io.fromCore.trigger.traceOff.poke(false.B)
      d.io.fromCore.blocks.foreach { b =>
        b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.NonTaken)
        b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
        b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
      }
      d.io.deqReady.poke(false.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.fromCore.blocks(0).valid.poke(true.B)
      for (i <- 0 until 16) { d.io.fromCore.blocks(0).bits.iaddr.poke((0x1000 + i * 4).U); d.clock.step() }
      d.io.deqReady.poke(true.B)
      d.io.fromCore.blocks(0).bits.iaddr.poke(0x2000.U)
      d.io.deqEntry.iaddr.expect(0x1000.U); d.io.overflow.expect(false.B); d.clock.step()
      d.io.fromCore.blocks(0).valid.poke(false.B)
      for (pc <- (1 until 16).map(i => 0x1000 + 4 * i) :+ 0x2000) {
        d.io.deqEntry.iaddr.expect(pc.U); d.io.deqEntry.traceLost.expect(false.B); d.clock.step()
      }
      d.io.empty.expect(true.B)
      println("[core-stall] 满载同拍消费+替换通过，未制造 overflow/marker")
    }
  }

  it should "keep RW1C stall/loss status sticky and prioritize new events over software clear" in {
    test(new StallStatusHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.event.poke(false.B); d.io.write.valid.poke(false.B); d.io.write.bits.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def status: BigInt = (d.io.status.peek().litValue >> 12) & 1
      def write(value: Int): Unit = {
        d.io.write.valid.poke(true.B); d.io.write.bits.poke(value.U)
        d.clock.step(); d.io.write.valid.poke(false.B)
      }
      status shouldBe BigInt(0)
      d.io.event.poke(true.B); d.clock.step(); d.io.event.poke(false.B)
      status shouldBe BigInt(1)
      write(1); status shouldBe BigInt(1) // zero write preserves RW1C
      write(1 | (1 << 12)); status shouldBe BigInt(0)
      d.io.event.poke(true.B); write(1 | (1 << 12)); status shouldBe BigInt(1)
      d.io.event.poke(false.B); write(7); status shouldBe BigInt(0)
      println("[core-stall] 实际 CSR：事件置位、零写保持、W1C、Enable 清除、并发事件优先全部通过")
    }
  }
}
