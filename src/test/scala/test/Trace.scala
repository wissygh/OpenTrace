package trace

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

object TraceMain extends App {
  implicit val p: Parameters = Parameters.empty
  val ctrlBus = Seq(BusType.TL, BusType.AXI, BusType.APB)
  ctrlBus.map{ bus => 
    val path = """./build/rtl/""" + s"${bus.busName}"
    val trace = LazyModule(new Trace(bus)(p))
    (new ChiselStage).execute(
      Array("--target-dir", path),
      Seq(
        ChiselGeneratorAnnotation(() => trace.module),
        FirtoolOption("--disable-all-randomization"),
        FirtoolOption("--disable-annotation-unknown"),
        FirtoolOption("--lowering-options=explicitBitcast,disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)
      )
    )
  }
}
