package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.control._
import trace.interface._

class ATBBridge(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {
  override lazy val module = new ATBBridgeImp(this)(baseAddr)
}

class ATBBridgeImp(wrapper: ATBBridge)(baseAddr: Int)(implicit p: Parameters)
    extends ComponentBaseImp(wrapper)(baseAddr) with ATBBridgeControl {
  val io = IO(new Bundle {
    val transportEmpty = Input(Bool())
    val in             = Flipped(Decoupled(new TraceBeat(funnelAtbDataBeats)))
    val atbm           = new ATBMaster(funnelAtbDataBeats)
    val upstream       = Flipped(new TraceControl)
  })
  val adapter = Module(new TraceATBAdapter(funnelAtbDataBeats))
  adapter.io.in <> io.in
  adapter.io.asyncFreq := ATBImpl.regOut.AsyncFreq.asUInt
  adapter.io.enable    := ATBControl.regOut.Enable.asBool
  adapter.io.id        := ATBControl.regOut.ID.asUInt
  io.atbm <> adapter.io.atbm
  io.upstream <> adapter.io.upstream
  ATBControl.fifoIsEmpty := adapter.io.empty && io.transportEmpty
  ATBControl.fifoIsFull  := false.B
  connectCtrlReg()
}
