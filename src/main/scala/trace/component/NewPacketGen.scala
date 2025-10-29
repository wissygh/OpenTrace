package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters
import trace.control._
import trace.interface._
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper
import utils.Compress
import utils.Lzc
import chisel3.util.{RegEnable => validVec}
import freechips.rocketchip.util.SeqToAugmentedSeq
import freechips.rocketchip.rocket.CFIType.branch
import firrtl.ir.Block



class EncoderCtrlInterface extends Bundle with TraceParameters {
  val traceStart = Bool()
  val filterRes  = Bool()
  val teCtrl   = new TeControlBundle
  val teInstFeat   = new TeInstFeaturesBundle
  val teDataCtrl = new TeDataControlBundle
}

class PacketGen extends Module with TraceParameters {

  val io = IO(new Bundle {
    val block = Input(new BlockBufferEntry)
    val fromCtrl = Input(new EncoderCtrlInterface)
    val packet   = DecoupledIO(new PacketEnq(maxPackets))
  })

  private val hasTrace = io.block.valid
  private val nxtBlock = RegEnable(io.block, 0.U.asTypeOf(new BlockBufferEntry), hasTrace)
  private val curBlock = RegEnable(nxtBlock, 0.U.asTypeOf(new BlockBufferEntry), hasTrace)
  private val preBlock = RegEnable(curBlock, 0.U.asTypeOf(new BlockBufferEntry), hasTrace)

  private val qualFromCore = io.fromCtrl.filterRes && !io.block.hartIsReset && !(io.block.priv === Priv.D)
  private val nxtQualified = RegEnable(qualFromCore, hasTrace)
  private val curQualified = RegEnable(nxtQualified, hasTrace)
  private val preQualified = RegEnable(curQualified, hasTrace)

  /**
    * next
    */
  private val nextPriv    = nxtBlock.priv
  private val nextCause   = nxtBlock.cause
  private val nextContext = nxtBlock.context
  private val nextTval    = nxtBlock.tval
  private val nextTime    = nxtBlock.time
  private val nextIsInt   = Itype.isInt(nxtBlock.itype)
  private val nextIsExp   = Itype.isExp(nxtBlock.itype)
  private val nextIsTrap  = nextIsExp || nextIsInt

  /**
   * pre
   */
  private val prePriv    = preBlock.priv
  private val preCause   = preBlock.cause
  private val preContext = preBlock.context
  private val preTval    = preBlock.tval
  private val preTime    = preBlock.time
  private val preIsInt   = Itype.isInt(preBlock.itype)
  private val preIsExp   = Itype.isExp(preBlock.itype)
  private val preIsTrap  = preIsExp || preIsInt
  private val preIsUpdiscon = Itype.isUninferable(preBlock.itype)

  /**
   * current
   */
  private val curPriv    = curBlock.priv
  private val curCause   = curBlock.cause
  private val curContext = curBlock.context
  private val curTval    = curBlock.tval
  private val curTime    = curBlock.time
  private val curIsInt   = Itype.isInt(curBlock.itype)
  private val curIsExp   = Itype.isExp(curBlock.itype)
  private val curIsTrap  = curIsExp || curIsInt
  private val curIsHalted = curBlock.priv === Priv.D
  private val curIsBrh = Itype.isBranchType(curBlock.itype)
  private val curNotify = curBlock.trigger === TriggerAction.Notify

  // branch map
  private val brhMap = RegInit(0.U(31.W)) // each bit represents the outcome of a branch, fifo
  private val brhCnt = RegInit(0.U(5.W))  // number of branch instructions stored in branchMap
  private val isBrhVecNxtCmt = VecInit(nxtBlock.blocks.map(_.valid).zip(
    nxtBlock.blocks.map(_.bits.itype).map(it => Itype.isBranchType(it))
  ).map {
    case (vld, isBrh) => vld && isBrh && qualifiedNxt
  })
  private val takenVecNxtCmt = VecInit(nxtBlock.blocks.map(_.bits.itype).map(it => Itype.isBranchTaken(it)))
  private val compressedMap  = VecInit(Seq.fill(BlockNum)(false.B))
  Compress.compress(isBrhVecNxtCmt, takenVecNxtCmt, compressedMap)

  private val brhCntNxtCmt = PopCount(nxtBlock.blocks.map(_.valid).zip(
    nxtBlock.blocks.map(_.bits.itype).map(it => Itype.isBranchType(it))
  ).map {
    case (vld, isBrh) => vld && isBrh
  }.toSeq)

  private val issueBrhMap = false.B                       // todo: fix
  private val nxtCmtIsOf  = brhCnt +& brhCntNxtCmt > 31.U // if overflow issue else store into brhMap
  brhCnt := Mux(issueBrhMap || nxtCmtIsOf, brhCntNxtCmt, brhCnt + brhCntNxtCmt)
  brhMap := Mux(issueBrhMap || nxtCmtIsOf, compressedMap.asUInt, (compressedMap.asUInt << brhCnt) | brhMap)

