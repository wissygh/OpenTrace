package trace

import chisel3._
import chisel3.util._
import chiseltest._
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.component._
import trace.interface._

/** Tests the actual BlockBuffer/PacketGen/Fifo path and the shared SRAM core,
  * without duplicating their datapath or relying on an async-CSR test backend.
  */
private class MultiHartTraceHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val fromCore = Input(Vec(2, new FromCore))
    val sinkEnable = Input(Bool())
    val control = Input(UInt(32.W))
    val features = Input(Vec(2, UInt(32.W)))
    val readPointer = Flipped(Valid(UInt(buffer_ptr_width.W)))
    val readData = Output(UInt(32.W))
    val wp = Output(UInt(buffer_ptr_width.W))
    val empty = Output(Bool())
  })
  val arbiter = Module(new PacketArbiter(2, encoderAtbDataBeats))
  arbiter.io.enable := true.B
  arbiter.io.disable.foreach(_ := false.B)
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
    control.blockBufferIsFull := blocks.io.overflow
    control.fifoIsFull := fifo.io.ctrl.fifoIsFull
    control.teCtrl := io.control
    control.teInstFeat := io.features(i)
    control.teDataCtrl := 0.U
    encoder.io.fromCtrl := control
    fifo.io.control.flushReq := false.B
    fifo.io.control.syncReq := false.B
    fifo.io.packetEnq <> encoder.io.packet
    arbiter.io.in(i) <> fifo.io.atbm
  }
  val sink = Module(new SRAMSinkCore(16))
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
