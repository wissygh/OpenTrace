package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.amba.axi4._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.{Control2BusRegBundle, TraceParameters}
import trace.control.SMEMRegisters

/** Same actual modules and reset separation used by SMEMImp. */
private class SMEMControlHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val bus = new Control2BusRegBundle
    val activeReset = Input(Bool())
    val in = Flipped(Decoupled(new trace.interface.TraceBeat(4)))
    val axi = new AXI4Bundle(AXI4BundleParameters(64, 32, 1))
    val committed = Output(UInt(64.W))
  })
  val window = SMEMWindow(BigInt("180000000", 16), 64)
  val registers = withReset((reset.asBool || io.activeReset).asAsyncReset) {
    Module(new SMEMRegisters(0x20050000, window))
  }
  val core = Module(new SMEMSinkCore(window))
  val writer = Module(new SMEMAXIWriter)
  registers.io.bus.b2c := io.bus.b2c; io.bus.c2b := registers.io.bus.c2b
  val guard = Module(new TraceSinkPacketGuard)
  guard.io.in <> io.in; guard.io.out <> core.io.in; guard.io.active := !io.activeReset
   core.io.enable := registers.io.enable; core.io.stopOnWrap := registers.io.stopOnWrap
  core.io.abort := io.activeReset; core.io.configuration <> registers.io.configuration
  core.io.request <> writer.io.request; core.io.response <> writer.io.response
  registers.io.committed := core.io.committed; registers.io.empty := core.io.empty && guard.io.empty
  registers.io.stop := core.io.stop; registers.io.wrap := core.io.wrap
  registers.io.fault := core.io.fault; registers.io.faultAddress := core.io.faultAddress
  io.axi <> writer.io.axi; io.committed := core.io.committed
}

class SMEMControlSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "SMEM control and AXI software-reset contract"
  it should "validate CSR edits, report write failures and drain old AXI responses through Active reset" in {
    test(new SMEMControlHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val base = BigInt("180000000", 16)
      d.io.bus.b2c.address.poke(0.U); d.io.bus.b2c.dataWrite.poke(0.U)
      d.io.bus.b2c.readEn.poke(false.B); d.io.bus.b2c.writeEn.poke(false.B)
      d.io.activeReset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(4.U)
      d.io.axi.aw.ready.poke(false.B); d.io.axi.w.ready.poke(false.B)
      d.io.axi.b.valid.poke(false.B); d.io.axi.b.bits.resp.poke(0.U); d.io.axi.b.bits.id.poke(0.U)
      d.io.axi.ar.ready.poke(true.B); d.io.axi.r.valid.poke(false.B)
      d.io.axi.r.bits.id.poke(0.U); d.io.axi.r.bits.resp.poke(0.U); d.io.axi.r.bits.data.poke(0.U)
      d.io.axi.r.bits.last.poke(true.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def read(offset: Int): BigInt = {
        d.io.bus.b2c.address.poke((0x50000 + offset).U); d.io.bus.b2c.readEn.poke(true.B)
        val data = d.io.bus.c2b.dataRead.peek().litValue
        d.io.bus.b2c.readEn.poke(false.B); data
      }
      def write(offset: Int, value: BigInt, rejected: Boolean = false): Unit = {
        d.io.bus.b2c.address.poke((0x50000 + offset).U); d.io.bus.b2c.dataWrite.poke(value.U)
        d.io.bus.b2c.writeEn.poke(true.B); d.io.bus.c2b.error.expect(rejected.B)
        d.clock.step(); d.io.bus.b2c.writeEn.poke(false.B)
      }
      def packet(value: BigInt): Unit = {
        d.io.in.bits.data.poke(value.U); d.io.in.valid.poke(true.B)
        d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def waitFor(valid: Bool): Unit = {
        var cycles = 0
        while (!valid.peek().litToBoolean && cycles < 50) { d.clock.step(); cycles += 1 }
        assert(cycles < 50)
      }
      def finish(response: Int = 0): Unit = {
        d.io.axi.aw.ready.poke(true.B); d.io.axi.w.ready.poke(true.B)
        waitFor(d.io.axi.aw.valid); d.clock.step()
        d.io.axi.aw.ready.poke(false.B); d.io.axi.w.ready.poke(false.B)
        waitFor(d.io.axi.b.ready)
        d.io.axi.b.bits.resp.poke(response.U); d.io.axi.b.valid.poke(true.B)
        d.clock.step(); d.io.axi.b.valid.poke(false.B); d.clock.step(4)
      }
      read(4) shouldBe BigInt(0x2901)
      read(0) shouldBe BigInt(0x19) // Active, Empty, SMEM
      read(0x14) shouldBe BigInt(1); read(0x10) shouldBe BigInt(0x80000000L)
      write(0x1c, 2); (read(0xf00) & 3) shouldBe BigInt(1) // temporary invalid high half
      write(0x1c, 1); (read(0xf00) & 3) shouldBe BigInt(0)
      write(0x18, 0x8000000cL); write(0x20, 0x80000004L)
      read(0x20) shouldBe BigInt(0x80000004L)
      write(0, 3)
      d.io.committed.expect((base + 4).U)
      write(0x10, 0x80000008L, rejected = true)
      read(0x10) shouldBe BigInt(0x80000000L)
      packet(BigInt("04030201", 16)); finish()
      d.io.committed.expect((base + 8).U)
      println("[SMEM-control] 1/4 实际 SMEM 发现值、64-bit 分半编辑、非法配置修正和运行中写保护通过")
      packet(BigInt("08070605", 16)); finish(response = 2)
      (read(0xf00) & 7) shouldBe BigInt(6)
      read(0xf04) shouldBe BigInt(0x80000008L); read(0xf08) shouldBe BigInt(1)
      (read(0) & 2) shouldBe BigInt(0); d.io.committed.expect((base + 8).U)
      write(0x20, 0x80000008L); (read(0xf00) & 3) shouldBe BigInt(0)
      write(0, 3)
      println("[SMEM-control] 2/4 B 错误停止 Enable，报告失败地址和 capture-invalid；合法重配才恢复")
      packet(BigInt("0c0b0a09", 16)); waitFor(d.io.axi.aw.valid)
      d.io.axi.aw.ready.poke(true.B); d.clock.step(); d.io.axi.aw.ready.poke(false.B)
      d.io.axi.aw.valid.expect(false.B); d.io.axi.w.valid.expect(true.B)
      d.io.activeReset.poke(true.B); d.clock.step(4)
      d.io.axi.w.valid.expect(true.B); d.io.axi.w.bits.data.expect("h0c0b0a09".U)
      (read(0) & 8) shouldBe BigInt(0) // no premature Empty while AXI has half a transaction
      d.io.activeReset.poke(false.B)
      d.io.axi.w.ready.poke(true.B); d.clock.step(); d.io.axi.w.ready.poke(false.B)
      waitFor(d.io.axi.b.ready)
      d.io.axi.b.bits.resp.poke(0.U); d.io.axi.b.valid.poke(true.B); d.clock.step()
      d.io.axi.b.valid.poke(false.B); d.clock.step(5)
      d.io.committed.expect(base.U); (read(0) & 8) shouldBe BigInt(8)
      read(0xf00) shouldBe BigInt(0)
      println("[SMEM-control] 3/4 Active 在 AW 已接受/W 受阻时复位，保留 W 和 B，排出后清新会话状态")
      write(0, 3); packet(BigInt("14131211", 16)); finish()
      d.io.committed.expect((base + 4).U)
      write(0, 1); (read(0) & 8) shouldBe BigInt(8)
      println("[SMEM-control] 4/4 重新启用后事务地址和提交独立，无旧 B/旧配置污染")
      write(0, 3)
      d.io.in.bits.packetBytes.poke(7.U); d.io.in.bits.bytes.poke(3.U)
      d.io.in.bits.last.poke(false.B); d.io.in.bits.data.poke(0x11111111L.U)
      d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.io.activeReset.poke(true.B); d.clock.step(3); d.io.activeReset.poke(false.B); d.clock.step(3)
      (read(0) & 8) shouldBe BigInt(0)
      d.io.in.bits.bytes.poke(2.U); d.io.in.bits.last.poke(true.B); d.io.in.bits.data.poke(0x222222.U)
      d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.clock.step(3); (read(0) & 8) shouldBe BigInt(8)
      d.io.in.bits.packetBytes.poke(4.U); d.io.in.bits.bytes.poke(3.U)
      write(0, 3); packet(BigInt("24232221", 16)); finish()
      d.io.committed.expect((base + 4).U)
      println("[SMEM-control] 输入半包Active reset：Empty等待guard丢完旧tail，新packet从base完整提交")

    }
  }
}
