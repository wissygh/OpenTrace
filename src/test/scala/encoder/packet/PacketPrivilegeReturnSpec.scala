package trace.component

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Paths}

class PacketPrivilegeReturnSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "privilege synchronization after xret with a live implicit return stack"

  it should "synchronize the first destination instruction without inheriting the abandoned call stack" in {
    test(new PacketGenHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { d =>
      val stream = new PacketStreamDriver(d)
      val base = BigInt("80001000", 16)
      for (mode <- Seq(0, 8, 9, 24, 40, 56); (fromPriv, toPriv, opcode) <- Seq((3, 1, 0x30200073), (1, 0, 0x10200073))) {
        stream.initialize(options = mode)
        val entries = Seq(PacketInput(base, 9, privilege = fromPriv),
          PacketInput(base + 0x100, 3, privilege = fromPriv),
          PacketInput(base + 0x200, privilege = toPriv),
          PacketInput(base + 0x204, 5, privilege = toPriv),
          PacketInput(base + 0x20c, privilege = toPriv))
        stream.send(entries); stream.stop()
        // Format30 must describe the first destination instruction, even while
        // a call in the old privilege left a return-stack entry. xret itself
        // is an explicit discontinuity and disambiguates this frontier.
        val syncs = stream.frames.filter(f => (f(2) & 15) == 3)
        syncs.exists { f =>
          val bits = BigInt(1, f.drop(2).reverse.map(_.toByte).toArray)
          val address = ((bits >> 104) << 1) & ((BigInt(1) << 50) - 1)
          val privilege = ((bits >> 5) & 7).toInt
          address == base + 0x200 && privilege == toPriv
        } shouldBe true
        val root = Paths.get(sys.env("MILL_WORKSPACE_ROOT"))
          .resolve(s"build/validation/requirements13-xret/packet/$mode/$fromPriv-$toPriv")
        for (name <- Seq("workload", "dut", "ref", "diff")) Files.createDirectories(root.resolve(name))
        val code = Array.fill[Byte](0x210)(0)
        for ((offset, word) <- Map(0 -> 0x100000ef, 0x100 -> opcode, 0x200 -> 0x13,
          0x204 -> 0x463, 0x20c -> 0x13); i <- 0 until 4) code(offset + i) = ((word >> (i * 8)) & 255).toByte
        Files.write(root.resolve("workload/code.bin"), code)
        Files.write(root.resolve("dut/trace.bin"), stream.frames.flatten.map(_.toByte).toArray)
        Files.writeString(root.resolve("dut/trace.json"),
          s"""{"profile":{"return_stack_size":4},"sources":{"3":{"hart_id":7,"raw":"../workload/code.bin","base":"0x${base.toString(16)}"}}}""")
        Files.writeString(root.resolve("ref/expected.json"), entries.map(_.pc).mkString("[", ",", "]"))
        println(s"[xret-privilege] mode=$mode $fromPriv->$toPriv first destination sync, old call stack cleared")
      }
    }
  }
}
