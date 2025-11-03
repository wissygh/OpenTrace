package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters
import trace.control._
import trace.interface._
import utils.Lzc

class EncoderCtrlInterface extends Bundle with TraceParameters {
  val filterRes         = Bool()
  val blockBufferIsFull = Bool()
  val fifoIsFull        = Bool()
  val teCtrl            = new TeControlBundle
  val teInstFeat        = new TeInstFeaturesBundle
  val teDataCtrl        = new TeDataControlBundle
}

class PacketGen extends Module with TraceParameters {

  val io = IO(new Bundle {
    val block    = Input(new BlockBufferEntry)
    val fromCtrl = Input(new EncoderCtrlInterface)
    val packet   = DecoupledIO(new PacketEnq(maxPackets))
  })

  private val teCtrl = io.fromCtrl.teCtrl

  private val nextBlock = io.block
  private val hasTrace  = io.block.valid
  private val curBlock  = RegEnable(nextBlock, 0.U.asTypeOf(new BlockBufferEntry), hasTrace)
  private val preBlock  = RegEnable(curBlock, 0.U.asTypeOf(new BlockBufferEntry), hasTrace)

  private val nextQualified = io.fromCtrl.filterRes && teCtrl.InstTracing.asBool &&
    !io.block.hartIsReset && !(io.block.priv === Priv.D)
  private val curQualified = RegEnable(nextQualified, hasTrace)
  private val preQualified = RegEnable(curQualified, hasTrace)

  /**
    * next
    */
  private val nextPriv       = nextBlock.priv
  private val nextCause      = nextBlock.cause
  private val nextContext    = nextBlock.context
  private val nextTval       = nextBlock.tval
  private val nextTime       = nextBlock.time
  private val nextIsInt      = Itype.isInt(nextBlock.itype)
  private val nextIsExp      = Itype.isExp(nextBlock.itype)
  private val nextIsTrap     = nextIsExp || nextIsInt
  private val nextIsBrh      = Itype.isBranchType(nextBlock.itype)
  private val nextIsTaken    = Itype.isBranchTaken(nextBlock.itype)
  private val nextIsNonTaken = Itype.isBranchNonTaken(nextBlock.itype)

  /**
   * pre
   */
  private val prePriv       = preBlock.priv
  private val preCause      = preBlock.cause
  private val preContext    = preBlock.context
  private val preTval       = preBlock.tval
  private val preTime       = preBlock.time
  private val preIsInt      = Itype.isInt(preBlock.itype)
  private val preIsExp      = Itype.isExp(preBlock.itype)
  private val preIsTrap     = preIsExp || preIsInt
  private val preIsUpdiscon = Itype.isUninferable(preBlock.itype)

  /**
   * current
   */
  private val curPriv     = curBlock.priv
  private val curCause    = curBlock.cause
  private val curContext  = curBlock.context
  private val curTval     = curBlock.tval
  private val curTime     = curBlock.time
  private val curIsInt    = Itype.isInt(curBlock.itype)
  private val curIsExp    = Itype.isExp(curBlock.itype)
  private val curIsTrap   = curIsExp || curIsInt
  private val curIsHalted = curBlock.priv === Priv.D
  private val curIsBrh    = Itype.isBranchType(curBlock.itype)
  private val curNotify   = curBlock.trigger === TriggerAction.Notify

  val resyncCnt = RegInit(0.U(20.W))                       // InstSyncMax is 15
  val syncMax   = 1.U << (teCtrl.InstSyncMax.asUInt + 4.U) // only support packet mode
  val resync =
    (!preQualified && curQualified) || prePriv =/= curPriv || (curBlock.ctype === Ctype.AsyncDiscontinuity) ||
      (resyncCnt > syncMax)
  val excpReported  = RegInit(false.B)
  val lastFullAdder = RegInit(0.U(iaddress_width_p.W))

  // branch map
  private val brhMap = RegInit(0.U(31.W)) // each bit represents the outcome of a branch, fifo
  private val brhCnt = RegInit(0.U(5.W))  // number of branch instructions stored in branchMap

  private val enqBrhMap   = nextIsBrh && nextQualified
  private val issueBrhMap = false.B         // todo: fix
  private val nextCmtIsOf = brhCnt === 31.U // if overflow issue else store into brhMap
  brhCnt := Mux(issueBrhMap || nextCmtIsOf, enqBrhMap, brhCnt + enqBrhMap)
  brhMap := Mux(issueBrhMap || nextCmtIsOf, enqBrhMap, (nextIsTaken << brhCnt) | brhMap)

  dontTouch(brhCnt)
  dontTouch(brhMap)

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
  def extend(raw: UInt): UInt = {
    val len  = raw.getWidth
    val data = Fill(payload_max_width - len, raw.head(1)) ## raw
    data
  }

