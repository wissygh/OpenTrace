package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InstructionReference._

class InstructionReferenceSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "independent instruction and control-event reference"
  for (seed <- Seq(2, 17, 83)) {
    it should s"compare random blocks traps qualification configuration and requested synchronization seed $seed" in {
      test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
        val driver = new PacketStreamDriver(d)
        driver.initialize(options = 0)
        val interval = if (seed == 17) 0 else 15
        d.io.teCtrl.poke((6 | (interval << 20)).U)
        val model = new Model(3, syncMax = interval)
        val random = new scala.util.Random(seed)
        var instructions = 0
        def send(b: Block): Unit = {
          d.io.filterRes.poke(b.qualified.B)
          driver.send(Seq(PacketInput(b.pc, kind = b.kind, retired = b.halfwords, privilege = b.privilege,
            context = b.context, ctype = b.ctype, time = b.time, cause = b.cause, tval = b.tval,
            notification = b.notification)))
          model.block(b); instructions += 1
        }
        val base = BigInt("80010000", 16)
        var context = BigInt(0)
        var fullAddress = false
        for (epoch <- 0 until 36) {
          val pc = base + epoch * 0x1000
          val branches = 1 + random.nextInt(70)
          val priv = Seq(0, 1, 3, 5, 6)(epoch % 5)
          send(Block(pc, privilege = priv, context = context, halfwords = 3, lastSize = 0))
          for (i <- 0 until branches) {
            send(Block(pc + 8 + i * 8, kind = if (random.nextBoolean()) 4 else 5, privilege = priv,
              context = context, halfwords = if (i % 5 == 0) 3 else 2))
          }
          if (epoch % 9 == 0) for (i <- 0 until 24)
            send(Block(pc + 0x250 + 4 * i, privilege = priv, context = context, notification = true))
          send(Block(pc + 0x300, privilege = priv, context = context, notification = true))
          if (epoch % 3 == 0) {
            driver.requestSync(); model.sync()
          }
          send(Block(pc + 0x304, kind = 8, privilege = priv, context = context))
          context += 1
          send(Block(pc + 0x400, privilege = priv, context = context, ctype = 1, time = epoch * 0x100L))
          send(Block(pc + 0x404, privilege = priv, context = context,
            ctype = if (epoch % 7 == 0) 3 else if (epoch % 2 == 0) 2 else 0))
          // Both deferred handler reporting and an immediate EPC after a jump.
          send(Block(pc + 0x408, kind = if (epoch % 2 == 0) 1 else 2, halfwords = 0,
            privilege = priv, context = context, cause = epoch % 16, tval = pc + 0x80))
          if (epoch % 4 == 0) send(Block(pc + 0x40c, kind = 1, halfwords = 0,
            privilege = priv, context = context, cause = 12, tval = pc + 0x84))
          send(Block(pc + 0x500, privilege = 3, context = context))
          send(Block(pc + 0x504, kind = 3, privilege = 3, context = context))
          send(Block(pc + 0x600, privilege = priv, context = context))
          send(Block(pc + 0x604, privilege = priv, context = context, notification = true))
          if (epoch % 3 == 0) {
            send(Block(pc + 0x608, privilege = priv, context = context, qualified = false))
          } else if (epoch % 3 == 1) {
            d.io.filterRes.poke(true.B)
            driver.send(Seq(PacketInput(0, retired = 0, lost = true))); model.loss()
          } else {
            // A configuration event closes the current segment before its new options.
            fullAddress = !fullAddress
            d.io.teInstFeat.poke(((3 << 16) | (if (fullAddress) 1 else 0)).U)
            driver.idle(48); model.configure(fullAddress)
          }
          d.io.filterRes.poke(true.B)
          driver.idle(32)
          withClue(s"seed=$seed epoch=$epoch: ") { driver.frames.toSeq shouldBe model.packets.toSeq }
        }
        send(Block(base + 0x30000, context = context, ctype = 3))
        send(Block(base + 0x30004, context = context))
        driver.stop(); model.close()
        driver.frames.toSeq shouldBe model.packets.toSeq
        println(s"[instruction-reference] seed=$seed ${instructions}个输入block/${model.packets.size}包，随机分支/多指令压缩尾/trap/handler/xret/context/过滤/loss/配置/SYNCREQ/stop逐byte一致；PC>0x80000000")
      }
    }
  }
}
