package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.component.CompBase

import scala.collection.immutable.SeqMap

trait ControlBase { self: CompBase =>
  
  val controlMods: Seq[CSRModule[_]]

  val controlRWMap: SeqMap[Int, (CSRAddrWriteBundle[_], UInt)]

  val controlOutMap: SeqMap[Int, UInt]

}
