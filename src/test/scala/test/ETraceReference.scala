package trace.component

/** Test-only normative wire model, independent of Chisel and the RTL pipeline. */
object ETraceReference {
  val AddressWidth = 50
  val AlignedWidth = AddressWidth - 1
  val Mask = (BigInt(1) << AddressWidth) - 1

  def payload(fields: Seq[(BigInt, Int)]): Seq[Int] = {
    var bits = 0
    var value = BigInt(0)
    fields.foreach { case (field, width) =>
      value |= (field & ((BigInt(1) << width) - 1)) << bits
      bits += width
    }
    if (value.testBit(bits - 1)) value -= BigInt(1) << bits
    var bytes = (bits + 7) / 8
    while (bytes > 1 && value >= -(BigInt(1) << (8 * bytes - 9)) && value < (BigInt(1) << (8 * bytes - 9)))
      bytes -= 1
    (0 until bytes).map(i => ((value >> (8 * i)) & 255).toInt)
  }

  def frame(source: Int, body: Seq[Int]): Seq[Int] = Seq(body.size, source) ++ body

  def synchronization(source: Int, pc: BigInt, taken: Boolean = false,
      privilege: Int = 3, time: BigInt = 0, context: BigInt = 0): Seq[Int] =
    frame(source, payload(Seq[(BigInt, Int)]((3, 2), (0, 2), (if (taken) 0 else 1, 1),
      (privilege, 3), (time, 64), (context, 32), (pc >> 1, AlignedWidth))))

  def support(source: Int, fullAddress: Boolean = false, status: Int = 0, tracing: Boolean = true,
      jumpCache: Boolean = false, branchPrediction: Boolean = false): Seq[Int] =
    frame(source, payload(Seq((BigInt(3), 2), (BigInt(3), 2), (if (tracing) BigInt(1) else BigInt(0), 1),
      (BigInt(0), 1), (BigInt(status), 2),
      (BigInt((if (fullAddress) 32 else 0) | (if (jumpCache) 16 else 0) | (if (branchPrediction) 8 else 0)), 6),
      (BigInt(0), 1), (BigInt(0), 1), (BigInt(0), 2))))

  def trap(source: Int, pc: BigInt, cause: Int, tval: BigInt = 0, interrupt: Boolean = false,
      handler: Boolean = true, privilege: Int = 3, context: BigInt = 0,
      time: BigInt = 0, taken: Boolean = false): Seq[Int] = {
    val fields = Seq[(BigInt, Int)]((3, 2), (1, 2), (if (taken) 0 else 1, 1), (privilege, 3),
      (time, 64), (context, 32), (cause, 8), (if (interrupt) 1 else 0, 1),
      (if (handler) 1 else 0, 1), (pc >> 1, AlignedWidth))
    frame(source, payload(fields ++ (if (interrupt) Seq.empty else Seq((tval, AddressWidth)))))
  }

  def context(source: Int, value: BigInt, time: BigInt = 0, privilege: Int = 3): Seq[Int] =
    frame(source, payload(Seq[(BigInt, Int)]((3, 2), (2, 2), (privilege, 3), (time, 64), (value, 32))))

  def address(source: Int, pc: BigInt, reference: BigInt, outcomes: Seq[Boolean] = Seq.empty,
      notifyRequested: Boolean = false, beforeSync: Boolean = false, fullAddress: Boolean = false): Seq[Int] = {
    require(outcomes.size <= 31)
    val aligned = ((if (fullAddress) pc else pc - reference) & Mask) >> 1
    val n = aligned.testBit(AlignedWidth - 1) ^ notifyRequested
    val u = n ^ beforeSync
    val prefix = if (outcomes.isEmpty) Seq((BigInt(2), 2)) else {
      val width = Seq(1, 3, 7, 15, 31).find(_ >= outcomes.size).get
      val map = outcomes.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (taken, i)) =>
        if (taken) bits else bits.setBit(i)
      }
      Seq((BigInt(1), 2), (BigInt(outcomes.size), 5), (map, width))
    }
    frame(source, payload(prefix ++ Seq((aligned, AlignedWidth),
      (if (n) BigInt(1) else BigInt(0), 1), (if (u) BigInt(1) else BigInt(0), 1))))
  }

  def branchOnly(source: Int, outcomes: Seq[Boolean]): Seq[Int] = {
    require(outcomes.size == 31)
    val map = outcomes.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (taken, i)) =>
      if (taken) bits else bits.setBit(i)
    }
    frame(source, payload(Seq((BigInt(1), 2), (BigInt(0), 5), (map, 31))))
  }

  def cachedTarget(source: Int, pc: BigInt, outcomes: Seq[Boolean] = Seq.empty): Seq[Int] = {
    require(outcomes.size <= 31)
    val map = outcomes.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (taken, i)) =>
      if (taken) bits else bits.setBit(i)
    }
    val fields = Seq[(BigInt, Int)]((0, 2), (1, 1), ((pc >> 1) & 255, 8), (outcomes.size, 5))
    frame(source, payload(fields ++ (if (outcomes.isEmpty) Seq.empty else
      Seq((map, Seq(1, 3, 7, 15, 31).find(_ >= outcomes.size).get)))))
  }
}
