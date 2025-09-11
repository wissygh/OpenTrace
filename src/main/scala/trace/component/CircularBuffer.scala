package trace.component

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.diplomacy.LazyModuleImp
import freechips.rocketchip.diplomacy.SimpleDevice
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.regmapper.RegFieldGroup
import freechips.rocketchip.tilelink.TLRegisterNode
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters

class CircularBuffer(implicit p: Parameters) extends LazyModule with TraceParameters {

  val device = new SimpleDevice("ram", Seq("buffer")) {
    override val alwaysExtended = true
  }

  val bufferNode = Option.when(ramIsSMEM)(TLRegisterNode(
    address = Seq(ramAddressSet),
    device = device,
    beatBytes = ramBeatBytes
  ))

  lazy val module = new CircularBufferImp(this)
}

class CircularBufferImp(wrapper: CircularBuffer)(implicit p: Parameters) extends LazyModuleImp(wrapper)
    with TraceParameters {
  val write = IO(new Bundle {
    val en   = Input(Bool())
    val ptr  = Input(UInt(64.W))
    val data = Input(UInt(funnelAtbDataWidth.W))
  })

  val read = Option.when(!ramIsSMEM)(IO(new Bundle {
    val en   = Input(Bool())
    val ptr  = Input(UInt(64.W))
    val data = Output(UInt(32.W))
  }))

  /**
    * parameter
    */
  private val wordWidth    = 32
  private val writeWidth   = funnelAtbDataWidth
  private val writeBeats   = writeWidth / 8
  private val writeStep    = funnelAtbDataWidth / wordWidth
  private val lg2WriteStep = log2Up(writeStep)

  val bufferVec = VecInit((0 until (buffer_num / writeStep)).map { idx =>
    RegEnable(write.data, write.en && ((write.ptr >> 2) === ((ramBaseAddr >> 2) + idx).U))
  })

  val bufferVecWire = bufferVec.asTypeOf(Vec(buffer_num, UInt(wordWidth.W)))

  read.foreach(rd =>
    bufferVecWire.zipWithIndex.map {
      case (bf, idx) => {
        rd.data := Mux(rd.en && (rd.ptr === (ramBaseAddr + idx).U), bf, 0.U)
      }
    }
  )

  wrapper.bufferNode.foreach(_.regmap(
    wrapper.ramBaseAddr -> RegFieldGroup(
      "trace",
      Some("trace circular buffer"),
      bufferVecWire.map(rdata => RegField.r(32, rdata))
    )
  ))
}
