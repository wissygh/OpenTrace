package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SMEMAXIWriterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "SMEM AXI4 transaction retention"
  it should "hold independent AW and W channels and retain error responses until consumed" in {
    test(new SMEMAXIWriter).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.request.valid.poke(false.B); d.io.request.bits.address.poke(0.U)
      d.io.request.bits.data.poke(0.U); d.io.request.bits.mask.poke(15.U)
      d.io.response.ready.poke(false.B)
      d.io.axi.aw.ready.poke(false.B); d.io.axi.w.ready.poke(false.B)
      d.io.axi.b.valid.poke(false.B); d.io.axi.b.bits.id.poke(0.U); d.io.axi.b.bits.resp.poke(0.U)
      d.io.axi.ar.ready.poke(true.B); d.io.axi.r.valid.poke(false.B)
      d.io.axi.r.bits.id.poke(0.U); d.io.axi.r.bits.data.poke(0.U); d.io.axi.r.bits.resp.poke(0.U)
      d.io.axi.r.bits.last.poke(true.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val address = BigInt("180000004", 16)
      for ((awFirst, response, id) <- Seq((true, 0, 0), (false, 0, 0), (true, 2, 0),
          (false, 3, 0), (true, 1, 0), (false, 0, 1))) {
        d.io.request.valid.poke(true.B); d.io.request.bits.address.poke(address.U)
        d.io.request.bits.data.poke("h76543210".U); d.io.request.bits.mask.poke(6.U)
        d.io.request.ready.expect(true.B); d.clock.step(); d.io.request.valid.poke(false.B)
        d.io.request.bits.address.poke(0.U); d.io.request.bits.data.poke(0.U)
        d.io.request.bits.mask.poke(1.U)
        def held(): Unit = {
          d.io.axi.aw.bits.addr.expect(address.U); d.io.axi.aw.bits.id.expect(0.U)
          d.io.axi.aw.bits.size.expect(2.U); d.io.axi.aw.bits.len.expect(0.U)
          d.io.axi.aw.bits.burst.expect(1.U)
          d.io.axi.w.bits.data.expect("h76543210".U); d.io.axi.w.bits.strb.expect(6.U)
          d.io.axi.w.bits.last.expect(true.B); d.io.axi.ar.valid.expect(false.B)
          d.io.request.ready.expect(false.B)
        }
        for (_ <- 0 until 4) { d.io.axi.aw.valid.expect(true.B); d.io.axi.w.valid.expect(true.B); held(); d.clock.step() }
        d.io.axi.aw.ready.poke(awFirst.B); d.io.axi.w.ready.poke((!awFirst).B); d.clock.step()
        for (_ <- 0 until 4) {
          d.io.axi.aw.valid.expect((!awFirst).B); d.io.axi.w.valid.expect(awFirst.B)
          held(); d.clock.step()
        }
        d.io.axi.aw.ready.poke((!awFirst).B); d.io.axi.w.ready.poke(awFirst.B)
        d.clock.step(); d.io.axi.aw.ready.poke(false.B); d.io.axi.w.ready.poke(false.B)
        d.io.axi.aw.valid.expect(false.B); d.io.axi.w.valid.expect(false.B)
        d.io.axi.b.ready.expect(true.B); d.io.response.valid.expect(false.B); d.clock.step(3)
        d.io.axi.b.valid.poke(true.B); d.io.axi.b.bits.resp.poke(response.U); d.io.axi.b.bits.id.poke(id.U)
        d.clock.step(); d.io.axi.b.valid.poke(false.B)
        for (_ <- 0 until 5) {
          d.io.response.valid.expect(true.B); d.io.response.bits.expect((response != 0 || id != 0).B)
          d.io.request.ready.expect(false.B); d.io.axi.b.ready.expect(false.B); d.clock.step()
        }
        d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B)
        d.io.request.ready.expect(true.B)
        println(s"[SMEM-AXI] ${if (awFirst) "AW先到" else "W先到"}，B.resp=$response id=$id，保持与错误交付通过")
      }
    }
  }
}
