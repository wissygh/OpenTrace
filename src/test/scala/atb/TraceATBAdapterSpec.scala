package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TraceATBAdapterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "TraceATBAdapter"
  it should "use one Bridge ID and keep it stable over a packet and stalls" in {
    test(new TraceATBAdapter) { d =>
      d.io.asyncFreq.poke(0.U)
      d.io.atbm.atclken.poke(true.B)
      d.io.atbm.af.valid.poke(false.B)
      d.io.atbm.syncReq.poke(false.B)
      d.io.upstream.flushAck.poke(false.B)
      d.io.enable.poke(true.B)
      d.io.id.poke(0x20.U)
      d.io.atbm.at.ready.poke(false.B)
      d.io.in.valid.poke(false.B)
      d.io.in.bits.data.poke(0.U)
      d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.last.poke(false.B)
      d.io.in.bits.packetBytes.poke(6.U)
      d.reset.poke(true.B)
      d.clock.step()
      d.reset.poke(false.B)
      d.io.in.valid.poke(true.B)
      d.io.in.bits.data.poke("h02010004".U) // srcID=0 in the byte stream
      d.clock.step()
      d.io.id.poke(0x21.U)
      d.io.enable.poke(false.B)
      d.io.in.bits.data.poke("h0403".U)
      d.io.in.bits.bytes.poke(1.U)
      d.io.in.bits.last.poke(true.B)
      d.io.in.ready.expect(true.B)
      d.clock.step()
      d.io.in.valid.poke(false.B)
      d.io.atbm.at.bits.id.expect(0x20.U)
      d.io.atbm.at.bits.data.expect("h02010004".U)
      d.clock.step(2)
      d.io.atbm.at.bits.id.expect(0x20.U)
      d.io.atbm.at.ready.poke(true.B)
      d.clock.step()
      d.io.atbm.at.bits.id.expect(0x20.U)
      d.io.atbm.at.bits.bytes.expect(1.U)
      d.clock.step()
      d.io.empty.expect(true.B)
    }
  }
}
