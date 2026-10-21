package trace.control

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CoreHPMSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "optional core HPM sampling"
  it should "disable with address zero and sample independent increments with core timestamps" in {
    test(new CoreHPMRegisters(0x20000000, counterWidth = 8)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
      d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B)
      d.io.time.poke(0.U); d.io.port.eventVec.foreach(_.inc.poke(0.U))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def read(address: Int): BigInt = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.readEn.poke(true.B)
        val value = d.io.bus.c2b.dataRead.peek().litValue
        d.io.bus.b2c.readEn.poke(false.B); value
      }
      def write(address: Int, value: BigInt): Unit = {
        d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.dataWrite.poke(value.U)
        d.io.bus.b2c.writeEn.poke(true.B); d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
      }
      for (port <- d.io.port.eventVec) { port.hpmaddr.expect(0.U); port.hpmevent.expect(0.U) }
      write(0x900, 2); read(0x900) shouldBe BigInt(0)
      val low0 = BigInt("c1203405", 16)
      val high0 = BigInt(4 << 18 | 2 << 13 | 1 << 8 | 0x55)
      write(0x904, low0); write(0x908, high0); write(0x900, 3)
      d.io.port.eventVec(0).hpmaddr.expect(3.U)
      d.io.port.eventVec(0).hpmevent.expect(((high0 << 32) | low0).U)
      write(0x924, 0x12345678); write(0x920, 31)
      d.io.port.eventVec(1).hpmaddr.expect(31.U)
      d.io.port.eventVec(1).hpmevent.expect(0x12345678L.U)
      println("[core-HPM] 1/4 实际CSR：0关闭、1/2拒绝、3/31可用、两路55-bit事件选择独立")
      for (cycle <- 0 until 6) {
        val time = (BigInt(0x12345678) << 32) | (cycle * 16)
        d.io.time.poke(time.U); d.io.port.eventVec(0).inc.poke(63.U)
        d.io.port.eventVec(1).inc.poke((cycle + 1).U); d.clock.step()
        read(0x90c) shouldBe BigInt(63)
        read(0x910) shouldBe BigInt(cycle * 16); read(0x914) shouldBe BigInt(0x12345678)
        read(0x918) shouldBe BigInt(math.min(255, (cycle + 1) * 63))
        read(0x92c) shouldBe BigInt(cycle + 1)
        read(0x938) shouldBe BigInt((cycle + 1) * (cycle + 2) / 2)
        read(0x91c) shouldBe BigInt(0)
      }
      println("[core-HPM] 2/4 inc/time同拍采样，独立累计、饱和与64-bit时间高低字通过")
      d.io.port.eventVec.foreach(_.inc.poke(0.U))
      write(0x900, 0)
      d.io.port.eventVec(0).hpmaddr.expect(0.U); d.io.port.eventVec(0).hpmevent.expect(0.U)
      d.io.port.eventVec(0).inc.poke(63.U); d.clock.step(5)
      read(0x90c) shouldBe BigInt(0); read(0x918) shouldBe BigInt(0)
      read(0x910) shouldBe BigInt(0); read(0x914) shouldBe BigInt(0)
      write(0x900, 4); read(0x918) shouldBe BigInt(0)
      d.io.time.poke(0xabcd.U); d.io.port.eventVec(0).inc.poke(2.U); d.clock.step()
      read(0x90c) shouldBe BigInt(2); read(0x918) shouldBe BigInt(2); read(0x910) shouldBe BigInt(0xabcd)
      println("[core-HPM] 3/4 配置写建立新epoch，关闭后忽略输入；重新使能无旧sample/time/累计")
      write(0x908, BigInt(3 << 18 | 3 << 13 | 3 << 8 | 0x66)) // illegal operations retain old values
      read(0x908) shouldBe BigInt(4 << 18 | 2 << 13 | 1 << 8 | 0x66)
      read(0x918) shouldBe BigInt(0)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      for (port <- d.io.port.eventVec) port.hpmaddr.expect(0.U)
      read(0x918) shouldBe BigInt(0); read(0x938) shouldBe BigInt(0)
      println("[core-HPM] 4/4 OPTYPE WARL、epoch清除与Active式复位通过；HPM不修改E-Trace包布局")
    }
  }
}
