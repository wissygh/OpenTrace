package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters

class ATGlobal extends Bundle {
  val atclk    = Clock()
  val atclken  = Bool()
  val atresetn = Bool()
}

class ATData(dataBeats: Int) extends Bundle with TraceParameters {
  require(dataBeats > 0 && isPow2(dataBeats))
  val bytes = UInt(log2Up(dataBeats).W) // datawidth/8 - 1
  val data  = UInt((dataBeats * 8).W)   // bytes align
  val id    = UInt(atbIdWidth.W)        // [0x01, 0x6F], other values are reserved.
}

class ATBMaster(dataBeats: Int) extends Bundle {
  // ATCLK and ATRESETn are supplied by the enclosing clock/reset ports.
  val atclken = Input(Bool())
  val at = DecoupledIO(new ATData(dataBeats))
  val af = Flipped(DecoupledIO(new Bundle {}))
  val syncReq = Input(Bool())
  val wakeup = Output(Bool())
}

class ATBSlave(dataBeats: Int) extends Bundle {
  val atclken = Input(Bool())
  val at = Flipped(DecoupledIO(new ATData(dataBeats)))
  val af = DecoupledIO(new Bundle {})
  val syncReq = Output(Bool())
  val wakeup = Input(Bool())
}
