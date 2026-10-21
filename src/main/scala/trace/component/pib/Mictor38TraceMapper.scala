package trace.component

import chisel3._
import trace.interface.PIBTracePins

/** Digital trace subset of the target-side Mictor-38 connector. Power/VREF and
  * JTAG/open-drain resets are supplied by the board/debug integration.
  */
class Mictor38TraceMapper extends Module {
  val io = IO(new Bundle {
    val trace           = Flipped(new PIBTracePins)
    val externalTrigger = Input(Bool())
    val pins   = Output(Vec(38, Bool())) // index 0 is physical pin 1; unused pins remain unassigned by this subset
    val driven = Output(UInt(38.W))      // only these positions belong to this mapper
  })
  io.pins.foreach(_ := false.B)
  val dataPins = Seq(38, 28, 26, 24, 22, 20, 18, 16, 37, 35, 33, 31, 29, 27, 25, 23)
  for ((pin, bit) <- dataPins.zipWithIndex) { io.pins(pin - 1) := io.trace.data(bit) }
  io.pins(6 - 1)  := io.trace.clock
  io.pins(10 - 1) := io.externalTrigger
  io.pins(34 - 1) := true.B
  io.pins(36 - 1) := false.B // TRC_CTL: no extra valid framing
  io.driven       := (dataPins ++ Seq(6, 10, 30, 32, 34, 36)).foldLeft(BigInt(0))((mask, pin) => mask.setBit(pin - 1)).U
}
