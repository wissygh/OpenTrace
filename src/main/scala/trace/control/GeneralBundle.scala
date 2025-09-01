package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import trace.Trace

class OneROFieldBundle extends OneFieldBundle {
  this.ALL.setRO().withReset(0.U)
}

object MajorVersion extends CSREnum with ROApply {
  val Default = Value(1.U)
}
object MinorVersion extends CSREnum with ROApply {
  val Default = Value(0.U)
}

object MajorProtocol extends CSREnum with ROApply {
  val EDefault = Value(0.U) // Must be 0 to indicate this version (2.0.x) of the E-Trace protocol
  val NDefault = Value(1.U)
}
object MinorProtocol extends CSREnum with ROApply {
  val Default = Value(0.U)
}

object TrcompTypeField extends CSREnum with ROApply {
  val Encoder   = Value(0x1.U)
  val Funnel    = Value(0x8.U)
  val RAM       = Value(0x9.U)
  val PIB       = Value(0xa.U)
  val ATBBridge = Value(0xe.U)
}
