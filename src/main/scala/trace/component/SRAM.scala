package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import trace.control._
import trace.interface._

class SRAM(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {
  override lazy val module = new SRAMImp(this)(baseAddr)
}

class SRAMImp(wrapper: SRAM)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with SRAMControl {

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

  val wpReg    = RegInit(0.U(62.W))
  val rpReg    = RegInit(0.U(62.W))
  val ramWP    = ramWPHigh.rdata ## ramWPLow.rdata
  val ramRP    = ramWPHigh.rdata ## ramWPLow.rdata
  val ramStart = ramStartHigh.rdata ## ramStartLow.regOut.Low.asUInt
  val ramLimit = ramLimitHigh.rdata ## ramLimitLow.regOut.Low.asUInt

  when(ramWPLow.w.wen) {
    wpReg := wpReg.head(32) ## ramWPLow.w.wdata.head(30)
  }.elsewhen(ramWPHigh.w.wen) {
    wpReg := ramWPHigh.w.wdata ## wpReg.tail(32)
  }.elsewhen(wpReg === ramLimit) {
    wpReg := ramStart
  }.elsewhen(traceWrite) {
    wpReg := wpReg + writeStep.U
  }

  when(ramRPLow.w.wen) {
    rpReg := rpReg.head(32) ## ramRPLow.w.wdata.head(30)
  }.elsewhen(ramRPHigh.w.wen) {
    rpReg := ramRPHigh.w.wdata ## rpReg.tail(32)
  }.elsewhen(rpReg === ramLimit) {
    rpReg := ramStart
  }.elsewhen(traceRead) {
    rpReg := rpReg + 1.U
  }

  val fifoIsEmpty = RegNext(traceRead && (rpReg + 1.U === wpReg))
  val fifoIsFull  = RegNext(traceWrite && (wpReg + writeStep.U >= rpReg)) // todo: check

  buffer.write.en   := traceWrite
  buffer.write.ptr  := ramWP
  buffer.write.data := bufferWriteData

  buffer.read.ptr := ramRP
  bufferReadData  := buffer.read.data

  io.atbs.af.valid := !RegNext(RegNext(io.atbs.af.valid) && io.atbs.af.fire)
  io.atbs.at.ready := !(ramControl.regOut.StopOnWrap.asBool && fifoIsFull) && ramControl.regOut.Enable.asBool

  /**
   * control reg io
   */
  controlMods.foreach { mod =>
    mod match {
      case m: HasWPBundle =>
        m.wp   := wpReg
        m.wrap := fifoIsFull // wrap around
      case _ =>
    }
    mod match {
      case m: HasRPBundle =>
        m.rp := rpReg
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
