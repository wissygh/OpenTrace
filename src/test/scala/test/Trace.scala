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
      Array(
        "--target-dir", path, 
        "--split-verilog"
      ),
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


/** Elaborate the supported two-hart sink variants without changing defaults. */
object TraceVariantsMain extends App {
  implicit val p: Parameters = Parameters.empty
  val configs = Seq((BusType.TL, false), (BusType.AXI, false), (BusType.APB, false), (BusType.TL, true))
  configs.foreach { case (bus, atbSink) =>
    val name = s"${bus.busName}-2hart-${if (atbSink) "atb" else "sram"}"
    val top = LazyModule(new Trace(bus, numHarts = 2, useAtbSink = atbSink))
    (new ChiselStage).execute(
      Array("--target-dir", s"./build/rtl/$name", "--split-verilog"),
      Seq(ChiselGeneratorAnnotation(() => top.module),
        FirtoolOption("--disable-all-randomization"), FirtoolOption("--disable-annotation-unknown"),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)))
  }
}
