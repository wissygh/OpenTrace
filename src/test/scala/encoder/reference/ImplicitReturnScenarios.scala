package trace.component

/** Raw instruction fixtures and independently enumerated dynamic retirement.
  * Shared stimulus only: no production codec or decoder computes these PCs.
  */
private[component] object ImplicitReturnScenarios {
  val base = BigInt("80001000", 16)
  case class Scenario(name: String, entries: Seq[PacketInput], code: Array[Byte], pcs: Seq[BigInt], xlen: Int = 64)
  def pc(offset: Int): BigInt = base + offset
  def all: Seq[Scenario] = {
    def scenario(name: String, inputs: Seq[PacketInput], words: Map[Int, Int],
        halves: Map[Int, Int] = Map.empty, pcs: Seq[BigInt] = Seq.empty, xlen: Int = 64): Scenario = {
      val size = (words.keys.map(_ + 4) ++ halves.keys.map(_ + 2)).max
      val code = Array.fill[Byte](size)(0)
      for ((offset, word) <- words; i <- 0 until 4) code(offset + i) = ((word >> (8 * i)) & 255).toByte
      for ((offset, half) <- halves; i <- 0 until 2) code(offset + i) = ((half >> (8 * i)) & 255).toByte
      Scenario(name, inputs, code, if (pcs.nonEmpty) pcs else inputs.filter(_.retired > 0).map(_.pc), xlen)
    }
    def input(offset: Int, kind: Int = 0): PacketInput = PacketInput(pc(offset), kind)
    val nested = Seq(input(0, 9), input(0x100, 9), input(0x200, 13), input(0x104, 13), input(4), input(8))
    val nestedCode = Map(0 -> 0x100000ef, 0x100 -> 0x100000ef, 0x200 -> 0x8067, 0x104 -> 0x8067, 4 -> 0x13, 8 -> 0x13)
    val recursive = Seq(input(0, 9), input(0x100, 4), input(0x104, 9), input(0x100, 4), input(0x104, 9),
      input(0x100, 5), input(0x108, 13), input(0x108, 13), input(0x108, 13), input(4), input(8))
    val recursiveCode = Map(0 -> 0x100000ef, 0x100 -> 0x463, 0x104 -> 0xffdff0ef, 0x108 -> 0x8067, 4 -> 0x13, 8 -> 0x13)
    val branchInputs = (0 until 64).map(i => input(0x100 + i * 4, 4))
    val branchCode = (0 until 64).map(i => (0x100 + i * 4) -> 0x1463).toMap
    val deep = (0 until 21).map(i => input(i * 0x100, 9)) ++ Seq(input(0x1500, 13)) ++
      (20 to 1 by -1).map(i => input(i * 0x100 + 4, 13)) ++ Seq(input(4), input(8))
    val deepCode = (0 until 21).map(i => (i * 0x100) -> 0x100000ef).toMap ++
      (1 to 20).map(i => (i * 0x100 + 4) -> 0x8067).toMap ++ Map(0x1500 -> 0x8067, 4 -> 0x13, 8 -> 0x13)
    Seq(
      scenario("nested", nested, nestedCode),
      scenario("modified", Seq(input(0, 9), input(0x100, 9), input(0x200, 13), input(0x300), input(0x304)),
        Map(0 -> 0x100000ef, 0x100 -> 0x100000ef, 0x200 -> 0x8067, 0x300 -> 0x13, 0x304 -> 0x13)),
      scenario("empty-tail", Seq(input(0, 11), input(0x100, 13), input(0x200), input(0x204)),
        Map(0 -> 0x1000006f, 0x100 -> 0x8067, 0x200 -> 0x13, 0x204 -> 0x13)),
      scenario("coroutine", Seq(input(0, 9), input(0x100, 12), input(0x200, 13), input(4), input(8)),
        Map(0 -> 0x100000ef, 0x100 -> 0x82e7, 0x200 -> 0x8067, 4 -> 0x13, 8 -> 0x13)),
      scenario("compressed", Seq(PacketInput(pc(0), 9, retired = 1, halfword = true),
        PacketInput(pc(4), 13, retired = 1, halfword = true), PacketInput(pc(2), retired = 1, halfword = true)),
        Map.empty, Map(0 -> 0x2011, 4 -> 0x8082, 2 -> 1), xlen = 32),
      scenario("x5", Seq(input(0, 9), PacketInput(pc(0x100), 13, retired = 1, halfword = true), input(4), input(8)),
        Map(0 -> 0x100002ef, 4 -> 0x13, 8 -> 0x13), Map(0x100 -> 0x8282)),
      scenario("multiple", Seq(PacketInput(pc(0), 9, retired = 4), input(0x100, 13), input(8), input(12)),
        Map(0 -> 0x13, 4 -> 0xfc000ef, 0x100 -> 0x8067, 8 -> 0x13, 12 -> 0x13),
        pcs = Seq(0, 4, 0x100, 8, 12).map(pc)),
      scenario("recursive", recursive, recursiveCode),
      scenario("recursive-stop", recursive.take(8), recursiveCode),
      scenario("recursive-notify", recursive.zipWithIndex.map { case (e, i) =>
        if (i == 7) e.copy(notification = true) else e
      }, recursiveCode),
      scenario("context", recursive.zipWithIndex.map { case (e, i) =>
        if (i == 7) e.copy(context = 1, ctype = 1) else if (i > 7) e.copy(context = 1) else e
      }, recursiveCode),
      scenario("precise-context", nested.zipWithIndex.map { case (e, i) =>
        if (i == 3) e.copy(context = 1, ctype = 2) else if (i > 3) e.copy(context = 1) else e
      }, nestedCode),
      scenario("trap", Seq(input(0, 9), input(0x100), PacketInput(pc(0x104), 1, retired = 0, cause = 11),
        input(0x200), input(0x204, 3), input(0x108, 13), input(4), input(8)),
        Map(0 -> 0x100000ef, 0x100 -> 0x13, 0x104 -> 0x73, 0x200 -> 0x13, 0x204 -> 0x30200073,
          0x108 -> 0x8067, 4 -> 0x13, 8 -> 0x13)),
      scenario("overflow", deep, deepCode),
      scenario("cache", Seq(input(0), input(4, 8), input(0x100), input(0x104, 13), input(8),
        input(12, 8), input(0x100), input(0x104, 13), input(16), input(20, 8), input(0x100),
        input(0x104, 13), input(24), input(28)),
        Map(0 -> 0x13, 4 -> 0x300e7, 8 -> 0x13, 12 -> 0x300e7, 16 -> 0x13, 20 -> 0x300e7,
          24 -> 0x13, 28 -> 0x13, 0x100 -> 0x13, 0x104 -> 0x8067)),
      scenario("prediction", Seq(input(0, 9)) ++ branchInputs ++ Seq(input(0x200, 13), input(4), input(8)),
        branchCode ++ Map(0 -> 0x100000ef, 0x200 -> 0x8067, 4 -> 0x13, 8 -> 0x13)),
      scenario("prediction-failure", Seq(input(0, 9)) ++ branchInputs ++ Seq(input(0x200, 5), input(0x208, 13), input(4), input(8)),
        branchCode ++ Map(0 -> 0x100000ef, 0x200 -> 0x463, 0x208 -> 0x8067, 4 -> 0x13, 8 -> 0x13))
    )
  }
}
