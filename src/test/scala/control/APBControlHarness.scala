package trace

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import java.nio.file.{Files, Paths}
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import trace.component.SMEMWindow
import trace.interface._

/** Real APB-to-TL, control CDC endpoints and actual components. */
private[trace] class APBControlHarness(sink: String = "sram", hpm: Boolean = true) extends Module {
  implicit val p: Parameters = Parameters.empty
  val design = LazyModule(new Trace(BusType.APB, numHarts = 2, enableHPM = hpm,
    useAtbSink = sink == "atb", usePibSink = sink == "pib",
    smemWindow = if (sink == "smem") Some(SMEMWindow(BigInt("83000000", 16), 4096)) else None))
  val top = Module(design.module)
  val ramReady = expose(top.ramMod.map(_.io.in.ready).getOrElse(false.B))
  val ramValid = expose(top.ramMod.map(_.io.in.valid).getOrElse(false.B))
  val funnelReady = expose(top.funnelMod.io.out.ready)
  val funnelValid = expose(top.funnelMod.io.out.valid)
  val encoderReady0 = expose(top.encoderModVec(0).io.out.ready)
  val encoderValid0 = expose(top.encoderModVec(0).io.out.valid)
  val encoderReady1 = expose(top.encoderModVec(1).io.out.ready)
  val encoderValid1 = expose(top.encoderModVec(1).io.out.valid)
  val apb = design.ctrlAPBMaster.get.getWrappedValue.head
  val io = IO(new Bundle {
    val control = Flipped(new APBBundle(apb.params))
    val core = Input(Vec(2, new FromCore))
    val toCore = Output(Vec(2, new ToCore))
    val events = Input(Vec(2, Vec(2, UInt(6.W))))
  })
  apb <> io.control
  top.io.clock_ctrl := clock; top.io.reset_ctrl := reset.asAsyncReset
  top.io.clock_core := clock; top.io.reset_core := reset.asAsyncReset
  top.io.clock_trace := clock; top.io.reset_trace := reset.asAsyncReset
  top.io.fromCore := io.core; io.toCore := top.io.toCore
  top.io.hpm.foreach(_.zipWithIndex.foreach { case (hart, h) =>
    hart.eventVec.zipWithIndex.foreach { case (event, i) => event.inc := io.events(h)(i) }
  })
  top.io.clock_atb.foreach(_ := clock); top.io.reset_atb_n.foreach(_ := !reset.asBool)
  top.io.atbm.foreach { port =>
    port.at.ready := true.B; port.atclken := true.B; port.af.valid := false.B
    port.af.bits := 0.U.asTypeOf(port.af.bits); port.syncReq := false.B
  }
  top.io.smem.foreach { port =>
    port.aw.ready := true.B; port.w.ready := true.B; port.b.valid := false.B
    port.b.bits := 0.U.asTypeOf(port.b.bits); port.ar.ready := true.B
    port.r.valid := false.B; port.r.bits := 0.U.asTypeOf(port.r.bits)
  }
}

private[trace] class APBControlDriver(val d: APBControlHarness) {
  var transactions = 0
  var waitCycles = 0
  val records = scala.collection.mutable.ArrayBuffer.empty[String]
  def save(name: String, status: String = "passed"): Unit = {
    val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve(s"build/validation/requirements14-apb/$name")
    Files.createDirectories(root.resolve("dut")); Files.createDirectories(root.resolve("diff"))
    Files.writeString(root.resolve("dut/apb-transactions.jsonl"), records.mkString("", "\n", "\n"))
    Files.writeString(root.resolve("diff/apb-result.json"),
      s"""{"status":"$status","transactions":$transactions,"wait_cycles":$waitCycles,"path":"actual APB -> TraceAPBToTL -> control CDC -> actual component"}""")
  }
  def initialize(): Unit = {
    d.clock.setTimeout(1000000)
    d.io.control.psel.poke(false.B); d.io.control.penable.poke(false.B)
    d.io.control.pwrite.poke(false.B); d.io.control.paddr.poke(0.U)
    d.io.control.pprot.poke(0.U); d.io.control.pwdata.poke(0.U); d.io.control.pstrb.poke(15.U)
    d.io.events.foreach(_.foreach(_.poke(0.U)))
    for (c <- d.io.core) {
      c.priv.poke(Priv.M); c.time.poke(0.U); c.context.poke(0.U); c.ctype.poke(Ctype.None)
      c.cause.poke(0.U); c.tval.poke(0.U); c.impdef.poke(0.U); c.hartIsReset.poke(false.B)
      c.trigger.traceOn.poke(false.B); c.trigger.traceOff.poke(false.B)
      for (b <- c.blocks) {
        b.valid.poke(false.B); b.bits.iaddr.poke(0.U); b.bits.itype.poke(Itype.None)
        b.bits.iretire.poke(2.U); b.bits.ilastsize.poke(Ilastsize.Word)
        b.bits.trigNotify.poke(false.B); b.bits.sijump.foreach(_.poke(false.B))
      }
    }
    d.reset.poke(true.B); d.clock.step(8); d.reset.poke(false.B); d.clock.step(30)
  }
  def access(address: BigInt, write: Boolean = false, value: BigInt = 0, strobe: Int = 15,
      error: Boolean = false): BigInt = {
    val bus = d.io.control
    bus.psel.poke(true.B); bus.penable.poke(false.B); bus.paddr.poke(address.U)
    bus.pwrite.poke(write.B); bus.pwdata.poke(value.U); bus.pstrb.poke(strobe.U)
    d.clock.step(); bus.penable.poke(true.B)
    var cycles = 0
    while (!bus.pready.peek().litToBoolean && cycles < 2000) { d.clock.step(); cycles += 1 }
    assert(cycles < 2000, f"APB timeout at 0x$address%x")
    bus.pslverr.expect(error.B)
    val result = bus.prdata.peek().litValue
    records += s"""{"index":$transactions,"address":"0x${address.toString(16)}","write":$write,"wdata":"0x${value.toString(16)}","strobe":$strobe,"rdata":"0x${result.toString(16)}","error":${bus.pslverr.peek().litToBoolean},"wait_cycles":$cycles}"""
    d.clock.step(); bus.psel.poke(false.B); bus.penable.poke(false.B); d.clock.step()
    transactions += 1; waitCycles += cycles
    result
  }
  def read(address: BigInt): BigInt = access(address)
  def write(address: BigInt, value: BigInt): Unit = { access(address, write = true, value = value) }
  def active(address: BigInt): Unit = {
    assert((read(address) & 1) == 0); write(address, 1)
    var count = 0
    while ((read(address) & 1) == 0 && count < 20) count += 1
    assert(count < 20, "component Active did not release")
  }
}
