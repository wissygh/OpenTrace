package trace.control

import chisel3._
import chisel3.util._
import bundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import bundles.CSRBundles._
import trace.component.ComponentBaseImp

import scala.collection.immutable.SeqMap

trait ControlBase { self: ComponentBaseImp =>
  
  val controlMods: Seq[CSRModule[_]] = null

  lazy val controlRWMap: SeqMap[Int, (CSRAddrWriteBundle[_], UInt)] = SeqMap.from(
    controlMods.map(reg => (reg.addr -> (reg.w -> reg.rdata))).iterator
  )

  lazy val controlOutMap: SeqMap[Int, UInt] = SeqMap.from(
    controlMods.map(reg => (reg.addr -> reg.regOut.asInstanceOf[CSRBundle].asUInt)).iterator
  )

}
