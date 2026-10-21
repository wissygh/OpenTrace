package trace.control

import bundles.CSRModule
import chisel3._
import chisel3.util._
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.interface.Hpm

/** Optional XiangShan-style core event selection and returned increment samples.
  * These are vendor CSRs, not additional E-Trace packet fields.
  */
class CoreHPMRegisters(baseAddr: Int, counterWidth: Int = 64) extends Module with TraceParameters {
  require(counterWidth >= 8 && counterWidth <= 64)
  val io = IO(new Bundle {
    val bus      = new Control2BusRegBundle
    val selected = Output(Bool())
    val port     = new Hpm
    val time     = Input(UInt(64.W))
  })
  val local = io.bus.b2c.address - (baseAddr & ctrlMask).U
  val reads = (0 until hpmevent_num).flatMap { index =>
    val base    = 0x900 + 0x20 * index
    val address = Module(new CSRModule(s"HPM${index}Address", new HpmAddrBundle))
    val low     = Module(new CSRModule(s"HPM${index}EventLow", new HpmEventLowBundle))
    val high    = Module(new CSRModule(s"HPM${index}EventHigh", new HpmEventHighBundle))
    for ((csr, offset) <- Seq((address, 0), (low, 4), (high, 8))) {
      csr.w.wen   := io.bus.b2c.writeEn && local === (base + offset).U
      csr.w.wdata := io.bus.b2c.dataWrite
    }
    val enabled    = address.regOut.Addr.asUInt =/= 0.U
    val configured = address.w.wen || low.w.wen || high.w.wen
    val sample     = RegInit(0.U(hpminc_width.W))
    val sampleTime = RegInit(0.U(64.W))
    val total      = RegInit(0.U(counterWidth.W))
    val increment  = io.port.eventVec(index).inc
    when(configured) {
      sample     := 0.U
      sampleTime := 0.U
      total      := 0.U
    }.elsewhen(enabled) {
      sample     := increment
      sampleTime := io.time
      val sum = total +& increment
      total := Mux(sum(counterWidth), Fill(counterWidth, 1.U(1.W)), sum(counterWidth - 1, 0))
    }
    io.port.eventVec(index).hpmaddr  := address.regOut.Addr.asUInt
    io.port.eventVec(index).hpmevent := Mux(enabled, Cat(high.rdata(22, 0), low.rdata), 0.U)
    val extended = Wire(UInt(64.W)); extended := total
    Seq(
      base.U          -> address.rdata,
      (base + 4).U    -> low.rdata,
      (base + 8).U    -> high.rdata,
      (base + 0xc).U  -> sample,
      (base + 0x10).U -> sampleTime(31, 0),
      (base + 0x14).U -> sampleTime(63, 32),
      (base + 0x18).U -> extended(31, 0),
      (base + 0x1c).U -> extended(63, 32)
    )
  }
  io.selected         := reads.map { case (address, _) => local === address }.reduce(_ || _)
  io.bus.c2b.dataRead := Mux(io.bus.b2c.readEn, MuxLookup(local, 0.U)(reads), 0.U)
  io.bus.c2b.error    := false.B
}
