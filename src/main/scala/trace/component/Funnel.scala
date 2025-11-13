package trace.component

import bundles.CSRAddrWriteBundle
import bundles.CSRModule
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.TLArbiter
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters
import scala.collection.immutable.SeqMap
import trace.Control2BusRegBundle
import trace.TraceParameters
import trace.control._
import trace.control.FunnelControl
import trace.interface._
import trace.interface.FromCore
import trace.interface.ToCore

class Funnel(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new FunnelImp(this)(baseAddr)
}

class FunnelImp(wrapper: Funnel)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with FunnelControl {

  val io = IO(new Bundle {
    // val atbsVec = Vec(hart_num, new ATBSlave(encoderAtbDataBeats))
    val asyncAtbsVec =
      Vec(hart_num, Flipped(new AsyncBundle(new ATData(encoderAtbDataBeats), compAsyncBridge)))
    val atbm = new ATBMaster(funnelAtbDataBeats) // todo fix width
  })

  /**
    * aribiter
    * todo: support FunnelDisInput, pipe a cycle
    */
  def arbiter[T <: Data](sinkReady: Bool, sources: DecoupledIO[ATData]*): UInt =
    if (sources.isEmpty) {
      0.U
    } else if (sources.size == 1) {
      sinkReady
    } else {
      // need to track the number of beats for each source
      val beatsIn = sources.map { s =>
        val header      = s.bits.data(7, 0).asTypeOf(new PacketHeader)
        val payloadLen  = header.length
        val timeStampEn = header.extend
        val deqPacketLen =
          Mux(payloadLen === 0.U, 1.U, 2.U +& payloadLen +& (timeStampEn << log2Ceil(time_width_p / 8)).asUInt)
        (deqPacketLen >> s.bits.bytes.getWidth) +& deqPacketLen(s.bits.bytes.getWidth - 1, 0).orR
      } // true length

      // The number of beats which remain to be sent
      val beatsLeft = RegInit(0.U)
      val idle      = beatsLeft === 0.U
      val latch     = idle && sinkReady // winner (if any) claims sink

      // Who wants access to the sink?
      val valids = sources.map(_.valid)
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
  private val atbm   = io.atbm
  private val atsVec = VecInit(io.asyncAtbsVec.map(asyncAtbs => FromAsyncBundle(asyncAtbs)))

  // src at
  val arbiterRes = arbiter(io.atbm.at.ready, atsVec: _*).asBools
  atsVec.zip(arbiterRes).zip(funnelDisInput.regOut.DisInput.asUInt.asBools).map { case ((atbs, aRes), dis) =>
    atbs.ready := aRes && !dis
  } // todo: add funnelControl.regOut.Enable.asBool
  val atbsFire1H    = atsVec.map(atbs => atbs.fire)
  val selectedSlave = Mux1H(atbsFire1H, atsVec)

  io.atbm.at.valid := selectedSlave.valid
  io.atbm.at.bits  := selectedSlave.bits

  /**
   * control reg io
   */
  val fifoIsEmpty = false.B // todo: fix
  val fifoIsFull  = false.B
  controlMods.foreach { mod =>
    mod match {
      case m: HasCtrlBundle =>
        m.fifoIsEmpty := fifoIsEmpty
        m.fifoIsFull  := fifoIsFull
      case _ =>
    }
  }

  connectCtrlReg()
}
