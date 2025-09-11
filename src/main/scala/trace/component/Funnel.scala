package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.TLArbiter
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
    * aribiter
    */
  def arbiter[T <: Data](sinkReady: Bool, sources: ATBSlave*): UInt =
    if (sources.isEmpty) {
      0.U
    } else if (sources.size == 1) {
      1.U
    } else {
      // need to track the number of beats for each source
      val beatsIn = sources.map { s =>
        val header      = s.at.bits.data(7, 0).asTypeOf(new PacketHeader)
        val payloadLen  = header.length
        val timeStampEn = header.extend
        val deqPacketLen =
          Mux(payloadLen === 0.U, 1.U, 2.U +& payloadLen +& (timeStampEn << log2Ceil(time_width_p / 8)).asUInt)
        (deqPacketLen >> s.at.bits.bytes.getWidth) +& deqPacketLen(s.at.bits.bytes.getWidth - 1, 0).orR
      } // true length

      // The number of beats which remain to be sent
      val beatsLeft = RegInit(0.U)
      val idle      = beatsLeft === 0.U
      val latch     = idle && sinkReady // winner (if any) claims sink

      // Who wants access to the sink?
      val valids = sources.map(_.at.valid)
      val readys =
        VecInit(TLArbiter.roundRobin(valids.size, Cat(valids.reverse), latch).asBools.map(arb => arb && sinkReady))
      val winner = VecInit((readys zip valids) map { case (r, v) => r && v })

      val maskedBeats = (winner zip beatsIn) map { case (w, b) => Mux(w, b, 0.U) }
      val initBeats   = maskedBeats.reduce(_ | _) // no winner => 0 beats
      beatsLeft := Mux(latch, initBeats, beatsLeft - sinkReady)

      readys.asUInt
    }

  /**
    * module: [[fifo]]
    */
  private val (atbsVec, atbm) = (io.atbsVec, io.atbm)
  val fifo                    = Module(new Fifo(fifoParam))

  // src af
  atbsVec.foreach(atbs => atbs.af.valid := atbm.af.valid) // flush req
  val flushCompleteNext = VecInit(atbsVec.map(atbs => Mux(atbm.af.valid, atbs.af.fire, false.B)))
  val flushCompleteReg  = RegNext(flushCompleteNext)

  // src at
  val arbiterRes = arbiter(fifo.io.packetEnq.ready, atbsVec: _*).asBools
  atbsVec.zip(arbiterRes).map { case (atbs, aRes) => atbs.at.ready := aRes }
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
