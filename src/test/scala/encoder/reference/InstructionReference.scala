package trace.component

import scala.collection.mutable.ArrayBuffer

/** Batch reference for the baseline E-Trace instruction profile. Inputs are
  * logical retirement ranges and explicit control events, not RTL pipeline
  * states. The model deliberately does not model ready/valid timing.
  * Optional Format0 algorithms have their separate normative models/tests.
  */
private[component] object InstructionReference {
  case class Block(pc: BigInt, kind: Int = 0, halfwords: Int = 2, lastSize: Int = 1,
      privilege: Int = 3, context: BigInt = 0, ctype: Int = 0, time: BigInt = 0,
      cause: Int = 0, tval: BigInt = 0, notification: Boolean = false, qualified: Boolean = true)
  private case class Logical(block: Block, syncRequest: Boolean = false)

  class Model(source: Int, syncMax: Int = 15, initialFullAddress: Boolean = false) {
    val packets = ArrayBuffer.empty[Seq[Int]]
    private var fullAddress = initialFullAddress
    private val segment = ArrayBuffer.empty[Logical]
    packets += ETraceReference.support(source, fullAddress = fullAddress)
    private def expand(b: Block): Seq[Logical] = {
      val trap = b.kind == 1 || b.kind == 2
      if (trap && b.halfwords != 0) {
        val retired = expand(b.copy(kind = 0))
        retired :+ Logical(b.copy(pc = b.pc + 2 * b.halfwords, halfwords = 0, ctype = 0, notification = false))
      } else if (b.halfwords > (1 << b.lastSize)) {
        Seq(Logical(b.copy(kind = 0, halfwords = 1 << b.lastSize, notification = false)),
          Logical(b.copy(pc = b.pc + 2 * (b.halfwords - (1 << b.lastSize)),
            halfwords = 1 << b.lastSize, ctype = 0)))
      } else Seq(Logical(b))
    }
    def block(b: Block): Unit = {
      if (!b.qualified) close(status = 1)
      else {
        // Asynchronous changes explicitly terminate the previous execution segment.
        if (b.ctype == 3) close(status = 1)
        segment ++= expand(b)
      }
    }
    def sync(): Unit = if (segment.nonEmpty) segment(segment.size - 1) = segment.last.copy(syncRequest = true)
    def loss(): Unit = { flush(ending = false); packets += ETraceReference.support(source, fullAddress = fullAddress, status = 2) }
    def configure(nextFullAddress: Boolean): Unit = {
      if (fullAddress != nextFullAddress) {
        val hadTrace = segment.nonEmpty
        flush(); fullAddress = nextFullAddress
        packets += ETraceReference.support(source, fullAddress = fullAddress, status = if (hadTrace) 1 else 0)
      }
    }
    def close(status: Int = 1): Unit = if (segment.nonEmpty) {
      flush(); packets += ETraceReference.support(source, fullAddress = fullAddress, status = status)
    }
    private def flush(ending: Boolean = true): Unit = {
      if (segment.isEmpty) return
      val input = segment.toVector
      var reference = BigInt(0)
      var history = Vector.empty[Boolean]
      var sinceSync = 0
      var force = false
      var priorTrap = Option.empty[Block]
      var trapReported = false
      def isTrap(b: Block): Boolean = b.kind == 1 || b.kind == 2
      def isJump(b: Block): Boolean = Set(3, 8, 10, 12, 13, 14)(b.kind)
      def boundary(b: Block, next: Block): Boolean = b.privilege != next.privilege || next.ctype == 2 || next.ctype == 3
      def emit(packet: Seq[Int], anchor: Option[BigInt] = None, sync: Boolean = false): Unit = {
        packets += packet
        sinceSync = if (sync) 0 else sinceSync + 1
        anchor.foreach(reference = _)
        if (sync) force = false
      }
      def address(b: Block, beforeSync: Boolean = false): Unit = {
        emit(ETraceReference.address(source, b.pc, reference, history,
          notifyRequested = b.notification, beforeSync = beforeSync, fullAddress = fullAddress), Some(b.pc))
        history = Vector.empty
      }
      def synchronize(b: Block): Unit = {
        emit(ETraceReference.synchronization(source, b.pc, taken = b.kind == 5,
          privilege = b.privilege, context = b.context, time = b.time), Some(b.pc), sync = true)
        history = Vector.empty
      }
      def trap(b: Block, address: Block, handler: Boolean): Unit = {
        emit(ETraceReference.trap(source, address.pc, b.cause, b.tval, interrupt = b.kind == 2,
          handler = handler, privilege = address.privilege, context = address.context,
          time = address.time, taken = address.kind == 5), Some(address.pc), sync = true)
        history = Vector.empty
      }
      for ((logical, index) <- input.zipWithIndex) {
        val b = logical.block
        val previous = input.lift(index - 1).map(_.block)
        val next = input.lift(index + 1).map(_.block)
        val end = next.isEmpty
        force ||= logical.syncRequest
        val sync = index == 0 || previous.exists(boundary(_, b)) || force || sinceSync >= (1 << (syncMax + 4))
        val pendingBefore = history.nonEmpty
        if (Set(4, 5)(b.kind)) history :+= b.kind == 5
        if (priorTrap.nonEmpty) {
          val old = priorTrap.get
          if (isTrap(b)) {
            if (!trapReported) trap(old, old, handler = false)
            trap(b, b, handler = false); trapReported = true; priorTrap = Some(b)
          } else {
            if (trapReported) synchronize(b) else trap(old, b, handler = true)
            priorTrap = None; trapReported = false
          }
        } else if (isTrap(b)) {
          if (previous.isEmpty || previous.exists(isJump) || (end && ending)) { trap(b, b, handler = false); trapReported = true }
          priorTrap = Some(b)
        } else if (sync && !pendingBefore) synchronize(b)
        else {
          if (b.ctype == 1) emit(ETraceReference.context(source, b.context, time = b.time, privilege = b.privilege))
          if (previous.exists(isJump)) address(b, beforeSync = (end && ending) || next.exists(n => isTrap(n) || boundary(b, n)) || sync)
          else if (b.notification || end || sync || next.exists(n => isTrap(n) || boundary(b, n))) address(b)
          else if (history.size == 31) { emit(ETraceReference.branchOnly(source, history)); history = Vector.empty }
        }
      }
      segment.clear()
    }
  }
}
