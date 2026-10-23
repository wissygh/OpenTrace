package trace.component

import chisel3._
import chiseltest._
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import trace.interface._

private[component] case class PacketInput(pc: BigInt, kind: Int = 0, retired: Int = 2,
    privilege: Int = 3, context: BigInt = 0, ctype: Int = 0, time: BigInt = 0,
    cause: Int = 0, tval: BigInt = 0, notification: Boolean = false, lost: Boolean = false, halfword: Boolean = false)

/** Protocol driver shared by optional-format tests; expected packets live in
  * an independent normative model, never in this handshake driver.
  */
private[component] class PacketStreamDriver(d: PacketGenHarness) extends Matchers {
  d.clock.setTimeout(100000)
  val frames = ArrayBuffer.empty[Seq[Int]]
  private var cycles = 0
  private var held = Option.empty[(BigInt, Seq[BigInt])]
  private def tick(): Unit = {
    val valid = d.io.packet.valid.peek().litToBoolean
    val ready = d.io.packet.ready.peek().litToBoolean
    val bits = (d.io.packet.bits.len.peek().litValue, d.io.packet.bits.data.map(_.peek().litValue).toSeq)
    held.foreach { previous => valid shouldBe true; bits shouldBe previous }
    held = if (valid && !ready) Some(bits) else None
    if (valid && ready) frames += bits._2.take(bits._1.toInt).map(_.toInt)
    d.clock.step(); cycles += 1
  }
  private def ready(): Unit = d.io.packet.ready.poke((cycles % 11 > 2).B)
  private def drive(e: PacketInput): Unit = {
    d.io.block.valid.poke(true.B); d.io.block.iaddr.poke(e.pc.U); d.io.block.itype.poke(e.kind.U)
    d.io.block.iretire.poke(e.retired.U); d.io.block.ilastsize.poke((if (e.halfword) Ilastsize.HalfWord else Ilastsize.Word))
    d.io.block.priv.poke(e.privilege.U); d.io.block.time.poke(e.time.U)
    d.io.block.context.poke(e.context.U); d.io.block.ctype.poke(e.ctype.U)
    d.io.block.cause.poke(e.cause.U); d.io.block.tval.poke(e.tval.U)
    d.io.block.notifyRequested.poke(e.notification.B)
    d.io.block.traceLost.poke(e.lost.B)
  }
  def idle(n: Int = 12): Unit = {
    d.io.block.valid.poke(false.B)
    for (_ <- 0 until n) { ready(); tick() }
  }
  def stall(n: Int): Unit = {
    d.io.block.valid.poke(false.B); d.io.packet.ready.poke(false.B); d.io.fifoIsFull.poke(true.B)
    for (_ <- 0 until n) tick()
    d.io.fifoIsFull.poke(false.B)
  }
  def initialize(options: Int = 32): Unit = {
    frames.clear(); held = None; cycles = 0
    d.io.teCtrl.poke((6 | (15 << 20)).U); d.io.teInstFeat.poke(((3 << 16) | options).U)
    d.io.teDataCtrl.poke(0.U); d.io.filterRes.poke(true.B); d.io.fifoIsFull.poke(false.B)
    d.io.syncReq.poke(false.B); d.io.flushReq.poke(false.B); d.io.stopReq.poke(false.B)
    d.io.block.trigger.poke(TriggerAction.None); d.io.block.traceOffAfter.poke(false.B)
    d.io.block.traceLost.poke(false.B); d.io.block.hartIsReset.poke(false.B); d.io.block.impdef.poke(0.U)
    drive(PacketInput(0)); d.io.block.valid.poke(false.B); d.io.packet.ready.poke(true.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); idle()
    frames.toSeq shouldBe Seq(ETraceReference.support(3, fullAddress = (options & 1) != 0,
      jumpCache = (options & 32) != 0, branchPrediction = (options & 16) != 0, implicitReturn = (options & 8) != 0))
  }
  def send(entries: Seq[PacketInput]): Unit = entries.foreach { e =>
    drive(e)
    var accepted = false
    var remaining = 200
    while (!accepted && remaining > 0) {
      ready(); accepted = d.io.blockReady.peek().litToBoolean; tick(); remaining -= 1
    }
    assert(accepted, s"packet input timeout at 0x${e.pc.toString(16)}")
  }
  def stop(): Unit = {
    d.io.block.valid.poke(false.B); d.io.stopReq.poke(true.B); ready(); tick(); d.io.stopReq.poke(false.B)
    idle(32); d.io.empty.expect(true.B)
  }
  def requestSync(): Unit = {
    d.io.block.valid.poke(false.B); d.io.syncReq.poke(true.B); ready(); tick(); d.io.syncReq.poke(false.B)
    idle(4)
  }
}
