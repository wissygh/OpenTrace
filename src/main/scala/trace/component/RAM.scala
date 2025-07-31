package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.control._


class RAM(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new RAMImp(this)(baseAddr)
}

class RAMImp(wrapper: RAM)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
  with RAMControl
{

  val traceWrite = WireInit(false.B) // todo： fix
  val traceRead  = bus.b2c.readEn && (bus.b2c.address === ramData.addr.U)

  val wpReg = RegInit(0.U(62.W))
  val rpReg = RegInit(0.U(62.W))
  val ramStart = ramStartHigh.rdata ## ramStartLow.regOut.Low.asUInt
  val ramLimit = ramLimitHigh.rdata ## ramLimitLow.regOut.Low.asUInt

  when(ramWPLow.w.wen){
    wpReg := wpReg.head(32) ## ramWPLow.w.wdata.head(30)
  }.elsewhen(ramWPHigh.w.wen){
    wpReg := ramWPHigh.w.wdata ## wpReg.tail(32)
  }.elsewhen(wpReg === ramLimit){
    wpReg := ramStart
  }.elsewhen(traceWrite){
    wpReg := wpReg + 1.U
  }

  when(ramRPLow.w.wen){
    rpReg := rpReg.head(32) ## ramRPLow.w.wdata.head(30)
  }.elsewhen(ramRPHigh.w.wen){
    rpReg := ramRPHigh.w.wdata ## rpReg.tail(32)
  }.elsewhen(rpReg === ramLimit){
    rpReg := ramStart
  }.elsewhen(traceRead){
    rpReg := rpReg + 1.U
  }

  /**
   * control reg io
   */
  controlMods.foreach { mod =>
    mod match {
      case m: HasWPBundle =>
        m.wp := wpReg
        m.wrap := (wpReg === ramLimit)
      case _ =>
    }
    mod match {
      case m: HasRPBundle =>
        m.rp := rpReg
      case _ =>
    }
    mod match {
      case m: HasRAMTypeBundle =>
        m.hasSRAM := ramImpl.regOut.HasSRAM.asBool
        m.hasSMEM := ramImpl.regOut.HasSMEM.asBool
        m.mode    := ramControl.regOut.Mode.asBool
      case _ =>
    }
    mod match {
      case m: HasRAMRdataBundle =>
        m.readData := 0.U // todo: fix
      case _ =>
    }
  }

  connectCtrlReg()
}
