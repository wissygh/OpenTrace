package trace.control

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

/** Word-only APB control access, issuing each access-phase request once. */
class TraceAPBToTL(implicit p: Parameters) extends LazyModule {
  val node = APBToTLNode()
  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out).foreach { case ((in, _), (out, edge)) =>
      val bytes = edge.manager.beatBytes
      require(bytes == 4 && in.params.dataBits == 32 && edge.manager.minLatency >= 1)
      val active   = in.psel && in.penable
      val pending  = RegInit(false.B)
      val rejected = RegInit(false.B)
      val size     = log2Ceil(bytes).U
      val beat     = Some(TransferSizes(bytes, bytes))
      val legal = in.paddr(1, 0) === 0.U && (!in.pwrite || in.pstrb.andR) && Mux(
        in.pwrite,
        edge.manager.supportsPutPartialSafe(in.paddr, size, beat),
        edge.manager.supportsGetSafe(in.paddr, size, beat)
      )
      out.a.valid        := active && !pending && !rejected && legal
      out.a.bits         := 0.U.asTypeOf(out.a.bits)
      out.a.bits.opcode  := Mux(in.pwrite, TLMessages.PutPartialData, TLMessages.Get)
      out.a.bits.size    := size
      out.a.bits.address := in.paddr
      out.a.bits.data    := in.pwdata
      out.a.bits.mask    := Mux(in.pwrite, in.pstrb, 15.U)
      out.a.bits.user :<= in.pauser
      out.a.bits.user.lift(AMBAProt).foreach { prot =>
        prot.privileged := in.pprot(0)
        prot.secure     := !in.pprot(1)
        prot.fetch      := in.pprot(2)
        prot.bufferable := true.B
        prot.modifiable := true.B
        prot.readalloc  := true.B
        prot.writealloc := true.B
      }
      out.d.ready := active
      in.pready   := active && (out.d.valid || rejected)
      in.pslverr  := in.pready && (rejected || out.d.bits.denied || out.d.bits.corrupt)
      in.prdata   := Mux(out.d.valid && !rejected, out.d.bits.data, 0.U)
      in.pduser :<= out.d.bits.user
      when(out.a.fire)(pending                                 := true.B)
      when(out.d.fire)(pending                                 := false.B)
      when(active && !pending && !rejected && !legal)(rejected := true.B)
      when(active && rejected)(rejected                        := false.B)
      out.b.ready                                              := true.B
      out.c.valid                                              := false.B
      out.c.bits                                               := 0.U.asTypeOf(out.c.bits)
      out.e.valid                                              := false.B
      out.e.bits                                               := 0.U.asTypeOf(out.e.bits)
    }
  }
}
