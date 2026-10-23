package trace.component

import chisel3._
import chiseltest._
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private class PredictionCountCodecHarness extends Module {
  val io = IO(new Bundle {
    val previous = Input(UInt(33.W))
    val increment = Input(Bool())
    val address = Input(UInt(49.W))
    val withAddress = Input(Bool())
    val failed = Input(Bool())
    val next = Output(UInt(33.W))
    val maximum = Output(Bool())
    val packet = Output(new PacketEnq(33))
  })
  val count = TraceBranchPredictor.countAfter(io.previous, io.increment)
  val encoded = TraceBranchPredictor.encodedCount(count)
  io.next := count; io.maximum := TraceBranchPredictor.atMaximum(count)
  val payload = Mux(io.withAddress,
    ETracePacketCodec.predictedAddress(encoded, io.failed, io.address, false.B, false.B, 1, 248),
    ETracePacketCodec.predictedBranches(encoded, 248, 1))
  io.packet := ETracePacketCodec.encapsulate(payload, 3.U(8.W), 33)
}

class PacketBranchPredictionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val support = ETraceReference.support(3, branchPrediction = true)
  private val end = ETraceReference.support(3, branchPrediction = true, status = 1)
  private val start = Seq(support, ETraceReference.synchronization(3, 0x1000))

  behavior of "Format0 correctly predicted branch count"
  it should "switch from branch maps at 31 correct branches and encode correct or failed address anchors" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      println("[branch-pred][1/4] cold predictor→0/1/30/31/32/63/127/1024个正确分支→notify地址→停止")
      for (count <- Seq(0, 1, 30, 31, 32, 63, 127, 1024)) {
        stream.initialize(options = 16)
        val anchor = 0x1004 + count * 4
        stream.send(Seq(PacketInput(0x1000)) ++ (0 until count).map(i => PacketInput(0x1004 + i * 4, 4)) ++
          Seq(PacketInput(anchor, notification = true), PacketInput(anchor + 4)))
        stream.stop()
        val expected = if (count < 31) ETraceReference.address(3, anchor, 0x1000,
          Seq.fill(count)(false), notifyRequested = true)
        else ETraceReference.prediction(3, count, Some(anchor), 0x1000, notifyRequested = true)
        withClue(s"correct branches=$count: ") {
          stream.frames.toSeq shouldBe start ++ Seq(expected, ETraceReference.address(3, anchor + 4, anchor), end)
        }
      }
      for (failed <- Seq(false, true)) {
        stream.initialize(options = 16)
        val count = if (failed) 32 else 31
        val anchor = BigInt(0x1004 + (count - 1) * 4)
        stream.send(Seq(PacketInput(0x1000)) ++ (0 until count).map { i =>
          PacketInput(0x1004 + i * 4, if (failed && i == count - 1) 5 else 4, notification = i == count - 1)
        } ++ Seq(PacketInput(anchor + 4)))
        stream.stop()
        stream.frames.toSeq shouldBe start ++ Seq(
          ETraceReference.prediction(3, 31, Some(anchor), 0x1000, failed = failed, notifyRequested = true),
          ETraceReference.address(3, anchor + 4, anchor), end)
      }
      println("[branch-pred][1/4] threshold、长正确计数、branch_fmt=addr/addr-fail逐字节通过，反压不改变packet")
    }
  }

  it should "retain real branch maps after a prediction failure and give prediction counts priority over cache indices" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize(options = 16)
      val outcomes = true +: Seq.fill(30)(false)
      stream.send(Seq(PacketInput(0x1000)) ++ outcomes.zipWithIndex.map { case (taken, i) =>
        PacketInput(0x1004 + i * 4, if (taken) 5 else 4)
      } ++ (0 until 31).map(i => PacketInput(0x1080 + i * 4, 4)) ++
        Seq(PacketInput(0x10fc, notification = true), PacketInput(0x1100)))
      stream.stop()
      stream.frames.toSeq shouldBe start ++ Seq(ETraceReference.branchOnly(3, outcomes),
        ETraceReference.prediction(3, 31, Some(0x10fc), 0x1000, notifyRequested = true),
        ETraceReference.address(3, 0x1100, 0x10fc), end)

      stream.initialize(options = 48)
      stream.send(Seq(PacketInput(0x1000), PacketInput(0x1004, 8), PacketInput(0x2000), PacketInput(0x2004, 8),
        PacketInput(0x2100)) ++ (0 until 31).map(i => PacketInput(0x2104 + i * 4, 4)) ++
        Seq(PacketInput(0x2180, 8), PacketInput(0x2000), PacketInput(0x2004, 8),
          PacketInput(0x2100), PacketInput(0x2104)))
      stream.stop()
      stream.frames.toSeq shouldBe Seq(ETraceReference.support(3, jumpCache = true, branchPrediction = true),
        ETraceReference.synchronization(3, 0x1000), ETraceReference.address(3, 0x2000, 0x1000),
        ETraceReference.address(3, 0x2100, 0x2000), ETraceReference.prediction(3, 31, Some(0x2000), 0x2100),
        ETraceReference.cachedTarget(3, 0x2100), ETraceReference.address(3, 0x2104, 0x2100),
        ETraceReference.support(3, jumpCache = true, branchPrediction = true, status = 1))
      println("[branch-pred][2/4] 任一预测失败保留Format1真实map；预测count优先于cache index，随后cache仍正确")
    }
  }

  it should "decode consecutive zero-payload count packets without losing either implicit failed branch" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize(options = 16)
      val first = (0 until 31).map(i => PacketInput(0x1004 + i * 4, 4))
      val second = (0 until 31).map(i => PacketInput(0x1088 + i * 4, 4))
      val entries = Seq(PacketInput(0x1000)) ++ first ++ Seq(PacketInput(0x1080, 5)) ++ second ++
        Seq(PacketInput(0x1104, 5), PacketInput(0x110c, notification = true), PacketInput(0x1110))
      stream.send(entries); stream.stop()
      stream.frames.toSeq shouldBe start ++ Seq(ETraceReference.prediction(3, 31), ETraceReference.prediction(3, 31),
        ETraceReference.address(3, 0x110c, 0x1000, notifyRequested = true),
        ETraceReference.address(3, 0x1110, 0x110c), end)
      ETraceReference.prediction(3, 31) shouldBe Seq(1, 3, 0)
      val out = Paths.get(sys.env("MILL_WORKSPACE_ROOT")).resolve("build/validation")
      Files.createDirectories(out)
      println(s"[optional-capture] output=${out.toAbsolutePath.normalize()}")
      Files.write(out.resolve("packetgen-branch-pred.bin"), stream.frames.flatten.map(_.toByte).toArray)
      val code = Array.fill[Byte](0x114)(0)
      for (entry <- entries) {
        val word = if (entry.kind == 4) 0x1463 else if (entry.kind == 5) 0x463 else 0x13
        for (i <- 0 until 4) code(entry.pc.toInt - 0x1000 + i) = ((word >> (8 * i)) & 255).toByte
      }
      Files.write(out.resolve("packetgen-branch-pred-code.bin"), code)
      Files.writeString(out.resolve("packetgen-branch-pred.json"),
        """{"source_bits":8,"sources":{"3":{"hart_id":7,"raw":"packetgen-branch-pred-code.bin","base":"0x1000","xlen":64}}}""")
      Files.writeString(out.resolve("packetgen-branch-pred-expected.json"), entries.map(_.pc).mkString("[", ",", "]"))
      println("[branch-pred][3/4] 两个payload=0有效packet及各自的implicit失败分支，导出67条真实镜像退休路径")
    }
  }

  it should "use the normative two-miss predictor transitions and reset independent entries" in {
    test(new TraceBranchPredictor) { d =>
      d.io.clear.poke(false.B); d.io.update.poke(false.B); d.io.address.poke(0x1000.U); d.io.taken.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val transitions = Map((0, false) -> 0, (0, true) -> 1, (1, false) -> 0, (1, true) -> 3,
        (3, false) -> 2, (3, true) -> 3, (2, false) -> 0, (2, true) -> 3)
      var state = 1
      for (taken <- Seq(false, true, true, true, false, true, false, false, true, true)) {
        d.io.taken.poke(taken.B); d.io.correct.expect((((state & 2) != 0) == taken).B)
        d.clock.step(2) // No update means no training under a stall.
        d.io.correct.expect((((state & 2) != 0) == taken).B)
        d.io.update.poke(true.B); d.clock.step(); d.io.update.poke(false.B)
        state = transitions((state, taken))
      }
      d.io.address.poke(0x1002.U); d.io.taken.poke(false.B); d.io.correct.expect(true.B)
      d.io.address.poke(0x1200.U); d.io.taken.poke(true.B); d.io.correct.expect(((state & 2) != 0).B)
      d.io.clear.poke(true.B); d.io.update.poke(true.B); d.clock.step()
      d.io.clear.poke(false.B); d.io.update.poke(false.B); d.io.taken.poke(false.B); d.io.correct.expect(true.B)
      println("[branch-pred][4/4] 规范两次失败转换、索引alias、独立entry、stall不训练及clear优先通过")
    }
  }

  it should "reset on synchronization and loss while preserving predictor state across Format32" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      stream.initialize(options = 16)
      stream.send(Seq(PacketInput(0x1000, 5)) ++ Seq.fill(31)(PacketInput(0x1000, 4)) ++
        Seq(PacketInput(0x1004, notification = true), PacketInput(0x1008)))
      stream.stop()
      stream.frames.toSeq shouldBe Seq(support, ETraceReference.synchronization(3, 0x1000, taken = true),
        ETraceReference.prediction(3, 31, Some(0x1004), 0x1000, notifyRequested = true),
        ETraceReference.address(3, 0x1008, 0x1004), end)

      stream.initialize(options = 16)
      stream.send(Seq(PacketInput(0x1000)) ++ Seq.fill(31)(PacketInput(0x1004, 5)) ++
        Seq(PacketInput(0x2000, context = 1, ctype = 1)) ++ Seq.fill(31)(PacketInput(0x1004, 5, context = 1)) ++
        Seq(PacketInput(0x2004, context = 1, notification = true), PacketInput(0x2008, context = 1)))
      stream.stop()
      stream.frames.toSeq shouldBe start ++ Seq(ETraceReference.branchOnly(3, Seq.fill(31)(true)),
        ETraceReference.context(3, 1), ETraceReference.prediction(3, 31, Some(0x2004), 0x1000, notifyRequested = true),
        ETraceReference.address(3, 0x2008, 0x2004), end)

      for (loss <- Seq(false, true)) {
        stream.initialize(options = 16)
        val privilege = if (loss) 3 else 1
        val marker = if (loss) Seq(PacketInput(0, retired = 0, lost = true)) else Seq.empty
        stream.send(Seq(PacketInput(0x1000)) ++ Seq.fill(31)(PacketInput(0x1004, 5)) ++
          Seq(PacketInput(0x2000, notification = true)) ++ marker ++ Seq(PacketInput(0x3000, privilege = privilege)) ++
          Seq.fill(31)(PacketInput(0x1004, 4, privilege = privilege)) ++
          Seq(PacketInput(0x3004, privilege = privilege, notification = true), PacketInput(0x3008, privilege = privilege)))
        stream.stop()
        stream.frames.toSeq shouldBe start ++ Seq(ETraceReference.branchOnly(3, Seq.fill(31)(true)),
          ETraceReference.address(3, 0x2000, 0x1000, notifyRequested = true)) ++
          (if (loss) Seq(ETraceReference.support(3, branchPrediction = true, status = 2)) else Seq.empty) ++
          Seq(ETraceReference.synchronization(3, 0x3000, privilege = privilege),
            ETraceReference.prediction(3, 31, Some(0x3004), 0x3000, notifyRequested = true),
            ETraceReference.address(3, 0x3008, 0x3004), end)
      }
      println("[branch-pred][epoch] 同步首branch不训练旧状态、Format32保留、privilege sync/loss均恢复01初值")
    }
  }

  it should "preserve the entire 32-bit wire count and recognize the maximum address-report threshold" in {
    test(new PredictionCountCodecHarness) { d =>
      val maximum = (BigInt(1) << 32) + 30
      for (wireCount <- Seq(BigInt(0), BigInt(1), BigInt(127), BigInt(128), (BigInt(1) << 31) - 1,
          BigInt(1) << 31, (BigInt(1) << 32) - 1);
          increment <- Seq(false, true); negative <- Seq(false, true)) {
        val correct = wireCount + 31
        val pc = if (negative) ETraceReference.Mask - 3 else BigInt(0x12345678)
        d.io.previous.poke((correct - (if (increment) 1 else 0)).U); d.io.increment.poke(increment.B)
        d.io.address.poke((pc >> 1).U); d.io.withAddress.poke(true.B); d.io.failed.poke(false.B)
        d.io.next.expect(correct.U); d.io.maximum.expect((correct == maximum).B)
        val expected = ETraceReference.prediction(3, correct, Some(pc))
        d.io.packet.len.expect(expected.size.U)
        d.io.packet.data.take(expected.size).zip(expected).foreach { case (actual, byte) => actual.expect(byte.U) }
      }
      println("[branch-pred][count] 33-bit逻辑计数及完整32-bit wire字段边界通过，0xffffffff对应max地址格式")
    }
  }

  it should "combine FullAddr and cache modes and drain prediction runs before an external synchronization request" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      for (options <- Seq(16, 17, 48, 49)) {
        val full = (options & 1) != 0
        val cache = (options & 32) != 0
        stream.initialize(options)
        stream.send(Seq(PacketInput(0x1000)) ++ (0 until 31).map(i => PacketInput(0x1004 + 4 * i, 4)) ++
          Seq(PacketInput(0x1080, notification = true), PacketInput(0x1084)))
        stream.stop()
        stream.frames.toSeq shouldBe Seq(ETraceReference.support(3, fullAddress = full, jumpCache = cache, branchPrediction = true),
          ETraceReference.synchronization(3, 0x1000),
          ETraceReference.prediction(3, 31, Some(0x1080), 0x1000, notifyRequested = true, fullAddress = full),
          ETraceReference.address(3, 0x1084, 0x1080, fullAddress = full),
          ETraceReference.support(3, fullAddress = full, jumpCache = cache, branchPrediction = true, status = 1))
      }
      stream.initialize(options = 16)
      stream.send(Seq(PacketInput(0x1000)) ++ (0 until 31).map(i => PacketInput(0x1004 + 4 * i, 4)))
      stream.requestSync()
      stream.send(Seq(PacketInput(0x1080, 5), PacketInput(0x1084, notification = true), PacketInput(0x1088)))
      stream.stop()
      stream.frames.toSeq shouldBe start ++ Seq(ETraceReference.prediction(3, 31, Some(0x107c), 0x1000),
        ETraceReference.synchronization(3, 0x1080, taken = true),
        ETraceReference.address(3, 0x1084, 0x1080, notifyRequested = true),
        ETraceReference.address(3, 0x1088, 0x1084), end)
      println("[branch-pred][组合] FullAddr/cache四配置与SYNCREQ先排预测run后同步通过，首branch不重复")
    }
  }
}
