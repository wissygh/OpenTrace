package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import IngressReference._

class IngressReferenceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "independent retirement and control-event queue model"
  for (seed <- Seq(3, 41, 127)) {
    it should s"compare every entry and ordered gap through randomized stalls resets and triggers seed $seed" in {
      test(new BlockBuffer).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
        val model = new Buffer(depth = 16, reserve = 6)
        val random = new scala.util.Random(seed)
        var lost = 0; var dropped = 0; var consumed = 0; var resetCount = 0
        val kinds = scala.collection.mutable.Set.empty[Int]
        val triggers = scala.collection.mutable.Set.empty[Int]
        def run(cycle: Cycle, ready: Boolean): Unit = {
          val c = d.io.fromCore; val m = cycle.metadata
          c.priv.poke(m.privilege.U); c.time.poke(m.time.U); c.context.poke(m.context.U); c.ctype.poke(m.ctype.U)
          c.cause.poke(m.cause.U); c.tval.poke(m.tval.U); c.impdef.poke(m.impdef.U); c.hartIsReset.poke(m.hartReset.B)
          c.trigger.traceOn.poke(cycle.traceOn.B); c.trigger.traceOff.poke(cycle.traceOff.B)
          for ((b, i) <- c.blocks.zipWithIndex) {
            val g = cycle.groups.lift(i).getOrElse(Group(0, 0, 0, 0, false))
            b.valid.poke((i < cycle.groups.size).B); b.bits.iaddr.poke(g.pc.U); b.bits.itype.poke(g.kind.U)
            b.bits.iretire.poke(g.halfwords.U); b.bits.ilastsize.poke(g.lastSize.U); b.bits.trigNotify.poke(g.notification.B)
            b.bits.sijump.foreach(_.poke(false.B))
          }
          d.io.deqReady.poke(ready.B)
          val expected = model.step(cycle, ready)
          d.io.empty.expect(expected.empty.B); d.io.bufferIsFull.expect(expected.full.B)
          d.io.droppedEntries.expect(expected.dropped.U); d.io.overflow.expect((expected.dropped != 0).B)
          dropped += expected.dropped
          val head = d.io.deqEntry
          head.valid.expect(expected.head.nonEmpty.B)
          expected.head.foreach { e =>
            val g = e.group; val m = e.metadata
            head.iaddr.expect(g.pc.U); head.itype.expect(g.kind.U); head.iretire.expect(g.halfwords.U)
            head.ilastsize.expect(g.lastSize.U); head.notifyRequested.expect(g.notification.B)
            head.priv.expect(m.privilege.U); head.time.expect(m.time.U); head.context.expect(m.context.U)
            head.ctype.expect(m.ctype.U); head.cause.expect(m.cause.U); head.tval.expect(m.tval.U)
            head.impdef.expect(m.impdef.U); head.hartIsReset.expect(m.hartReset.B)
            head.trigger.expect(e.trigger.U); head.traceOffAfter.expect(e.offAfter.B); head.traceLost.expect(e.lost.B)
            if (ready) { consumed += 1; if (e.lost) lost += 1; kinds += g.kind; triggers += e.trigger }
          }
          d.clock.step()
        }
        val idle = Cycle(Seq.empty, Metadata())
        run(idle, false); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        for (cycle <- 0 until 1800) {
          if (Set(510, 1020, 1530)(cycle)) {
            d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); model.reset(); resetCount += 1
          }
          val count = random.nextInt(4)
          val groups = (0 until count).map { i =>
            val choices = if (i == count - 1) Seq(0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13, 14, 15)
              else Seq(0, 0, 0, 4, 5, 8, 13)
            val kind = choices(random.nextInt(choices.size))
            val size = random.nextInt(2)
            Group(BigInt("80010000", 16) + cycle * 64 + i * 16, kind,
              if ((kind == 1 || kind == 2) && random.nextBoolean()) 0 else (1 << size) + random.nextInt(5), size,
              random.nextInt(7) == 0)
          }
          val meta = Metadata(Seq(0, 1, 3, 4, 5, 6)(random.nextInt(6)), BigInt(cycle) << 36,
            BigInt(32, random), random.nextInt(4), random.nextInt(256), BigInt(50, random), BigInt(32, random), cycle % 53 == 0)
          val input = Cycle(groups, meta, cycle % 23 == 0, cycle % 29 == 0)
          withClue(s"seed=$seed cycle=$cycle: ") { run(input, cycle % 151 >= 40 && random.nextInt(4) != 0) }
        }
        for (_ <- 0 until 50) run(idle, true)
        d.io.empty.expect(true.B)
        lost should be > 0; dropped should be > 0; consumed should be > 200
        kinds should contain allElementsOf Seq(0, 1, 2, 3, 4, 5, 8, 13)
        triggers.toSet shouldBe Set(0, 1, 2, 3)
        println(s"[ingress-reference] seed=$seed 1800随机周期/${resetCount}次非空复位/${consumed}条完整entry/${dropped}条拒收/${lost}个有序loss；所有sideband/trigger/ctype逐项一致，PC>0x80000000")
      }
    }
  }
}
