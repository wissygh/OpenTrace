package bundles

import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.noPrefix
import chisel3.util._

object CSRBundles {
  class OneFieldBundle extends CSRBundle {
    val ALL = RW(31, 0)
  }

  class FieldInitBundle extends OneFieldBundle {
    this.ALL.setRW().withReset(0.U)
  }
}
