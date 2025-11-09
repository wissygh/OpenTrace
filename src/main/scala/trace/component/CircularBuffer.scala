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

class CircularBuffer extends Module
    with TraceParameters {
  val write = IO(new Bundle {
    val en   = Input(Bool())
    val ptr  = Input(UInt(buffer_ptr_width.W))
    val data = Input(UInt(funnelAtbDataWidth.W))
  })

  val read = IO(new Bundle {
    val ptr  = Input(UInt(buffer_ptr_width.W))
    val data = Output(UInt(32.W))
  })

  /**
    * parameter
    */
  private val wordWidth    = 32
  private val writeWidth   = funnelAtbDataWidth
  private val writeBeats   = writeWidth / 8
  private val writeStep    = funnelAtbDataWidth / wordWidth
  private val lg2WriteStep = log2Up(writeStep)

  val bufferVec = VecInit((0 until (buffer_num / writeStep)).map { idx =>
    RegEnable(write.data, write.en && (write.ptr === idx.U))
  })

  val bufferVecWire = bufferVec.asTypeOf(Vec(buffer_num, UInt(wordWidth.W)))

  read.data := Mux1H(bufferVecWire.zipWithIndex.map { case (bf, idx) =>
    (read.ptr === idx.U, bf)
  })
}
