package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import scala.collection.immutable.SeqMap
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.control._
import trace.interface.FromCore
import trace.interface.PIBTracePins
import trace.interface.ToCore
import trace.interface.TraceBeat

/**
 * Since the PIB supports many different modes, it is necessary to follow a particular programming
 * sequence:
 *
 * • Activate the PIB by setting trPibActive.
 *
 * • Set the trPibMode, trPibDivider, trPibClkCenter, and trPibCalibrate fields. This will set the
 * TRC_DATA outputs to the quiescent state (whether that is high or low depends on trPibMode) and
 * start TRC_CLK running.
 *
 * • Activate the receiving device, such as a trace probe. Allow time for PLL to sync up, if using a PLL
 * with a parallel PIB mode.
 *
 * • Set trPibEnable. This enables the PIB to generate output either immediately (calibration mode) or
 * when the Trace Encoder or Trace Funnel begins sending trace messages/packets.
 */

class PIB(baseAddr: Int, val hasSerializer: Boolean = false)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new PIBImp(this)(baseAddr)
}

class PIBImp(wrapper: PIB)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with PIBControl {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new TraceBeat(4)))
    val pins    = new PIBTracePins
    val syncReq = Output(Bool())
  })
  val serializer = Module(new PIBSerializer)
  serializer.io.in <> io.in
  serializer.io.enable    := PIBControl.regOut.Enable.asBool
  serializer.io.mode      := PIBControl.regOut.Mode.asUInt
  serializer.io.divider   := PIBControl.regOut.Divider.asUInt
  serializer.io.center    := PIBControl.regOut.ClkCenter.asBool
  serializer.io.calibrate := PIBControl.regOut.Calibrate.asBool
  io.pins <> serializer.io.pins
  io.syncReq := serializer.io.syncReq
  PIBControl.empty.foreach(_ := serializer.io.empty)
  connectCtrlReg()
}
