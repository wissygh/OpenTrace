package trace.interface

import chisel3._
import chisel3.util._

import utils._
import trace.TraceParameters

class FromCore extends Bundle with TraceParameters {
  val priv    = Priv()
  val time    = UInt(Xlen.W)
  val context = UInt(Xlen.W)
  val cause   = UInt(CauseWidth.W)
  val tval    = UInt(TvalWidth.W)
  val impdef  = UInt(ImpdefWidth.W)
  val trigger = new Bundle {
    val traceOn = Bool()
    val traceOff = Bool()
    val TraceNotify = Vec(BlockNum, Bool())
  }
  val blocks  = Vec(BlockNum, ValidIO(new Bundle{
    val iaddr     = UInt(IaddrWidth.W)
    val itype     = UInt(ItypeWidth.W)
    val iretire   = UInt(IretireWidthCompressed.W)
    val ilastsize = UInt(IlastsizeWidth.W)
  }))
}

class ToCore extends Bundle{
  val enable = Bool()
  val stall  = Bool()
}

object Itype extends NamedUInt(4) {
  def None                 = 0.U
  def Exception            = 1.U    //rob
  def Interrupt            = 2.U    //rob
  def ExpIntReturn         = 3.U    //rename
  def NonTaken             = 4.U    //commit
  def Taken                = 5.U    //commit
  def UninferableJump      = 6.U    //It's reserved when width of itype is 4.
  def reserved             = 7.U    //reserved
  def UninferableCall      = 8.U    //rename
  def InferableCall        = 9.U    //rename
  def UninferableTailCall  = 10.U   //rename
  def InferableTailCall    = 11.U   //rename
  def CoRoutineSwap        = 12.U   //rename
  def FunctionReturn       = 13.U   //rename
  def OtherUninferableJump = 14.U   //rename
  def OtherInferableJump   = 15.U   //rename

  // Assuming the branchType is NonTaken here, it will be correctly modified after writeBack.
  def Branch = NonTaken

  def isExp(itype: UInt) = Seq(Exception).map(_ === itype).reduce(_ || _)

  def isInt(itype: UInt) = Seq(Interrupt).map(_ === itype).reduce(_ || _)

  def isTrap(itype: UInt) = Seq(Exception, Interrupt).map(_ === itype).reduce(_ || _)

  def isTrapOrXret(itype: UInt) = Seq(Exception, Interrupt, ExpIntReturn).map(_ === itype).reduce(_ || _)

  def isNotNone(itype: UInt) = itype =/= None

  def isBranchType(itype: UInt) = itype === Branch

  // supportSijump
  def isUninferable(itype: UInt) = Seq(UninferableCall, UninferableTailCall, CoRoutineSwap,
    OtherUninferableJump).map(_ === itype).reduce(_ || _)
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

object Dtype extends NamedUInt(4) {
  def Load                 = 0.U
  def Store                = 1.U
  def CSRRW                = 4.U
  def CSRRS                = 5.U
  def CSRRC                = 6.U
  def AtomicSwap           = 8.U
  def AtomicAdd            = 9.U
  def AtomicAND            = 10.U
  def AtomicOR             = 11.U
  def AtomicXOR            = 12.U
  def AtomicMax            = 13.U
  def AtomicMin            = 14.U
  def CondStFail           = 15.U
}

/**
 * hpm
 */
class Hpm extends Bundle with TraceParameters {
  val eventVec = Vec(hpmevent_num, new Bundle {
    val hpmaddr = Output(UInt(5.W))
    val hpmevent = Output(UInt(hpmevent_width.W))
    val inc = Input(UInt(hpminc_width.W))
  })
}
