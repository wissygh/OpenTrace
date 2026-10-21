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

/** Check the non-power-of-two topology and the advertised source-count limit. */
object TraceLimitsMain extends App {
  implicit val p: Parameters = Parameters.empty
  for (harts <- Seq(3, 16)) {
    val top = LazyModule(new Trace(BusType.TL, numHarts = harts))
    (new ChiselStage).execute(
      Array("--target-dir", s"./build/rtl/tl-${harts}hart-sram", "--split-verilog"),
      Seq(ChiselGeneratorAnnotation(() => top.module),
        FirtoolOption("--disable-all-randomization"), FirtoolOption("--disable-annotation-unknown"),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)))
    println(s"[Funnel-limit] $harts hart实际TL顶层、独立encoder/CDC/staging、Funnel和SRAM生成完成")
  }
}


/** The address window is a test configuration, not a platform memory map. */
object TraceSMEMMain extends App {
  implicit val p: Parameters = Parameters.empty
  for (bus <- Seq(BusType.TL, BusType.AXI, BusType.APB)) {
    val top = LazyModule(new Trace(bus, numHarts = 2,
      smemWindow = Some(trace.component.SMEMWindow(BigInt("180000000", 16), 0x10000))))
    (new ChiselStage).execute(
      Array("--target-dir", s"./build/rtl/${bus.busName}-2hart-smem", "--split-verilog"),
      Seq(ChiselGeneratorAnnotation(() => top.module),
        FirtoolOption("--disable-all-randomization"), FirtoolOption("--disable-annotation-unknown"),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)))
    println(s"[SMEM-rtl] ${bus.busName} 双 hart / 64-bit 地址 / 32-bit AXI 写口生成完成")
  }
}


object TraceHPMMain extends App {
  implicit val p: Parameters = Parameters.empty
  val top = LazyModule(new Trace(BusType.TL, numHarts = 2, enableHPM = true))
  (new ChiselStage).execute(
    Array("--target-dir", "./build/rtl/tl-2hart-hpm", "--split-verilog"),
    Seq(ChiselGeneratorAnnotation(() => top.module),
      FirtoolOption("--disable-all-randomization"), FirtoolOption("--disable-annotation-unknown"),
      circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)))
  println("[core-HPM-rtl] 双 hart 独立 HPM 选择/增量端口及默认无 HPM 端口配置生成完成")
}


object TracePIBMain extends App {
  implicit val p: Parameters = Parameters.empty
  for (bus <- Seq(BusType.TL, BusType.AXI, BusType.APB)) {
    val top = LazyModule(new Trace(bus, numHarts = 2, usePibSink = true))
    (new ChiselStage).execute(
      Array("--target-dir", s"./build/rtl/${bus.busName}-2hart-pib", "--split-verilog"),
      Seq(ChiselGeneratorAnnotation(() => top.module),
        FirtoolOption("--disable-all-randomization"), FirtoolOption("--disable-annotation-unknown"),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog)))
    println(s"[PIB-rtl] ${bus.busName} 双 hart -> PIB 独占输出生成完成")
  }
}
