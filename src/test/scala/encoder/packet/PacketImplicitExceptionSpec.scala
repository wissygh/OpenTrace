package trace.component

import chisel3._
import chiseltest._
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PacketImplicitExceptionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "implicit exception address omission"

  it should "finish the old trap encoding before reporting a feature change under backpressure" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      val scenario = ImplicitExceptionScenarios.all.find(_.name == "direct-exception").get
      stream.initialize(2)
      stream.send(scenario.entries)
      d.io.teInstFeat.poke((3 << 16).U)
      stream.stall(37); stream.idle(40)
      stream.send(scenario.entries); stream.stop()
      val trap = scenario.traps.head
      stream.frames.filter(f => (f(2) & 15) == 7).toSeq shouldBe Seq(
        ETraceReference.trap(3, scenario.vector, trap.cause, trap.tval, implicitException = true),
        ETraceReference.trap(3, scenario.vector, trap.cause, trap.tval))
      val declarations = stream.frames.filter(f => (f(2) & 15) == 15)
      declarations.head shouldBe ETraceReference.support(3, implicitException = true)
      declarations.last shouldBe ETraceReference.support(3, status = 1)
      println("[implicit-exception][config] held old trap keeps omitted address; subsequent support and trap switch back to explicit mode")
    }
  }

  it should "retain EPCs and pack tval without an address hole under every optional mode combination" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      for (mode <- ImplicitExceptionScenarios.modes; scenario <- ImplicitExceptionScenarios.all) {
        stream.initialize(mode)
        stream.send(scenario.entries.take(2)); stream.stall(37)
        stream.send(scenario.entries.drop(2)); stream.stop()
        val actualTraps = stream.frames.filter(f => (f(2) & 15) == 7).toSeq
        val expectedTraps = scenario.traps.zipWithIndex.map { case (t, i) =>
          val handler = scenario.entries.find(e => e.retired > 0 && e.pc >= ImplicitExceptionScenarios.base + 0x1000)
          val epc = scenario.entries.filter(e => e.retired == 0).lift(i).getOrElse(PacketInput(0))
          ETraceReference.trap(3, if (t.handler) handler.get.pc else epc.pc, t.cause, t.tval,
            interrupt = t.interrupt, handler = t.handler, privilege = if (t.handler) 3 else epc.privilege,
            taken = t.handler && scenario.name == "handler-branch", implicitException = (mode & 2) != 0)
        }
        withClue(s"mode=$mode ${scenario.name}: ") { actualTraps shouldBe expectedTraps }
        val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
          .resolve(s"build/validation/implicit-exception/packet/$mode/${scenario.name}")
        for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
        Files.write(root.resolve("workload/code.bin"), scenario.code)
        Files.write(root.resolve("dut/trace.bin"), stream.frames.flatten.map(_.toByte).toArray)
        Files.writeString(root.resolve("dut/trace.json"), scenario.config(3, discovered = false))
        Files.writeString(root.resolve("ref/expected.json"), scenario.expected.mkString("[", ",", "]"))
        Files.writeString(root.resolve("ref/traps.json"), scenario.trapReference)
        Files.writeString(root.resolve("ref/mode.json"), s"""{"features":$mode}""")
        println(s"[implicit-exception][packet] mode=$mode ${scenario.name}: ${scenario.traps.size} trap packets match independent wire golden; 37-cycle stall stable")
      }
    }
  }
}
