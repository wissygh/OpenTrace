package trace

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters

object TraceClockRequestMain extends App {
  implicit val p: Parameters = Parameters.empty
  for (exposed <- Seq(false, true)) {
    val top = LazyModule(new Trace(BusType.TL, numHarts = 2, useAtbSink = true,
      exposeAtbClockRequest = exposed))
    (new ChiselStage).execute(
      Array("--target-dir", s"./build/rtl/tl-2hart-atb-clock-request-$exposed", "--split-verilog"),
      Seq(ChiselGeneratorAnnotation(() => top.module),
        FirtoolOption("--disable-all-randomization"), FirtoolOption("--disable-annotation-unknown"),
        CIRCTTargetAnnotation(CIRCTTarget.SystemVerilog)))
    println(s"[ATB-clock-request] actual two-hart Trace generated, optional source request=$exposed")
  }
}
