package trace.interface

import chisel3._
import chisel3.util._
import trace.TraceParameters
import utils._

class FromCore extends Bundle with TraceParameters {
  val priv        = Priv()
  val time        = UInt(Xlen.W)
  val context     = UInt(contextWidth.W)
  val ctype       = UInt(2.W)
  val cause       = UInt(CauseWidth.W)
  val tval        = UInt(TvalWidth.W)
  val impdef      = UInt(ImpdefWidth.W)
  val hartIsReset = Bool()
  val trigger = new Bundle {
    val traceOn  = Bool()
    val traceOff = Bool()
  }
  val blocks = Vec(
    BlockNum,
    ValidIO(new Bundle {
      val iaddr      = UInt(IaddrWidth.W)
      val itype      = UInt(ItypeWidth.W)
      val iretire    = UInt(IretireWidthCompressed.W)
      val ilastsize  = UInt(IlastsizeWidth.W)
      val sijump     = Option.when(supportSijump)(Bool())
      val trigNotify = Bool()
    })
  )
}

class ToCore extends Bundle {
  val enable = Bool()
  val stall  = Bool()
}

class BlockBufferEntry extends Bundle with TraceParameters {
  // side band signals
  val priv            = Priv()
  val time            = UInt(Xlen.W)
  val context         = UInt(32.W)
  val ctype           = UInt(2.W)
  val cause           = UInt(CauseWidth.W)
  val tval            = UInt(TvalWidth.W)
  val impdef          = UInt(ImpdefWidth.W)
  val hartIsReset     = Bool()
  val trigger         = UInt(2.W) // None, traceOn, traceOff, notify
  val traceOffAfter   = Bool()    // simultaneous trace-on/off: trace this entire block, then stop
  val notifyRequested = Bool()    // notification is independent of trace-on/off actions
  val traceLost       = Bool()    // ordered gap marker, never an instruction
  // group signals of a block
  val valid     = Bool()
  val iaddr     = UInt(IaddrWidth.W)
  val itype     = UInt(ItypeWidth.W)
  val iretire   = UInt(IretireWidthCompressed.W)
  val ilastsize = UInt(IlastsizeWidth.W)
}

object Itype extends NamedUInt(4) {
  def None                 = 0.U
  def Exception            = 1.U  // rob
  def Interrupt            = 2.U  // rob
  def ExpIntReturn         = 3.U  // rename
  def NonTaken             = 4.U  // commit
  def Taken                = 5.U  // commit
  def UninferableJump      = 6.U  // It's reserved when width of itype is 4.
  def reserved             = 7.U  // reserved
  def UninferableCall      = 8.U  // rename
  def InferableCall        = 9.U  // rename
  def UninferableTailCall  = 10.U // rename
  def InferableTailCall    = 11.U // rename
  def CoRoutineSwap        = 12.U // rename
  def FunctionReturn       = 13.U // rename
  def OtherUninferableJump = 14.U // rename
  def OtherInferableJump   = 15.U // rename

  // Assuming the branchType is NonTaken here, it will be correctly modified after writeBack.
  def Branch = NonTaken

  def isExp(itype: UInt) = Seq(Exception).map(_ === itype).reduce(_ || _)

  def isInt(itype: UInt) = Seq(Interrupt).map(_ === itype).reduce(_ || _)

  def isTrap(itype: UInt) = Seq(Exception, Interrupt).map(_ === itype).reduce(_ || _)

  def isTrapOrXret(itype: UInt) = Seq(Exception, Interrupt, ExpIntReturn).map(_ === itype).reduce(_ || _)

  def isNotNone(itype: UInt) = itype =/= None

  def isBranchType(itype: UInt) = itype === NonTaken || itype === Taken

  def isBranchTaken(itype: UInt) = itype === Taken

  def isBranchNonTaken(itype: UInt) = itype === NonTaken

  // supportSijump
  def isUninferable(itype: UInt) =
    Seq(UninferableCall, UninferableTailCall, CoRoutineSwap, FunctionReturn, OtherUninferableJump).map(
      _ === itype
    ).reduce(_ || _)
}

object Ilastsize extends NamedUInt(1) {
  def HalfWord = 0.U
  def Word     = 1.U
}

object Priv extends NamedUInt(3) {
  def HU = 0.U
  def HS = 1.U
  def M  = 3.U
  def D  = 4.U
  def VU = 5.U
  def VS = 6.U
}

object TriggerAction extends NamedUInt(2) {
  def None     = 0.U
  def TraceOn  = 1.U
  def TraceOff = 2.U
  def Notify   = 3.U
}

object Ctype extends NamedUInt(2) {
  def None               = 0.U
  def Imprecisely        = 1.U
  def Precisely          = 2.U
  def AsyncDiscontinuity = 3.U
}

object Dtype extends NamedUInt(4) {
  def Load       = 0.U
  def Store      = 1.U
  def CSRRW      = 4.U
  def CSRRS      = 5.U
  def CSRRC      = 6.U
  def AtomicSwap = 8.U
  def AtomicAdd  = 9.U
  def AtomicAND  = 10.U
  def AtomicOR   = 11.U
  def AtomicXOR  = 12.U
  def AtomicMax  = 13.U
  def AtomicMin  = 14.U
  def CondStFail = 15.U
}

/**
 * hpm
 */
class Hpm extends Bundle with TraceParameters {
  val eventVec = Vec(
    hpmevent_num,
    new Bundle {
      val hpmaddr  = Output(UInt(5.W))
      val hpmevent = Output(UInt(hpmevent_width.W))
      val inc      = Input(UInt(hpminc_width.W))
    }
  )
}
