package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import trace.control.SMEMRegisters
import trace.interface.TraceBeat

/** Explicit, write-only AXI system-memory sink in the trace clock domain. */
class SMEM(baseAddr: Int, val window: SMEMWindow)(implicit p: Parameters) extends ComponentBase(baseAddr) {
  override lazy val module = new SMEMImp(this)(baseAddr)
}

class SMEMImp(wrapper: SMEM)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr) {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new TraceBeat(4)))
    val axi = new AXI4Bundle(AXI4BundleParameters(64, 32, 1))
    // Must reset with the memory interconnect; software Active must not cancel AW/W/B.
    val transactionReset = Input(AsyncReset())
  })
  val registers = Module(new SMEMRegisters(baseAddr, wrapper.window))
  val core      = withReset(io.transactionReset)(Module(new SMEMSinkCore(wrapper.window)))
  val writer    = withReset(io.transactionReset)(Module(new SMEMAXIWriter))
  registers.io.bus.b2c := bus.b2c
  bus.c2b              := registers.io.bus.c2b
  core.io.in <> io.in
  core.io.enable     := registers.io.enable
  core.io.stopOnWrap := registers.io.stopOnWrap
  core.io.abort      := reset.asBool
  core.io.configuration <> registers.io.configuration
  core.io.request <> writer.io.request
  core.io.response <> writer.io.response
  registers.io.committed    := core.io.committed
  registers.io.empty        := core.io.empty
  registers.io.stop         := core.io.stop
  registers.io.wrap         := core.io.wrap
  registers.io.fault        := core.io.fault
  registers.io.faultAddress := core.io.faultAddress
  io.axi <> writer.io.axi
}
