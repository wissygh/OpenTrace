package trace.control

import bundles._
import bundles.CSRBundles._
import bundles.CSRDefines._
import bundles.CSRDefines.{CSRROField => RO, CSRRWField => RW, CSRWARLField => WARL, _}
import bundles.CSRFunc._
import chisel3._
import chisel3.util._
import trace.Trace
import trace.TraceParameters
import trace.component.SMEMImp

/**
 * Single RAM Sink may support both SRAM and SMEM modes, but not both may be enabled
 * at the same time. It is also possible to have more than one RAM Sink in a system.
 *
 * Trace RAM Sink may be instantiated or configured to support storing trace into dedicated SRAM or system memory.
 * Dedicated SRAM memory must be read via dedicated trRamData register, while memory in SMEM
 * mode should be read as any other memory on system bus - for example using SBA (System Bus
 * Access) access mode as defined in the RISC-V Debug Specification.
 *
 */

trait SMEMControl extends ControlBase { self: SMEMImp =>

  val ramControl = Module(new CSRModule("RAMControl", new RAMControlBundle) with HasRamCtrlBundle with HasCtrlBundle {
    regOut.Active := !reset.asBool
    when(wen) {
      reg.Enable := wdata.Enable
    }.elsewhen(ramWrapStop) {
      reg.Enable := false.B
    }
    reg.Emptry := fifoIsEmpty
  })
    .setAddr(CtrlReg.RAMControl)

  val ramImpl = Module(new CSRModule("RAMImpl", new RAMImplBundle))
    .setAddr(CtrlReg.RAMImpl)

  val ramStartLow = Module(new CSRModule("RAMStartLow", new RAMAddrLowBundle))
    .setAddr(CtrlReg.RAMStartLow)

  val ramStartHigh = Module(new CSRModule("RAMStartHigh", new OneFieldBundle))
    .setAddr(CtrlReg.RAMStartHigh)

  val ramLimitLow = Module(new CSRModule("RAMLimitLow", new RAMAddrLowBundle))
    .setAddr(CtrlReg.RAMLimitLow)

  val ramLimitHigh = Module(new CSRModule("RAMLimitHigh", new OneFieldBundle))
    .setAddr(CtrlReg.RAMLimitHigh)

  val ramWPLow = Module(new CSRModule("RAMWPLow", new RAMPointerBundle)
    with HasWPBundle {
    when(wen) {
      reg.Wrap := false.B
    }.elsewhen(wrap) { // wraps
      reg.Wrap := true.B
    }
    regOut.Low := wp.tail(32)
  })
    .setAddr(CtrlReg.RAMWPLow)

  val ramWPHigh = Module(new CSRModule("RAMWPHigh", new FieldInitBundle) with HasWPBundle {
    regOut.ALL := wp.head(32)
  })
    .setAddr(CtrlReg.RAMWPHigh)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    ramControl,
    ramImpl,
    ramStartLow,
    ramStartHigh,
    ramLimitLow,
    ramLimitHigh,
    ramWPLow,
    ramWPHigh
  )
}
