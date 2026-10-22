package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.TraceParameters
import trace.interface._

/** Real packet generation, byte FIFO and SRAM; no synthetic packet injection. */
private class MixedPacketStorageHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val block = Input(new BlockBufferEntry)
    val blockReady = Output(Bool())
    val stop = Input(Bool())
    val sinkEnable = Input(Bool())
    val transfer = Input(Bool())
    val packet = Output(new PacketEnq(maxPackets))
    val valid = Output(Bool())
    val ready = Output(Bool())
    val encodedEmpty = Output(Bool())
    val empty = Output(Bool())
    val wp = Output(UInt(8.W))
    val readPointer = Flipped(Valid(UInt(8.W)))
    val data = Output(UInt(32.W))
  })
  val gen = Module(new PacketGen)
  val fifo = Module(new Fifo(FifoParam(true, maxPackets * 2, maxPackets, encoderAtbDataBeats)))
  val ram = Module(new SRAMSinkCore(256))
  gen.io.block := io.block
  val control = WireDefault((6 | (15 << 20)).U(32.W))
  val features = WireDefault(((BigInt(8) << 28) | (3 << 16) | 48).U(32.W))
  gen.io.fromCtrl.teCtrl := control
  gen.io.fromCtrl.teInstFeat := features
  gen.io.fromCtrl.teDataCtrl := 0.U(32.W)
  gen.io.fromCtrl.filterRes := true.B
  gen.io.fromCtrl.fifoIsFull := fifo.io.ctrl.fifoIsFull
  gen.io.stopReq := io.stop
  gen.io.syncReq := false.B; gen.io.flushReq := false.B
  fifo.io.packetEnq <> gen.io.packet
  fifo.io.control.flushReq := false.B; fifo.io.control.syncReq := false.B
  ram.io.in.valid := fifo.io.atbm.valid && io.transfer
  ram.io.in.bits := fifo.io.atbm.bits
  fifo.io.atbm.ready := ram.io.in.ready && io.transfer
  ram.io.enable := io.sinkEnable; ram.io.stopOnWrap := true.B
  ram.io.writePointer.valid := false.B; ram.io.writePointer.bits := 0.U
  ram.io.readPointer <> io.readPointer; ram.io.read := false.B
  io.blockReady := gen.io.blockReady
  io.packet := gen.io.packet.bits; io.valid := gen.io.packet.valid; io.ready := gen.io.packet.ready
  io.encodedEmpty := gen.io.empty && fifo.io.ctrl.fifoIsEmpty
  io.empty := ram.io.empty; io.wp := ram.io.wp; io.data := ram.io.readData
  assert(!ram.io.wrap && !ram.io.stop, "mixed-format capture must fit in the SRAM")
}

class PacketMixedFormatsSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Entry(pc: Int, kind: Int = 0, context: Int = 0, ctype: Int = 0,
      notifyRequested: Boolean = false, cause: Int = 0, lost: Boolean = false, size: Int = 1, time: Int = 0) {
    def retired: Boolean = !lost && kind != 1 && kind != 2
  }
  behavior of "all instruction packet formats in a shared execution stream"
  for (seed <- Seq(1, 7, 19)) {
  it should s"retain prediction cache trap context loss and compressed retirement with backpressure seed $seed" in {
    test(new MixedPacketStorageHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.clock.setTimeout(100000)
      val frames = ArrayBuffer.empty[Seq[Int]]
      var held = Option.empty[(BigInt, Seq[BigInt])]
      var cycles = 0
      var stalled = 0
      val random = new scala.util.Random(seed)
      def tick(): Unit = {
        d.io.transfer.poke((cycles > 120 && random.nextInt(11) >= 5).B)
        val valid = d.io.valid.peek().litToBoolean
        val ready = d.io.ready.peek().litToBoolean
        val bits = (d.io.packet.len.peek().litValue, d.io.packet.data.map(_.peek().litValue).toSeq)
        held.foreach { old => valid shouldBe true; bits shouldBe old }
        held = if (valid && !ready) Some(bits) else None
        if (valid && !ready) stalled += 1
        if (valid && ready) frames += bits._2.take(bits._1.toInt).map(_.toInt)
        d.clock.step(); cycles += 1
      }
      def drive(e: Entry): Unit = {
        val b = d.io.block
        b.valid.poke(true.B); b.iaddr.poke(e.pc.U); b.itype.poke(e.kind.U)
        b.iretire.poke((if (e.retired) 1 << e.size else 0).U); b.ilastsize.poke(e.size.U)
        b.context.poke(e.context.U); b.ctype.poke(e.ctype.U); b.time.poke(e.time.U)
        b.notifyRequested.poke(e.notifyRequested.B); b.cause.poke(e.cause.U); b.traceLost.poke(e.lost.B)
      }
      d.io.block.priv.poke(Priv.M); d.io.block.trigger.poke(TriggerAction.None)
      d.io.block.traceOffAfter.poke(false.B); d.io.block.tval.poke(0.U)
      d.io.block.hartIsReset.poke(false.B); d.io.block.impdef.poke(0.U)
      drive(Entry(0)); d.io.block.valid.poke(false.B)
      d.io.stop.poke(false.B); d.io.sinkEnable.poke(true.B); d.io.transfer.poke(false.B)
      d.io.readPointer.valid.poke(false.B); d.io.readPointer.bits.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)

      val prefix = Seq(Entry(0x1000)) ++ (0 until 31).map(i => Entry(0x1004 + i * 8, 5)) ++
        Seq(Entry(0x10fc, notifyRequested = true)) ++ (0 until 31).map(i => Entry(0x1100 + i * 4, 4)) ++
        Seq(Entry(0x117c, 5), Entry(0x1184, notifyRequested = true), Entry(0x1188))
      val cache = Seq(Entry(0x3000, context = 1, ctype = 3), Entry(0x3004, 8, context = 1),
        Entry(0x4000, 4, context = 1), Entry(0x4004, 8, context = 1), Entry(0x4100, context = 1),
        Entry(0x4104, 8, context = 1), Entry(0x4000, 4, context = 1), Entry(0x4004, 8, context = 1),
        Entry(0x4100, context = 2, ctype = 1, time = 0x1234), Entry(0x4104, 8, context = 2),
        Entry(0x4000, 4, context = 3, ctype = 2), Entry(0x4004, 8, context = 3), Entry(0x4100, context = 3))
      val traps = Seq(Entry(0x4104, 2, context = 3, cause = 7), Entry(0x5000, context = 3),
        Entry(0x5004, 3, context = 3), Entry(0x4104, 8, context = 3), Entry(0x6000, 1, context = 3, cause = 11),
        Entry(0x7000, context = 3), Entry(0x7004, 4, context = 3), Entry(0x7008, context = 3, notifyRequested = true),
        Entry(0x700c, context = 3))
      val prediction = Seq(Entry(0x8000, context = 4, ctype = 3)) ++
        (0 until 31).map(i => Entry(0x8004 + i * 4, 4, context = 4)) ++
        Seq(Entry(0x8080, context = 4, notifyRequested = true)) ++
        (0 until 31).map(i => Entry(0x8084 + i * 4, 4, context = 4)) ++
        Seq(Entry(0x8100, 5, context = 4, notifyRequested = true), Entry(0x8108, context = 4, notifyRequested = true),
          Entry(0, context = 4, lost = true), Entry(0x8200, context = 4), Entry(0x8204, 4, context = 4),
          Entry(0x8208, context = 4, notifyRequested = true), Entry(0x820c, context = 4),
          Entry(0x9000, context = 5, ctype = 2), Entry(0x9004, context = 5, size = 0), Entry(0x9006, context = 5, size = 0))
      val entries = prefix ++ cache ++ traps ++ prediction
      println(s"[mixed-packets][1/5] seed=$seed：冷预测map→长正确run/失败→cache/context→interrupt/xret/exception→loss/同步→压缩尾指令")
      entries.foreach { entry =>
        drive(entry)
        var accepted = false
        var budget = 2000
        while (!accepted && budget > 0) { accepted = d.io.blockReady.peek().litToBoolean; tick(); budget -= 1 }
        assert(accepted, s"mixed-format input timeout at 0x${entry.pc.toHexString}")
      }
      d.io.block.valid.poke(false.B); d.io.stop.poke(true.B); tick(); d.io.stop.poke(false.B)
      var budget = 10000
      while (!d.io.encodedEmpty.peek().litToBoolean && budget > 0) { tick(); budget -= 1 }
      assert(budget > 0, "all encoded packets must drain")
      d.io.sinkEnable.poke(false.B)
      for (_ <- 0 until 12) tick()
      d.io.empty.expect(true.B)
      stalled should be > 0
      println(s"[mixed-packets][2/5] FIFO/SRAM真实反压${stalled}周期，停顿len/全部data保持，停止排空完成")

      def support(status: Int = 0) = ETraceReference.support(3, jumpCache = true, branchPrediction = true, status = status)
      val expected = Seq(support(), ETraceReference.synchronization(3, 0x1000),
        ETraceReference.branchOnly(3, Seq.fill(31)(true)), ETraceReference.address(3, 0x10fc, 0x1000, notifyRequested = true),
        ETraceReference.prediction(3, 31), ETraceReference.address(3, 0x1184, 0x10fc, notifyRequested = true),
        ETraceReference.address(3, 0x1188, 0x1184), support(1), ETraceReference.synchronization(3, 0x3000, context = 1),
        ETraceReference.address(3, 0x4000, 0x3000, Seq(false)), ETraceReference.address(3, 0x4100, 0x4000),
        ETraceReference.cachedTarget(3, 0x4000, Seq(false)), ETraceReference.context(3, 2, time = 0x1234),
        ETraceReference.cachedTarget(3, 0x4100), ETraceReference.address(3, 0x4104, 0x4100),
        ETraceReference.synchronization(3, 0x4000, context = 3), ETraceReference.address(3, 0x4100, 0x4000, beforeSync = true),
        ETraceReference.trap(3, 0x5000, 7, interrupt = true, context = 3),
        ETraceReference.address(3, 0x4104, 0x5000, beforeSync = true),
        ETraceReference.trap(3, 0x6000, 11, handler = false, context = 3),
        ETraceReference.synchronization(3, 0x7000, context = 3),
        ETraceReference.address(3, 0x7008, 0x7000, Seq(false), notifyRequested = true),
        ETraceReference.address(3, 0x700c, 0x7008), support(1), ETraceReference.synchronization(3, 0x8000, context = 4),
        ETraceReference.prediction(3, 31, Some(0x8080), 0x8000, notifyRequested = true),
        ETraceReference.prediction(3, 31, Some(0x8100), 0x8080, failed = true, notifyRequested = true),
        ETraceReference.address(3, 0x8108, 0x8100, notifyRequested = true), support(2),
        ETraceReference.synchronization(3, 0x8200, context = 4),
        ETraceReference.address(3, 0x8208, 0x8200, Seq(false), notifyRequested = true),
        ETraceReference.address(3, 0x820c, 0x8208), ETraceReference.synchronization(3, 0x9000, context = 5),
        ETraceReference.address(3, 0x9006, 0x9000), support(1))
      frames.size shouldBe expected.size
      frames.zip(expected).zipWithIndex.foreach { case ((actual, golden), index) =>
        withClue(s"mixed packet #$index: ") { actual shouldBe golden }
      }
      println(s"[mixed-packets][3/5] ${frames.size}包与独立wire golden逐字节一致，覆盖全部instruction格式与子格式")
      val words = d.io.wp.peek().litValue.toInt
      val capture = (0 until words).flatMap { index =>
        d.io.readPointer.valid.poke(true.B); d.io.readPointer.bits.poke(index.U); tick()
        d.io.readPointer.valid.poke(false.B)
        val word = d.io.data.peek().litValue
        (0 until 4).map(lane => ((word >> (8 * lane)) & 255).toInt)
      }
      capture.take(frames.flatten.size) shouldBe frames.flatten.toSeq
      capture.drop(frames.flatten.size).foreach(_ shouldBe 0)
      println(s"[mixed-packets][4/5] 实际SRAM读回${capture.size}bytes，packet字节与输入握手完全一致，仅尾部补null")
      val out = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve(s"build/validation/mixed-packets/seed-$seed")
      for (name <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(out.resolve(name))
      Files.write(out.resolve("dut/trace.bin"), capture.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x8008)(0)
      def put(pc: Int, word: Int, size: Int = 4): Unit =
        for (i <- 0 until size) code(pc - 0x1000 + i) = ((word >> (i * 8)) & 255).toByte
      entries.filter(_.retired).foreach { e =>
        val word = e.kind match {
          case 4 => 0x1463 // bne x0,x0,+8
          case 5 => 0x463 // beq x0,x0,+8
          case 8 => 0x28067 // jalr x0,x5,0; the actual target is traced
          case 3 => 0x30200073 // mret
          case _ => if (e.size == 0) 1 else 0x13
        }
        put(e.pc, word, if (e.size == 0) 2 else 4)
      }
      put(0x6000, 0x73) // ecall does not retire
      val jump = 0x9000 - 0x820c
      put(0x820c, (((jump >> 20) & 1) << 31) | (((jump >> 1) & 1023) << 21) |
        (((jump >> 11) & 1) << 20) | (((jump >> 12) & 255) << 12) | 0x6f)
      Files.write(out.resolve("workload/code.bin"), code)
      Files.writeString(out.resolve("dut/trace.json"),
        """{"sources":{"3":{"hart_id":7,"raw":"../workload/code.bin","base":"0x1000","xlen":64}}}""")
      Files.writeString(out.resolve("ref/retired-pcs.json"), entries.filter(_.retired).map(e => s"\"0x${e.pc.toHexString}\"").mkString("[", ",", "]"))
      Files.writeString(out.resolve("ref/packet-golden.json"), expected.map(p =>
        p.map(b => f"$b%02x").mkString("\"", " ", "\"")).mkString("[", ",", "]"))
      Files.writeString(out.resolve("ref/input.jsonl"), entries.map(e =>
        s"{\"pc\":\"0x${e.pc.toHexString}\",\"itype\":${e.kind},\"context\":${e.context},\"ctype\":${e.ctype},\"retired\":${e.retired},\"loss\":${e.lost}}\n").mkString)
      println(s"[mixed-packets][5/5] 导出${entries.count(_.retired)}条模块退休参考/真实镜像及SRAM采集；不冒称香山ROB真值")
    }
  }
  }
}