  dontTouch(brhCnt)
  dontTouch(brhMap)

  val resyncCnt = RegInit(0.U(5.W)) // todo: fix 根据mode来计数以及设置
  val resync = !preQualified && curQualified || prePriv =/= curPriv || preContext =/= curContext || 
    (resyncCnt > io.fromCtrl.teCtrl.InstSyncMax.asUInt)
  val excpReported = RegInit(false.B)

  /**
    * resync state machine
    */
  val s1 :: s2 :: s3 :: Nil = Enum(3)
  val state = RegInit(s1)
  switch(state){
    is(s1){
      when(resyncCnt >= io.fromCtrl.teCtrl.InstSyncMax.asUInt){
        state := s2
      }
    }
    is(s2){
      state := s3
    }
    is(s3){
      state := s1
    }
  }


  val packetVec     = VecInit(Seq.fill(BlockNum)(Wire(new NormalPacket)))
 
  when(curQualified && curBlock.valid && hasTrace){ // 只有当有指令提交时流水线才会流动
    when(preIsTrap){
      when(curIsTrap){ // [已完成] 当前是异常，前一个是异常, 当前会发一个 handler 包, 有异常的cycle有且只会产生一个包
        val f31 = Wire(new Format31)
        f31.format    := 3.U
        f31.subformat := 1.U
        f31.branch    := !Itype.isBranchTaken(curBlock.itype)
        f31.privilege := curPriv
        f31.time.foreach(_ := curTime)
        f31.context.foreach(_ := curContext)
        f31.ecause    := preCause
        f31.interrupt := preIsInt
        f31.thaddr    := false.B
        f31.address   := curBlock.iaddr
        f31.tval      := Mux(preIsInt, 0.U, preTval) // only for exception, omitted for int
        excpReported  := true.B
      }.otherwise {
        when(excpReported){ // [未完成] 当前不是异常，前一个是异常，前前一个也是异常或者updiscon，当前会发一个 sync 包, todo: 后面的包
          val f30 = Wire(new Format30)
          f30.format    := 3.U
          f30.subformat := 0.U
          f30.branch    := !(Itype.isBranchTaken(curBlock.itype) && ((curBlock.iretire >> 1) === curBlock.ilastsize)) // 0: branch taken, 1: branch not taken or other type
          f30.privilege := curPriv
          f30.time.foreach(_ := curTime)
          f30.context.foreach(_ := curContext)
          f30.address := curBlock.iaddr
          excpReported := false.B
        }.otherwise{ // [未完成] 当前不是异常，前一个是异常，前前一个不是异常，当前会发一个 handler 包, todo: 后面的包
          val f31 = Wire(new Format31)
          f31.format    := 3.U
          f31.subformat := 1.U
          f31.branch    := !Itype.isBranchTaken(curBlock.itype)
          f31.privilege := curPriv
          f31.time.foreach(_ := curTime)
          f31.context.foreach(_ := curContext)
          f31.ecause    := preCause
          f31.interrupt := preIsInt
          f31.thaddr    := true.B
          f31.address   := curBlock.iaddr
          f31.tval      := Mux(preIsInt, 0.U, preTval) // only for exception, omitted for int
        }
      }
      resyncCnt := 0.U
    }.otherwise {
      when(resync){ // [未完成] 同步包
        val f30 = Wire(new Format30)
        f30.format    := 3.U
        f30.subformat := 0.U
        f30.branch    := !(Itype.isBranchTaken(curBlock.itype) && ((curBlock.iretire >> 1) === curBlock.ilastsize)) // 0: branch taken, 1: branch not taken or other type
        f30.privilege := curPriv
        f30.time.foreach(_ := curTime)
        f30.context.foreach(_ := curContext)
        f30.address := curBlock.iaddr
      }.otherwise{
        when(preIsUpdiscon){
          when(curIsTrap){ // [未完成] 当前是异常，前一个是 updiscon, 当前会发一个 handler 包, todo: 后面的包
            val f31 = Wire(new Format31)
            f31.format    := 3.U
            f31.subformat := 1.U
            f31.branch    := !Itype.isBranchTaken(curBlock.itype)
            f31.privilege := curPriv
            f31.time.foreach(_ := curTime)
            f31.context.foreach(_ := curContext)
            f31.ecause    := curCause
            f31.interrupt := curIsInt
            f31.thaddr    := false.B
            f31.address   := curBlock.iaddr
            f31.tval      := Mux(curIsInt, 0.U, curTval) // only for exception, omitted for int
            excpReported  := true.B
          }.otherwise{// [未完成] 前一个是 updiscon, 当前不是异常，发普通的包
            val f2 = Wire(new Format2)
            f2.format := 2.U
            f2.address := curBlock.iaddr
            f2.notifyTrigger := curNotify
            f2.updiscon := true.B
          }
        }.otherwise{
          when(resyncCnt === io.fromCtrl.teCtrl.InstSyncMax.asUInt && brhCnt > 0.U || curNotify){ //[未完成]
            

          }.otherwise{
            when(nextIsTrap || ((curPriv =/= nextPriv || curContext =/= nextContext) && brhCnt > 0.U) || curQualified && !nxtQualified){ // [未完成] 当前不是异常，后一个是异常或者特权级/上下文变更，发普通的包
              val f2 = Wire(new Format2)
              f2.format := 2.U
              f2.address := curBlock.iaddr
              f2.notifyTrigger := curNotify
              f2.updiscon := false.B
            }.otherwise {
              when(brhCnt > 0.U){ // only branch map
                val f1 = Wire(new Format1NoAddress)
                f1.format := 1.U
                f1.branches := brhCnt
                f1.branch_map := brhMap
              }
            }
          }
        }
      }
    }
  }

