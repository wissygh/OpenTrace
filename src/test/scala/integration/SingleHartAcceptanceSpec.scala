package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}
import trace.Control2BusRegBundle
import trace.interface._
import InstructionReference._

/** Single-clock functional acceptance; independent-domain/reset behavior remains
  * covered by the actual four-clock TraceSystemSpec, rather than assumed here.
  */
private class SingleHartAcceptanceHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val io = IO(new Bundle {
    val bus = new Control2BusRegBundle
    val core = Input(new FromCore)
    val toCore = Output(new ToCore)
    val transfer = Input(Bool())
  })
  val encoder = withReset(reset.asAsyncReset) { Module(LazyModule(new Encoder(0x20000000)).module) }
  val funnel = withReset(reset.asAsyncReset) { Module(LazyModule(new Funnel(0x20010000)).module) }
  val ram = withReset(reset.asAsyncReset) { Module(LazyModule(new SRAM(0x20040000)).module) }
  val components = Seq(encoder -> 0, funnel -> 1, ram -> 4)
  for ((component, page) <- components) {
    component.bus.b2c := io.bus.b2c
    component.bus.b2c.readEn := io.bus.b2c.readEn && io.bus.b2c.address(23, 16) === page.U
    component.bus.b2c.writeEn := io.bus.b2c.writeEn && io.bus.b2c.address(23, 16) === page.U
  }
  io.bus.c2b := Mux1H(components.map { case (c, page) => (io.bus.b2c.address(23, 16) === page.U) -> c.bus.c2b })
  encoder.io.fromCore := io.core; io.toCore := encoder.io.toCore
  funnel.io.in(0) <> encoder.io.out; funnel.io.sourceControl(0) <> encoder.io.control
  funnel.io.inputPending := encoder.io.out.valid; funnel.io.quiesce := false.B
  funnel.io.control.syncReq := false.B; funnel.io.control.flushReq := false.B
  ram.io.in.valid := funnel.io.out.valid && io.transfer
  ram.io.in.bits := funnel.io.out.bits
  funnel.io.out.ready := ram.io.in.ready && io.transfer
  ram.io.transportEmpty := !funnel.io.out.valid
}

class SingleHartAcceptanceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = BigInt("80000000", 16)
  private def pc(offset: Int): BigInt = base + offset
  private def initialize(d: SingleHartAcceptanceHarness): Unit = {
    d.clock.setTimeout(100000)
    d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
    d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B); d.io.transfer.poke(true.B)
    val c = d.io.core
    c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
    c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
    c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
    c.blocks.foreach { b =>
      b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
      b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word); b.bits.trigNotify.poke(false.B)
      b.bits.sijump.foreach(_.poke(false.B))
    }
    d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B); d.clock.step(20)
  }
  private def read(d: SingleHartAcceptanceHarness, address: Int): BigInt = {
    d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.readEn.poke(true.B)
    d.io.bus.c2b.error.expect(false.B); val value = d.io.bus.c2b.dataRead.peek().litValue
    d.clock.step(); d.io.bus.b2c.readEn.poke(false.B); value
  }
  private def write(d: SingleHartAcceptanceHarness, address: Int, value: BigInt): Unit = {
    d.io.bus.b2c.address.poke(address.U); d.io.bus.b2c.dataWrite.poke(value.U); d.io.bus.b2c.writeEn.poke(true.B)
    d.io.bus.c2b.error.expect(false.B); d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
  }
  private def capture(d: SingleHartAcceptanceHarness): Seq[Int] = {
    write(d, 0, 1 | (15 << 20)); d.clock.step(200)
    (read(d, 0) & 11) shouldBe BigInt(9)
    write(d, 0x10000, 1); d.clock.step(80); (read(d, 0x10000) & 11) shouldBe BigInt(9)
    write(d, 0x40000, 1); d.clock.step(20); (read(d, 0x40000) & 11) shouldBe BigInt(9)
    val wp = read(d, 0x40020).toInt
    wp should be > 0; wp should be < 1024; (wp & 3) shouldBe 0
    write(d, 0x40028, 0)
    val bytes = (0 until wp / 4).flatMap { _ =>
      val word = read(d, 0x40040); (0 until 4).map(i => ((word >> (8 * i)) & 255).toInt)
    }
    read(d, 0x40028) shouldBe BigInt(wp)
    bytes
  }
  private def saveCapture(name: String, bytes: Seq[Int], code: Array[Byte], expected: Seq[BigInt], golden: Seq[Seq[Int]] = Seq.empty): Unit = {
    val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve(s"build/validation/single-hart-acceptance/$name")
    for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
    Files.write(root.resolve("dut/trace.bin"), bytes.map(_.toByte).toArray)
    Files.write(root.resolve("workload/code.bin"), code)
    Files.writeString(root.resolve("dut/trace.json"),
      """{"sources":{"0":{"hart_id":0,"raw":"../workload/code.bin","base":"0x80001000","xlen":64}}}""")
    Files.writeString(root.resolve("ref/retired-pcs.json"), expected.map(p => s"\"0x${p.toString(16)}\"").mkString("[", ",", "]"))
    if (golden.nonEmpty) Files.writeString(root.resolve("ref/packet-golden.json"), golden.map(p => p.map(b => f"$b%02x").mkString("\"", " ", "\"")).mkString("[", ",", "]"))
  }
  behavior of "single hart functional acceptance through actual components"
  it should "match retirement controls traps contexts notify trace off reset and filtering against the reference" in {
    test(new SingleHartAcceptanceHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      initialize(d)
      write(d, 0x40000, 0x103); write(d, 0x10000, 3)
      write(d, 0x404, 8); write(d, 0x400, 3); write(d, 0xc, 1) // match M privilege
      write(d, 0, 0x807 | (15 << 20)); d.clock.step(30)
      val model = new Model(0)
      val expectedPCs = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val code = Array.fill[Byte](0x7010)(0)
      def put(address: BigInt, word: Int, size: Int = 4): Unit =
        for (i <- 0 until size) code((address - pc(0x1000)).toInt + i) = ((word >> (8 * i)) & 255).toByte
      def send(offset: Int, kind: Int = 0, context: Int = 0, ctype: Int = 0, cause: Int = 0,
          notification: Boolean = false, size: Int = 1, qualified: Boolean = true, off: Boolean = false): Unit = {
        val b = Block(pc(offset), kind, if (kind == 1 || kind == 2) 0 else 1 << size, size,
          privilege = if (qualified) 3 else 1, context = context, ctype = ctype, cause = cause, notification = notification, qualified = qualified)
        val c = d.io.core; val g = c.blocks(0)
        c.priv.poke(b.privilege.U); c.context.poke(context.U); c.ctype.poke(ctype.U); c.cause.poke(cause.U)
        c.trigger.traceOff.poke(off.B)
        g.valid.poke(true.B); g.bits.iaddr.poke(b.pc.U); g.bits.itype.poke(kind.U)
        g.bits.iretire.poke(b.halfwords.U); g.bits.ilastsize.poke(size.U); g.bits.trigNotify.poke(notification.B)
        d.clock.step(); g.valid.poke(false.B); c.trigger.traceOff.poke(false.B); c.ctype.poke(0.U)
        d.clock.step(18)
        model.block(b)
        if (qualified && b.halfwords != 0) expectedPCs += b.pc
        val word = kind match { case 4 => 0x1463; case 5 => 0x463; case 8 => 0x28067; case 3 => 0x30200073; case 1 => 0x73; case _ => if (size == 0) 1 else 0x13 }
        put(b.pc, word, if (size == 0) 2 else 4)
        if (off) model.disable()
      }
      println("[single-acceptance][1/4] 实际Encoder→Funnel→SRAM：高地址分支/间接跳转/trap/context/过滤/trigger-off")
      send(0x1000); send(0x1004, 5); send(0x100c, 4); send(0x1010, 8); send(0x2000, notification = true)
      send(0x2004, 2, cause = 7); send(0x3000); send(0x3004, 3)
      send(0x2004, context = 1, ctype = 2); send(0x2008, context = 1, ctype = 1, notification = true)
      send(0x200c, 1, context = 1, cause = 11); send(0x4000, context = 1); send(0x4004, context = 1)
      send(0x5000, context = 2, ctype = 3, size = 0); send(0x5002, context = 2, size = 0, notification = true)
      send(0x5004, context = 2, qualified = false)
      send(0x6000, context = 2); send(0x6004, context = 2, notification = true, off = true)
      d.clock.step(60); write(d, 0, 0x807 | (15 << 20)); model.enable(); d.clock.step(30)
      send(0x7000, context = 2); send(0x7004, context = 2)
      d.io.core.hartIsReset.poke(true.B); d.clock.step(50); d.io.core.hartIsReset.poke(false.B); model.close()
      send(0x8000, context = 2); send(0x8004, context = 2, notification = true)
      model.disable()
      val bytes = capture(d)
      bytes.take(model.packets.flatten.size) shouldBe model.packets.flatten.toSeq
      bytes.drop(model.packets.flatten.size).foreach(_ shouldBe 0)
      read(d, 0xf10) shouldBe BigInt(0); read(d, 0xf18) shouldBe BigInt(0)
      // Direct jumps connect precisely delimited segments in the instruction image.
      def jal(offset: Int, target: Int): Unit = {
        val delta = target - offset
        put(pc(offset), (((delta >> 20) & 1) << 31) | (((delta >> 1) & 1023) << 21) |
          (((delta >> 11) & 1) << 20) | (((delta >> 12) & 255) << 12) | 0x6f)
      }
      jal(0x4004, 0x5000)
      saveCapture("controls", bytes, code, expectedPCs.toSeq, model.packets.toSeq)
      println(s"[single-acceptance][2/4] ${model.packets.size}包/${bytes.size}bytes与独立完整wire参考一致，全部寄存器Empty、drop/loss=0")
      println(s"[single-acceptance][3/4] 导出${expectedPCs.size}条退休参考，异常EPC不退休；支持包/handler/context独立可查")
      println("[single-acceptance][4/4] build/validation/single-hart-acceptance/controls，下一步decoder完整指令对拍")
    }
  }
  it should "retain every offered retirement when a bounded core obeys stall during downstream backpressure" in {
    test(new SingleHartAcceptanceHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      initialize(d)
      write(d, 0x40000, 0x103); write(d, 0x10000, 3); write(d, 0, 0x2007 | (15 << 20))
      d.clock.step(30); d.io.transfer.poke(false.B)
      val g = d.io.core.blocks(0)
      g.bits.iaddr.poke(pc(0x1000).U); g.bits.itype.poke(Itype.OtherUninferableJump)
      var offered = 0; var stalled = 0; var cycles = 0
      var delayedStall = false
      while (offered < 100) {
        assert(cycles < 6000, "bounded-stall core did not finish")
        d.io.transfer.poke((cycles >= 280 && cycles % 17 > 4).B)
        val stall = d.io.toCore.stall.peek().litToBoolean
        // One complete additional cycle may arrive after a stall is observed.
        val valid = !delayedStall
        g.valid.poke(valid.B)
        if (valid) offered += 1
        if (stall) stalled += 1
        d.clock.step(); delayedStall = stall; cycles += 1
      }
      g.valid.poke(false.B); d.io.transfer.poke(true.B); d.clock.step(300)
      val bytes = capture(d)
      stalled should be > 0
      read(d, 0xf10) shouldBe BigInt(0); read(d, 0xf18) shouldBe BigInt(0)
      read(d, 0xf20) should be > BigInt(0)
      (read(d, 0) & 0x1000) shouldBe BigInt(0x1000)
      val model = new Model(0)
      for (_ <- 0 until offered) model.block(Block(pc(0x1000), kind = 14))
      model.disable()
      bytes.take(model.packets.flatten.size) shouldBe model.packets.flatten.toSeq
      bytes.drop(model.packets.flatten.size).foreach(_ shouldBe 0)
      val code = Array.fill[Byte](4)(0)
      val word = 0x28067
      for (i <- 0 until 4) code(i) = ((word >> (i * 8)) & 255).toByte
      saveCapture("stall", bytes, code, Seq.fill(offered)(pc(0x1000)), model.packets.toSeq)
      println(s"[single-acceptance-stall] ${offered}条独立输入退休/${stalled}个stall周期/1拍反馈延迟，完整组件wire一致、drop/loss=0，恢复后全部排空")
    }
  }

  it should "report actual ingress loss after old packets and resume with a fresh source synchronization" in {
    test(new SingleHartAcceptanceHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      initialize(d)
      write(d, 0x40000, 0x103); write(d, 0x10000, 3); write(d, 0, 7 | (15 << 20))
      d.clock.step(30); d.io.transfer.poke(false.B)
      val g = d.io.core.blocks(0)
      g.valid.poke(true.B); g.bits.iaddr.poke(pc(0x1000).U); g.bits.itype.poke(Itype.OtherUninferableJump)
      val offered = 200
      d.clock.step(offered); g.valid.poke(false.B)
      d.io.transfer.poke(true.B); d.clock.step(600)
      val rejected = read(d, 0xf10).toInt
      rejected should be > 0; rejected should be < offered
      read(d, 0xf18) should be > BigInt(0)
      read(d, 0xf20) shouldBe BigInt(0) // drop mode must never pretend it stalled the hart
      for (address <- Seq(pc(0x2000), pc(0x2004))) {
        g.bits.iaddr.poke(address.U); g.bits.itype.poke(Itype.None); g.bits.trigNotify.poke(true.B)
        g.valid.poke(true.B); d.clock.step(); g.valid.poke(false.B); d.clock.step(24)
      }
      val bytes = capture(d)
      val code = Array.fill[Byte](0x1008)(0)
      for ((offset, word) <- Seq(0 -> 0x28067, 0x1000 -> 0x13, 0x1004 -> 0x13); i <- 0 until 4)
        code(offset + i) = ((word >> (i * 8)) & 255).toByte
      // Independent offered-PC sequence and separately validated raw-drop accounting.
      // This is module evidence, not an independent architectural ROB reference.
      val expected = Seq.fill(offered - rejected)(pc(0x1000)) ++ Seq(pc(0x2000), pc(0x2004))
      saveCapture("overflow", bytes, code, expected)
      println(s"[single-acceptance-loss] ${offered}条输入/${rejected}条实际拒收，旧trace→loss→新同步→恢复尾部；${bytes.size}bytes，交decoder检查全部保留PC计数")
    }
  }

}
