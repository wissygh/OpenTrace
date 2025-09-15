package trace.component

import chisel3._
import circt.stage._
import chiseltest._
import chiseltest.ChiselScalatestTester
import chiseltest.VerilatorBackendAnnotation
import chiseltest.simulator.VerilatorFlags
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import chisel3.stage.ChiselGeneratorAnnotation
import org.chipsalliance.cde.config.Parameters


import freechips.rocketchip.diplomacy.LazyModule

object RAMMain extends App {
  val path = """./build/rtl"""
  implicit val p: Parameters = Parameters.empty
  val ram = LazyModule(new SMEM(0)(p))
  val params = List(1)
  for (i <- params) {
    (new ChiselStage).execute(
      Array("--target-dir", path),
      Seq(
        ChiselGeneratorAnnotation(() => ram.module),
        FirtoolOption("--disable-all-randomization"),
        FirtoolOption("--disable-annotation-unknown"),
        FirtoolOption("--lowering-options=explicitBitcast,disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)
      )
    )
  }
}
