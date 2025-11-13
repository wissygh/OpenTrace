package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import trace.control._
import trace.interface._
import utility.HasCircularQueuePtrHelper

class SRAM(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {
  override lazy val module = new SRAMImp(this)(baseAddr)
}

class SRAMImp(wrapper: SRAM)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with SRAMControl
    with HasCircularQueuePtrHelper {

  val io = IO(new Bundle {
    val atbs = new ATBSlave(funnelAtbDataBeats)
  })

  val buffer = Module(new CircularBuffer)

  /**
    * parameter
    */
  private val wordWidth  = 32
  private val writeWidth = funnelAtbDataWidth
  private val writeBeats = writeWidth / 8
  private val writeStep  = funnelAtbDataWidth / wordWidth

  /**
   * atb 2 ram
   */

  val traceWrite      = io.atbs.at.fire
  val bufferWriteData = io.atbs.at.bits.data

  val traceRead      = bus.b2c.readEn && (bus.b2c.address === ramData.addr.U)
  val bufferReadData = Wire(UInt(32.W))

  val wpReg  = RegInit(FifoPtr(buffer_num, false.B, 0.U))
  val rpReg  = RegInit(FifoPtr(buffer_num, false.B, 0.U))
  val wpNext = WireInit(wpReg)
  val rpNext = WireInit(rpReg)

  dontTouch(wpReg)
  dontTouch(rpReg)

  when(ramWPLow.w.wen) {
    wpNext.value := ramWPHigh.rdata ## ramWPLow.w.wdata.head(30)
  }.elsewhen(ramWPHigh.w.wen) {
    wpNext.value := ramWPHigh.w.wdata ## ramWPLow.rdata.head(30)
  }.elsewhen(traceWrite) {
    wpNext := wpReg + writeStep.U
  }

  when(ramRPLow.w.wen) {
    rpNext.value := ramRPHigh.rdata ## ramRPLow.w.wdata.head(30)
  }.elsewhen(ramRPHigh.w.wen) {
    rpNext.value := ramRPHigh.w.wdata ## ramRPLow.rdata.head(30)
  }.elsewhen(traceRead) {
    rpNext := rpReg + 1.U
  }

  val fifoIsEmpty = isEmpty(wpReg, rpReg)
  val fifoIsFull  = isFull(wpNext, rpNext) && traceWrite

  buffer.write.en   := traceWrite
  buffer.write.ptr  := wpReg.value
  buffer.write.data := bufferWriteData

  buffer.read.ptr := rpReg.value
  bufferReadData  := buffer.read.data

  io.atbs.at.ready := ramControl.regOut.Enable.asBool // todo: fix

  /**
   * control reg io
   */
  controlMods.foreach { mod =>
    mod match {
      case m: HasWPBundle =>
        m.wp   := wpReg.value
        m.wrap := fifoIsFull // wrap around
      case _ =>
    }
    mod match {
      case m: HasRPBundle =>
        m.rp := rpReg.value
      case _ =>
    }
    mod match {
      case m: HasRAMRdataBundle =>
        m.readData := bufferReadData
      case _ =>
    }
    mod match {
      case m: HasRamCtrlBundle =>
        m.ramWrapStop := ramControl.regOut.StopOnWrap.asBool && fifoIsFull
      case _ =>
    }
    mod match {
      case m: HasCtrlBundle =>
        m.fifoIsEmpty := fifoIsEmpty
        m.fifoIsFull  := fifoIsFull
      case _ =>
    }
  }

  connectCtrlReg()
}
