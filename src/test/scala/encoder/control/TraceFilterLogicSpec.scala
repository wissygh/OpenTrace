package trace.component

import bundles.CSRModule
import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.control._

private class TraceFilterLogicHarness extends Module {
  val io = IO(new Bundle {
    val privileges = Input(UInt(8.W))
    val privilege = Input(UInt(3.W))
    val causes = Input(UInt(64.W))
    val cause = Input(UInt(8.W))
    val selection = Input(UInt(3.W))
    val enabled = Input(UInt(3.W))
    val matches = Input(UInt(3.W))
    val privilegeMatch = Output(Bool())
    val causeMatch = Output(Bool())
    val selectedMatch = Output(Bool())
    val write = Input(Bool())
    val address = Input(UInt(2.W))
    val data = Input(UInt(32.W))
    val values = Output(Vec(4, UInt(32.W)))
  })
  io.privilegeMatch := TraceFilterLogic.privilege(io.privileges, io.privilege)
  io.causeMatch := TraceFilterLogic.cause(io.causes, io.cause)
  io.selectedMatch := TraceFilterLogic.selected(io.selection, io.enabled, io.matches)
  val inst = withReset(reset.asAsyncReset) { Module(new CSRModule("MatchInst", new TeFilterMatchInstBundle)) }
  val select = withReset(reset.asAsyncReset) {
    Module(new CSRModule("InstFilters", new TeInstFiltersBundle { override def filter_num = 3 }))
  }
  val control = withReset(reset.asAsyncReset) { Module(new CSRModule("FilterControl", new TeFilterControlBundle)) }
  val data = withReset(reset.asAsyncReset) { Module(new CSRModule("FilterData", new TeFilterMatchDataBundle)) }
  Seq(inst, select, control, data).zipWithIndex.foreach { case (csr, i) =>
    csr.w.wen := io.write && io.address === i.U; csr.w.wdata := io.data; io.values(i) := csr.rdata
  }
}

class TraceFilterLogicSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "trace filter selection masks"
  it should "match privilege and cause sets and OR only enabled selected filters" in {
    test(new TraceFilterLogicHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      d.io.privileges.poke(0.U); d.io.privilege.poke(0.U)
      d.io.causes.poke(0.U); d.io.cause.poke(0.U)
      d.io.selection.poke(0.U); d.io.enabled.poke(0.U); d.io.matches.poke(0.U)
      d.io.write.poke(false.B); d.io.address.poke(0.U); d.io.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      for (mask <- 0 until 256; privilege <- 0 until 8) {
        d.io.privileges.poke(mask.U); d.io.privilege.poke(privilege.U)
        d.io.privilegeMatch.expect(((mask & (1 << privilege)) != 0).B)
      }
      val causes = Set(0, 2, 31, 32, 63)
      d.io.causes.poke(causes.foldLeft(BigInt(0))((mask, cause) => mask.setBit(cause)).U)
      for (cause <- 0 until 256) {
        d.io.cause.poke(cause.U); d.io.causeMatch.expect(causes.contains(cause).B)
      }
      println("[filter-masks] 1/3 2048 组 privilege 集合与 256 个 cause 编号通过，包括 31/32/63/64 边界")
      for (selection <- 0 until 8; enabled <- 0 until 8; matches <- 0 until 8) {
        d.io.selection.poke(selection.U); d.io.enabled.poke(enabled.U); d.io.matches.poke(matches.U)
        val expected = selection == 0 || (0 until 3).exists(i =>
          Seq(selection, enabled, matches).forall(mask => (mask & (1 << i)) != 0))
        d.io.selectedMatch.expect(expected.B)
      }
      println("[filter-masks] 2/3 512 组多 filter OR、disabled filter 和无选择 bypass 通过")
      def write(address: Int, data: BigInt): Unit = {
        d.io.address.poke(address.U); d.io.data.poke(data.U); d.io.write.poke(true.B)
        d.clock.step(); d.io.write.poke(false.B)
      }
      write(0, 0x7b); (d.io.values(0).peek().litValue & 255) shouldBe BigInt(0x7b)
      write(0, 0); (d.io.values(0).peek().litValue & 255) shouldBe BigInt(0)
      write(0, 0x84); (d.io.values(0).peek().litValue & 255) shouldBe BigInt(0) // reserved bits rejected
      write(0, 10); (d.io.values(0).peek().litValue & 255) shouldBe BigInt(10)
      write(1, 7); d.io.values(1).expect(7.U)
      write(2, BigInt("ffffffff", 16)); ((d.io.values(2).peek().litValue >> 24) & 3) shouldBe BigInt(0)
      write(3, BigInt("ffffffff", 16)); d.io.values(3).expect(0.U)
      println("[filter-masks] 3/3 实际 CSR 多权限/多 filter mask 可写，保留位及未实现 data filter 不可开启")
    }
  }
}
