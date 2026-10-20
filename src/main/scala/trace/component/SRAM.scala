package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.control._
import trace.interface._

class SRAM(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {
  override lazy val module = new SRAMImp(this)(baseAddr)
}

class SRAMImp(wrapper: SRAM)(baseAddr: Int)(implicit p: Parameters)
    extends ComponentBaseImp(wrapper)(baseAddr) with SRAMControl {
  require(funnelAtbDataWidth == 32)
  val io   = IO(new Bundle { val in = Flipped(Decoupled(new TraceBeat(funnelAtbDataBeats))) })
  val core = Module(new SRAMSinkCore(buffer_num))
  core.io.in <> io.in
  core.io.enable     := ramControl.regOut.Enable.asBool
  core.io.stopOnWrap := ramControl.regOut.StopOnWrap.asBool
  // Public CSR addresses are byte addresses; core pointers are 32-bit words.
  core.io.writePointer.valid := ramWPLow.w.wen && ramWPLow.w.wdata(31, 2) < buffer_num.U
  core.io.writePointer.bits  := ramWPLow.w.wdata(31, 2)
  core.io.readPointer.valid  := ramRPLow.w.wen && ramRPLow.w.wdata(31, 2) < buffer_num.U
  core.io.readPointer.bits   := ramRPLow.w.wdata(31, 2)
  core.io.read               := bus.b2c.readEn && bus.b2c.address === ((baseAddr & ctrlMask) + CtrlReg.RAMData).U
  val fifoIsEmpty = core.io.empty
  val fifoIsFull  = core.io.stop
  ramControl.fifoIsEmpty := fifoIsEmpty
  ramControl.fifoIsFull  := fifoIsFull
  controlMods.foreach {
    case m: HasWPBundle =>
      m.wp   := core.io.wp
      m.wrap := core.io.wrap
    case m: HasRPBundle       => m.rp          := core.io.rp
    case m: HasRAMRdataBundle => m.readData    := core.io.readData
    case m: HasRamCtrlBundle  => m.ramWrapStop := core.io.stop
    case m: HasCtrlBundle =>
      m.fifoIsEmpty := fifoIsEmpty
      m.fifoIsFull  := fifoIsFull
    case _ =>
  }
  connectCtrlReg()
}
