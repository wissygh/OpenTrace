package trace.component

/** Explicit retired-PC fixtures. Faulting instructions and unretired handlers
  * are excluded independently of the packet decoder.
  */
private[component] object ImplicitExceptionScenarios {
  val base = BigInt("80001000", 16)
  val modes = Seq(0, 2, 3, 10, 18, 34, 58, 59)
  case class Trap(cause: Int, interrupt: Boolean = false, handler: Boolean = true, tval: BigInt = 0)
  case class Scenario(name: String, entries: Seq[PacketInput], words: Map[Int, Int],
      pcs: Seq[Int], vector: BigInt, traps: Seq[Trap]) {
    def code: Array[Byte] = {
      val bytes = Array.fill[Byte](words.keys.max + 4)(0)
      for ((offset, word) <- words; i <- 0 until 4) bytes(offset + i) = ((word >> (8 * i)) & 255).toByte
      bytes
    }
    def expected: Seq[BigInt] = pcs.map(base + _)
    def config(source: Int, discovered: Boolean): String = {
      val profile = if (discovered) "\"discovery\":\"discovery.bin\"" else "\"profile\":{\"return_stack_size\":4}"
      s"""{$profile,"sources":{"$source":{"hart_id":0,"raw":"../workload/code.bin","base":"0x${base.toString(16)}","trap_vectors":{"3":"0x${vector.toString(16)}"}}}}"""
    }
    def trapReference: String = traps.map { t =>
      s"""{"ecause":${t.cause},"interrupt":${t.interrupt},"thaddr":${if (t.handler) 1 else 0},"tval":"0x${t.tval.toString(16)}"}"""
    }.mkString("[", ",", "]")
  }
  private def input(offset: Int, kind: Int = 0, retired: Int = 2, cause: Int = 0,
      value: BigInt = 0, privilege: Int = 3): PacketInput =
    PacketInput(base + offset, kind, retired = retired, cause = cause, tval = value, privilege = privilege)

  def all: Seq[Scenario] = {
    val vector = base + 0x1000
    val highTval = (BigInt(1) << 49) | BigInt("123456789", 16)
    Seq(
      Scenario("direct-exception", Seq(input(0), input(4, 1, 0, 2, highTval), input(0x1000), input(0x1004)),
        Map(0 -> 0x13, 4 -> -1, 0x1000 -> 0x13, 0x1004 -> 0x13), Seq(0, 0x1000, 0x1004), vector,
        Seq(Trap(2, tval = highTval))),
      Scenario("vectored-interrupt", Seq(input(0), input(4, 2, 0, 7), input(0x101c), input(0x1020)),
        Map(0 -> 0x13, 0x101c -> 0x13, 0x1020 -> 0x13), Seq(0, 0x101c, 0x1020), vector | 1,
        Seq(Trap(7, interrupt = true))),
      Scenario("direct-interrupt", Seq(input(0), input(4, 2, 0, 11), input(0x1000), input(0x1004)),
        Map(0 -> 0x13, 0x1000 -> 0x13, 0x1004 -> 0x13), Seq(0, 0x1000, 0x1004), vector,
        Seq(Trap(11, interrupt = true))),
      Scenario("vectored-exception", Seq(input(0), input(4, 1, 0, 11), input(0x1000), input(0x1004)),
        Map(0 -> 0x13, 4 -> 0x73, 0x1000 -> 0x13, 0x1004 -> 0x13), Seq(0, 0x1000, 0x1004), vector | 1,
        Seq(Trap(11))),
      Scenario("indirect-epc", Seq(input(0, 10), input(0x800, 1, 0, 12, base + 0x800), input(0x1000), input(0x1004)),
        Map(0 -> 0x28067, 0x1000 -> 0x13, 0x1004 -> 0x13), Seq(0, 0x1000, 0x1004), vector,
        Seq(Trap(12, handler = false, tval = base + 0x800))),
      Scenario("first-epc", Seq(input(0x800, 1, 0, 12, base + 0x800), input(0x1000), input(0x1004)),
        Map(0x1000 -> 0x13, 0x1004 -> 0x13), Seq(0x1000, 0x1004), vector,
        Seq(Trap(12, handler = false, tval = base + 0x800))),
      Scenario("consecutive-unretired", Seq(input(0), input(4, 1, 0, 2, 4),
        input(0x800, 1, 0, 12, base + 0x800, privilege = 1), input(0x1000), input(0x1004)),
        Map(0 -> 0x13, 4 -> -1, 0x1000 -> 0x13, 0x1004 -> 0x13), Seq(0, 0x1000, 0x1004), vector,
        Seq(Trap(2, handler = false, tval = 4), Trap(12, handler = false, tval = base + 0x800))),
      Scenario("retired-prefix", Seq(input(0, 1, 6, 2, base + 12), input(0x1000), input(0x1004)),
        Map(0 -> 0x13, 4 -> 0x13, 8 -> 0x13, 12 -> -1, 0x1000 -> 0x13, 0x1004 -> 0x13),
        Seq(0, 4, 8, 0x1000, 0x1004), vector, Seq(Trap(2, tval = base + 12))),
      Scenario("handler-branch", Seq(input(0), input(4, 2, 0, 7), input(0x1000, 5), input(0x1008), input(0x100c)),
        Map(0 -> 0x13, 0x1000 -> 0x463, 0x1008 -> 0x13, 0x100c -> 0x13), Seq(0, 0x1000, 0x1008, 0x100c), vector,
        Seq(Trap(7, interrupt = true))),
      Scenario("stop-before-handler", Seq(input(0), input(4, 1, 0, 2, 4)),
        Map(0 -> 0x13, 4 -> -1), Seq(0), vector, Seq(Trap(2, handler = false, tval = 4)))
    )
  }
}
