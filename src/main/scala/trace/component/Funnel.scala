package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters
import trace.control._
import trace.interface._

class Funnel(baseAddr: Int, val numSources: Int = 1)(implicit p: Parameters) extends ComponentBase(baseAddr) {
  require(numSources > 0 && numSources <= 16)
  override lazy val module = new FunnelImp(this)(baseAddr)
}

class FunnelImp(wrapper: Funnel)(baseAddr: Int)(implicit p: Parameters)
    extends ComponentBaseImp(wrapper)(baseAddr) with FunnelControl {
  require(encoderAtbDataWidth == funnelAtbDataWidth, "A width adapter is required for unequal stream widths")
  val io = IO(new Bundle {
    val in = Vec(wrapper.numSources, Flipped(Decoupled(new TraceBeat(encoderAtbDataBeats))))
    val sourceControl = Vec(wrapper.numSources, Flipped(new TraceControl))
    val out = Decoupled(new TraceBeat(funnelAtbDataBeats))
    val control = new TraceControl
  })
  val core = Module(new TraceFunnelCore(wrapper.numSources, encoderAtbDataBeats))
  core.io.in <> io.in
  core.io.sourceControl <> io.sourceControl
  core.io.control <> io.control
  core.io.enable := funnelControl.regOut.Enable.asBool
  core.io.disable := VecInit((0 until wrapper.numSources).map(i => funnelDisInput.regOut.DisInput.asUInt(i)))
  io.out <> core.io.out
  val fifoIsEmpty = core.io.empty
  val fifoIsFull = false.B
  controlMods.foreach {
    case m: HasCtrlBundle =>
      m.fifoIsEmpty := fifoIsEmpty
      m.fifoIsFull := fifoIsFull
    case _ =>
  }
  connectCtrlReg()
}
