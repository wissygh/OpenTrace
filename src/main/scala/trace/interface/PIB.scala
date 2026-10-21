package trace.interface

import chisel3._

/** Target-to-probe logical trace signals. Pad cells and debug/JTAG are board IO. */
class PIBTracePins extends Bundle {
  val clock   = Output(Bool())
  val data    = Output(UInt(16.W))
  val control = Output(Bool()) // connectors specification: TRC_CTL is zero
}
