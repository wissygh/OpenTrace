package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters
import utils._

class Mictor38Bundle extends Bundle {
  val NC1     = Output(Bool()) // pin  01
  val NC2     = Output(Bool()) // pin  02
  val NC3     = Output(Bool()) // pin  03
  val NC4     = Output(Bool()) // pin  04
  val GND5    = Output(Bool()) // pin  05
  val TRC_CLK = Output(Bool()) // pin  06  trace
  val TRIGIN  = Input(Bool())  // pin  07  debug
  val TRIGOUT = Output(Bool()) // pin  08  debug
  val nRESET  = Input(Bool())  // pin  09  debug
  val EXTTRIG = Output(Bool()) // pin  10  trace

  val TDO        = Output(Bool()) // pin  11 debug
  val VREF_TRACE = Input(Bool())  // pin  12 trace
  val GND13      = Output(Bool()) // pin  13
  val VREF_DEBUG = Input(Bool())  // pin  14 debug
  val TCK        = Input(Bool())  // pin  15 debug
  val TRC_DATA7  = Output(Bool()) // pin  16 trace
  val TMS        = Input(Bool())  // pin  17 debug
  val TRC_DATA6  = Output(Bool()) // pin  18 trace
  val TDI        = Input(Bool())  // pin  19 debug
  val TRC_DATA5  = Output(Bool()) // pin  20 trace

  val nTRST      = Input(Bool())  // pin  21 debug
  val TRC_DATA4  = Output(Bool()) // pin  22 trace
  val TRC_DATA15 = Output(Bool()) // pin  23 trace
  val TRC_DATA3  = Output(Bool()) // pin  24 trace
  val TRC_DATA14 = Output(Bool()) // pin  25 trace
  val TRC_DATA2  = Output(Bool()) // pin  26 trace
  val TRC_DATA13 = Output(Bool()) // pin  27 trace
  val TRC_DATA1  = Output(Bool()) // pin  28 trace
  val TRC_DATA12 = Output(Bool()) // pin  29 trace
  val GND30      = Output(Bool()) // pin  30 trace

  val TRC_DATA11 = Output(Bool()) // pin  31 trace
  val GND32      = Output(Bool()) // pin  32 trace
  val TRC_DATA10 = Output(Bool()) // pin  33 trace
  val LOGIC1     = Output(Bool()) // pin  34 trace
  val TRC_DATA9  = Output(Bool()) // pin  35 trace
  val TRC_CTL    = Output(Bool()) // pin  36 trace
  val TRC_DATA8  = Output(Bool()) // pin  37 trace
  val TRC_DATA0  = Output(Bool()) // pin  38 trace
}
