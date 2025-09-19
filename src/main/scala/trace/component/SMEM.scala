package trace.component

import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._
import freechips.rocketchip.amba.AMBAProtField
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import trace.control._
import trace.interface._
import utils.VerilogAXI4Record
import utils.VerilogAXI4Record._

class SMEM(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  /**
    * trace write to system memory
    */
  val tlMaster2SM = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1("traceWrite")),
    requestFields = Seq(AMBAProtField())
  )))

  private val axi4master = AXI4SlaveNode(Seq(
    AXI4SlavePortParameters(
      slaves = Seq(
        AXI4SlaveParameters(
          address = Seq(AddressSet(0, (BigInt(1) << 12) - 1)), // todo: fix
          regionType = RegionType.UNCACHED,
          supportsRead = TransferSizes(1, funnelAtbDataBeats),
          supportsWrite = TransferSizes(1, funnelAtbDataBeats),
          interleavedId = Some(0)
        )
      ),
      beatBytes = funnelAtbDataBeats
    )
  ))

  axi4master :=
    AXI4UserYanker() :=
    AXI4Deinterleaver(funnelAtbDataBeats) :=
    TLToAXI4() :=
    tlMaster2SM

  val axi4masternode = InModuleBody {
    val axi4masternode = chisel3.IO(new VerilogAXI4Record(axi4master.in.head._1.params))
    axi4masternode.viewAs[AXI4Bundle] <> axi4master.in.head._1
    axi4masternode
  }

  override lazy val module = new SMEMImp(this)(baseAddr)
}

class SMEMImp(wrapper: SMEM)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
    with SMEMControl {

  val io = IO(new Bundle {
    val atbs = new ATBSlave(funnelAtbDataBeats)
  })

  /**
    * parameter
    */
  private val wordWidth  = 32
  private val writeWidth = funnelAtbDataWidth
  private val writeBeats = writeWidth / 8
  private val writeStep  = funnelAtbDataWidth / wordWidth

  /**
   * atb 2 system memory
   */
  val traceWrite      = io.atbs.at.fire
  val bufferWriteData = io.atbs.at.bits.data

  val wpReg    = RegInit(0.U(62.W))
  val ramWP    = ramWPHigh.rdata ## ramWPLow.rdata
  val ramStart = ramStartHigh.rdata ## ramStartLow.regOut.Low.asUInt
  val ramLimit = ramLimitHigh.rdata ## ramLimitLow.regOut.Low.asUInt

  // todo: check
  val fifoIsFull  = wpReg === ramLimit
  val fifoIsEmpty = wpReg === ramStart

  when(ramWPLow.w.wen) {
    wpReg := wpReg.head(32) ## ramWPLow.w.wdata.head(30)
  }.elsewhen(ramWPHigh.w.wen) {
    wpReg := ramWPHigh.w.wdata ## wpReg.tail(32)
  }.elsewhen(wpReg === ramLimit) {
    wpReg := ramStart
  }.elsewhen(traceWrite) {
    wpReg := wpReg + writeStep.U
  }

  /**
    * wtite request
    */
  val (tl, edge) = wrapper.tlMaster2SM.out(0)
  when(traceWrite && (ramControl.regOut.Enable.asBool && ramControl.regOut.Active.asBool)) {
    tl.a.valid := true.B
    tl.a.bits  := edge.Put(0.U, wpReg, io.atbs.at.bits.bytes, io.atbs.at.bits.data)._2
  }.otherwise {
    tl.a.valid := false.B
    tl.a.bits  := 0.U.asTypeOf(tl.a.bits)
  }

  io.atbs.af.valid := !RegNext(RegNext(io.atbs.af.valid) && io.atbs.af.fire)
  io.atbs.at.ready := !(ramControl.regOut.StopOnWrap.asBool && fifoIsFull) && ramControl.regOut.Enable.asBool

  /**
   * control reg io
   */
  controlMods.foreach { mod =>
    mod match {
      case m: HasWPBundle =>
        m.wp   := wpReg
        m.wrap := fifoIsFull
      case _ =>
    }
    mod match {
      case m: HasRamCtrlBundle =>
        m.ramWrapStop := ramControl.regOut.StopOnWrap.asBool && fifoIsFull
      case _ =>
    }
    mod match {
      case m: HasCtrlBundle =>
        m.fifoIsEmpty := fifoIsEmpty
        m.fifoIsFull  := fifoIsFull
      case _ =>
    }
  }

  connectCtrlReg()
}