  // branch map end

  /**
   * Format3 Payload
   * 30: Synchronisation, Start of tracing, or resync.
   * 31: Trap, Exception or interrupt cause and trap handler address.
   * 32: Context, Context change.
   * 33: Support, Supporting information for the decoder.
   * 
   * Format2: don't support implict return
   *  differential address and no branch information.
   * 
   * Format1: don't support implict return
   *  includes branch information and may include differential address.
   * 
   * Format0: don't support jump target cache and branch predict now
   *  00: no address; 00 address
   *  01: no branchmap; branchmap
   */

  /**
    * f33: support packet
    * 1. trTeEnable 0->1 edge
    * 2. trace end
    * 3. back pressure
    * 4. config change // don't support change config when tracing
    */
  // 默认 tracing 是一个很大的范围，在该范围内进行 filter，且被 filter 的范围一定是连续的
  val qual_stausEnVec = VecInit(Seq.fill(4)(false.B))
  val f33En = qual_stausEnVec.asUInt.orR
  qual_stausEnVec(0) := !RegNext(io.fromCtrl.teCtrl.Enable.asBool) && io.fromCtrl.teCtrl.Enable.asBool
  qual_stausEnVec(1) := io.fromCtrl.teCtrl.InstTracing.asBool && curQualified && !nxtQualified // todo: fix
  qual_stausEnVec(2) := io.packet.valid && !io.packet.ready
  qual_stausEnVec(3) := io.fromCtrl.teCtrl.InstTracing.asBool && preIsUpdiscon && preQualified && !curQualified //  todo: fix


  val f33 = Wire(new Format33)
  f33.format       := 3.U
  f33.subformat    := 3.U
  f33.ienable      := true.B
  f33.encoder_mode := 0.U
  f33.qual_status := Mux1H(
    qual_stausEnVec,
    Seq(QualStatus.No_change, QualStatus.Ended_req, QualStatus.Trace_lost, QualStatus.Ended_ntr)
  )
  f33.sijump     := io.fromCtrl.teInstFeat.EnSiJump.asBool
  f33.imRet      := io.fromCtrl.teInstFeat.EnImRet.asBool
  f33.imExp      := io.fromCtrl.teInstFeat.NoTrapDiff.asBool
  f33.branchPred := io.fromCtrl.teInstFeat.EnBranchPred.asBool
  f33.jumpCache  := io.fromCtrl.teInstFeat.EnJumpTargetCache.asBool
  f33.fullAddr   := io.fromCtrl.teInstFeat.NoAddrDiff.asBool
  // don't support data trace now
  f33.denable  := io.fromCtrl.teDataCtrl.DataImplemented.asBool && io.fromCtrl.teDataCtrl.DataTracing.asBool
  f33.dloss    := false.B
  f33.doptions := 0.U

  // 在不同的情况下，不止发的包的格式不同，包的个数也不同


  def extend(raw: UInt): UInt = {
    val len   = raw.getWidth
    val unify = Fill(payload_max_width - len, raw.head(1)) ## raw
    unify
  }

  val payload = extend(0.U)
  dontTouch(payload)

  // wrap payload into packet
  val packet     = Wire(new NormalPacket)
  val pos        = (payload_max_width + 1).U - Lzc(Mux(payload.head(1).asBool, ~payload, payload))
  val payloadLen = (pos >> 3) + pos(2, 0).orR

  packet.header.extend        := false.B
  packet.header.flow          := 0.U
  packet.header.length        := payloadLen
  packet.srcid                := io.fromCtrl.teInstFeat.SrcID.asUInt
  packet.timestamp            := 0.U
  packet.payload.traceType    := 0.U // instruction trace
  packet.payload.tracePayload := payload

  /**
   * packet gen todo: delete
   */
  io.packet.valid     := payloadLen =/= 0.U && io.fromCtrl.teCtrl.InstTracing.asBool && io.fromCtrl.teCtrl.Enable.asBool
  io.packet.bits.len  := payloadLen + 2.U // payLoad + srcid + header
  io.packet.bits.data := packet.asUInt.asTypeOf(io.packet.bits.data)
}
