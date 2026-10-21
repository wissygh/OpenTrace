package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class SMEMSinkCoreSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "SMEM packet staging and committed writes"
  private val base = BigInt("180000000", 16)
  private class Driver(d: SMEMSinkCore) {
    val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0xaa)
    val writes = mutable.ArrayBuffer.empty[(BigInt, BigInt, Int)]
    var clock = 0
    var pending = Option.empty[(Int, Boolean)]
    var failAt = -1
    var allow = true
    var held = Option.empty[(BigInt, BigInt, Int)]
    d.io.enable.poke(true.B); d.io.stopOnWrap.poke(false.B); d.io.abort.poke(false.B)
    d.io.in.valid.poke(false.B); d.io.in.bits.data.poke(0.U); d.io.in.bits.bytes.poke(0.U)
    d.io.in.bits.last.poke(true.B); d.io.in.bits.packetBytes.poke(1.U)
    d.io.configuration.valid.poke(false.B)
    d.io.configuration.bits.start.poke(base.U); d.io.configuration.bits.limit.poke((base + 60).U)
    d.io.configuration.bits.pointer.poke(base.U)
    d.io.request.ready.poke(false.B); d.io.response.valid.poke(false.B); d.io.response.bits.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
      val ready = allow && pending.isEmpty && clock % 3 != 0
      d.io.request.ready.poke(ready.B)
      val response = pending.exists(_._1 == 0) && allow
      d.io.response.valid.poke(response.B); d.io.response.bits.poke(pending.exists(_._2).B)
      val valid = d.io.request.valid.peek().litToBoolean
      val bits = if (valid) Some((d.io.request.bits.address.peek().litValue,
        d.io.request.bits.data.peek().litValue, d.io.request.bits.mask.peek().litValue.toInt)) else None
      held.foreach(h => bits shouldBe Some(h))
      held = if (valid && !ready) bits else None
      val request = valid && ready
      val replied = response && d.io.response.ready.peek().litToBoolean
      if (request) {
        pending shouldBe None
        val (address, data, mask) = bits.get
        (address & 3) shouldBe BigInt(0)
        mask should be > 0
        writes += bits.get
        val failed = writes.size - 1 == failAt
        if (!failed) for (lane <- 0 until 4 if (mask & (1 << lane)) != 0)
          memory(address + lane) = ((data >> (lane * 8)) & 255).toInt
        pending = Some((2, failed))
      } else if (replied) pending = None
      else pending = pending.map { case (left, failed) => (math.max(0, left - 1), failed) }
      d.clock.step(); clock += 1
    }
    def packet(bytes: Seq[Int]): Unit = {
      bytes.grouped(4).zipWithIndex.foreach { case (beat, index) =>
        d.io.in.valid.poke(true.B)
        d.io.in.bits.data.poke(beat.zipWithIndex.map { case (b, lane) => BigInt(b) << (8 * lane) }.sum.U)
        d.io.in.bits.bytes.poke((beat.size - 1).U)
        d.io.in.bits.last.poke((index * 4 + beat.size == bytes.size).B)
        d.io.in.bits.packetBytes.poke(bytes.size.U)
        var timeout = 0
        while (!d.io.in.ready.peek().litToBoolean && timeout < 150) { tick(); timeout += 1 }
        assert(timeout < 150, "SMEM input did not become ready")
        tick()
      }
      d.io.in.valid.poke(false.B)
    }
    def drain(): Unit = {
      var timeout = 0
      while (!d.io.empty.peek().litToBoolean && timeout < 250) { tick(); timeout += 1 }
      assert(timeout < 250, "SMEM did not drain memory responses")
    }
    def configure(start: BigInt, limit: BigInt, pointer: BigInt): Unit = {
      d.io.enable.poke(false.B); drain()
      d.io.configuration.bits.start.poke(start.U); d.io.configuration.bits.limit.poke(limit.U)
      d.io.configuration.bits.pointer.poke(pointer.U); d.io.configuration.valid.poke(true.B)
      d.io.configuration.ready.expect(true.B); tick(); d.io.configuration.valid.poke(false.B)
    }
  }

  it should "pack short adjacent packets with byte strobes and publish only complete acknowledged packets" in {
    test(new SMEMSinkCore(SMEMWindow(base, 64))).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val run = new Driver(d)
      println("[SMEM-core] 1/4 完整 packet staging，独立请求/应答反压，64-bit byte 地址")
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h04030201".U)
      d.io.in.bits.bytes.poke(3.U); d.io.in.bits.last.poke(false.B); d.io.in.bits.packetBytes.poke(7.U)
      run.tick(); d.io.in.valid.poke(false.B); run.tick(5)
      run.writes shouldBe empty
      d.io.enable.poke(false.B) // still finish the already admitted packet
      d.io.in.valid.poke(true.B); d.io.in.bits.data.poke("h070605".U)
      d.io.in.bits.bytes.poke(2.U); d.io.in.bits.last.poke(true.B)
      run.tick(); d.io.in.valid.poke(false.B)
      d.io.committed.expect(base.U)
      run.allow = false; run.tick(4); d.io.committed.expect(base.U)
      run.allow = true; run.drain()
      d.io.committed.expect((base + 8).U)
      (0 until 8).map(i => run.memory(base + i)) shouldBe (1 to 7) :+ 0
      d.io.enable.poke(true.B)
      run.packet(Seq(11, 12, 13)); run.drain(); d.io.committed.expect((base + 11).U)
      run.packet(Seq(14, 15, 16, 17, 18)); run.drain(); d.io.committed.expect((base + 16).U)
      (0 until 8).map(i => run.memory(base + 8 + i)) shouldBe (11 to 18)
      // Second packet shares the first packet's final memory word.
      run.writes.exists { case (address, _, mask) => address == base + 8 && mask == 8 } shouldBe true
      d.io.fault.expect(0.U)
      println("[SMEM-core] 2/4 相邻 packet 紧凑跨 word，旧 byte 不被覆盖；禁用仅在最后补 null")
      for (size <- Seq(1, 2, 3, 4, 7, 13, 33)) {
        run.configure(base, base + 60, base)
        d.io.enable.poke(true.B)
        val bytes = (1 to size).map(i => (i * 13 + size) & 255)
        run.packet(bytes); run.drain(); d.io.committed.expect((base + size).U)
        (0 until size).map(i => run.memory(base + i)) shouldBe bytes
        d.io.enable.poke(false.B); run.drain()
        d.io.committed.expect((base + ((size + 3) / 4) * 4).U)
        for (i <- size until ((size + 3) / 4) * 4) run.memory(base + i) shouldBe 0
      }
      println("[SMEM-core] 3/4 1..33-byte 边界 packet 与尾部补零、提交指针全部一致")
      d.io.fault.expect(0.U)
      println("[SMEM-core] 4/4 完成，无普通 backpressure 误报 fault")
    }
  }

  it should "reserve whole StopOnWrap packets, wrap exact fits and reject invalid configurations" in {
    test(new SMEMSinkCore(SMEMWindow(base, 64))).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val run = new Driver(d)
      run.configure(base, base + 8, base) // deliberately non power of two: 12 bytes
      d.io.enable.poke(true.B); d.io.stopOnWrap.poke(true.B)
      run.packet(1 to 7); run.drain(); val count = run.writes.size
      d.io.in.valid.poke(true.B); d.io.in.bits.packetBytes.poke(7.U)
      d.io.in.ready.expect(false.B); run.tick(2); d.io.in.valid.poke(false.B); run.drain()
      d.io.stop.expect(true.B); d.io.committed.expect((base + 8).U)
      run.writes.size shouldBe count + 1 // final padding only, no rejected packet bytes
      run.configure(base, base + 8, base)
      d.io.enable.poke(true.B); d.io.stopOnWrap.poke(true.B)
      run.packet(1 to 12); run.drain(); d.io.committed.expect(base.U); d.io.stop.expect(true.B)
      run.configure(base, base + 8, base + 8)
      d.io.enable.poke(true.B); d.io.stopOnWrap.poke(false.B)
      run.packet(1 to 7); run.drain(); d.io.committed.expect((base + 3).U)
      (0 until 4).map(i => run.memory(base + 8 + i)) shouldBe (1 to 4)
      (0 until 3).map(i => run.memory(base + i)) shouldBe (5 to 7)
      d.io.enable.poke(false.B); run.drain(); d.io.committed.expect((base + 4).U)
      for ((start, limit, ptr) <- Seq((base - 4, base + 8, base), (base, base + 64, base),
          (base, base + 8, base + 1), (base + 8, base, base + 8))) {
        run.configure(start, limit, ptr); d.io.fault.expect(1.U)
        val issued = run.writes.size; d.io.enable.poke(true.B); run.tick(4)
        run.writes.size shouldBe issued; run.configure(base, base + 8, base); d.io.fault.expect(0.U)
      }
      println("[SMEM-core] StopOnWrap 完整容量、精确占满、循环跨末端、非法窗口/指针对拍通过")
    }
  }

  it should "leave a failed packet unpublished and finish offered writes before aborting" in {
    test(new SMEMSinkCore(SMEMWindow(base, 64))).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val run = new Driver(d)
      run.packet(Seq(1, 2, 3)); run.drain(); d.io.committed.expect((base + 3).U)
      run.failAt = run.writes.size + 1
      run.packet(4 to 12); run.drain()
      d.io.fault.expect(2.U); d.io.committed.expect((base + 3).U)
      d.io.faultAddress.expect((base + 4).U)
      val before = run.writes.size; run.tick(8); run.writes.size shouldBe before
      println("[SMEM-core] 内存错误停止后续写，未完成 packet 不发布 WP；旧成功前缀保留")
      run.configure(base, base + 60, base); run.failAt = -1; d.io.enable.poke(true.B)
      run.allow = false; run.packet(21 to 27); run.tick(3)
      d.io.request.valid.expect(true.B)
      d.io.abort.poke(true.B); run.tick(3); d.io.empty.expect(false.B)
      d.io.abort.poke(false.B); run.allow = true; run.drain()
      d.io.committed.expect(base.U); d.io.fault.expect(0.U)
      run.writes.size shouldBe before + 1 // already offered word completes, later words discarded
      run.packet(31 to 36); run.drain(); d.io.committed.expect((base + 6).U)
      run.packet(41 to 47)
      while (run.pending.isEmpty) run.tick()
      d.io.abort.poke(true.B); run.tick(); d.io.abort.poke(false.B); run.drain()
      d.io.committed.expect(base.U)
      run.packet(Seq(51, 52)); run.drain(); d.io.committed.expect((base + 2).U)
      println("[SMEM-core] offer/应答期间 Active abort 排出旧事务后清状态，新 session 不接收旧应答")
    }
  }
}