  val payload = Wire(UInt(payload_max_width.W))
  payload := 0.U
  dontTouch(payload)

  when(curQualified && curBlock.valid && nextBlock.valid && teCtrl.InstTracing.asBool) {
    when(preIsTrap) {
      when(curIsTrap) {
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
        f31.tval := Mux(
          preIsInt,
          Fill(iaddress_width_p, curBlock.iaddr.head(1)),
          preTval
        ) // only for exception, omitted for int
        payload      := extend(f31.asUInt)
        excpReported := true.B
      }.otherwise {
        when(excpReported) {
          val f30 = Wire(new Format30)
          f30.format    := 3.U
          f30.subformat := 0.U
          f30.branch := !(Itype.isBranchTaken(
            curBlock.itype
          ) && ((curBlock.iretire >> 1) === curBlock.ilastsize)) // 0: branch taken, 1: branch not taken or other type
          f30.privilege := curPriv
          f30.time.foreach(_ := curTime)
          f30.context.foreach(_ := curContext)
          f30.address  := curBlock.iaddr
          payload      := extend(f30.asUInt)
          excpReported := false.B
        }.otherwise {
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
          f31.tval := Mux(
            preIsInt,
            Fill(iaddress_width_p, curBlock.iaddr.head(1)),
            preTval
          ) // only for exception, omitted for int
          payload := extend(f31.asUInt)
        }
      }
      resyncCnt := 0.U
    }.otherwise {
      when(resync) {
        val f30 = Wire(new Format30)
        f30.format    := 3.U
        f30.subformat := 0.U
        f30.branch := !(Itype.isBranchTaken(
          curBlock.itype
        ) && ((curBlock.iretire >> 1) === curBlock.ilastsize)) // 0: branch taken, 1: branch not taken or other type
        f30.privilege := curPriv
        f30.time.foreach(_ := curTime)
        f30.context.foreach(_ := curContext)
        f30.address := curBlock.iaddr
        payload     := extend(f30.asUInt)
        resyncCnt   := 0.U
      }.otherwise {
        when(preIsUpdiscon) {
          when(curIsTrap) {
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
            f31.tval := Mux(
              curIsInt,
              Fill(iaddress_width_p, curBlock.iaddr.head(1)),
              curTval
            ) // only for exception, omitted for int
            payload      := extend(f31.asUInt)
            excpReported := true.B
            resyncCnt    := 0.U
          }.otherwise { // report address. todo: may be need notify, to gen diff address. notify or updiscon?
            // updiscon, need to report
            when(brhCnt === 0.U) {
              val f2 = Wire(new Format2)
              f2.format        := 2.U
              f2.diffAddress   := curBlock.iaddr
              f2.notifyTrigger := f2.diffAddress.head(1).asBool // todo: fix
              f2.updiscon      := !f2.notifyTrigger
              payload          := extend(f2.asUInt)
            }.otherwise {
              val f1 = Wire(new Format1WithAddr)
              f1.format        := 1.U
              f1.branches      := brhCnt
              f1.branch_map    := brhMap
              f1.diffAddress   := curBlock.iaddr
              f1.notifyTrigger := f1.diffAddress.head(1).asBool // todo: fix
              f1.updiscon      := !f1.notifyTrigger
              payload          := extend(f1.asUInt)
            }
            resyncCnt := resyncCnt + 1.U
          }
        }.otherwise {
          when(resyncCnt === syncMax && brhCnt > 0.U || curNotify) { // todo: fix notify
            when(brhCnt > 0.U && curNotify) {
              val f1 = Wire(new Format1WithAddr)
              f1.format        := 1.U
              f1.branches      := brhCnt
              f1.branch_map    := brhMap
              f1.diffAddress   := curBlock.iaddr + (curBlock.iretire - curBlock.ilastsize) // todo: fix
              f1.notifyTrigger := !f1.diffAddress.head(1).asBool                           // todo: fix
              f1.updiscon      := f1.notifyTrigger
              payload          := extend(f1.asUInt)
              resyncCnt        := resyncCnt + 1.U
            }.elsewhen(brhCnt > 0.U && !curNotify) {
              val f1 = Wire(new Format1NoAddr)
              f1.format     := 1.U
              f1.branches   := brhCnt
              f1.branch_map := brhMap
              payload       := extend(f1.asUInt)
              resyncCnt     := resyncCnt + 1.U
            }.elsewhen(brhCnt === 0.U && curNotify) {
              val f2 = Wire(new Format2)
              f2.format        := 2.U
              f2.diffAddress   := curBlock.iaddr + (curBlock.iretire - curBlock.ilastsize) // todo: fix
              f2.notifyTrigger := !f2.diffAddress.head(1).asBool                           // todo: fix
              f2.updiscon      := f2.notifyTrigger
              payload          := extend(f2.asUInt)
              resyncCnt        := resyncCnt + 1.U
            }
          }.otherwise {
            when(
              nextIsTrap || ((curPriv =/= nextPriv || curBlock.ctype === Ctype.AsyncDiscontinuity) && brhCnt > 0.U) || curQualified && !nextQualified
            ) { // report branch
              when(brhCnt > 0.U) {
                val f1 = Wire(new Format1NoAddr)
                f1.format     := 1.U
                f1.branches   := brhCnt
                f1.branch_map := brhMap
                payload       := extend(f1.asUInt)
                resyncCnt     := resyncCnt + 1.U
              }
            }.otherwise {
              when(nextCmtIsOf) { // only branch map
                val f1 = Wire(new Format1NoAddr)
                f1.format     := 1.U
                f1.branches   := brhCnt
                f1.branch_map := brhMap
                payload       := extend(f1.asUInt)
                resyncCnt     := resyncCnt + 1.U
              }.elsewhen(curBlock.ctype === Ctype.Imprecisely) {
                val f32 = Wire(new Format32)
                f32.format    := 3.U
                f32.subformat := 2.U
                f32.privilege := curPriv
                f32.time.foreach(_ := curTime)
                f32.context.foreach(_ := curContext)
                payload   := extend(f32.asUInt)
                resyncCnt := resyncCnt + 1.U
              }
            }
          }

        }
      }
    }
  }.otherwise {

    /**
      * f33: support packet
      * 1. trTeEnable 0->1 edge
      * 2. trace end
      * 3. back pressure
      * 4. config change // don't support change config when tracing
      */
    val qual_stausEnVec = VecInit(Seq.fill(4)(false.B))
    qual_stausEnVec(0) := !RegNext(teCtrl.Enable.asBool) && teCtrl.Enable.asBool
    qual_stausEnVec(1) := teCtrl.InstTracing.asBool && preQualified && !curQualified // todo: fix
    qual_stausEnVec(2) := io.fromCtrl.blockBufferIsFull || io.fromCtrl.fifoIsFull
    qual_stausEnVec(3) := teCtrl.InstTracing.asBool && preIsUpdiscon &&
      preQualified && !curQualified //  todo: fix

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

    val supportEn = qual_stausEnVec.asUInt.orR
    payload   := extend(Mux(supportEn, f33.asUInt, 0.U))
    resyncCnt := Mux(supportEn, 0.U, resyncCnt)
  }

