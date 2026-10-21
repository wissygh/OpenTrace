package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.Control2BusRegBundle
import trace.interface.TraceBeat

private class SRAMControlHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val io = IO(new Bundle {
    val bus = new Control2BusRegBundle
    val in = Flipped(Decoupled(new TraceBeat(4)))
  })
  val sink = withReset(reset.asAsyncReset) { Module(LazyModule(new SRAM(0x20050000)).module) }
  sink.bus.b2c := io.bus.b2c; io.bus.c2b := sink.bus.c2b
  sink.io.in <> io.in; sink.io.transportEmpty := true.B
}

class SRAMControlSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "actual SRAM control register and storage path"
  it should "report byte addresses and wrap read and write pointers at the advertised last word" in {
    test(new SRAMControlHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
      d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U)
      d.io.in.bits.bytes.poke(3.U); d.io.in.bits.packetBytes.poke(4.U); d.io.in.bits.last.poke(true.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      def read(offset: Int): BigInt = {
        d.io.bus.b2c.address.poke((0x50000 + offset).U); d.io.bus.b2c.readEn.poke(true.B)
        d.io.bus.c2b.error.expect(false.B)
        val result = d.io.bus.c2b.dataRead.peek().litValue
        d.clock.step(); d.io.bus.b2c.readEn.poke(false.B); result
      }
      def write(offset: Int, value: BigInt): Unit = {
        d.io.bus.b2c.address.poke((0x50000 + offset).U); d.io.bus.b2c.dataWrite.poke(value.U)
        d.io.bus.b2c.writeEn.poke(true.B); d.io.bus.c2b.error.expect(false.B)
        d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
      }
      def packet(bytes: Seq[Int]): Unit = {
        require(bytes.size <= 4)
        d.io.in.bits.bytes.poke((bytes.size - 1).U); d.io.in.bits.packetBytes.poke(bytes.size.U)
        d.io.in.bits.data.poke(bytes.zipWithIndex.map { case (b, i) => BigInt(b) << (i * 8) }.sum.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
        d.io.in.valid.poke(false.B)
      }
      val limit = 256 * 4 - 4
      read(0) shouldBe BigInt(9); read(4) shouldBe BigInt(0x1901)
      read(0x18) shouldBe BigInt(limit)
      for (word <- Seq(BigInt(0), BigInt("ffffffff", 16))) {
        for (offset <- Seq(0x10, 0x14, 0x18, 0x1c, 0x24, 0x2c)) write(offset, word)
        read(0x10) shouldBe BigInt(0); read(0x14) shouldBe BigInt(0)
        read(0x18) shouldBe BigInt(limit); read(0x1c) shouldBe BigInt(0)
        read(0x24) shouldBe BigInt(0); read(0x2c) shouldBe BigInt(0)
      }
      println("[SRAM-control][1/4] 真实Impl/Limit与写0/全1发现通过：256 words=1024 bytes，末word地址0x3fc")
      write(0x20, limit); write(0x28, limit)
      read(0x20) shouldBe BigInt(limit); read(0x28) shouldBe BigInt(limit)
      write(0, 3 | (1 << 8)); packet(Seq(1, 2, 3, 4)); d.clock.step(5)
      (read(0) & 0xb) shouldBe BigInt(9) // auto-stop: Active + Empty
      read(0x20) shouldBe BigInt(1) // WP wraps to byte0, Wrap sticky bit set
      read(0x40) shouldBe BigInt("04030201", 16)
      read(0x28) shouldBe BigInt(0)
      println("[SRAM-control][2/4] WP/RP设为Limit后访问最后word均回绕至0，StopOnWrap清Enable，Wrap独立置位")
      write(0x20, 0); read(0x20) shouldBe BigInt(0)
      write(0, 3)
      write(0x20, 0x100) // forbidden while enabled, leaves live WP intact
      read(0x20) shouldBe BigInt(0)
      packet(Seq(5, 6, 7)); write(0, 1); d.clock.step(5)
      (read(0) & 0xb) shouldBe BigInt(9); read(0x20) shouldBe BigInt(4)
      write(0x28, 0); read(0x40) shouldBe BigInt("00070605", 16)
      read(0x28) shouldBe BigInt(4)
      write(0x28, limit + 4); read(0x28) shouldBe BigInt(4)
      write(0x20, limit + 4); read(0x20) shouldBe BigInt(4)
      println("[SRAM-control][3/4] 禁用补尾只加packet后的null，指针按4字节推进，运行中WP和超范围指针写不生效")
      // Keep the current implementation's allowed live readback, without
      // treating concurrent capture and reads as a consistent snapshot.
      write(0x20, 0); write(0, 3); packet(Seq(8, 9, 10, 11)); d.clock.step(3)
      write(0x28, 0); read(0x40) shouldBe BigInt("0b0a0908", 16)
      read(0x28) shouldBe BigInt(4)
      println("[SRAM-control][4/4] 实现支持运行中RP/Data访问；同地址并发写/环形覆盖不保证一致快照")
    }
  }
}
