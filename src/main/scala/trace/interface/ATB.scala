package trace.interface

import chisel3._
import chisel3.util._

case class ATBBundleParameters(
    bytesWidth: Int,
    dataWidth:  Int,
    idWidth:    Int
) {}

class ATGlobal extends Bundle {
  val atclk    = Bool()
  val atclken  = Bool()
  val atresetn = Bool()
}

class ATData extends Bundle {
  val bytes = UInt(2.W)  // datawidth/8 - 1
  val data  = UInt(32.W) // bytes align
  val id    = UInt(7.W)  // [0x01, 0x6F], other values are reserved.
}

class ATBMaster extends Bundle {
  // val global  = Input(new ATGlobal)
  val at = DecoupledIO(new ATData)
  val af = Flipped(DecoupledIO(new Bundle {}))
  // val syncReq = Input(Bool())
}

class ATBSlave extends Bundle {
  // val global  = Input(new ATGlobal)
  val at = Flipped(DecoupledIO(new ATData))
  val af = DecoupledIO(new Bundle {})
  // val syncReq = Output(Bool())
}
