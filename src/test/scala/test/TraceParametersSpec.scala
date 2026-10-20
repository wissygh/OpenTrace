package trace

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TraceParametersSpec extends AnyFlatSpec with Matchers {
  behavior of "implemented trace profile"
  it should "accept defaults and reject configurations that cannot preserve the advertised wire geometry" in {
    new TraceParameters {}.validateImplementationParameters()
    new TraceParameters { override def hart_num = 2 }.validateImplementationParameters()
    val invalid = Seq[() => TraceParameters](
      () => new TraceParameters { override def hart_num = 0 },
      () => new TraceParameters { override def hart_num = 17 },
      () => new TraceParameters { override def src_bits_p = 0 },
      () => new TraceParameters { override def src_bits_p = 12 },
      () => new TraceParameters { override def packet_header_time_p = 8 },
      () => new TraceParameters { override def trace_type_width = 1 },
      () => new TraceParameters { override def IaddrWidth = 65 },
      () => new TraceParameters { override def iaddress_lsb_p = 2 },
      () => new TraceParameters { override def contextWidth = 64 },
      () => new TraceParameters { override def ts_width_p = 64 },
      () => new TraceParameters { override def payload_max_width = 256 },
      () => new TraceParameters { override def payload_max_width = 208 },
      () => new TraceParameters { override def maxPackets = 32 },
      () => new TraceParameters { override def atbIdWidth = 8 },
      () => new TraceParameters { override def encoderAtbDataWidth = 24 },
      () => new TraceParameters { override def buffer_num = 255 },
      () => new TraceParameters { override def buffer_ptr_width = 7 },
      () => new TraceParameters { override def core_stall_response_cycles_p = 5 },
      () => new TraceParameters { override def filter_trap_depth_bits_p = 0 })
    invalid.zipWithIndex.foreach { case (build, i) =>
      withClue(s"invalid profile #$i: ") { intercept[IllegalArgumentException](build().validateImplementationParameters()) }
    }
    println(s"[profile] 默认/双 hart 合法，${invalid.size} 组来源/包长/总线/存储/容量非法配置均拒绝")
  }
}
