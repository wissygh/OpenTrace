package trace.interface

import chisel3._
import chisel3.util._

class ATB extends Bundle {
  val at = DecoupledIO(new Bundle {
    val bytes = UInt(4.W)
    val data  = UInt(128.W)
    val id    = UInt(7.W)
  })
  val af = Flipped(DecoupledIO(new Bundle {}))
}
