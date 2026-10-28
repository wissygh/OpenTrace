package trace

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class APBControlSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenTrace actual APB control interface"
  it should "access real component Active and implementation registers using APB setup/access phases" in {
    test(new APBControlHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val bus = new APBControlDriver(d); bus.initialize()
      for ((address, implementation) <- Seq(0x20000000L -> 0x101, 0x20010000L -> 0x101,
        0x20020000L -> 0x801, 0x20030000L -> 0xe01, 0x20040000L -> 0xa01, 0x20050000L -> 0x1901)) {
        println(f"[APB-control][discovery] component=0x$address%x Active/readback and RO implementation")
        bus.active(address); bus.read(address + 4) shouldBe BigInt(implementation)
      }
      bus.save("discovery")
      println(s"[APB-control] ${bus.transactions} real APB transfers completed through control CDC")
    }
  }
}
