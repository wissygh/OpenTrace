package trace

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}
import trace.interface._

/** Same control topology as XiangShan SoCMisc: an eight-byte peripheral bus
  * with another eight-byte slave and a byte-beat Debug SBA port share the
  * actual four-byte xs_socmisc Trace entrance.
  */
private class SingleHartSramDesign(adaptCPUWidth: Boolean = true)(implicit p: Parameters) extends LazyModule {
  val trace = LazyModule(new Trace(BusType.TL, xs_socmisc = true))
  val xbar = TLXbar()
  val peripherals = TLXbar()
  val otherSlave = LazyModule(new TLError(
    DevNullParams(address = Seq(AddressSet(0x30000000L, 0xfff)), maxAtomic = 8, maxTransfer = 8),
    beatBytes = 8))
  val cpu = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("cpu", sourceId = IdRange(0, 1))))))
  val sba = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("sba", sourceId = IdRange(0, 1))))))
  trace.ctrlnode.get := TLBuffer() := xbar
  otherSlave.node := peripherals
  peripherals := cpu
  if (adaptCPUWidth) xbar := TLFIFOFixer() := TLFragmenter(4, 8, holdFirstDeny = true) := TLWidthWidget(8) := peripherals
  else xbar := peripherals
  xbar := TLBuffer() := TLWidthWidget(1) := sba
  val cpuIO = InModuleBody(cpu.makeIOs())
  val sbaIO = InModuleBody(sba.makeIOs())
  class Imp extends LazyModuleImp(this) {
    val fromCore = IO(Input(new FromCore))
    val toCore = IO(Output(new ToCore))
    trace.module.io.clock_core := clock; trace.module.io.reset_core := reset.asAsyncReset
    trace.module.io.clock_ctrl := clock; trace.module.io.reset_ctrl := reset.asAsyncReset
    trace.module.io.clock_trace := clock; trace.module.io.reset_trace := reset.asAsyncReset
    trace.module.io.fromCore(0) := fromCore; toCore := trace.module.io.toCore(0)
  }
  lazy val module = new Imp
}

private class SingleHartSramHarness(adaptCPUWidth: Boolean = true) extends Module {
  implicit val p: Parameters = Parameters.empty
  val design = LazyModule(new SingleHartSramDesign(adaptCPUWidth))
  val top = Module(design.module)
  val cpu = design.cpuIO.getWrappedValue.head
  val sba = design.sbaIO.getWrappedValue.head
  val io = IO(new Bundle {
    val cpu = Flipped(new TLBundle(SingleHartSramHarness.this.cpu.params))
    val sba = Flipped(new TLBundle(SingleHartSramHarness.this.sba.params))
    val core = Input(new FromCore)
    val toCore = Output(new ToCore)
  })
  cpu <> io.cpu; sba <> io.sba
  top.fromCore := io.core; io.toCore := top.toCore
}

class SingleHartSramSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "single-hart actual Trace SRAM and SoCMisc control route"
  it should "reject the original unadapted mixed-width peripheral crossbar before RTL generation" in {
    val failure = intercept[IllegalArgumentException] {
      _root_.circt.stage.ChiselStage.emitCHIRRTL(new SingleHartSramHarness(adaptCPUWidth = false))
    }
    failure.getMessage should include("data widths don't match")
    failure.getMessage should include("4B")
    failure.getMessage should include("8B")
    println("[single-SRAM][regression] 原接线4B Trace与8B外设并接时复现同一Xbar位宽异常")
  }
  it should "configure through CPU and byte SBA then read the complete execution stream via SRAM CSR" in {
    test(new SingleHartSramHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(20000)
      for (bus <- Seq(d.io.cpu, d.io.sba)) {
        bus.a.valid.poke(false.B); bus.d.ready.poke(true.B)
        bus.a.bits.opcode.poke(TLMessages.Get); bus.a.bits.param.poke(0.U)
        bus.a.bits.size.poke(2.U); bus.a.bits.source.poke(0.U); bus.a.bits.address.poke(0.U)
        bus.a.bits.mask.poke(0.U); bus.a.bits.data.poke(0.U); bus.a.bits.corrupt.poke(false.B)
      }
      val c = d.io.core
      c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
      c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
      c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
      c.blocks.foreach { b =>
        b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
        b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word); b.bits.trigNotify.poke(false.B)
        b.bits.sijump.foreach(_.poke(false.B))
      }
      d.reset.poke(true.B); d.clock.step(5); d.reset.poke(false.B); d.clock.step(50)
      def waitFor(what: String)(condition: => Boolean): Unit = {
        var n = 0
        while (!condition && n < 1000) { d.clock.step(); n += 1 }
        assert(n < 1000, what)
      }
      def access(address: Int, write: Boolean = false, value: BigInt = 0,
          bytePort: Boolean = false, doubleword: Boolean = false): BigInt = {
        require(!doubleword || !bytePort)
        val bus = if (bytePort) d.io.sba else d.io.cpu
        val lanes = if (bytePort) 4 else 1
        val shift = if (bytePort) 0 else (address & 7) * 8
        bus.d.ready.poke(false.B)
        // XiangShan Uncache uses the masked edge.Put overload even for a
        // complete word. Debug SBA uses PutFullData on its separate route.
        bus.a.bits.address.poke(address.U)
        bus.a.bits.opcode.poke(if (!write) TLMessages.Get else
          if (bytePort) TLMessages.PutFullData else TLMessages.PutPartialData)
        bus.a.bits.size.poke((if (doubleword) 3 else 2).U)
        bus.a.bits.mask.poke((if (bytePort) BigInt(1) else
          (if (doubleword) BigInt(255) else BigInt(15)) << (address & 7)).U)
        for (lane <- 0 until (if (write) lanes else 1)) {
          bus.a.bits.data.poke((if (bytePort) (value >> (8 * lane)) & 255 else value << shift).U)
          bus.a.valid.poke(true.B); waitFor("control A ready")(bus.a.ready.peek().litToBoolean)
          d.clock.step(); bus.a.valid.poke(false.B)
        }
        var result = BigInt(0)
        for (lane <- 0 until (if (write) 1 else lanes)) {
          bus.d.ready.poke(false.B); waitFor("control D valid")(bus.d.valid.peek().litToBoolean)
          bus.d.bits.denied.expect(false.B)
          bus.d.bits.opcode.expect(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
          val data = bus.d.bits.data.peek().litValue
          d.clock.step(2); bus.d.valid.expect(true.B); bus.d.bits.data.expect(data.U)
          result |= (if (bytePort) data << (8 * lane) else (data >> shift) &
            (if (doubleword) (BigInt(1) << 64) - 1 else BigInt("ffffffff", 16)))
          bus.d.ready.poke(true.B); d.clock.step()
        }
        result
      }
      val encoder = 0x20000000; val funnel = 0x20010000; val ram = 0x20040000
      d.io.cpu.a.bits.data.getWidth shouldBe 64
      d.io.sba.a.bits.data.getWidth shouldBe 8
      println("[single-SRAM][1/5] 实际Trace SoCMisc入口：CPU64的PutPartialData整word写入，SBA8的PutFullData组装访问")
      for (address <- Seq(encoder, funnel, ram)) {
        (access(address) & 1) shouldBe BigInt(0)
        access(address, write = true, value = 1, bytePort = true)
        waitFor("Active release") { (access(address) & 1) == 1 }
      }
      access(ram + 4, bytePort = true) shouldBe BigInt(0x1901)
      access(ram + 4) shouldBe BigInt(0x1901) // high word/lane on the 64-bit peripheral bus
      ((access(encoder + 8) >> 28) & 15) shouldBe BigInt(8)
      access(ram + 0x1c) shouldBe BigInt(0) // absent high address register, upper word/lane
      val discovered = access(ram, doubleword = true) // two 32-bit register reads, one 64-bit reply
      (discovered >> 32) shouldBe BigInt(0x1901)
      (discovered & BigInt("ffffffff", 16)) shouldBe BigInt(9)
      access(ram + 0x18, bytePort = true) shouldBe BigInt(0x3fc)
      access(ram + 0x20, write = true, value = 0, bytePort = true)
      access(ram, write = true, value = 3 | (1 << 8))
      access(funnel, write = true, value = 3, bytePort = true)
      access(encoder, write = true, value = 7)
      waitFor("core trace enable")(d.io.toCore.enable.peek().litToBoolean)
      val flow = Seq((0x1000, 0, 1), (0x1004, 5, 1), (0x1000, 0, 1), (0x1004, 4, 1),
        (0x1008, 0, 1), (0x100c, 8, 1), (0x2000, 0, 1), (0x2004, 13, 0), (0x1010, 0, 1), (0x1014, 0, 1))
      for ((pc, kind, size) <- flow) {
        d.io.toCore.stall.expect(false.B)
        c.blocks(0).bits.iaddr.poke(pc.U); c.blocks(0).bits.itype.poke(kind.U)
        c.blocks(0).bits.ilastsize.poke(size.U); c.blocks(0).bits.iretire.poke((1 << size).U)
        c.blocks(0).valid.poke(true.B); d.clock.step(); c.blocks(0).valid.poke(false.B); d.clock.step(5)
      }
      println("[single-SRAM][2/5] 10条退休输入：taken/not-taken循环、间接调用、压缩返回；每条有实际镜像")
      access(encoder, write = true, value = 1, bytePort = true)
      waitFor("encoder Disable/Empty") { (access(encoder) & 0xb) == 9 }
      access(funnel, write = true, value = 1)
      waitFor("Funnel automatic drain") { (access(funnel, bytePort = true) & 0xb) == 9 }
      access(ram, write = true, value = 1)
      waitFor("SRAM padding drain") { (access(ram) & 0xb) == 9 }
      println("[single-SRAM][3/5] encoder→Funnel→SRAM依次软件停止，自动flush/Empty与末尾补null完成")
      val wp = access(ram + 0x20).toInt
      (wp & 1) shouldBe 0
      val bytes = wp & ~3
      bytes should be > 0
      access(ram + 0x28, write = true, value = 0, bytePort = true)
      val capture = (0 until bytes / 4).flatMap { index =>
        val word = access(ram + 0x40, bytePort = index % 2 == 0)
        (0 until 4).map(lane => ((word >> (lane * 8)) & 255).toByte)
      }.toArray
      access(ram + 0x28) shouldBe BigInt(bytes)
      println(s"[single-SRAM][4/5] CPU/SBA交替读实际RAMData，RP自动推进，捕获${capture.length}bytes")
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).get
      val out = workspace.resolve("build/validation"); Files.createDirectories(out)
      Files.write(out.resolve("single-hart-sram.bin"), capture)
      val code = Array.fill[Byte](0x1010)(0)
      def put(pc: Int, instruction: Long, size: Int = 4): Unit =
        for (i <- 0 until size) code(pc - 0x1000 + i) = ((instruction >> (8 * i)) & 255).toByte
      for (pc <- Seq(0x1008, 0x1010, 0x1014, 0x2000)) put(pc, 0x13)
      put(0x1000, 0xfff08093L); put(0x1004, 0xfe009ee3L); put(0x100c, 0x280e7)
      put(0x2004, 0x8082, 2)
      Files.write(out.resolve("single-hart-sram-code.bin"), code)
      Files.writeString(out.resolve("single-hart-sram.json"),
        """{"source_bits":8,"sources":{"0":{"hart_id":0,"raw":"single-hart-sram-code.bin","base":"0x1000","xlen":64}}}""")
      println("[single-SRAM][5/5] 导出真实top capture交decoder逐条比较；该测试未代替香山实核emu")
    }
  }
}
