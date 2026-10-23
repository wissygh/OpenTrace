package trace.control

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.Control2BusRegBundle
import trace.component._
import trace.interface._

/** Actual component register decoders, including the optional HPM integration. */
private class RegisterMapHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val io = IO(new Bundle {
    val bus = new Control2BusRegBundle
    val bank = Input(UInt(3.W))
    val core = Input(new FromCore)
    val hpm = new Hpm
    val out = Decoupled(new TraceBeat(4))
  })
  val encoder = withReset(reset.asAsyncReset) { Module(LazyModule(new Encoder(0x20000000, enableHPM = true)).module) }
  val funnel = withReset(reset.asAsyncReset) { Module(LazyModule(new Funnel(0x20010000, 3)).module) }
  val ram = withReset(reset.asAsyncReset) { Module(LazyModule(new SRAM(0x20040000)).module) }
  val atb = withReset(reset.asAsyncReset) { Module(LazyModule(new ATBBridge(0x20020000)).module) }
  val pib = withReset(reset.asAsyncReset) { Module(LazyModule(new PIB(0x20030000, hasSerializer = true)).module) }
  val components = Seq(encoder, funnel, ram, atb, pib)
  for ((component, i) <- components.zipWithIndex) {
    component.bus.b2c := io.bus.b2c
    component.bus.b2c.readEn := io.bus.b2c.readEn && io.bank === i.U
    component.bus.b2c.writeEn := io.bus.b2c.writeEn && io.bank === i.U
  }
  io.bus.c2b := Mux1H(components.zipWithIndex.map { case (c, i) => (io.bank === i.U) -> c.bus.c2b })
  encoder.io.fromCore := io.core; io.hpm <> encoder.io.hpm.get; io.out <> encoder.io.out
  encoder.io.control.syncReq := false.B; encoder.io.control.flushReq := false.B
  for (input <- funnel.io.in) { input.valid := false.B; input.bits := 0.U.asTypeOf(input.bits) }
  funnel.io.sourceControl.foreach(c => c.flushAck := c.flushReq)
  funnel.io.inputPending := false.B; funnel.io.quiesce := false.B; funnel.io.out.ready := true.B
  funnel.io.control.syncReq := false.B; funnel.io.control.flushReq := false.B
  ram.io.in.valid := false.B; ram.io.in.bits := 0.U.asTypeOf(ram.io.in.bits); ram.io.transportEmpty := true.B
  atb.io.in.valid := false.B; atb.io.in.bits := 0.U.asTypeOf(atb.io.in.bits); atb.io.transportEmpty := true.B
  atb.io.upstream.flushAck := true.B; atb.io.atbm.atclken := true.B; atb.io.atbm.at.ready := true.B
  atb.io.atbm.af.valid := false.B; atb.io.atbm.syncReq := false.B
  pib.io.in.valid := false.B; pib.io.in.bits := 0.U.asTypeOf(pib.io.in.bits); pib.io.transportEmpty := true.B
}

class ControlRegisterMapSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val bases = Seq(0, 0x10000, 0x40000, 0x20000, 0x30000)
  private val all = BigInt("ffffffff", 16)
  private def initialize(d: RegisterMapHarness): Unit = {
    d.clock.setTimeout(100000)
    d.io.bank.poke(0.U); d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
    d.io.bus.b2c.writeEn.poke(false.B); d.io.bus.b2c.readEn.poke(false.B); d.io.out.ready.poke(true.B)
    val c = d.io.core
    c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
    c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
    c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
    c.blocks.foreach { b =>
      b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
      b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word); b.bits.trigNotify.poke(false.B)
      b.bits.sijump.foreach(_.poke(false.B))
    }
    d.io.hpm.eventVec.foreach(_.inc.poke(0.U))
    reset(d)
  }
  private def reset(d: RegisterMapHarness): Unit = {
    d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B); d.clock.step(20)
  }
  private def read(d: RegisterMapHarness, bank: Int, offset: Int): BigInt = {
    d.io.bank.poke(bank.U); d.io.bus.b2c.address.poke((bases(bank) + offset).U)
    d.io.bus.b2c.readEn.poke(true.B); d.io.bus.c2b.error.expect(false.B)
    val result = d.io.bus.c2b.dataRead.peek().litValue
    d.io.bus.b2c.readEn.poke(false.B); result
  }
  private def write(d: RegisterMapHarness, bank: Int, offset: Int, value: BigInt): Unit = {
    d.io.bank.poke(bank.U); d.io.bus.b2c.address.poke((bases(bank) + offset).U)
    d.io.bus.b2c.dataWrite.poke(value.U); d.io.bus.b2c.writeEn.poke(true.B)
    d.io.bus.c2b.error.expect(false.B); d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
  }
  behavior of "Trace Control Interface register map"
  it should "check actual component reset RO RW WARL and reserved address behavior against independent masks" in {
    test(new RegisterMapHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      initialize(d)
      case class Register(bank: Int, address: Int, initial: BigInt, next: (BigInt, BigInt) => BigInt)
      def ro(bank: Int, address: Int, value: BigInt) = Register(bank, address, value, (old, _) => old)
      def rw(bank: Int, address: Int, mask: BigInt, initial: BigInt = 0, fixed: BigInt = 0) =
        Register(bank, address, initial, (_, value) => fixed | (value & mask))
      val registers = Seq(
        Register(0, 0, 0x21007d, (_, v) => BigInt(0x10079) | (v & 0xf02802) | (if (v.testBit(1)) v & 4 else BigInt(0))),
        ro(0, 4, 0x101), rw(0, 8, 0x39, BigInt(8) << 28, BigInt(8) << 28), rw(0, 12, 1),
        ro(0, 0x10, 0), ro(0, 0x1c, 0),
        rw(0, 0x40, 0x830a, 0x3f000231L, 0x3f000031L),
        rw(0, 0x400, 0x1111f),
        Register(0, 0x404, 1, (old, v) => (if ((v & 0x84) != 0) old & 255 else v & 255) | (v & 256)),
        rw(0, 0x408, all), rw(0, 0x40c, all), rw(0, 0x410, all), rw(0, 0x414, all), ro(0, 0x418, 0),
        Register(0, 0x600, 0, (old, v) => (v & 0xf770) |
          (if ((v & 3) == 3) old & 3 else v & 3) |
          (if (((v >> 2) & 3) == 3) old & 12 else v & 12)),
        rw(0, 0x610, all), rw(0, 0x614, all), rw(0, 0x618, all), rw(0, 0x61c, all),
        rw(1, 0, 2, 9, 9), ro(1, 4, 0x801), rw(1, 8, 7),
        ro(1, 0x40, 0), ro(1, 0x48, 0), ro(1, 0x4c, 0),
        rw(2, 0, 0x102, 9, 9), ro(2, 4, 0x1901), ro(2, 0x10, 0), ro(2, 0x14, 0),
        ro(2, 0x18, 0x3fc), ro(2, 0x1c, 0), ro(2, 0x24, 0), ro(2, 0x2c, 0),
        Register(3, 0, 0x109, (old, v) => {
          val id = (v >> 8) & 127
          BigInt(9) | (v & 2) | (if (id >= 1 && id < 112) id << 8 else old & 0x7f00)
        }), rw(3, 4, 0x7000, 0xe01, 0xe01), ro(4, 4, 0xa01)
      )
      val random = new scala.util.Random(481)
      val words = Seq(BigInt(0), all) ++ (0 until 32).flatMap(i => Seq(BigInt(1) << i, all ^ (BigInt(1) << i))) ++
        Seq.fill(20)(BigInt(32, random))
      for (r <- registers) {
        reset(d)
        var expected = r.initial
        withClue(s"reset bank=${r.bank} offset=0x${r.address.toHexString}: ") { read(d, r.bank, r.address) shouldBe expected }
        for (word <- words) {
          write(d, r.bank, r.address, word); d.clock.step(24)
          expected = r.next(expected, word)
          withClue(s"bank=${r.bank} offset=0x${r.address.toHexString} write=0x${word.toString(16)}: ") {
            read(d, r.bank, r.address) shouldBe expected
          }
        }
      }
      println(s"[register-map][1/3] ${registers.size}个实际CSR × ${words.size}种写值：reset/RO/RW/WARL/保留位通过")
      // Every unimplemented aligned standard address reads zero and ignores writes.
      val implemented = Seq(
        Set(0, 4, 8, 12, 0x10, 0x1c, 0x40, 0x48, 0x4c, 0x400, 0x404, 0x408, 0x40c, 0x410, 0x414, 0x418,
          0x600, 0x610, 0x614, 0x618, 0x61c), Set(0, 4, 8, 0x40, 0x48, 0x4c),
        Set(0, 4, 0x10, 0x14, 0x18, 0x1c, 0x20, 0x24, 0x28, 0x2c, 0x40), Set(0, 4), Set(0, 4))
      reset(d)
      var holes = 0
      for (bank <- bases.indices; offset <- 0 until 0x900 by 4 if !implemented(bank)(offset)) {
        read(d, bank, offset) shouldBe BigInt(0)
        write(d, bank, offset, all); read(d, bank, offset) shouldBe BigInt(0); holes += 1
      }
      for ((bank, implementation) <- Seq(0 -> 0x101, 1 -> 0x801, 2 -> 0x1901, 3 -> 0xe01, 4 -> 0xa01))
        read(d, bank, 4) shouldBe BigInt(implementation)
      println(s"[register-map][2/3] 五类实际组件${holes}个未实现标准地址写全1/读0，无寄存器别名")
      // ATID legality is exhaustive, with invalid writes retaining the preceding legal ID.
      var id = 1
      for (value <- 0 until 128) {
        write(d, 3, 0, BigInt(value << 8)); if (value > 0 && value < 112) id = value
        read(d, 3, 0) shouldBe BigInt(9 | (id << 8))
      }
      println("[register-map][3/3] ATID全部128值、三源DisInput有效位和未实现trigger/data寄存器门槛通过")
    }
  }

  it should "associate actual HPM increments and the emitted synchronization packet with the same ingress timestamp" in {
    test(new RegisterMapHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      initialize(d)
      var packet = scala.collection.mutable.ArrayBuffer.empty[Int]
      val packets = scala.collection.mutable.ArrayBuffer.empty[Seq[Int]]
      def tick(): Unit = {
        if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
          val data = d.io.out.bits.data.peek().litValue
          for (i <- 0 to d.io.out.bits.bytes.peek().litValue.toInt) packet += ((data >> (8 * i)) & 255).toInt
          if (d.io.out.bits.last.peek().litToBoolean) { packets += packet.toSeq; packet = scala.collection.mutable.ArrayBuffer.empty[Int] }
        }
        d.clock.step()
      }
      write(d, 0, 0x904, 0x12345678); write(d, 0, 0x908, 0x40201); write(d, 0, 0x900, 3)
      d.io.hpm.eventVec(0).hpmaddr.expect(3.U)
      d.io.hpm.eventVec(0).hpmevent.expect(((BigInt(0x40201) << 32) | 0x12345678).U)
      // Stop trace before configuring the internal /1 timestamp clock.
      write(d, 0, 0, 1); d.clock.step(24)
      write(d, 0, 0x40, 0x8000); d.clock.step(2); write(d, 0, 0x40, 0x8006)
      write(d, 0, 0, 7 | (15 << 20)); for (_ <- 0 until 12) tick()
      packets.clear()
      val time = read(d, 0, 0x48)
      time should be > BigInt(0)
      d.io.hpm.eventVec(0).inc.poke(5.U); d.io.hpm.eventVec(1).inc.poke(63.U)
      val b = d.io.core.blocks(0)
      b.valid.poke(true.B); b.bits.iaddr.poke(BigInt("80010000", 16).U)
      tick(); b.valid.poke(false.B); d.io.hpm.eventVec.foreach(_.inc.poke(0.U))
      read(d, 0, 0x90c) shouldBe BigInt(5); read(d, 0, 0x910) shouldBe time
      read(d, 0, 0x918) shouldBe BigInt(5); read(d, 0, 0x938) shouldBe BigInt(0) // second event remains disabled
      b.valid.poke(true.B); b.bits.iaddr.poke(BigInt("80010004", 16).U)
      tick(); b.valid.poke(false.B)
      for (_ <- 0 until 36) tick()
      packets.toSeq shouldBe Seq(ETraceReference.synchronization(0, BigInt("80010000", 16), time = time))
      write(d, 0, 0x900, 0); d.io.hpm.eventVec(0).inc.poke(63.U); d.clock.step(5)
      read(d, 0, 0x90c) shouldBe BigInt(0); read(d, 0, 0x918) shouldBe BigInt(0)
      println(s"[core-HPM-integration] 实际Encoder CSR→event选择→inc累计/禁用，HPM sample与Format30共用入队time=$time，PC=0x80010000")
    }
  }
}
