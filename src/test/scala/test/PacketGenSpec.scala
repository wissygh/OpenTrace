package trace.component

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.TraceParameters
import trace.interface._

private class PacketGenHarness extends Module with TraceParameters {
  val io = IO(new Bundle {
    val block            = Input(new BlockBufferEntry)
    val filterRes        = Input(Bool())
    val blockBufferIsFull = Input(Bool())
    val fifoIsFull       = Input(Bool())
    val teCtrl           = Input(UInt(32.W))
    val teInstFeat       = Input(UInt(32.W))
    val teDataCtrl       = Input(UInt(32.W))
    val blockReady       = Output(Bool())
    val packet           = Decoupled(new PacketEnq(maxPackets))
    val syncReq          = Input(Bool())
  })

  val gen  = Module(new PacketGen)
  val ctrl = Wire(new EncoderCtrlInterface)
  ctrl.filterRes         := io.filterRes
  ctrl.blockBufferIsFull := io.blockBufferIsFull
  ctrl.fifoIsFull        := io.fifoIsFull
  ctrl.teCtrl            := io.teCtrl
  ctrl.teInstFeat        := io.teInstFeat
  ctrl.teDataCtrl        := io.teDataCtrl
  gen.io.syncReq         := io.syncReq
  gen.io.block           := io.block
  gen.io.fromCtrl        := ctrl
  io.blockReady          := gen.io.blockReady
  io.packet              <> gen.io.packet
}

class PacketGenSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "PacketGen"

  it should "hold encoder state while the packet output is stalled" in {
    test(new PacketGenHarness) { dut =>
      dut.io.syncReq.poke(false.B)
      dut.io.filterRes.poke(true.B)
      dut.io.blockBufferIsFull.poke(false.B)
      dut.io.fifoIsFull.poke(false.B)
      dut.io.teCtrl.poke(6.U) // Enable=1, InstTracing=1
      dut.io.teInstFeat.poke(0.U)
      dut.io.teDataCtrl.poke(0.U)
      dut.io.packet.ready.poke(false.B)

      def clearBlock(): Unit = {
        dut.io.block.valid.poke(false.B)
        dut.io.block.priv.poke(Priv.M)
        dut.io.block.time.poke(0.U)
        dut.io.block.context.poke(0.U)
        dut.io.block.ctype.poke(Ctype.None)
        dut.io.block.cause.poke(0.U)
        dut.io.block.tval.poke(0.U)
        dut.io.block.impdef.poke(0.U)
        dut.io.block.hartIsReset.poke(false.B)
        dut.io.block.trigger.poke(TriggerAction.None)
        dut.io.block.iaddr.poke(0.U)
        dut.io.block.itype.poke(Itype.None)
        dut.io.block.iretire.poke(0.U)
        dut.io.block.ilastsize.poke(Ilastsize.HalfWord)
      }

      def driveBlock(address: BigInt): Unit = {
        dut.io.block.valid.poke(true.B)
        dut.io.block.priv.poke(Priv.M)
        dut.io.block.time.poke(0.U)
        dut.io.block.context.poke(0.U)
        dut.io.block.ctype.poke(Ctype.None)
        dut.io.block.cause.poke(0.U)
        dut.io.block.tval.poke(0.U)
        dut.io.block.impdef.poke(0.U)
        dut.io.block.hartIsReset.poke(false.B)
        dut.io.block.trigger.poke(TriggerAction.None)
        dut.io.block.iaddr.poke(address.U)
        dut.io.block.itype.poke(Itype.None)
        dut.io.block.iretire.poke(1.U)
        dut.io.block.ilastsize.poke(Ilastsize.Word)
      }

      clearBlock()
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      driveBlock(0x1000)
      dut.clock.step()

      driveBlock(0x1004)
      dut.io.packet.valid.expect(true.B)
      dut.io.blockReady.expect(false.B)
      val heldData = dut.io.packet.bits.data.map(_.peek().litValue)
      dut.io.syncReq.poke(true.B)
      dut.clock.step()
      dut.io.syncReq.poke(false.B)
      dut.clock.step()
      dut.io.packet.valid.expect(true.B)
      dut.io.blockReady.expect(false.B)
      dut.io.packet.bits.data.zip(heldData).foreach { case (byte, value) =>
        byte.expect(value.U)
      }

      dut.io.packet.ready.poke(true.B)
      dut.clock.step()
      dut.io.packet.valid.expect(false.B)
      dut.io.blockReady.expect(true.B)
    }
  }

  it should "remember a SYNCREQ during idle and emit a new Format 30 at the next trace opportunity" in {
    test(new PacketGenHarness) { d =>
      println("[ATB-test] Encoder SYNCREQ：空闲期间锁存请求，后续有效指令触发 Format 30")
      d.io.syncReq.poke(false.B)
      d.io.filterRes.poke(true.B)
      d.io.blockBufferIsFull.poke(false.B)
      d.io.fifoIsFull.poke(false.B)
      d.io.teCtrl.poke(6.U)
      d.io.teInstFeat.poke(0.U)
      d.io.teDataCtrl.poke(0.U)
      d.io.packet.ready.poke(true.B)
      d.io.block.valid.poke(false.B)
      d.io.block.priv.poke(Priv.M)
      d.io.block.time.poke(0.U)
      d.io.block.context.poke(0.U)
      d.io.block.ctype.poke(Ctype.None)
      d.io.block.cause.poke(0.U)
      d.io.block.tval.poke(0.U)
      d.io.block.impdef.poke(0.U)
      d.io.block.hartIsReset.poke(false.B)
      d.io.block.trigger.poke(TriggerAction.None)
      d.io.block.iaddr.poke(0.U)
      d.io.block.itype.poke(Itype.None)
      d.io.block.iretire.poke(2.U)
      d.io.block.ilastsize.poke(Ilastsize.Word)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.block.valid.poke(true.B)
      for (address <- Seq(0x1000, 0x1004, 0x1008)) {
        d.io.block.iaddr.poke(address.U); d.clock.step()
      }
      d.io.block.valid.poke(false.B)
      d.io.syncReq.poke(true.B); d.clock.step()
      d.io.syncReq.poke(false.B); d.clock.step(3)
      d.io.packet.valid.expect(false.B)
      d.io.block.valid.poke(true.B)
      d.io.block.iaddr.poke(0x100c.U)
      d.io.packet.valid.expect(true.B)
      (d.io.packet.bits.data(2).peek().litValue & 15) shouldBe BigInt(3)
      d.clock.step()
      d.io.block.iaddr.poke(0x1010.U)
      d.io.packet.valid.expect(false.B)
    }
  }
}