  // wrap payload into packet
  val packet      = Wire(new NormalPacket)
  val lzcIn       = Mux(payload.head(1).asBool, ~payload, payload)
  val lzc         = Lzc(lzcIn, false)
  val packetWidth = Mux(lzcIn.orR, (payload_max_width + 1).U - lzc.io.out, 0.U)
  val payloadLen  = (packetWidth >> 3) + packetWidth(2, 0).orR

  packet.header.extend        := false.B
  packet.header.flow          := 0.U
  packet.header.length        := payloadLen
  packet.srcid                := io.fromCtrl.teInstFeat.SrcID.asUInt
  packet.timestamp            := 0.U
  packet.payload.traceType    := 0.U // instruction trace
  packet.payload.tracePayload := payload

  dontTouch(packet)
  dontTouch(lzc.io.out)
  dontTouch(lzc.io.isZero)
  dontTouch(packetWidth)
  dontTouch(payloadLen)
  dontTouch(curBlock)
  dontTouch(preBlock)

  val f30_debug        = payload.asTypeOf(new Format30)
  val f31_debug        = payload.asTypeOf(new Format31)
  val f32_debug        = payload.asTypeOf(new Format32)
  val f33_debug        = payload.asTypeOf(new Format33)
  val f2_debug         = payload.asTypeOf(new Format2)
  val f1NoAddr_debug   = payload.asTypeOf(new Format1NoAddr)
  val f1WithAddr_debug = payload.asTypeOf(new Format1WithAddr)

  dontTouch(f30_debug)
  dontTouch(f31_debug)
  dontTouch(f32_debug)
  dontTouch(f33_debug)
  dontTouch(f2_debug)
  dontTouch(f1NoAddr_debug)
  dontTouch(f1WithAddr_debug)

  /**
   * packet gen todo: delete
   */
  io.packet.valid     := payloadLen =/= 0.U && teCtrl.InstTracing.asBool // todo: add teCtrl.Enable.asBool
  io.packet.bits.len  := payloadLen + 2.U                                // payLoad + srcid + header
  io.packet.bits.data := packet.asUInt.asTypeOf(io.packet.bits.data)
}
