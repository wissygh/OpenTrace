package trace.component

import chisel3._
import chiseltest._
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PacketJumpCacheSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val support = ETraceReference.support(3, jumpCache = true)
  private val end = ETraceReference.support(3, jumpCache = true, status = 1)
  private val prefix = Seq(support, ETraceReference.synchronization(3, 0x1000),
    ETraceReference.address(3, 0x2000, 0x1000), ETraceReference.address(3, 0x2100, 0x2000))
  private val fill = Seq(PacketInput(0x1000), PacketInput(0x1004, 8), PacketInput(0x2000),
    PacketInput(0x2004, 8), PacketInput(0x2100))

  behavior of "Format0 jump target cache"
  it should "encode hits with every allocated branch map size and hold complete packets under backpressure" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      println("[jump-cache][1/3] 填两个cache目标→积累分支→缓存命中→停止；逐字节检查index及各map宽度")
      val cases = Seq(0, 1, 2, 3, 4, 7, 8, 15, 16, 30).map((_, false)) :+ (30, true)
      for ((pending, targetBranch) <- cases) {
        stream.initialize()
        val outcomes = (0 until pending).map(_ % 2 == 0)
        stream.send(fill ++ outcomes.zipWithIndex.map { case (taken, i) =>
          PacketInput(0x2104 + i * 4, if (taken) 5 else 4)
        } ++ Seq(PacketInput(0x2104 + pending * 4, 8),
          PacketInput(0x2000, if (targetBranch) 5 else 0), PacketInput(0x2004)))
        stream.stop()
        withClue(s"pending=$pending targetBranch=$targetBranch: ") {
          stream.frames.toSeq shouldBe prefix ++ Seq(
            ETraceReference.cachedTarget(3, 0x2000, outcomes ++ (if (targetBranch) Seq(true) else Seq.empty)),
            ETraceReference.address(3, 0x2004, 0x2000), end)
        }
      }
      println("[jump-cache][1/3] 11组通过：0..31 branches、1/3/7/15/31-bit map及反压下len/data稳定")
    }
  }

  it should "replace colliding targets invalidate on synchronization and preserve entries across context-only packets" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize()
      stream.send(fill ++ Seq(PacketInput(0x2104, 8), PacketInput(0x2000), PacketInput(0x2004, 8),
        PacketInput(0x2200), PacketInput(0x2204, 8), PacketInput(0x2000), PacketInput(0x2004, 8),
        PacketInput(0x2000), PacketInput(0x2004)))
      stream.stop()
      stream.frames.toSeq shouldBe prefix ++ Seq(ETraceReference.cachedTarget(3, 0x2000),
        ETraceReference.address(3, 0x2200, 0x2000), ETraceReference.address(3, 0x2000, 0x2200),
        ETraceReference.cachedTarget(3, 0x2000), ETraceReference.address(3, 0x2004, 0x2000), end)

      stream.initialize()
      stream.send(fill ++ Seq(PacketInput(0x2104, 8), PacketInput(0x2000), PacketInput(0x2004),
        PacketInput(0x3000, privilege = 1), PacketInput(0x3004, 8, privilege = 1),
        PacketInput(0x2000, privilege = 1), PacketInput(0x2004, privilege = 1)))
      stream.stop()
      stream.frames.toSeq shouldBe prefix ++ Seq(ETraceReference.cachedTarget(3, 0x2000),
        ETraceReference.address(3, 0x2004, 0x2000), ETraceReference.synchronization(3, 0x3000, privilege = 1),
        ETraceReference.address(3, 0x2000, 0x3000), ETraceReference.address(3, 0x2004, 0x2000), end)

      stream.initialize()
      stream.send(fill ++ Seq(PacketInput(0x2104, 8), PacketInput(0x2000, context = 1, ctype = 1),
        PacketInput(0x2004, context = 1)))
      stream.stop()
      stream.frames.toSeq shouldBe prefix ++ Seq(ETraceReference.context(3, 1),
        ETraceReference.cachedTarget(3, 0x2000), ETraceReference.address(3, 0x2004, 0x2000), end)

      stream.initialize()
      stream.send(fill ++ Seq(PacketInput(0x2104, 8), PacketInput(0x2000),
        PacketInput(0x2004, 1, retired = 0, cause = 2), PacketInput(0x4000), PacketInput(0x4004, 8),
        PacketInput(0x2000), PacketInput(0x2004)))
      stream.stop()
      stream.frames.toSeq shouldBe prefix ++ Seq(ETraceReference.address(3, 0x2000, 0x2100, beforeSync = true),
        ETraceReference.trap(3, 0x4000, 2), ETraceReference.address(3, 0x2000, 0x4000),
        ETraceReference.address(3, 0x2004, 0x2000), end)

      stream.initialize()
      stream.send(fill ++ Seq(PacketInput(0x2104, 8), PacketInput(0x2000), PacketInput(0, retired = 0, lost = true),
        PacketInput(0x3000), PacketInput(0x3004, 8), PacketInput(0x2000), PacketInput(0x2004)))
      stream.stop()
      stream.frames.toSeq shouldBe prefix ++ Seq(ETraceReference.cachedTarget(3, 0x2000),
        ETraceReference.support(3, jumpCache = true, status = 2), ETraceReference.synchronization(3, 0x3000),
        ETraceReference.address(3, 0x2000, 0x3000), ETraceReference.address(3, 0x2004, 0x2000), end)
      println("[jump-cache][2/3] collision、Format30/31与loss失效、Format32保留通过；不使用旧epoch index")
    }
  }

  it should "export a real indirect-call stream using address misses and cached targets to the decoder" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize()
      val pcs = Seq(0x1000, 0x1004, 0x2000, 0x2004, 0x2100, 0x2104, 0x2000, 0x2004, 0x2100, 0x2104)
      stream.send(pcs.map(pc => PacketInput(pc, if ((pc & 7) == 4) 8 else 0)))
      stream.stop()
      stream.frames.toSeq shouldBe prefix ++ Seq(ETraceReference.cachedTarget(3, 0x2000),
        ETraceReference.cachedTarget(3, 0x2100), ETraceReference.address(3, 0x2104, 0x2100), end)
      val workspace = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
      val out = workspace.resolve("build/validation"); Files.createDirectories(out)
      Files.write(out.resolve("packetgen-jump-cache.bin"), stream.frames.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x1108)(0)
      def put(pc: Int, word: Int): Unit = for (i <- 0 until 4) code(pc - 0x1000 + i) = ((word >> (8 * i)) & 255).toByte
      for (pc <- Seq(0x1000, 0x2000, 0x2100)) put(pc, 0x13)
      put(0x1004, 0x28067); put(0x2004, 0x30067); put(0x2104, 0x28067)
      Files.write(out.resolve("packetgen-jump-cache-code.bin"), code)
      Files.writeString(out.resolve("packetgen-jump-cache.json"),
        """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-jump-cache-code.bin","base":"0x1000","xlen":64}}}""")
      Files.writeString(out.resolve("packetgen-jump-cache-expected.json"), pcs.mkString("[", ",", "]"))
      println("[jump-cache][3/3] 真实PacketGen含两次miss和两次index hit，导出10条退休流交独立decoder")
    }
  }
}
