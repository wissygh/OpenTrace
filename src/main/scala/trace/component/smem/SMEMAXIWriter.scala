package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._

/** One outstanding AXI4 word write, with independent AW/W completion. Reset is
  * the system/interconnect reset, never the software component Active reset.
  */
class SMEMAXIWriter extends Module {
  val io = IO(new Bundle {
    val request  = Flipped(Decoupled(new SMEMWordWrite))
    val response = Decoupled(Bool())
    val axi      = new AXI4Bundle(AXI4BundleParameters(64, 32, 1))
  })
  val idle :: sending :: replying :: done :: Nil = Enum(4)
  val state                                      = RegInit(idle)
  val request                                    = Reg(new SMEMWordWrite)
  val addressSent                                = RegInit(false.B)
  val dataSent                                   = RegInit(false.B)
  val error                                      = Reg(Bool())
  io.request.ready := state === idle
  when(io.request.fire) {
    assert(io.request.bits.address(1, 0) === 0.U && io.request.bits.mask.orR)
    request     := io.request.bits
    addressSent := false.B
    dataSent    := false.B
    state       := sending
  }
  io.axi.aw.valid                  := state === sending && !addressSent
  io.axi.aw.bits                   := 0.U.asTypeOf(io.axi.aw.bits)
  io.axi.aw.bits.addr              := request.address
  io.axi.aw.bits.size              := 2.U
  io.axi.aw.bits.burst             := 1.U // INCR, one beat, ID 0, normal non-cacheable data
  io.axi.w.valid                   := state === sending && !dataSent
  io.axi.w.bits                    := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.w.bits.data               := request.data
  io.axi.w.bits.strb               := request.mask
  io.axi.w.bits.last               := true.B
  when(io.axi.aw.fire)(addressSent := true.B)
  when(io.axi.w.fire)(dataSent     := true.B)
  when(state === sending && (addressSent || io.axi.aw.fire) && (dataSent || io.axi.w.fire)) {
    state := replying
  }
  io.axi.b.ready := state === replying
  when(io.axi.b.fire) {
    error := io.axi.b.bits.resp =/= 0.U || io.axi.b.bits.id =/= 0.U
    state := done
  }
  io.response.valid            := state === done
  io.response.bits             := error
  when(io.response.fire)(state := idle)
  io.axi.ar.valid              := false.B
  io.axi.ar.bits               := 0.U.asTypeOf(io.axi.ar.bits)
  io.axi.r.ready               := true.B // no read requests are generated
}
