package trace

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.amba.axi4._
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.component._
import trace.interface._

/** Tests the actual BlockBuffer/PacketGen/Fifo path and the shared SRAM core,
  * without duplicating their datapath or relying on an async-CSR test backend.
  */
private class MultiHartTraceHarness(words: Int = 16, useSMEM: Boolean = false) extends Module with TraceParameters {
  val io = IO(new Bundle {
    val smem = Option.when(useSMEM)(new AXI4Bundle(AXI4BundleParameters(64, 32, 1)))
    val fromCore = Input(Vec(2, new FromCore))
    val sinkEnable = Input(Bool())
    val control = Input(UInt(32.W))
    val features = Input(Vec(2, UInt(32.W)))
    val readPointer = Flipped(Valid(UInt(buffer_ptr_width.W)))
    val readData = Output(UInt(32.W))
    val wp = Output(UInt(buffer_ptr_width.W))
    val empty = Output(Bool())
    val ingressOverflow = Output(Bool())
    val encodersEmpty = Output(Bool())
  })
  val arbiter = Module(new PacketArbiter(2, encoderAtbDataBeats))
  arbiter.io.enable := true.B
  arbiter.io.disable.foreach(_ := false.B)
  val overflow = Wire(Vec(2, Bool()))
  val empty = Wire(Vec(2, Bool()))
  io.ingressOverflow := overflow.asUInt.orR
  io.encodersEmpty := empty.asUInt.andR
  for (i <- 0 until 2) {
    val blocks = Module(new BlockBuffer)
    val encoder = Module(new PacketGen)
    encoder.io.syncReq := false.B
    encoder.io.flushReq := false.B
    encoder.io.stopReq := io.fromCore(i).hartIsReset || io.fromCore(i).priv === Priv.D
    val fifo = Module(new Fifo(FifoParam(true, maxPackets * 3, maxPackets, encoderAtbDataBeats)))
    blocks.io.fromCore := io.fromCore(i)
    blocks.io.deqReady := encoder.io.blockReady
    encoder.io.block := blocks.io.deqEntry
    val control = Wire(new EncoderCtrlInterface)
    control.filterRes := true.B
    control.fifoIsFull := fifo.io.ctrl.fifoIsFull
    control.teCtrl := io.control
    control.teInstFeat := io.features(i)
    control.teDataCtrl := 0.U
    encoder.io.fromCtrl := control
    fifo.io.control.flushReq := false.B
    fifo.io.control.syncReq := false.B
    fifo.io.packetEnq <> encoder.io.packet
    arbiter.io.in(i) <> fifo.io.atbm
    overflow(i) := blocks.io.overflow
    empty(i) := blocks.io.empty && encoder.io.empty && fifo.io.ctrl.fifoIsEmpty
  }
  if (useSMEM) {
    val base = BigInt("180000000", 16)
    val sink = Module(new SMEMSinkCore(SMEMWindow(base, words * 4)))
    val writer = Module(new SMEMAXIWriter)
    sink.io.in <> arbiter.io.out
    sink.io.enable := io.sinkEnable
    sink.io.stopOnWrap := false.B
    sink.io.abort := false.B
    sink.io.configuration.valid := false.B
    sink.io.configuration.bits := 0.U.asTypeOf(sink.io.configuration.bits)
    sink.io.request <> writer.io.request
    sink.io.response <> writer.io.response
    io.smem.get <> writer.io.axi
    io.wp := (sink.io.committed - base.U) >> 2
    io.empty := sink.io.empty
    io.readData := 0.U
    assert(sink.io.fault === 0.U, "SMEM execution capture must remain error-free")
  } else {
    val sink = Module(new SRAMSinkCore(words))
    sink.io.in <> arbiter.io.out
    sink.io.enable := io.sinkEnable
    sink.io.stopOnWrap := false.B
    sink.io.writePointer.valid := false.B
    sink.io.writePointer.bits := 0.U
    sink.io.readPointer <> io.readPointer
    sink.io.read := false.B
    io.wp := sink.io.wp
    io.empty := sink.io.empty
    io.readData := sink.io.readData
  }
}

class MultiHartTraceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "multi-hart trace"
  it should "store two real encoder streams with their in-band source IDs" in {
    println("[RTL->decoder][1/8] 创建双 hart RTL 集成测试：FromCore -> BlockBuffer -> PacketGen -> Fifo -> PacketArbiter -> SRAMSinkCore")
    test(new MultiHartTraceHarness) { d =>
      println("[RTL->decoder][2/8] 配置 trace：启用 SRAM sink、打开两个 encoder 的指令跟踪，并设置 srcID=0/1")
      d.io.sinkEnable.poke(true.B)
      d.io.control.poke((6 | (2 << 20)).U)
      for (i <- 0 until 2) d.io.features(i).poke((BigInt(8) << 28 | BigInt(i) << 16).U)
      d.io.readPointer.valid.poke(false.B)
      d.io.readPointer.bits.poke(0.U)
      d.io.fromCore.foreach { core =>
        core.priv.poke(Priv.M)
        core.time.poke(0.U)
        core.context.poke(0.U)
        core.ctype.poke(Ctype.None)
        core.cause.poke(0.U)
        core.tval.poke(0.U)
        core.impdef.poke(0.U)
        core.hartIsReset.poke(false.B)
        core.trigger.traceOn.poke(false.B)
        core.trigger.traceOff.poke(false.B)
        core.blocks.foreach { block =>
          block.valid.poke(false.B)
          block.bits.iaddr.poke(0.U)
          block.bits.itype.poke(Itype.None)
          block.bits.iretire.poke(2.U)
          block.bits.ilastsize.poke(Ilastsize.Word)
          block.bits.trigNotify.poke(false.B)
        }
      }
      d.reset.poke(true.B)
      d.clock.step()
      d.reset.poke(false.B)
      println("[RTL->decoder][3/8] RTL 复位完成，开始向两个 hart 注入 4 个连续指令块")
      for (n <- 0 until 4) {
        for (i <- 0 until 2) {
          val block = d.io.fromCore(i).blocks(0)
          block.valid.poke(true.B)
          val address = (if (i == 0) 0x1000 else 0x8000) + n * 4
          block.bits.iaddr.poke(address.U)
          println(f"[RTL->decoder][3/8]   cycle=$n hart=$i 输入指令地址=0x$address%08x")
        }
        d.clock.step()
      }
      d.io.fromCore.foreach(_.blocks.foreach(_.valid.poke(false.B)))
      println("[RTL->decoder][4/8] 输入结束，等待 encoder/FIFO/仲裁器/SRAM 完成 packet 排空")
      d.clock.step(50)
      d.io.sinkEnable.poke(false.B)
      d.clock.step(5)
      d.io.empty.expect(true.B)
      val words = d.io.wp.peek().litValue.toInt
      words should be > 0
      println(s"[RTL->decoder][5/8] SRAM 已排空：写入 $words 个 32-bit word，开始按读指针读回")
      val captured = (0 until words).flatMap { index =>
        d.io.readPointer.valid.poke(true.B)
        d.io.readPointer.bits.poke(index.U)
        d.clock.step()
        d.io.readPointer.valid.poke(false.B)
        val word = d.io.readData.peek().litValue
        println(s"[RTL->decoder][5/8]   SRAM[$index] = 0x${word.toString(16)}")
        (0 until 4).map(i => ((word >> (8 * i)) & 255).toByte)
      }.toArray
      println(s"[RTL->decoder][6/8] 读回 ${captured.length} 个字节，检查 packet 边界和带内 srcID")
      var cursor = 0
      var packetCount = 0
      var nullCount = 0
      val syncSources = scala.collection.mutable.Set[Int]()
      while (cursor < captured.length) {
        val length = captured(cursor) & 31
        if (length == 0) {
          nullCount += 1
          cursor += 1
        }
        else {
          assert(cursor + 2 + length <= captured.length)
          val source = captured(cursor + 1) & 255
          Set(0, 1) should contain(source)
          if ((captured(cursor + 2) & 15) == 3) syncSources += source
          packetCount += 1
          println(s"[RTL->decoder][6/8]   packet#$packetCount offset=$cursor length=$length srcID=$source")
          cursor += 2 + length
        }
      }
      syncSources.toSet shouldBe Set(0, 1)
      println(s"[RTL->decoder][7/8] packet 检查通过：$packetCount 个普通 packet、$nullCount 个 null/padding 字节，syncSources=${syncSources.toSeq.sorted.mkString(",")}")
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val artifact = workspace.resolve(Paths.get("build", "validation", "two-hart-from-core.bin"))
      Files.createDirectories(artifact.getParent)
      Files.write(artifact, captured)
      println(s"[RTL->decoder][8/8] 已生成 decoder 输入文件：$artifact；下一步由 Python decoder 解析并校验 0x1000/0x8000")
    }
  }
}


class MultiHartExecutionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private case class Block(pc: Int, kind: Int = 0, halfwords: Int = 2, lastSize: Int = 1,
      notification: Boolean = false, cause: Int = 0, tval: Int = 0, context: Int = 0, ctype: Int = 0)
  behavior of "complete multi-hart SRAM execution capture"
  for (useSMEM <- Seq(false, true)) {
  it should s"retain independent execution and traps through ${if (useSMEM) "SMEM AXI" else "SRAM"} stalls" in {
    test(new MultiHartTraceHarness(128, useSMEM)).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val captureStem = if (useSMEM) "two-hart-smem" else "two-hart-execution"
      val sinkName = if (useSMEM) "SMEM AXI" else "SRAM"
      println(s"[RTL-full][1/6] sink=$sinkName： 双 hart 实际 raw/PacketGen/Fifo -> PacketArbiter -> SRAM，覆盖完整退休片段")
      d.io.sinkEnable.poke(true.B); d.io.control.poke((6 | (15 << 20)).U)
      d.io.readPointer.valid.poke(false.B); d.io.readPointer.bits.poke(0.U)
      for (i <- 0 until 2) {
        d.io.features(i).poke(((BigInt(8) << 28) | (i << 16)).U)
        val core = d.io.fromCore(i)
        core.priv.poke(Priv.M); core.time.poke(0.U); core.context.poke(0.U); core.ctype.poke(Ctype.None)
        core.cause.poke(0.U); core.tval.poke(0.U); core.impdef.poke(0.U); core.hartIsReset.poke(false.B)
        core.trigger.traceOn.poke(false.B); core.trigger.traceOff.poke(false.B)
        core.blocks.foreach { b =>
          b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
          b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
          b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
        }
      }
      val memory = scala.collection.mutable.Map.empty[BigInt, Int]
      val memoryBase = BigInt("180000000", 16)
      var address = Option.empty[BigInt]
      var writeData = Option.empty[(BigInt, Int)]
      var responseDelay = -1
      var memoryCycle = 0
      d.io.smem.foreach { axi =>
        axi.aw.ready.poke(false.B); axi.w.ready.poke(false.B)
        axi.b.valid.poke(false.B); axi.b.bits.resp.poke(0.U); axi.b.bits.id.poke(0.U)
        axi.ar.ready.poke(true.B); axi.r.valid.poke(false.B)
        axi.r.bits.id.poke(0.U); axi.r.bits.data.poke(0.U); axi.r.bits.resp.poke(0.U); axi.r.bits.last.poke(true.B)
      }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def tick(n: Int): Unit = for (_ <- 0 until n) {
        d.io.ingressOverflow.expect(false.B)
        d.io.smem.foreach { axi =>
          val awReady = address.isEmpty && memoryCycle % 5 != 0
          val wReady = writeData.isEmpty && memoryCycle % 3 == 0
          val bValid = responseDelay == 0
          axi.aw.ready.poke(awReady.B); axi.w.ready.poke(wReady.B); axi.b.valid.poke(bValid.B)
          if (bValid && axi.b.ready.peek().litToBoolean) {
            responseDelay = -1; address = None; writeData = None
          } else if (responseDelay > 0) responseDelay -= 1
          if (awReady && axi.aw.valid.peek().litToBoolean) {
            axi.aw.bits.size.expect(2.U); axi.aw.bits.len.expect(0.U); axi.aw.bits.id.expect(0.U)
            address = Some(axi.aw.bits.addr.peek().litValue)
          }
          if (wReady && axi.w.valid.peek().litToBoolean) {
            axi.w.bits.last.expect(true.B)
            writeData = Some((axi.w.bits.data.peek().litValue, axi.w.bits.strb.peek().litValue.toInt))
          }
          if (address.nonEmpty && writeData.nonEmpty && responseDelay < 0) {
            val (data, mask) = writeData.get
            for (lane <- 0 until 4 if (mask & (1 << lane)) != 0)
              memory(address.get + lane) = ((data >> (lane * 8)) & 255).toInt
            responseDelay = 2 + memoryCycle % 4
          }
          axi.ar.valid.expect(false.B)
        }
        d.clock.step(); memoryCycle += 1
      }
      val inputs = Seq(
        Seq(Block(0x1000), Block(0x1004, 5), Block(0x1000), Block(0x1004, 4),
          Block(0x1008, 8, 4), Block(0x2000, halfwords = 3, lastSize = 0, notification = true),
          Block(0x2006, 13), Block(0xff0, notification = true), Block(0xff4)),
        Seq(Block(0x8000), Block(0x8004, 1, 0, cause = 2, tval = 0x8004),
          Block(0x9000, 2, 4, cause = 7), Block(0xa000, context = 1, ctype = 2),
          Block(0xa004, notification = true, context = 1), Block(0xa008, context = 1),
          Block(0xb000, context = 2, ctype = 3), Block(0xb004, context = 2)))
      for (index <- 0 until inputs.map(_.size).max) {
        if (index == 4) { d.io.sinkEnable.poke(false.B); println(s"[RTL-full][2/6] 暂停 $sinkName 接收，观察队列反压和整包仲裁") }
        for (hart <- 0 until 2) {
          val core = d.io.fromCore(hart)
          core.blocks(0).valid.poke((index < inputs(hart).size).B)
          if (index < inputs(hart).size) {
            val block = inputs(hart)(index); val b = core.blocks(0).bits
            b.iaddr.poke(block.pc.U); b.itype.poke(block.kind.U); b.iretire.poke(block.halfwords.U)
            b.ilastsize.poke(block.lastSize.U); b.trigNotify.poke(block.notification.B)
            core.cause.poke(block.cause.U); core.tval.poke(block.tval.U)
            core.context.poke(block.context.U); core.ctype.poke(block.ctype.U)
            println(f"[RTL-full][2/6] hart=$hart group=$index pc=0x${block.pc}%x itype=${block.kind} iretire=${block.halfwords}")
          }
        }
        tick(1); d.io.fromCore.foreach(_.blocks(0).valid.poke(false.B)); tick(9)
        if (index == 4) { d.io.sinkEnable.poke(true.B); println(s"[RTL-full][2/6] 恢复 $sinkName，继续发送两路独立执行流") }
      }
      tick(30) // raw entries are drained; each encoder retains its final logical instruction
      d.io.fromCore.foreach(_.hartIsReset.poke(true.B)); tick(1)
      d.io.fromCore.foreach(_.hartIsReset.poke(false.B))
      var drainCycles = 0
      while (!d.io.encodersEmpty.peek().litToBoolean && drainCycles < 2000) { tick(1); drainCycles += 1 }
      d.io.encodersEmpty.expect(true.B)
      d.io.sinkEnable.poke(false.B)
      var tailCycles = 0
      while (!d.io.empty.peek().litToBoolean && tailCycles < 200) { tick(1); tailCycles += 1 }
      d.io.empty.expect(true.B)
      val words = d.io.wp.peek().litValue.toInt
      words should be > 0
      words should be < 128
      println(s"[RTL-full][3/6] 两路停止并排空，$sinkName 捕获 $words word，开始读回")
      val bytes = if (useSMEM) (0 until words * 4).map(i => memory(memoryBase + i)) else (0 until words).flatMap { index =>
        d.io.readPointer.valid.poke(true.B); d.io.readPointer.bits.poke(index.U); tick(1)
        d.io.readPointer.valid.poke(false.B)
        val word = d.io.readData.peek().litValue
        (0 until 4).map(i => ((word >> (8 * i)) & 255).toInt)
      }
      val frames = Array.fill(2)(scala.collection.mutable.ArrayBuffer[Seq[Int]]())
      var cursor = 0
      var padding = 0
      while (cursor < bytes.size) {
        val length = bytes(cursor) & 31
        if (length == 0) { padding += 1; cursor += 1 }
        else {
          assert(cursor + 2 + length <= bytes.size, "SRAM ended inside a packet")
          val source = bytes(cursor + 1)
          Set(0, 1) should contain(source)
          frames(source) += bytes.slice(cursor, cursor + length + 2)
          cursor += length + 2
        }
      }
      import trace.component.ETraceReference
      frames(0).toSeq shouldBe Seq(ETraceReference.support(0), ETraceReference.synchronization(0, 0x1000),
        ETraceReference.address(0, 0x2000, 0x1000, Seq(true, false)),
        ETraceReference.address(0, 0x2004, 0x2000, notifyRequested = true),
        ETraceReference.address(0, 0xff0, 0x2004), ETraceReference.address(0, 0xff4, 0xff0),
        ETraceReference.support(0, status = 1))
      frames(1).toSeq shouldBe Seq(ETraceReference.support(1), ETraceReference.synchronization(1, 0x8000),
        ETraceReference.trap(1, 0x9000, 2, 0x8004), ETraceReference.address(1, 0x9004, 0x9000),
        ETraceReference.trap(1, 0xa000, 7, interrupt = true, context = 1),
        ETraceReference.address(1, 0xa004, 0xa000, notifyRequested = true),
        ETraceReference.address(1, 0xa008, 0xa004), ETraceReference.support(1, status = 1),
        ETraceReference.synchronization(1, 0xb000, context = 2),
        ETraceReference.address(1, 0xb004, 0xb000), ETraceReference.support(1, status = 1))
      println(s"[RTL-full][4/6] $sinkName 内 ${frames.map(_.size).sum} 个完整 packet 按 srcID 对独立 golden，padding=$padding")
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val out = workspace.resolve(Paths.get("build", "validation")); Files.createDirectories(out)
      Files.write(out.resolve(s"$captureStem.bin"), bytes.map(_.toByte).toArray)
      val code0 = Array.fill[Byte](0x2020 - 0xff0)(0)
      val code1 = Array.fill[Byte](0x3020)(0)
      def put(code: Array[Byte], base: Int, pc: Int, instruction: Long, size: Int = 4): Unit =
        for (i <- 0 until size) code(pc - base + i) = ((instruction >> (8 * i)) & 255).toByte
      for (pc <- Seq(0xff0, 0xff4, 0x1008)) put(code0, 0xff0, pc, 0x13)
      put(code0, 0xff0, 0x1000, 0xfff08093L); put(code0, 0xff0, 0x1004, 0xfe009ee3L)
      put(code0, 0xff0, 0x100c, 0x280e7); put(code0, 0xff0, 0x2000, 0x10b7)
      put(code0, 0xff0, 0x2004, 0x10c1, 2); put(code0, 0xff0, 0x2006, 0x8067)
      for (pc <- Seq(0x8000, 0x9000, 0x9004, 0xa000, 0xa004, 0xa008, 0xb000, 0xb004)) put(code1, 0x8000, pc, 0x13)
      Files.write(out.resolve("two-hart-execution-hart0.bin"), code0)
      Files.write(out.resolve("two-hart-execution-hart1.bin"), code1)
      val config = """{"source_bits":8,"sources":{"0":{"hart_id":0,"raw":"two-hart-execution-hart0.bin","base":"0xff0","xlen":64},"1":{"hart_id":1,"raw":"two-hart-execution-hart1.bin","base":"0x8000","xlen":64}}}"""
      Files.write(out.resolve(s"$captureStem.json"), config.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      println(s"[RTL-full][5/6] 写出 $sinkName capture、两份实际指令镜像和 srcID 配置")
      println("[RTL-full][6/6] 下一阶段 decoder 校验 hart0 全部 11 条、hart1 全部 8 条退休 PC 及 EPC/context")
    }
  }
}

}
