package trace.component

import chisel3._
import chisel3.util._
import trace.interface.Itype

object TraceFilterLogic {
  def privilege(choices: UInt, privilege: UInt): Bool = (choices & UIntToOH(privilege, 8)).orR

  def cause(choices: UInt, cause: UInt): Bool = {
    require(choices.getWidth == 64)
    cause < 64.U && choices(cause(5, 0))
  }

  def trap(choices: UInt, code: UInt, kind: UInt, matchCause: Bool, matchType: Bool, interrupt: Bool): Bool =
    Itype.isTrap(kind) && (!matchCause || cause(choices, code)) &&
      (!matchType || Mux(interrupt, Itype.isInt(kind), Itype.isExp(kind)))

  /** No filters selected means no filtering; otherwise selected, enabled
    * filters combine by OR, with each filter's own conditions combined by AND.
    */
  def selected(selection: UInt, enabled: UInt, matches: UInt): Bool =
    !selection.orR || (selection & enabled & matches).orR
}
