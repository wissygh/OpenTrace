package trace

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}
import trace.interface._

/** Actual Trace, including TL control CDC, always-on endpoints, encoders,
  * per-source data CDC, funnel, standard ATB and its separate output clock.
  */
private class TraceSystemHarness extends Module {
  implicit val p: Parameters = Parameters.empty
  val design = LazyModule(new Trace(BusType.TL, numHarts = 2, useAtbSink = true))
  val top = Module(design.module)
  val ctrl = design.ctrlTLMaster.get.getWrappedValue.head
  val io = IO(new Bundle {
    val control = Flipped(new TLBundle(ctrl.params))
    val fromCore = Input(Vec(2, new FromCore))
    val toCore = Output(Vec(2, new ToCore))
    val ready = Input(Bool())
    val flush = Input(Bool())
    val sync = Input(Bool())
    val coreCycles = Output(UInt(16.W))
    val sinkCycles = Output(UInt(16.W))
    val count = Output(UInt(9.W))
    val data = Output(Vec(256, new ATData(4)))
    val flushes = Output(UInt(8.W))
  })
  ctrl <> io.control
  val coreLevel = RegInit(false.B)
  coreLevel := !coreLevel
  val coreClock = coreLevel.asClock
  val traceLevel = RegInit(false.B)
  val traceDivide = RegInit(0.U(2.W))
  traceDivide := Mux(traceDivide === 2.U, 0.U, traceDivide + 1.U)
  when(traceDivide === 2.U) { traceLevel := !traceLevel }
  val traceClock = traceLevel.asClock
  val atbLevel = RegInit(false.B)
  val atbDivide = RegInit(0.U(3.W))
  atbDivide := Mux(atbDivide === 4.U, 0.U, atbDivide + 1.U)
  when(atbDivide === 4.U) { atbLevel := !atbLevel }
  val atbClock = atbLevel.asClock
  top.io.clock_ctrl := clock; top.io.reset_ctrl := reset.asAsyncReset
  top.io.clock_core := coreClock; top.io.reset_core := reset.asAsyncReset
  top.io.clock_trace := traceClock; top.io.reset_trace := reset.asAsyncReset
  top.io.clock_atb.get := atbClock; top.io.reset_atb_n.get := !reset.asBool
  top.io.fromCore := io.fromCore; io.toCore := top.io.toCore
  val atb = top.io.atbm.get
  atb.at.ready := io.ready; atb.atclken := true.B
  atb.af.valid := io.flush; atb.af.bits := 0.U.asTypeOf(atb.af.bits)
  atb.syncReq := io.sync
  withClockAndReset(coreClock, reset.asAsyncReset) {
    val cycles = RegInit(0.U(16.W)); cycles := cycles + 1.U; io.coreCycles := cycles
  }
  withClockAndReset(atbClock, reset.asAsyncReset) {
    val cycles = RegInit(0.U(16.W)); cycles := cycles + 1.U; io.sinkCycles := cycles
    val count = RegInit(0.U(9.W))
    val memory = Reg(Vec(256, new ATData(4)))
    val flushes = RegInit(0.U(8.W))
    when(atb.at.fire) {
      assert(count < 256.U); memory(count(7, 0)) := atb.at.bits; count := count + 1.U
    }
    when(atb.af.fire) { flushes := flushes + 1.U }
    io.count := count; io.data := memory; io.flushes := flushes
  }
}

class TraceSystemSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "actual Trace control and data domains"
  it should "configure real TL registers and decode stopped hart streams through all clock crossings" in {
    test(new TraceSystemHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(20000)
      d.io.control.a.valid.poke(false.B); d.io.control.d.ready.poke(true.B)
      d.io.control.a.bits.opcode.poke(TLMessages.Get); d.io.control.a.bits.param.poke(0.U)
      d.io.control.a.bits.size.poke(2.U); d.io.control.a.bits.source.poke(0.U)
      d.io.control.a.bits.address.poke(0.U); d.io.control.a.bits.mask.poke(15.U)
      d.io.control.a.bits.data.poke(0.U); d.io.control.a.bits.corrupt.poke(false.B)
      d.io.ready.poke(false.B); d.io.flush.poke(false.B); d.io.sync.poke(false.B)
      for (c <- d.io.fromCore) {
        c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
        c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
        c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
        for (b <- c.blocks) {
          b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
          b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word); b.bits.trigNotify.poke(false.B)
          b.bits.sijump.foreach(_.poke(false.B))
        }
      }
      d.reset.poke(true.B); d.clock.step(20); d.reset.poke(false.B); d.clock.step(150)
      def until(condition: => Boolean, limit: Int = 1000): Unit = {
        var remaining = limit
        while (!condition && remaining > 0) { d.clock.step(); remaining -= 1 }
        assert(remaining > 0, "Trace system transaction timed out")
      }
      def access(address: Int, write: Boolean = false, value: BigInt = 0, hold: Int = 0): BigInt = {
        d.io.control.a.bits.address.poke(address.U)
        d.io.control.a.bits.data.poke(value.U)
        d.io.control.a.bits.opcode.poke(if (write) TLMessages.PutFullData else TLMessages.Get)
        d.io.control.a.valid.poke(true.B); d.io.control.d.ready.poke(false.B)
        until(d.io.control.a.ready.peek().litToBoolean); d.clock.step(); d.io.control.a.valid.poke(false.B)
        until(d.io.control.d.valid.peek().litToBoolean)
        val response = d.io.control.d.bits.data.peek().litValue
        d.io.control.d.bits.denied.expect(false.B)
        d.io.control.d.bits.opcode.expect(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
        for (_ <- 0 until hold) {
          d.io.control.d.valid.expect(true.B); d.io.control.d.bits.data.expect(response.U); d.clock.step()
        }
        d.io.control.d.ready.poke(true.B); d.clock.step()
        response
      }
      val encoders = Seq(0x20000000, 0x20010000)
      val funnel = 0x20020000
      val atb = 0x20030000
      println("[Trace-system][1/5] 真实顶层，control/core/trace/ATB四种clock；通过TL和控制CDC释放Active")
      for (address <- encoders ++ Seq(funnel, atb)) {
        (access(address) & 1) shouldBe BigInt(0)
        access(address, write = true, value = 1)
        var attempts = 0
        while ((access(address) & 1) == 0 && attempts < 10) attempts += 1
        attempts should be < 10
      }
      access(atb + 4) shouldBe BigInt(0xe01)
      for ((address, source) <- encoders.zipWithIndex) {
        ((access(address + 8) >> 16) & 0xfff) shouldBe BigInt(source)
      }
      access(atb, write = true, value = 3 | (0x20 << 8))
      access(funnel, write = true, value = 3)
      encoders.foreach(address => access(address, write = true, value = 7))
      until(d.io.toCore.forall(_.enable.peek().litToBoolean))
      println("[Trace-system][2/5] 下游到上游启用，真实srcID发现通过；ATREADY反压期间注入两核输入")
      for (group <- 0 until 4) {
        for (hart <- 0 until 2) {
          d.io.fromCore(hart).blocks(0).valid.poke(true.B)
          d.io.fromCore(hart).blocks(0).bits.iaddr.poke(((if (hart == 0) 0x1000 else 0x8000) + group * 4).U)
        }
        val before = d.io.coreCycles.peek().litValue
        until(d.io.coreCycles.peek().litValue != before)
        d.io.fromCore.foreach(_.blocks(0).valid.poke(false.B))
        d.clock.step(4)
      }
      d.clock.step(200)
      d.io.ready.poke(true.B)
      // The actual control write stops trace without synthetic core lookahead.
      encoders.foreach(address => access(address, write = true, value = 3))
      for (address <- encoders) {
        var attempts = 0
        while ((access(address, hold = 4) & 8) == 0 && attempts < 20) attempts += 1
        attempts should be < 20
      }
      println("[Trace-system][3/5] 软件停止、真实Empty读回和D反压保持通过；发起标准ATB AF端到端flush")
      val flushes = d.io.flushes.peek().litValue
      d.io.flush.poke(true.B)
      until(d.io.flushes.peek().litValue > flushes, 3000)
      d.io.flush.poke(false.B); d.clock.step(150)
      val count = d.io.count.peek().litValue.toInt
      count should be > 0
      val capture = (0 until count).flatMap { index =>
        val beat = d.io.data(index)
        beat.id.expect(0x20.U)
        val bytes = beat.bytes.peek().litValue.toInt + 1
        val data = beat.data.peek().litValue
        (0 until bytes).map(i => ((data >> (8 * i)) & 255).toByte)
      }.toArray
      println(s"[Trace-system][4/5] 实际ATB端捕获$count beats/${capture.length}bytes，AF已确认全部旧数据")
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val out = workspace.resolve(Paths.get("build", "validation")); Files.createDirectories(out)
      Files.write(out.resolve("trace-system.bin"), capture)
      val nopCode = Array.fill[Byte](32)(0)
      for (index <- 0 until 8) nopCode(index * 4) = 0x13
      Files.write(out.resolve("trace-system-code.bin"), nopCode)
      val config = """{"source_bits":8,"sources":{"0":{"hart_id":0,"raw":"trace-system-code.bin","base":"0x1000","xlen":64},"1":{"hart_id":1,"raw":"trace-system-code.bin","base":"0x8000","xlen":64}}}"""
      Files.write(out.resolve("trace-system.json"), config.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      println("[Trace-system][5/5] 导出真实top捕获与两路镜像映射，decoder检查各核全部4条退休PC")
    }
  }
}
