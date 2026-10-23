package trace.component

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PacketJumpCacheCostSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "jump cache packet cost selection"

  it should "keep short signed address deltas and use indices only when no larger after sign compression" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      var smallerAddress = 0
      var smallerIndex = 0
      var equal = 0
      var cases = 0
      println("[cache-cost] actual PacketGen: warm target→other reference→hit; compare independent wire bytes, with output stalls")
      for (mode <- Seq(32, 33, 40, 41);
           offset <- Seq(0, 0x80, 0x100, 0x1fc);
           delta <- Seq(-4096, -256, -16, -4, 4, 16, 256, 4096);
           count <- Seq(0, 1, 2, 3, 4, 7, 8, 15, 16, 30)) {
        val target = BigInt(0x80003000L + offset)
        val reference = target - delta
        // Equal low index means the intermediate miss replaced the warmed entry.
        val hit = ((target >> 1) & 255) != ((reference >> 1) & 255)
        val full = (mode & 1) != 0
        val returns = (mode & 8) != 0
        val outcomes = (0 until count).map(i => i % 3 == 0)
        stream.initialize(mode)
        stream.send(Seq(PacketInput(0x80001000L), PacketInput(0x80001004L, 8), PacketInput(target, 8),
          PacketInput(reference)) ++ outcomes.zipWithIndex.map { case (taken, i) =>
          PacketInput(reference + 4 + i * 4, if (taken) 5 else 4)
        } ++ Seq(PacketInput(reference + 4 + count * 4, 8), PacketInput(target), PacketInput(target + 4)))
        stream.stop()
        val address = ETraceReference.address(3, target, reference, outcomes, fullAddress = full)
        val index = ETraceReference.cachedTarget(3, target, outcomes)
        val selected = if (hit && index.size <= address.size) index else address
        if (hit) {
          if (address.size < index.size) smallerAddress += 1
          else if (index.size < address.size) smallerIndex += 1
          else equal += 1
        }
        withClue(s"mode=$mode offset=$offset delta=$delta count=$count hit=$hit: ") {
          stream.frames.toSeq shouldBe Seq(
            ETraceReference.support(3, fullAddress = full, jumpCache = true, implicitReturn = returns),
            ETraceReference.synchronization(3, 0x80001000L),
            ETraceReference.address(3, target, 0x80001000L, fullAddress = full),
            ETraceReference.address(3, reference, target, fullAddress = full),
            selected,
            ETraceReference.address(3, target + 4, target, fullAddress = full),
            ETraceReference.support(3, fullAddress = full, jumpCache = true, implicitReturn = returns, status = 1))
          selected.size should be <= address.size
        }
        cases += 1
      }
      smallerAddress should be > 0
      smallerIndex should be > 0
      equal should be > 0
      println(s"[cache-cost] $cases cases passed; address shorter=$smallerAddress, index shorter=$smallerIndex, ties=$equal; collisions stay explicit")
    }
  }
}
