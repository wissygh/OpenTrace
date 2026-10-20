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
    test(new MultiHartTraceHarness) { d =>
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
      for (n <- 0 until 4) {
        for (i <- 0 until 2) {
          val block = d.io.fromCore(i).blocks(0)
          block.valid.poke(true.B)
          block.bits.iaddr.poke(((if (i == 0) 0x1000 else 0x8000) + n * 4).U)
        }
        d.clock.step()
      }
      d.io.fromCore.foreach(_.blocks.foreach(_.valid.poke(false.B)))
      d.clock.step(50)
      d.io.sinkEnable.poke(false.B)
      d.clock.step(5)
      d.io.empty.expect(true.B)
      val words = d.io.wp.peek().litValue.toInt
      words should be > 0
      val captured = (0 until words).flatMap { index =>
        d.io.readPointer.valid.poke(true.B)
        d.io.readPointer.bits.poke(index.U)
        d.clock.step()
        d.io.readPointer.valid.poke(false.B)
        val word = d.io.readData.peek().litValue
        (0 until 4).map(i => ((word >> (8 * i)) & 255).toByte)
      }.toArray
      var cursor = 0
      val syncSources = scala.collection.mutable.Set[Int]()
      while (cursor < captured.length) {
        val length = captured(cursor) & 31
        if (length == 0) cursor += 1
        else {
          assert(cursor + 2 + length <= captured.length)
          val source = captured(cursor + 1) & 255
          Set(0, 1) should contain(source)
          if ((captured(cursor + 2) & 15) == 3) syncSources += source
          cursor += 2 + length
        }
      }
      syncSources.toSet shouldBe Set(0, 1)
      val workspace = sys.env.get("MILL_WORKSPACE_ROOT").map(Paths.get(_)).getOrElse(
        Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
          .find(p => Files.isRegularFile(p.resolve("build.sc"))).get)
      val artifact = workspace.resolve(Paths.get("build", "validation", "two-hart-from-core.bin"))
      Files.createDirectories(artifact.getParent)
      Files.write(artifact, captured)
    }
  }
}
