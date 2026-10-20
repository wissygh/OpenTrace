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
    val in = Flipped(Decoupled(new TraceBeat(funnelAtbDataBeats)))
    val atbm = new ATBMaster(funnelAtbDataBeats)
  })
  val adapter = Module(new TraceATBAdapter(funnelAtbDataBeats))
  adapter.io.in <> io.in
  adapter.io.enable := ATBControl.regOut.Enable.asBool
  adapter.io.id := ATBControl.regOut.ID.asUInt
  io.atbm <> adapter.io.atbm
  ATBControl.fifoIsEmpty := adapter.io.empty
  ATBControl.fifoIsFull := false.B
  connectCtrlReg()
}
