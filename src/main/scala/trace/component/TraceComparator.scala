package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.control._
import trace.interface._

/** Unsigned comparisons within the selected ingress signal's width. The range
  * latch advances once per accepted raw block, never once per stalled cycle.
  */
class TraceComparator extends Module with TraceParameters {
  val io = IO(new Bundle {
    val block        = Input(new BlockBufferEntry)
    val control      = Input(new TeCompControlBundle)
    val primary      = Input(UInt(64.W))
    val secondary    = Input(UInt(64.W))
    val configure    = Input(Bool())
    val advance      = Input(Bool())
    val matched      = Output(Bool())
    val notification = Output(Bool())
  })
  val control = io.control
  def input(select: UInt): UInt = MuxLookup(select, 0.U(64.W))(Seq(
    CompInputField.Iaddr.asUInt   -> io.block.iaddr,
    CompInputField.Context.asUInt -> io.block.context,
    CompInputField.Tval.asUInt    -> io.block.tval
  ))
  def widthMask(select: UInt): UInt = MuxLookup(select, 0.U(64.W))(Seq(
    CompInputField.Iaddr.asUInt   -> ((BigInt(1) << IaddrWidth) - 1).U(64.W),
    CompInputField.Context.asUInt -> ((BigInt(1) << contextWidth) - 1).U(64.W),
    CompInputField.Tval.asUInt    -> ((BigInt(1) << TvalWidth) - 1).U(64.W)
  ))
  def compare(data: UInt, value: UInt, function: UInt, primary: Boolean): Bool =
    MuxLookup(function, false.B)(Seq(
      0.U -> (data === value),
      1.U -> (data =/= value),
      2.U -> (data < value),
      3.U -> (data <= value),
      4.U -> (data > value),
      5.U -> (data >= value),
      6.U -> (!primary).B,
      7.U -> true.B
    ))
  val primaryMask = widthMask(control.PInput.asUInt) &
    Mux(control.SFunc === CompSFuncField.AlwaysTrueMask, io.secondary, Fill(64, 1.U))
  val secondaryMask = widthMask(control.SInput.asUInt)
  val p = compare(input(control.PInput.asUInt) & primaryMask, io.primary & primaryMask, control.PFunc.asUInt, true)
  val s =
    compare(input(control.SInput.asUInt) & secondaryMask, io.secondary & secondaryMask, control.SFunc.asUInt, false)
  val latched = RegInit(false.B)
  when(io.configure) {
    latched := control.MatchMode === MatchModeField.PUntilS && control.PFunc === CompPFuncField.AlwaysFalseLatch
  }.elsewhen(io.advance) {
    when(s)(latched := false.B).elsewhen(p)(latched := true.B)
  }
  io.matched := MuxLookup(control.MatchMode.asUInt, false.B)(Seq(
    MatchModeField.P.asUInt       -> p,
    MatchModeField.PS.asUInt      -> (p && s),
    MatchModeField.NonPS.asUInt   -> !(p && s),
    MatchModeField.PUntilS.asUInt -> (latched || p)
  ))
  io.notification := (control.PNotify.asBool && control.PInput === CompInputField.Iaddr && p) ||
    (control.SNotify.asBool && control.SInput === CompInputField.Iaddr && s)
}
