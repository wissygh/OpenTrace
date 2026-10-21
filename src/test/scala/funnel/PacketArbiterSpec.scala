package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface.TraceBeat

class PacketArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "PacketArbiter"

  for (sources <- Seq(1, 3, 16)) {
    it should s"serve every continuously requesting source once per round with $sources inputs" in {
      test(new PacketArbiter(sources, 4)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
        val completed = Array.fill(sources)(0)
        val offset = Array.fill(sources)(0)
        val skipped = Array.fill(sources)(0)
        val random = new scala.util.Random(117 + sources)
        d.io.enable.poke(true.B); d.io.disable.foreach(_.poke(false.B)); d.io.out.ready.poke(false.B)
        d.io.in.foreach { in =>
          in.valid.poke(false.B); in.bits.data.poke(0.U); in.bits.bytes.poke(3.U)
          in.bits.packetBytes.poke(4.U); in.bits.last.poke(true.B)
        }
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        var held = Option.empty[(BigInt, BigInt, BigInt, Boolean)]
        var owner = Option.empty[Int]
        var total = 0
        var cycles = 0
        while (completed.min < 8 && cycles < sources * 200) {
          cycles += 1
          for (source <- 0 until sources) {
            val beats = 1 + source % 3
            val in = d.io.in(source)
            in.valid.poke(true.B); in.bits.packetBytes.poke((beats * 4).U)
            in.bits.last.poke((offset(source) == beats - 1).B)
            in.bits.data.poke(((BigInt(source) << 24) | (BigInt(completed(source)) << 8) | offset(source)).U)
          }
          d.io.out.ready.poke((random.nextInt(4) != 0).B)
          d.io.out.valid.expect(true.B)
          val observed = (d.io.out.bits.data.peek().litValue, d.io.out.bits.bytes.peek().litValue,
            d.io.out.bits.packetBytes.peek().litValue, d.io.out.bits.last.peek().litToBoolean)
          held.foreach(previous => observed shouldBe previous)
          val source = (observed._1 >> 24).toInt
          owner.foreach(previous => source shouldBe previous)
          owner = Some(source)
          val fire = d.io.out.ready.peek().litToBoolean
          for (i <- 0 until sources) d.io.in(i).ready.expect((fire && i == source).B)
          held = if (fire) None else Some(observed)
          if (fire) {
            observed._1 shouldBe ((BigInt(source) << 24) | (BigInt(completed(source)) << 8) | offset(source))
            observed._2 shouldBe BigInt(3)
            observed._3 shouldBe BigInt((1 + source % 3) * 4)
            if (observed._4) {
              for (i <- 0 until sources) {
                skipped(i) = if (i == source) 0 else skipped(i) + 1
                withClue(s"source $i starved after packet $total: ") { skipped(i) should be < sources }
              }
              completed(source) += 1; offset(source) = 0; total += 1; owner = None
            } else offset(source) += 1
          }
          d.clock.step()
        }
        completed.min should be >= 8
        completed.max - completed.min should be <= 1
        println(s"[Funnel-fairness] ${sources}源持续竞争/${total}整包/${cycles}周期，随机反压中保持包原子，每源最多等待${sources - 1}个其它包")
      }
    }
  }

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
