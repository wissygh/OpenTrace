package trace.control

import chisel3._
import chisel3.util._
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.component.SMEMConfiguration
import trace.component.SMEMWindow

/** SMEM-only register bank. Address edits are accepted only while disabled and
  * drained; the core validates their complete 64-bit combination. Software may
  * temporarily create an invalid combination while writing its separate halves.
  */
class SMEMRegisters(baseAddr: Int, window: SMEMWindow) extends Module with TraceParameters {
  val io = IO(new Bundle {
    val bus           = new Control2BusRegBundle
    val configuration = Decoupled(new SMEMConfiguration)
    val enable        = Output(Bool())
    val stopOnWrap    = Output(Bool())
    val committed     = Input(UInt(64.W))
    val empty         = Input(Bool())
    val stop          = Input(Bool())
    val wrap          = Input(Bool())
    val fault         = Input(UInt(2.W))
    val faultAddress  = Input(UInt(64.W))
  })
  val enable       = RegInit(false.B)
  val stopOnWrap   = RegInit(false.B)
  val start        = RegInit(window.base.U(64.W))
  val limit        = RegInit(window.limit.U(64.W))
  val pointer      = RegInit(window.base.U(64.W))
  val edited       = RegInit(false.B)
  val wrap         = RegInit(false.B)
  val request      = io.bus.b2c
  val local        = request.address - (baseAddr & ctrlMask).U
  val writing      = request.writeEn
  val controlWrite = writing && local === CtrlReg.RAMControl.U
  val pointerWrite = local === CtrlReg.RAMWPLow.U || local === CtrlReg.RAMWPHigh.U
  val configWord = Seq(
    CtrlReg.RAMStartLow,
    CtrlReg.RAMStartHigh,
    CtrlReg.RAMLimitLow,
    CtrlReg.RAMLimitHigh,
    CtrlReg.RAMWPLow,
    CtrlReg.RAMWPHigh
  ).map(i => local === i.U).reduce(_ || _)
  val currentPointer = Mux(edited, pointer, io.committed)
  def replaceLow(old:  UInt): UInt = Cat(old(63, 32), request.dataWrite(31, 2), 0.U(2.W))
  def replaceHigh(old: UInt): UInt = Cat(request.dataWrite, old(31, 0))
  val nextStart = MuxLookup(local, start)(Seq(
    CtrlReg.RAMStartLow.U  -> replaceLow(start),
    CtrlReg.RAMStartHigh.U -> replaceHigh(start)
  ))
  val nextLimit = MuxLookup(local, limit)(Seq(
    CtrlReg.RAMLimitLow.U  -> replaceLow(limit),
    CtrlReg.RAMLimitHigh.U -> replaceHigh(limit)
  ))
  val nextPointer = MuxLookup(local, currentPointer)(Seq(
    CtrlReg.RAMWPLow.U  -> replaceLow(currentPointer),
    CtrlReg.RAMWPHigh.U -> replaceHigh(currentPointer)
  ))
  io.configuration.valid        := writing && configWord && !enable && io.empty && !reset.asBool
  io.configuration.bits.start   := nextStart
  io.configuration.bits.limit   := nextLimit
  io.configuration.bits.pointer := nextPointer
  when(io.configuration.fire) {
    start   := nextStart
    limit   := nextLimit
    pointer := nextPointer
    edited  := true.B
    when(pointerWrite)(wrap := false.B)
  }
  when(controlWrite) {
    enable     := request.dataWrite(1)
    stopOnWrap := request.dataWrite(8)
    when(request.dataWrite(1))(edited := false.B)
  }
  when(io.stop)(enable := false.B)
  when(io.wrap)(wrap := true.B)
  io.enable     := enable && !reset.asBool
  io.stopOnWrap := stopOnWrap
  val control = Cat(0.U(23.W), stopOnWrap, 0.U(3.W), 1.U(1.W), io.empty, 0.U(1.W), enable, !reset.asBool)
  // Vendor status: bits[1:0]=fault; bit2=capture invalid due to a write error;
  // bits[5:4]=next committed byte offset; bit8=busy. Fault clears on valid config.
  val status = io.fault | ((io.fault === 2.U).asUInt << 2) |
    (io.committed(1, 0) << 4) | ((!io.empty).asUInt << 8)
  val read = MuxLookup(local, 0.U(32.W))(Seq(
    CtrlReg.RAMControl.U   -> control,
    CtrlReg.RAMImpl.U      -> "h2901".U, // HasSMEM=1, HasSRAM=0, RAM version 1.0
    CtrlReg.RAMStartLow.U  -> start(31, 0),
    CtrlReg.RAMStartHigh.U -> start(63, 32),
    CtrlReg.RAMLimitLow.U  -> limit(31, 0),
    CtrlReg.RAMLimitHigh.U -> limit(63, 32),
    CtrlReg.RAMWPLow.U     -> Cat(currentPointer(31, 2), 0.U(1.W), wrap),
    CtrlReg.RAMWPHigh.U    -> currentPointer(63, 32),
    "hf00".U               -> status,
    "hf04".U               -> io.faultAddress(31, 0),
    "hf08".U               -> io.faultAddress(63, 32)
  ))
  io.bus.c2b.dataRead := Mux(request.readEn, read, 0.U)
  io.bus.c2b.error    := writing && configWord && !io.configuration.fire
}
