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
import trace.component.SRAMImp

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

trait SRAMControl extends ControlBase { self: SRAMImp =>

  val ramControl = Module(new CSRModule("RAMControl", new RAMControlBundle) with HasRamCtrlBundle with HasCtrlBundle {
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

  val ramStartLow = Module(new CSRModule("RAMStartLow", new RAMAddrLowBundle) {
    regOut := 0.U
  })
    .setAddr(CtrlReg.RAMStartLow)

  val ramStartHigh = Module(new CSRModule("RAMStartHigh", new OneFieldBundle) {
    regOut := 0.U
  })
    .setAddr(CtrlReg.RAMStartHigh)

  val ramLimitLow = Module(new CSRModule("RAMLimitLow", new RAMAddrLowBundle) {
    regOut := ramLimitAddr.U(64.W).tail(32)
  })
    .setAddr(CtrlReg.RAMLimitLow)

  val ramLimitHigh = Module(new CSRModule("RAMLimitHigh", new OneFieldBundle) {
    regOut := ramLimitAddr.U(64.W).head(32)
  })
    .setAddr(CtrlReg.RAMLimitHigh)

  val ramWPLow = Module(new CSRModule("RAMWPLow", new RAMPointerBundle)
    with HasWPBundle {
    when(wen) {
      reg.Wrap := wdata.Wrap.asBool
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

  val ramRPLow = Module(new CSRModule("RAMRPLow", new RAMAddrLowBundle) with HasRPBundle {
    regOut.Low := rp.tail(32)
  })
    .setAddr(CtrlReg.RAMRPLow)

  val ramRPHigh = Module(new CSRModule("RAMRPHigh", new FieldInitBundle) with HasRPBundle {
    regOut.ALL := rp.head(32)
  })
    .setAddr(CtrlReg.RAMRPHigh)

  val ramData = Module(new CSRModule("RAMRPHigh", new OneROFieldBundle)
    with HasRAMRdataBundle {
    regOut := readData
  }) // Optional write is not supported
    .setAddr(CtrlReg.RAMData)

  override val controlMods: Seq[CSRModule[_]] = Seq(
    ramControl,
    ramImpl,
    ramStartLow,
    ramStartHigh,
    ramLimitLow,
    ramLimitHigh,
    ramWPLow,
    ramWPHigh,
    ramRPLow,
    ramRPHigh,
    ramData
  )
}
