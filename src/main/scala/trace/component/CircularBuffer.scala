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
    val data = Input(UInt(32.W))
  })

  val read = Option.when(!ramIsSMEM)(IO(new Bundle {
    val en   = Input(Bool())
    val ptr  = Input(UInt(64.W))
    val data = Output(UInt(32.W))
  }))

  val bufferVec = Reg(Vec(buffer_num, UInt(32.W)))

  bufferVec.zipWithIndex.map {
    case (bf, idx) => {
      bf := Mux(write.en && (write.ptr === (ramBaseAddr + idx).U), write.data, bf)
    }
  }

  read.foreach(rd =>
    bufferVec.zipWithIndex.map {
      case (bf, idx) => {
        rd.data := Mux(rd.en && (rd.ptr === (ramBaseAddr + idx).U), bf, 0.U)
      }
    }
  )

  wrapper.bufferNode.foreach(_.regmap(
    wrapper.ramBaseAddr -> RegFieldGroup(
      "trace",
      Some("trace circular buffer"),
      bufferVec.map(reg => RegField.r(32, reg))
    )
  ))
}
