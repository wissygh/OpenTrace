package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TraceSinkPacketGuardSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "sink packet boundary recovery"
  it should "discard only the old tail across software reset and resume at a complete new packet" in {
    test(new TraceSinkPacketGuard) { d =>
      d.io.active.poke(true.B); d.io.in.valid.poke(false.B); d.io.out.ready.poke(true.B)
      d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.packetBytes.poke(9.U); d.io.in.bits.last.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke(0x1111.U)
      d.io.out.valid.expect(true.B); d.clock.step(); d.io.empty.expect(false.B)
      d.io.active.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.dropping.expect(true.B); d.io.in.ready.expect(true.B); d.io.out.valid.expect(false.B)
      d.io.in.bits.data.poke(0x2222.U); d.clock.step()
      d.io.active.poke(true.B) // new session before the old final beat arrives
      d.io.in.valid.poke(false.B); d.clock.step(3)
      d.io.dropping.expect(true.B); d.io.empty.expect(false.B)
      d.io.in.valid.poke(true.B); d.io.in.bits.last.poke(true.B); d.io.in.bits.bytes.poke(0.U)
      d.io.in.ready.expect(true.B); d.io.out.valid.expect(false.B); d.clock.step()
      d.io.empty.expect(true.B); d.io.dropping.expect(false.B)
      d.io.in.bits.packetBytes.poke(1.U); d.io.in.bits.data.poke(0x33.U)
      d.io.out.ready.poke(true.B); d.io.out.valid.expect(true.B); d.io.out.bits.data.expect(0x33.U)
      d.clock.step(); d.io.in.valid.poke(false.B); d.io.empty.expect(true.B)
      println("[sink-reset] 已接纳首beat后Active reset：只丢旧tail，提前重启也不把tail当新packet")
    }
  }

  it should "retain an unaccepted first beat and avoid consuming a new packet while inactive" in {
    test(new TraceSinkPacketGuard) { d =>
      d.io.active.poke(true.B); d.io.out.ready.poke(false.B); d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke(0x55.U); d.io.in.bits.bytes.poke(0.U)
      d.io.in.bits.packetBytes.poke(1.U); d.io.in.bits.last.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.ready.expect(false.B); d.clock.step()
      d.io.active.poke(false.B); d.io.in.ready.expect(false.B); d.io.dropping.expect(false.B)
      d.clock.step(3); d.io.empty.expect(true.B)
      d.io.active.poke(true.B); d.io.out.ready.poke(true.B)
      d.io.out.valid.expect(true.B); d.io.out.bits.data.expect(0x55.U); d.clock.step()
      d.io.in.valid.poke(false.B); d.io.empty.expect(true.B)
      println("[sink-reset] 未接纳的首beat保持于上游，inactive不额外消费整包")
    }
  }
}
