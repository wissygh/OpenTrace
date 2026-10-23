package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}

class PacketImplicitReturnSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "full-address implicit return prediction"
  private val base = BigInt("80001000", 16)
  private def pc(offset: Int): BigInt = base + offset
  private val start = ETraceReference.support(3, implicitReturn = true)
  private val end = ETraceReference.support(3, status = 1, implicitReturn = true)

  private def export(name: String, stream: PacketStreamDriver, entries: Seq[PacketInput], words: Map[Int, Int],
      expected: Seq[BigInt] = Seq.empty): Unit = {
    val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve(s"build/validation/implicit-return/epochs/$name")
    for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
    val code = Array.fill[Byte](words.keys.max + 4)(0)
    for ((offset, word) <- words; i <- 0 until 4) code(offset + i) = ((word >> (8 * i)) & 255).toByte
    Files.write(root.resolve("workload/code.bin"), code)
    Files.write(root.resolve("dut/trace.bin"), stream.frames.flatten.map(_.toByte).toArray)
    Files.writeString(root.resolve("dut/trace.json"),
      s"""{"profile":{"return_stack_size":4},"sources":{"3":{"hart_id":7,"raw":"../workload/code.bin","base":"0x${base.toString(16)}"}}}""")
    Files.writeString(root.resolve("ref/expected.json"), (if (expected.nonEmpty) expected else entries.map(_.pc)).mkString("[", ",", "]"))
  }

  it should "infer nested calls and explicitly locate a modified return at its pre-pop depth" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize(options = 8)
      val entries = Seq(PacketInput(pc(0), 9), PacketInput(pc(0x100), 9), PacketInput(pc(0x200), 13),
        PacketInput(pc(0x104), 13), PacketInput(pc(4)), PacketInput(pc(8)))
      stream.send(entries); stream.stop()
      stream.frames.toSeq shouldBe Seq(start, ETraceReference.synchronization(3, pc(0)),
        ETraceReference.address(3, pc(8), pc(0), irDepth = Some(0)), end)
      println("[implicit-return][1/3] 两层call/return无需返回目标包；停止报告depth0，反压下packet稳定")

      stream.initialize(options = 8)
      val failed = Seq(PacketInput(pc(0), 9), PacketInput(pc(0x100), 9), PacketInput(pc(0x200), 13),
        PacketInput(pc(0x300)), PacketInput(pc(0x304)))
      stream.send(failed); stream.stop()
      stream.frames.toSeq shouldBe Seq(start, ETraceReference.synchronization(3, pc(0)),
        ETraceReference.address(3, pc(0x300), pc(0), irDepth = Some(2)),
        ETraceReference.address(3, pc(0x304), pc(0x300), irDepth = Some(2)), end)
      println("[implicit-return][2/3] 改写返回目标显式报告depth2；失败不弹栈、不冒充已推断返回")
    }
  }

  it should "export recursion compression overflow context traps and optional format combinations" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      for (mode <- Seq(0, 8, 9, 24, 40, 56); scenario <- ImplicitReturnScenarios.all) {
        stream.initialize(options = mode)
        stream.send(scenario.entries); stream.stop()
        val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
          .resolve(s"build/validation/implicit-return/packet/$mode/${scenario.name}")
        for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
        Files.write(root.resolve("workload/code.bin"), scenario.code)
        Files.write(root.resolve("dut/trace.bin"), stream.frames.flatten.map(_.toByte).toArray)
        Files.writeString(root.resolve("dut/trace.json"),
          s"""{"profile":{"return_stack_size":4},"sources":{"3":{"hart_id":7,"raw":"../workload/code.bin","base":"0x${base.toString(16)}","xlen":${scenario.xlen}}}}""")
        Files.writeString(root.resolve("ref/expected.json"), scenario.pcs.mkString("[", ",", "]"))
        println(s"[implicit-return][matrix] mode=$mode scenario=${scenario.name} ${scenario.pcs.size}条退休")
      }
    }
  }

  it should "preserve calls under long output stalls and reset the stack at synchronization and loss frontiers" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      val entries = Seq(PacketInput(pc(0), 8), PacketInput(pc(0x100), 8), PacketInput(pc(0x200), 13),
        PacketInput(pc(0x104), 13), PacketInput(pc(4)), PacketInput(pc(8)))
      val code = Map(0 -> 0x300e7, 0x100 -> 0x300e7, 0x200 -> 0x8067, 0x104 -> 0x8067, 4 -> 0x13, 8 -> 0x13)
      stream.initialize(options = 8)
      stream.send(entries.take(3)); stream.stall(37); stream.send(entries.drop(3)); stream.stop()
      stream.frames.toSeq shouldBe Seq(start, ETraceReference.synchronization(3, pc(0)),
        ETraceReference.address(3, pc(0x100), pc(0)), ETraceReference.address(3, pc(0x200), pc(0x100)),
        ETraceReference.address(3, pc(8), pc(0x200), irDepth = Some(0)), end)
      export("stall-37", stream, entries, code)

      val direct = entries.updated(0, entries(0).copy(kind = 9)).updated(1, entries(1).copy(kind = 9))
      stream.initialize(options = 8)
      stream.send(direct.take(3)); stream.requestSync(); stream.send(direct.drop(3)); stream.stop()
      stream.frames.toSeq shouldBe Seq(start, ETraceReference.synchronization(3, pc(0)),
        ETraceReference.address(3, pc(0x200), pc(0), irDepth = Some(2)),
        ETraceReference.synchronization(3, pc(0x104)), ETraceReference.address(3, pc(4), pc(0x104)),
        ETraceReference.address(3, pc(8), pc(4), irDepth = Some(0)), end)
      export("syncreq", stream, direct, code.updated(0, 0x100000ef).updated(0x100, 0x100000ef))

      stream.initialize(options = 8)
      stream.send(Seq(PacketInput(pc(0), 9), PacketInput(pc(0x100)), PacketInput(0, retired = 0, lost = true),
        PacketInput(pc(0x200), 13), PacketInput(pc(0x300)), PacketInput(pc(0x304))))
      stream.stop()
      stream.frames.toSeq shouldBe Seq(start, ETraceReference.synchronization(3, pc(0)),
        ETraceReference.address(3, pc(0x100), pc(0), irDepth = Some(1)),
        ETraceReference.support(3, status = 2, implicitReturn = true), ETraceReference.synchronization(3, pc(0x200)),
        ETraceReference.address(3, pc(0x300), pc(0x200)),
        ETraceReference.address(3, pc(0x304), pc(0x300), irDepth = Some(0)), end)
      export("loss", stream, Seq.empty, Map(0 -> 0x100000ef, 0x100 -> 0x13, 0x200 -> 0x8067, 0x300 -> 0x13, 0x304 -> 0x13),
        Seq(0, 0x100, 0x200, 0x300, 0x304).map(pc))
      println("[implicit-return][epoch] 37拍反压保持、SYNCREQ深度前沿/清栈、loss后空栈显式返回全部通过")
    }
  }

  it should "export real nested call code and its complete implicit return trace" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize(options = 8)
      val entries = Seq(PacketInput(pc(0), 9), PacketInput(pc(0x100), 9), PacketInput(pc(0x200), 13),
        PacketInput(pc(0x104), 13), PacketInput(pc(4)), PacketInput(pc(8)))
      stream.send(entries); stream.stop()
      val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve("build/validation/implicit-return/nested")
      for (dir <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(dir))
      val code = Array.fill[Byte](0x204)(0)
      def put(offset: Int, word: Int): Unit = for (i <- 0 until 4) code(offset + i) = ((word >> (8 * i)) & 255).toByte
      put(0, 0x100000ef); put(0x100, 0x100000ef) // jal ra,+0x100
      put(0x200, 0x8067); put(0x104, 0x8067); put(4, 0x13); put(8, 0x13)
      Files.write(root.resolve("workload/code.bin"), code)
      Files.write(root.resolve("dut/trace.bin"), stream.frames.flatten.map(_.toByte).toArray)
      Files.writeString(root.resolve("dut/trace.json"),
        s"""{"profile":{"return_stack_size":4},"sources":{"3":{"hart_id":7,"raw":"../workload/code.bin","base":"0x${base.toString(16)}"}}}""")
      Files.writeString(root.resolve("ref/expected.json"), entries.map(_.pc).mkString("[", ",", "]"))
      println(s"[implicit-return][3/3] 真实嵌套call/return 6条退休流：${root.toAbsolutePath}")
    }
  }
}
