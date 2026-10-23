package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

class TraceReturnStackSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "bounded full return address stack"
  it should "match an independent list through overflow underflow clear and simultaneous operations" in {
    for (size <- Seq(1, 2, 4)) test(new TraceReturnStack(size, 50)) { d =>
      val capacity = 1 << size
      var expected = Vector.empty[BigInt]
      val random = new Random(811 + size)
      d.io.clear.poke(false.B); d.io.push.poke(false.B); d.io.pop.poke(false.B); d.io.link.poke(0.U)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def cycle(clear: Boolean, push: Boolean, pop: Boolean, link: BigInt): Unit = {
        d.io.depth.expect(expected.size.U); d.io.top.expect(expected.lastOption.getOrElse(BigInt(0)).U)
        d.io.clear.poke(clear.B); d.io.push.poke(push.B); d.io.pop.poke(pop.B); d.io.link.poke(link.U)
        if (clear) expected = Vector.empty else if (pop && expected.nonEmpty) expected = expected.dropRight(1)
        if (push) expected = (expected :+ link).takeRight(capacity)
        d.clock.step()
        d.io.depth.expect(expected.size.U); d.io.top.expect(expected.lastOption.getOrElse(BigInt(0)).U)
      }
      for (i <- 0 until capacity * 3) cycle(false, true, false, BigInt("80001000", 16) + i * 2)
      for (_ <- 0 until capacity + 3) cycle(false, false, true, 0)
      for (_ <- 0 until 1000) cycle(random.nextInt(19) == 0, random.nextBoolean(), random.nextBoolean(), BigInt(50, random))
      cycle(true, true, true, BigInt("80002002", 16))
      println(s"[return-stack] $capacity entries：丢最旧/保最新、空栈pop、clear后push、1000随机更新全部通过")
    }
  }
}
