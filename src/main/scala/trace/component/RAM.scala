package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import trace.control._


class RAM(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  val buffer = LazyModule(new CircularBuffer)
  val ramIdentityNode = Option.when(ramIsSMEM)(TLIdentityNode())
  buffer.bufferNode.foreach(_ := ramIdentityNode.get)

  override lazy val module = new RAMImp(this)(baseAddr)
}

class RAMImp(wrapper: RAM)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
  with RAMControl
{

  val bufferMod = wrapper.buffer.module

  /**
   * todo: some tmp signal which need to fix
   */
  val traceWrite = WireInit(true.B) // todo： fix
  val traceRead  = bus.b2c.readEn && (bus.b2c.address === ramData.addr.U)
  val bufferWriteData = Wire(UInt(32.W))
  bufferWriteData := ramControl.rdata // use ramControl to gen correct verilog, todo: fix to packets
  val bufferReadData = Wire(UInt(32.W))
  bufferReadData := 0.U

  val wpReg = RegInit(0.U(62.W))
  val rpReg = RegInit(0.U(62.W))
  val ramWP = ramWPHigh.rdata ## ramWPLow.rdata
  val ramRP = ramWPHigh.rdata ## ramWPLow.rdata
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
        m.readData := bufferReadData
      case _ =>
    }
  }


  // for sram and smem, packets -> ram sink
  bufferMod.write.en := traceWrite
  bufferMod.write.ptr := ramWP
  bufferMod.write.data := bufferWriteData

  // only for sram
  bufferMod.read.foreach( rd => {
    rd.en := traceRead
    rd.ptr := ramRP
    bufferReadData := rd.data
  })

  connectCtrlReg()
}
