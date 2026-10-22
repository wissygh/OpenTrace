package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths, StandardCopyOption}
import trace.TraceIngressConfig
import trace.interface._

private class XSTraceRetirement extends Bundle {
  val valid = Bool()
  val bits = new Bundle {
    val ftqIdx = new Bundle { val value = UInt(6.W) }
    val ftqOffset = UInt(4.W)
    val tracePipe = new Bundle { val itype = UInt(4.W); val iretire = UInt(7.W); val ilastsize = Bool() }
  }
}
private class ExistingXSTraceBuffer(path: String) extends BlackBox with HasBlackBoxPath {
  override def desiredName = "TraceBuffer"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val reset = Input(Bool())
    val io_in_fromEncoder_enable = Input(Bool()); val io_in_fromEncoder_stall = Input(Bool())
    val io_in_fromRob_blocks = Input(Vec(8, new XSTraceRetirement))
    val io_out_blockCommit = Output(Bool())
    val io_out_groups_blocks = Output(Vec(3, new XSTraceRetirement))
  })
  addPath(path)
}

/** Reuses the user's generated TraceBuffer, with the exact Default Top trace
  * wrappers: Trace stage1 and stage3, plus MemBlock/L2Top registers in both
  * directions. This is not an emu rebuild or a replacement for real-core runs.
  */
private class XSPipelineIngressHarness(path: String, config: TraceIngressConfig) extends Module {
  val io = IO(new Bundle {
    val rob = Input(Vec(8, new XSTraceRetirement))
    val blocked = Output(Bool())
    val consume = Input(Bool())
    val head = Output(new BlockBufferEntry)
    val dropped = Output(UInt(2.W))
    val empty = Output(Bool())
    val stall = Output(Bool())
  })
  val xs = Module(new ExistingXSTraceBuffer(path))
  val buffer = Module(new BlockBuffer(config.stallResponseCycles, config.bufferEntries))
  xs.io.clock := clock; xs.io.reset := reset.asBool; xs.io.io_in_fromEncoder_enable := true.B
  xs.io.io_in_fromEncoder_stall := RegNext(RegNext(buffer.io.bufferIsFull, false.B), false.B)
  io.blocked := xs.io.io_out_blockCommit
  for (i <- 0 until 8) {
    xs.io.io_in_fromRob_blocks(i).valid := RegEnable(io.rob(i).valid, false.B, !io.blocked)
    xs.io.io_in_fromRob_blocks(i).bits := RegEnable(io.rob(i).bits,
      0.U.asTypeOf(io.rob(i).bits), io.rob(i).valid)
  }
  val groups = RegNext(RegNext(RegNext(xs.io.io_out_groups_blocks,
    0.U.asTypeOf(xs.io.io_out_groups_blocks)), 0.U.asTypeOf(xs.io.io_out_groups_blocks)),
    0.U.asTypeOf(xs.io.io_out_groups_blocks))
  buffer.io.fromCore := 0.U.asTypeOf(new FromCore)
  buffer.io.fromCore.priv := Priv.M
  for (i <- 0 until 3) {
    buffer.io.fromCore.blocks(i).valid := groups(i).valid
    buffer.io.fromCore.blocks(i).bits.iaddr := "h80010000".U +
      (Cat(groups(i).bits.ftqIdx.value, groups(i).bits.ftqOffset) << 2)
    buffer.io.fromCore.blocks(i).bits.itype := groups(i).bits.tracePipe.itype
    buffer.io.fromCore.blocks(i).bits.iretire := groups(i).bits.tracePipe.iretire
    buffer.io.fromCore.blocks(i).bits.ilastsize := groups(i).bits.tracePipe.ilastsize
  }
  buffer.io.deqReady := io.consume
  io.head := buffer.io.deqEntry; io.dropped := buffer.io.droppedEntries
  io.empty := buffer.io.empty; io.stall := buffer.io.bufferIsFull
}

class XiangShanIngressSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "actual generated XiangShan trace-buffer feedback contract"
  for (config <- Seq(TraceIngressConfig(), TraceIngressConfig(64, 16))) {
    it should s"measure peak eight-wide retirement through the real trace buffer depth=${config.bufferEntries} response=${config.stallResponseCycles}" in {
      val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
      val rtl = root.resolve("delp/XiangShan-trace/build/rtl/TraceBuffer.sv")
      val out = root.resolve(s"build/validation/xiangshan-ingress/depth-${config.bufferEntries}")
      for (name <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(out.resolve(name))
      Files.writeString(out.resolve("diff/comparison.json"), """{"status":"incomplete"}""")
      assert(Files.isRegularFile(rtl), "requires the user's existing generated XiangShan TraceBuffer.sv")
      test(new XSPipelineIngressHarness(rtl.toString, config)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
        d.clock.setTimeout(20000)
        d.io.consume.poke(false.B)
        for (b <- d.io.rob) {
          b.valid.poke(false.B); b.bits.ftqIdx.value.poke(0.U); b.bits.ftqOffset.poke(0.U)
          b.bits.tracePipe.itype.poke(4.U); b.bits.tracePipe.iretire.poke(2.U); b.bits.tracePipe.ilastsize.poke(true.B)
        }
        d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
        var offered = 0; var dropped = 0; var stalls = 0
        val actual = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        for (cycle <- 0 until 1500) {
          val valid = offered < 384 && !d.io.blocked.peek().litToBoolean
          for ((b, i) <- d.io.rob.zipWithIndex) {
            b.valid.poke(valid.B)
            val tag = offered + i
            b.bits.ftqIdx.value.poke(((tag / 16) & 63).U); b.bits.ftqOffset.poke((tag & 15).U)
          }
          if (valid) offered += 8
          val consume = cycle >= 160 && cycle % 5 != 0
          d.io.consume.poke(consume.B)
          dropped += d.io.dropped.peek().litValue.toInt
          if (d.io.stall.peek().litToBoolean) stalls += 1
          if (consume && d.io.head.valid.peek().litToBoolean && !d.io.head.traceLost.peek().litToBoolean)
            actual += d.io.head.iaddr.peek().litValue
          d.clock.step()
        }
        offered shouldBe 384; stalls should be > 0; d.io.empty.expect(true.B)
        actual.size + dropped shouldBe offered
        val expected = (0 until offered).map(i => BigInt("80010000", 16) + i * 4)
        if (config.bufferEntries == 16) dropped should be > 0
        else { dropped shouldBe 0; actual.toSeq shouldBe expected }
        def pcs(values: Seq[BigInt]): String = values.map(p => s"\"0x${p.toString(16)}\"").mkString("[", ",", "]")
        Files.writeString(out.resolve("ref/rob-pcs.json"), pcs(expected))
        Files.writeString(out.resolve("dut/retired-pcs.json"), pcs(actual.toSeq))
        Files.copy(rtl, out.resolve("dut/TraceBuffer.sv"), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(out.resolve("workload/config.json"),
          s"""{"buffer_entries":${config.bufferEntries},"stall_response_cycles":${config.stallResponseCycles},"rob_width":8,"groups":3,"feedback_registers":2,"return_registers":3}""")
        Files.writeString(out.resolve("diff/comparison.json"),
          s"""{"status":"passed","expected_loss":${config.bufferEntries == 16},"offered":$offered,"retained":${actual.size},"dropped":$dropped,"stall_cycles":$stalls,"reference_kind":"synthetic ROB input through actual generated XiangShan TraceBuffer, not full core"}""")
        println(s"[XS-ingress-contract] depth=${config.bufferEntries}/response=${config.stallResponseCycles}: 实际TraceBuffer+Default Top 2级反馈/3级返回，${offered}条ROB输入/${actual.size}条输出/${dropped}条拒收/${stalls}个stall周期")
      }
    }
  }
}
