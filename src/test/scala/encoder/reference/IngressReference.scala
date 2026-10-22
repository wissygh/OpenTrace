package trace.component

import scala.collection.mutable

/** Architectural retirement-group model. It has no Chisel or RTL helper imports.
  * Ordinary adjacent groups form one range, ending at a discontinuity/notify or
  * at the end of the retirement cycle. A finite queue preserves metadata and
  * inserts an ordered gap before admitting any post-overflow group.
  */
private[component] object IngressReference {
  case class Group(pc: BigInt, kind: Int, halfwords: Int, lastSize: Int, notification: Boolean)
  case class Metadata(privilege: Int = 3, time: BigInt = 0, context: BigInt = 0, ctype: Int = 0,
      cause: Int = 0, tval: BigInt = 0, impdef: BigInt = 0, hartReset: Boolean = false)
  case class Cycle(groups: Seq[Group], metadata: Metadata, traceOn: Boolean = false, traceOff: Boolean = false)
  case class Entry(group: Group, metadata: Metadata, trigger: Int = 0, offAfter: Boolean = false, lost: Boolean = false)
  case class Observation(head: Option[Entry], empty: Boolean, full: Boolean, dropped: Int)

  def compact(cycle: Cycle): Seq[Entry] = {
    val ranges = mutable.ArrayBuffer.empty[Group]
    var first = Option.empty[BigInt]
    var size = 0
    for ((group, index) <- cycle.groups.zipWithIndex) {
      if (first.isEmpty) first = Some(group.pc)
      size += group.halfwords
      if (group.kind != 0 || group.notification || index == cycle.groups.size - 1) {
        ranges += group.copy(pc = first.get, halfwords = size)
        first = None; size = 0
      }
    }
    if (ranges.isEmpty && (cycle.traceOn || cycle.traceOff)) ranges += Group(0, 0, 0, 0, false)
    ranges.zipWithIndex.map { case (group, i) =>
      val trigger = if (cycle.traceOn && i == 0) 1 else if (cycle.traceOff && i == ranges.size - 1) 2
        else if (group.notification) 3 else 0
      Entry(group, cycle.metadata.copy(ctype = if (i == 0) cycle.metadata.ctype else 0), trigger,
        cycle.traceOn && cycle.traceOff && ranges.size == 1)
    }.toSeq
  }

  class Buffer(depth: Int, reserve: Int) {
    private val entries = mutable.Queue.empty[Entry]
    private var pendingGap = false
    private var pendingTrigger = 0
    def reset(): Unit = { entries.clear(); pendingGap = false; pendingTrigger = 0 }
    def step(cycle: Cycle, consume: Boolean): Observation = {
      val batch = compact(cycle)
      val available = depth - entries.size + (if (consume && entries.nonEmpty) 1 else 0)
      val accepted = !pendingGap && batch.size <= available
      val dropped = if (accepted) 0 else batch.size
      val result = Observation(entries.headOption, entries.isEmpty && !pendingGap,
        pendingGap || depth - entries.size < reserve, dropped)
      if (consume && entries.nonEmpty) entries.dequeue()
      val currentTrigger = if (cycle.traceOff) 2 else if (cycle.traceOn) 1 else 0
      val gapTrigger = if (currentTrigger != 0) currentTrigger else pendingTrigger
      if (pendingGap && available > 0) {
        entries.enqueue(Entry(Group(0, 0, 0, 0, false), Metadata(privilege = 0), trigger = gapTrigger, lost = true))
        pendingGap = false; pendingTrigger = 0
      } else if (accepted) entries.enqueueAll(batch)
      else if (dropped > 0) { pendingGap = true; pendingTrigger = gapTrigger }
      result
    }
  }
}
