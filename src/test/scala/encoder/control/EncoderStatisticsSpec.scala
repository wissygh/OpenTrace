package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random
import trace.Control2BusRegBundle
import trace.interface._

private class StatisticsIngressHarness extends Module {
  val io = IO(new Bundle {
    val core = Input(new FromCore)
    val consume = Input(Bool())
    val bus = new Control2BusRegBundle
    val dropped = Output(UInt(2.W))
    val head = Output(new BlockBufferEntry)
  })
  val buffer = Module(new BlockBuffer)
  val statistics = Module(new EncoderStatistics(0x20000000))
  buffer.io.fromCore := io.core; buffer.io.deqReady := io.consume
  statistics.io.bus.b2c := io.bus.b2c; io.bus.c2b := statistics.io.bus.c2b
  statistics.io.droppedEntries := buffer.io.droppedEntries
  statistics.io.lossMarker := buffer.io.deqEntry.valid && buffer.io.deqEntry.traceLost && io.consume
  statistics.io.coreStall := false.B
  statistics.io.packetWait := false.B; statistics.io.downstreamWait := false.B
  statistics.io.packetAccepted := false.B
  io.dropped := buffer.io.droppedEntries; io.head := buffer.io.deqEntry
}

class EncoderStatisticsSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "encoder runtime diagnostics"
  it should "separate loss and waiting events with saturating counters and concurrent clear" in {
    test(new EncoderStatistics(0x20000000, 8)) { d =>
      d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.readEn.poke(false.B)
      d.io.bus.b2c.writeEn.poke(false.B); d.io.bus.b2c.dataWrite.poke(0.U)
      d.io.droppedEntries.poke(0.U); d.io.lossMarker.poke(false.B); d.io.coreStall.poke(false.B)
      d.io.packetWait.poke(false.B); d.io.downstreamWait.poke(false.B); d.io.packetAccepted.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def read(offset: Int): BigInt = {
        d.io.bus.b2c.address.poke(offset.U); d.io.bus.b2c.readEn.poke(true.B)
        d.io.selected.expect(true.B)
        val value = d.io.bus.c2b.dataRead.peek().litValue
        d.io.bus.b2c.readEn.poke(false.B); value
      }
      read(0xf08) shouldBe BigInt(3)
      val random = new Random(20261021)
      var counts = Vector.fill(6)(0)
      var flags = 0
      for (cycle <- 0 until 500) {
        val events = Vector(random.nextInt(4), random.nextInt(2), random.nextInt(2),
          random.nextInt(2), random.nextInt(2), random.nextInt(2))
        val clear = cycle == 190 || cycle == 410
        d.io.droppedEntries.poke(events(0).U); d.io.lossMarker.poke((events(1) != 0).B)
        d.io.coreStall.poke((events(2) != 0).B); d.io.packetWait.poke((events(3) != 0).B)
        d.io.downstreamWait.poke((events(4) != 0).B); d.io.packetAccepted.poke((events(5) != 0).B)
        d.io.bus.b2c.address.poke(0xf04.U); d.io.bus.b2c.writeEn.poke(clear.B)
        d.io.bus.b2c.dataWrite.poke(1.U)
        d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
        if (clear) { counts = Vector.fill(6)(0); flags = 0 }
        counts = counts.zip(events).map { case (before, event) => math.min(255, before + event) }
        val current = (if (events(0) != 0) 1 else 0) | (events(2) << 1) | (events(3) << 2) | (events(4) << 3)
        flags |= current
        for (i <- 0 until 6) {
          read(0xf10 + i * 8) shouldBe BigInt(counts(i))
          read(0xf14 + i * 8) shouldBe BigInt(0)
        }
        read(0xf00) shouldBe BigInt((flags << 8) | current)
      }
      println("[trace-statistics] 500 周期独立事件记账，饱和/高低读回/清除与新事件并发/seen 标志通过")
    }
  }

  it should "count actual rejected raw groups and one ordered loss marker rather than a capacity warning" in {
    test(new StatisticsIngressHarness) { d =>
      val c = d.io.core
      c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
      c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
      c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
      c.blocks.foreach { b =>
        b.valid.poke(false.B); b.bits.iaddr.poke(0x1000.U); b.bits.itype.poke(Itype.NonTaken)
        b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
        b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
      }
      d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
      d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B)
      d.io.consume.poke(false.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      c.blocks.foreach(_.valid.poke(true.B))
      for (_ <- 0 until 5) { d.io.dropped.expect(0.U); d.clock.step() }
      d.io.dropped.expect(3.U); d.clock.step(2)
      c.blocks.foreach(_.valid.poke(false.B)); d.io.consume.poke(true.B)
      var markers = 0
      for (_ <- 0 until 20) {
        if (d.io.head.valid.peek().litToBoolean && d.io.head.traceLost.peek().litToBoolean) markers += 1
        d.clock.step()
      }
      markers shouldBe 1
      def read(offset: Int): BigInt = {
        d.io.bus.b2c.address.poke(offset.U); d.io.bus.b2c.readEn.poke(true.B)
        val value = d.io.bus.c2b.dataRead.peek().litValue
        d.io.bus.b2c.readEn.poke(false.B); value
      }
      read(0xf10) shouldBe BigInt(6)
      read(0xf18) shouldBe BigInt(1)
      for (i <- 2 until 6) read(0xf10 + i * 8) shouldBe BigInt(0)
      println("[trace-statistics] 真实 raw buffer：6 个拒收 group、1 个恢复 marker；无虚构 packet drop")
    }
  }
}
