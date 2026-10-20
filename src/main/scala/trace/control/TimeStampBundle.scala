package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import trace._

class TsControlBundle extends CSRBundle {
  val Active = RO(0).withReset(0.U) // aliases the encoder's component reset
  val Count  = TsCountEnField(1, wNoFilter).withReset(TsCountEnField.Stop) // InternalSystem/InternalCore
  val Reset = RW(2).withReset(0.U) // InternalSystem/InternalCore, W1 to clear timestamp counter.
  val RunInDebug = TsCountEnField(3, wNoFilter).withReset(TsCountEnField.Stop) // InternalCore clock.
  val Mode       = TsModeROField(6, 4).withReset(TsModeROField.Default)
  val Prescale =
    TsPrescaleField(9, 8, wNoFilter).withReset(TsPrescaleField.Default)
  val Enable = RW(15).withReset(0.U)
  val Width  = TsWidthROField(29, 24).withReset(TsWidthROField.Default) // 6 bits; counter: 0 bits -> 63 bits
}

object TsCountEnField extends CSREnum with WARLApply {
  val Stop = Value(0.U)
  val Runs = Value(1.U)
}

object TsModeROField extends CSREnum with ROApply with TraceParameters {
  val None           = Value(0.U)
  val External       = Value(1.U)
  val InternalSystem = Value(2.U)
  val InternalCore   = Value(3.U)
  val Shared         = Value(4.U)

  def Default = clock_domain.U
  override def isLegal(enumeration: CSREnumType): Bool =
    enumeration.isOneOf(None, External, InternalSystem, InternalCore, Shared)
}

object TsWidthROField extends CSREnum with ROApply with TraceParameters {
  val Default = Value(ts_width_p.U)
}

object TsPrescaleField extends CSREnum with WARLApply with TraceParameters {
  val Default = Value(ts_prescale_p.U)
}

class EncoderTimestampControlRegister extends CSRModule("TsControl", new TsControlBundle) {
  regOut.Active := !reset.asBool
  regOut.Reset := false.B // W1 command, never a stored reset request on readback
}
