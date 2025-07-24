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

object MajorVersion extends CSREnum with ROApply{
  val Default =  Value(1.U)
}
object MinorVersion extends CSREnum with ROApply{
  val Default =  Value(0.U)
}

object MajorProtocol extends CSREnum with ROApply{
  val NDefault =  Value(1.U)
  val EDefault =  Value(2.U)
}
object MinorProtocol extends CSREnum with ROApply{
  val Default =  Value(0.U)
}

object TrcompTypeField extends CSREnum with ROApply {
  val Encoder   = Value(0x1.U)
  val Funnel    = Value(0x8.U)
  val Ramsink   = Value(0x9.U)
  val PIB       = Value(0xA.U)
  val ATBBridge = Value(0xE.U)
}
