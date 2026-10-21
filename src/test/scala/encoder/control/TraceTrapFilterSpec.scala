package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trace.interface.Itype

private class TraceTrapFilterHarness extends Module {
  val io = IO(new Bundle {
    val kind = Input(UInt(4.W))
    val cause = Input(UInt(8.W))
    val choices = Input(UInt(64.W))
    val matchCause = Input(Bool())
    val matchType = Input(Bool())
    val interrupt = Input(Bool())
    val advance = Input(Bool())
    val clear = Input(Bool())
    val matched = Output(Bool())
    val depth = Output(UInt(2.W))
    val saturated = Output(Bool())
  })
  val scope = Module(new TraceTrapFilterScope(2))
  scope.io.kind := io.kind
  scope.io.trigger := TraceFilterLogic.trap(io.choices, io.cause, io.kind,
    io.matchCause, io.matchType, io.interrupt)
  scope.io.advance := io.advance; scope.io.clear := io.clear
  io.matched := scope.io.matched; io.depth := scope.io.depth; io.saturated := scope.io.saturated
}

class TraceTrapFilterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "nested trap filter scope"
  it should "match a whole nested handler range and change depth only on accepted events" in {
    test(new TraceTrapFilterHarness) { d =>
      d.io.kind.poke(Itype.None); d.io.cause.poke(0.U); d.io.choices.poke(((1 << 2) | (1 << 7)).U)
      d.io.matchCause.poke(true.B); d.io.matchType.poke(true.B); d.io.interrupt.poke(false.B)
      d.io.advance.poke(false.B); d.io.clear.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.matched.expect(false.B)
      def event(kind: Int, cause: Int = 0, depth: Int, before: Boolean = true): Unit = {
        d.io.kind.poke(kind.U); d.io.cause.poke(cause.U)
        d.io.matched.expect(before.B); d.io.advance.poke(true.B); d.clock.step()
        d.io.advance.poke(false.B); d.io.depth.expect(depth.U)
      }
      event(1, 3, depth = 0, before = false) // wrong cause
      event(2, 2, depth = 0, before = false) // wrong type
      d.io.kind.poke(Itype.Exception); d.io.cause.poke(2.U)
      d.io.matched.expect(true.B); d.clock.step(5); d.io.depth.expect(0.U)
      event(1, 2, depth = 1)
      event(0, depth = 1)
      event(2, 50, depth = 2) // nested trap of another kind/cause must count
      event(1, 30, depth = 3)
      d.io.kind.poke(Itype.ExpIntReturn)
      d.clock.step(5); d.io.depth.expect(3.U); d.io.matched.expect(true.B)
      event(3, depth = 2); event(3, depth = 1); event(3, depth = 0)
      d.io.kind.poke(Itype.None); d.io.matched.expect(false.B)
      event(3, depth = 0, before = false) // cannot underflow
      println("[trap-filter] 1/3 cause/type AND 触发、嵌套任意 trap、匹配 return 范围、stall 保持通过")

      d.io.matchType.poke(false.B)
      event(2, 7, depth = 1) // cause only accepts an interrupt
      d.io.clear.poke(true.B); d.clock.step(); d.io.clear.poke(false.B)
      d.io.depth.expect(0.U)
      d.io.matchCause.poke(false.B); d.io.matchType.poke(true.B); d.io.interrupt.poke(true.B)
      event(1, 7, depth = 0, before = false)
      event(2, 200, depth = 1) // type only does not require a representable cause
      d.io.kind.poke(Itype.None); d.io.matched.expect(true.B)
      d.io.clear.poke(true.B); d.clock.step(); d.io.clear.poke(false.B)
      d.io.depth.expect(0.U); d.io.matched.expect(false.B)
      println("[trap-filter] 2/3 单 cause/单 type、配置/trace-lost 清除与重新触发通过")

      event(2, 1, depth = 1)
      event(1, 2, depth = 2)
      event(1, 3, depth = 3)
      d.io.kind.poke(Itype.Exception); d.io.advance.poke(true.B)
      d.io.saturated.expect(true.B); d.clock.step(); d.io.advance.poke(false.B)
      d.io.depth.expect(3.U)
      event(3, depth = 2); event(3, depth = 1); event(3, depth = 0)
      d.io.kind.poke(Itype.None); d.io.matched.expect(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.io.depth.expect(0.U)
      println("[trap-filter] 3/3 有界深度饱和及复位通过；超过上限允许规范说明的提前退出")
    }
  }
}
