package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import scala.collection.immutable.SeqMap
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.control.FunnelControl
import trace.interface._
import trace.interface.FromCore
import trace.interface.Payload
import trace.interface.ToCore

class Funnel(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new FunnelImp(this)(baseAddr)
}

class FunnelImp(wrapper: Funnel)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with FunnelControl {

  val io = IO(new Bundle {
    val atbsVec = Vec(hart_num, new ATBSlave(encoderAtbDataWidth))
    val atbm    = new ATBMaster(funnelAtbDataWidth) // todo fix width
  })

  /**
    * parameter
    */
  val fifoParam = FifoParam(
    isLeaf = false,
    fifoDepth = maxPackets * 6,
    enqDataBeats = encoderAtbDataWidth / 8,
    deqDataBeats = funnelAtbDataWidth / 8
  )

  /**
    * module: [[fifo]]
    */
  // todo round-robin arbiter
  // todo: first to arbitrate, then assert atbs.at.ready
  val resArb = VecInit(Seq.fill(hart_num)(true.B))

  // todo: fifo
  private val (atbsVec, atbm) = (io.atbsVec, io.atbm)
  val fifo                    = Module(new Fifo(fifoParam))

  // src af
  atbsVec.foreach(atbs => atbs.af.valid := atbm.af.valid) // flush req
  val flushCompleteNext = VecInit(atbsVec.map(atbs => Mux(atbm.af.valid, atbs.af.fire, false.B)))
  val flushCompleteReg  = RegNext(flushCompleteNext)

  // src at
  atbsVec.map(atbs => atbs.at.ready := fifo.io.packetEnq.ready)
  val atbsFire1H    = atbsVec.map(atbs => atbs.at.fire)
  val selectedSlave = Mux1H(atbsFire1H, atbsVec)

  fifo.io.packetEnq.valid     := selectedSlave.at.fire
  fifo.io.packetEnq.bits.data := selectedSlave.at.bits.data.asTypeOf(fifo.io.packetEnq.bits.data)
  fifo.io.packetEnq.bits.len  := selectedSlave.at.bits.bytes +& 1.U // true length
  fifo.io.fromCtrl.srcid      := selectedSlave.at.bits.id
  fifo.io.sourceFlushAll.foreach(_ := flushCompleteReg.asUInt.orR) // all slave complete flush

  io.atbm <> fifo.io.atbm

  connectCtrlReg()
}
