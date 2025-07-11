package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.Trace


class TsControlBundle extends CSRBundle {
  val Active              = RW(0).withReset(0.U)
  val Count               = TsCountEnField(1, wNoFilter).withReset(TsCountEnField.Stop) // InternalSystem/InternalCore
  val Reset               = RW(2).withReset(0.U) // InternalSystem/InternalCore, W1 to clear timestamp counter.
  val RunInDebug          = TsCountEnField(3, wNoFilter).withReset(TsCountEnField.Stop) // InternalCore clock.
  val Mode                = TsModeField(6, 4, wNoFilter).withReset(TsModeField.None)
  val Prescale            = RW(9, 8).withReset(0.U) // InternalSystem/InternalCore, clock prescale: 2^(2 * Prescale)
  val Enable              = RW(15).withReset(0.U)
  val Width               = TsWidthROField(29, 24).withReset(TsWidthROField.Default) // 6 bits; counter: 0 bits -> 63 bits
}

object TsCountEnField extends CSREnum with WARLApply {
  val Stop        = Value(0.U)
  val Runs        = Value(1.U)
}

object TsModeField extends CSREnum with WARLApply {
  val None            = Value(0.U)
  val External        = Value(1.U)
  val InternalSystem  = Value(2.U)
  val InternalCore    = Value(3.U)
  val Shared          = Value(4.U)
  override def isLegal(enumeration: CSREnumType): Bool = enumeration.isOneOf(None, External, InternalSystem, InternalCore, Shared)
}

object TsWidthROField extends CSREnum with ROApply {
  val Default     = Value(63.U)
}

