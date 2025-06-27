package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.Trace


class OneROFieldBundle extends OneFieldBundle {
  this.ALL.setRO().withReset(0.U)
}

object TrcompTypeFiled extends CSREnum with WARLApply {
  val Encoder   = Value(0x1.U)
  val Funnel    = Value(0x8.U)
  val Ramsink   = Value(0x9.U)
  val Pibsink   = Value(0xA.U)
  val Atbbridge = Value(0xE.U)
}
