package trace.control

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.ResetCatchAndSync
import trace.Control2BusRegBundle
import trace.TraceParameters

/** Always-on, word-access control endpoint. Component reset never resets the
  * response buffer or the software Active latch that releases that component.
  */
class TraceControlEndpoint(baseAddr: Int, busParameters: TLBundleParameters, deferReset: Boolean = false)
    extends Module with TraceParameters {
  require(busParameters.dataBits == ctrlDataSize)
  val io = IO(new Bundle {
    val a              = Flipped(Decoupled(new TLBundleA(busParameters)))
    val d              = Decoupled(new TLBundleD(busParameters))
    val bus            = Flipped(new Control2BusRegBundle)
    val active         = Output(Bool())
    val componentReset = Output(AsyncReset())
    val resetReady     = Option.when(deferReset)(Input(Bool()))
    val quiesce        = Option.when(deferReset)(Output(Bool()))
  })
  val active         = withReset(reset.asAsyncReset)(RegInit(false.B))
  val componentReset = ResetCatchAndSync(clock, reset.asBool || !active).asAsyncReset
  io.componentReset := componentReset
  io.active         := active && !componentReset.asBool

  val response = Reg(new TLBundleD(busParameters))
  val pending  = withReset(reset.asAsyncReset)(RegInit(false.B))
  io.a.ready := !pending || io.d.ready
  io.d.valid := pending
  io.d.bits  := response

  val read = io.a.bits.opcode === TLMessages.Get
  // CPU uncached stores may use PutPartialData even when every word lane is
  // present. Both opcodes have identical CSR semantics for that full mask.
  // Partial masks still return denied without issuing a CSR write.
  val write = io.a.bits.opcode === TLMessages.PutFullData || io.a.bits.opcode === TLMessages.PutPartialData
  val legal = (read || write) && io.a.bits.size === log2Ceil(ctrlBeatBytes).U &&
    io.a.bits.address(1, 0) === 0.U && io.a.bits.mask.andR &&
    io.a.bits.param === 0.U && !io.a.bits.corrupt
  val localAddress = io.a.bits.address(ctrlAddrWidthInner - 1, 0)
  val controlWord  = localAddress === (baseAddr & ctrlMask).U
  val activeWrite  = io.a.fire && legal && write && controlWord
  val resetPending = Option.when(deferReset)(withReset(reset.asAsyncReset)(RegInit(false.B)))
  if (deferReset) {
    // Finish an already offered packet before acknowledging power-down. The
    // request itself blocks new arbitration on this edge, even before pending.
    io.quiesce.get := resetPending.get || (activeWrite && !io.a.bits.data(0))
    when(resetPending.get && io.resetReady.get) {
      active           := false.B
      resetPending.get := false.B
    }
  }
  io.bus.b2c.address   := localAddress
  io.bus.b2c.dataWrite := io.a.bits.data
  io.bus.b2c.readEn    := io.a.fire && legal && read
  io.bus.b2c.writeEn   := io.a.fire && legal && write
  val readData = Mux(controlWord, (io.bus.c2b.dataRead & "hfffffffe".U) | io.active.asUInt, io.bus.c2b.dataRead)

  when(io.a.fire) {
    response        := 0.U.asTypeOf(new TLBundleD(busParameters))
    response.opcode := Mux(read, TLMessages.AccessAckData, TLMessages.AccessAck)
    response.source := io.a.bits.source
    response.size   := io.a.bits.size
    response.echo   := io.a.bits.echo
    response.data   := Mux(read && legal, readData, 0.U)
    response.denied := !legal || io.bus.c2b.error
    pending         := true.B
    when(activeWrite) {
      if (deferReset) {
        when(io.a.bits.data(0) || io.resetReady.get) {
          active           := io.a.bits.data(0)
          resetPending.get := false.B
        }.otherwise(resetPending.get := true.B)
      } else {
        active := io.a.bits.data(0)
      }
    }
  }.elsewhen(io.d.fire) {
    pending := false.B
  }
}
