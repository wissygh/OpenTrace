package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface.TraceBeat

class PacketArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "PacketArbiter"

  it should "retain a stalled first beat and drain its packet after disable" in {
    test(new PacketArbiter(2, 4)) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.io.disable.foreach(_.poke(false.B))
      dut.io.in.foreach { in =>
        in.valid.poke(false.B)
        in.bits.data.poke(0.U)
        in.bits.bytes.poke(3.U)
        in.bits.packetBytes.poke(8.U)
        in.bits.last.poke(false.B)
      }
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in(0).valid.poke(true.B)
      dut.io.in(0).bits.data.poke(0x10.U)
      dut.clock.step()
      dut.io.in(1).valid.poke(true.B)
      dut.io.in(1).bits.data.poke(0x20.U)
      dut.io.in(1).bits.last.poke(true.B)
      dut.io.enable.poke(false.B)
      dut.io.disable(0).poke(true.B)
      dut.io.out.bits.data.expect(0x10.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.in(0).valid.poke(true.B)
      dut.io.in(0).bits.data.poke(0x11.U)
      dut.io.in(0).bits.last.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(0x11.U)
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.io.enable.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(0x20.U)
    }
  }

  it should "keep one source selected until its last beat" in {
    test(new PacketArbiter(numSources = 2, dataBeats = 4)) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.disable(0).poke(false.B)
      dut.io.disable(1).poke(false.B)
      dut.io.in.foreach { source =>
        source.valid.poke(false.B)
        source.bits.data.poke(0.U)
        source.bits.bytes.poke(3.U)
        source.bits.packetBytes.poke(8.U)
        source.bits.last.poke(true.B)
      }

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in(0).valid.poke(true.B)
      dut.io.in(0).bits.data.poke("h10".U)
      dut.io.in(0).bits.last.poke(false.B)
      dut.io.in(1).valid.poke(false.B)
      dut.io.in(1).bits.data.poke("h20".U)
      dut.io.in(1).bits.last.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("h10".U)
      dut.clock.step()

      // Source 0 remains selected for its final beat even though source 1 is
      // continuously valid and has a complete one-beat packet.
      dut.io.in(1).valid.poke(true.B)
      dut.io.in(0).bits.data.poke("h11".U)
      dut.io.in(0).bits.last.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("h11".U)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("h20".U)
    }
  }

  it should "hold the selected beat while the sink is stalled" in {
    test(new PacketArbiter(numSources = 2, dataBeats = 4)) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.io.disable.foreach(_.poke(false.B))
      dut.io.in.foreach { source =>
        source.valid.poke(false.B)
        source.bits.data.poke(0.U)
        source.bits.bytes.poke(3.U)
        source.bits.packetBytes.poke(8.U)
        source.bits.last.poke(true.B)
      }
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in(0).valid.poke(true.B)
      dut.io.in(0).bits.data.poke("h55".U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("h55".U)
      dut.clock.step(2)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("h55".U)
    }
  }
}
