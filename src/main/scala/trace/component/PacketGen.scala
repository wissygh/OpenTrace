// package trace.component

// import cats.syntax.validated
// import chisel3._
// import chisel3.util._
// import org.chipsalliance.cde.config.Parameters
// import trace.TraceParameters
// import trace.control.TeInstFeaturesBundle
// import trace.interface._
// import utility.CircularQueuePtr
// import utility.HasCircularQueuePtrHelper
// import utils.Lzc
// import freechips.rocketchip.util.SeqBoolBitwiseOps

// class EncoderCtrlInterface extends Bundle with TraceParameters {
//   val srcid      = UInt(src_bits_p.W)
//   val traceStart = Bool()
//   val tracing    = Bool()
//   val filterRes  = Vec(BlockNum, Bool())
//   val features   = new TeInstFeaturesBundle
//   val dTraceEn   = Bool()
// }

// class PacketGen extends Module with TraceParameters {

//   val io = IO(new Bundle {
//     val fromCore = Input(new FromCore)
//     val fromCtrl = Input(new EncoderCtrlInterface)
//     val packet   = DecoupledIO(new PacketEnq(maxPackets))
//   })
//   private val fromCore = io.fromCore
//   private val (priv, cause, scontext, hcontext, time, tval, impdef, trigger, blocks) =
//     (
//       fromCore.priv,
//       fromCore.cause,
//       fromCore.scontext,
//       fromCore.hcontext,
//       fromCore.time,
//       fromCore.tval,
//       fromCore.impdef,
//       fromCore.trigger,
//       fromCore.blocks
//     )

//   /**
//     * exp/int only occurs in block0
//     * first valid block must be block0 every cycle
//     */
//   private val block0       = blocks(0)
//   private val ingressValid = fromCore.blocks.map(_.valid).reduce(_ | _)
//   private val itypeIsInt   = block0.valid && Itype.isInt(block0.bits.itype)
//   private val itypeIsExp   = block0.valid && Itype.isExp(block0.bits.itype)
//   private val itypeIsTrap  = itypeIsExp || itypeIsInt
//   private val runInDebug   = priv === Priv.D

//   // todo: Cause/priv/tval has changed when block(0) is exp/int?
//   private val prePriv     = RegEnable(priv, ingressValid)
//   private val preCause    = RegEnable(cause, ingressValid)
//   private val preScontext = RegEnable(scontext, ingressValid)
//   private val preHcontext = RegEnable(hcontext, ingressValid)
//   private val preTval     = RegEnable(tval, ingressValid)
//   private val preTime     = RegEnable(time, ingressValid)
//   private val preIsInt    = RegEnable(itypeIsInt, ingressValid)
//   private val preIsExp    = RegEnable(itypeIsExp, ingressValid)
//   private val preIsTrap   = preIsExp || preIsInt

//   private val validVec     = VecInit(blocks.map(_.valid))
//   private val itypeVec     = VecInit(blocks.map(_.bits.itype))
//   private val isBrhVec     = VecInit(itypeVec.zip(validVec).map { case (it, vld) => Itype.isBranchType(it) && vld })
//   private val notifyVec    = VecInit(trigger.TraceNotify.zip(validVec).map { case (notify, vld) => notify && vld })
//   private val hasNotify    = notifyVec.orR
//   private val notifyBlock  = PriorityMux(notifyVec, blocks) // only select the first notify to deal
//   private val qualifiedVec = VecInit(validVec.zip(io.fromCtrl.filterRes).map { case (vld, res) => vld && res })
//   private val qualified    = qualifiedVec.asUInt.orR
//   private val lastQualifiedItype = PriorityMux(qualifiedVec.reverse, itypeVec.reverse)
//   private val preIsUpdiscon      = RegEnable(Itype.isUninferable(lastQualifiedItype), ingressValid)

//   private val start   = io.fromCtrl.traceStart // trace start from the cycle traceStart
//   private val tracing = io.fromCtrl.tracing    // don't trace when dessert teControl.InstTracing

//   // ingress port
//   private val ingressVec = Reg(Vec(3, new FromCore)) // 3 cycle
//   private val IngressPre = ingressVec.last
//   private val IngressCur = ingressVec(1)
//   private val IngressNxt = ingressVec.head

//   // // branch map todo: refactor
//   // private val branchMap        = RegInit(0.U(31.W)) // each bit represents the outcome of a branch, fifo
//   // private val branchMapPtr     = RegInit(0.U(5.W))  // points to the first valid bit in branchMap, ptr
//   // private val branchMapPtrNext = WireInit(branchMapPtr)
//   // branchMapPtr := branchMapPtrNext
//   // private val branchMapFull = branchMapPtrNext >= 30.U
//   // private val aheadBranchCnt =
//   //   0.U +: (0 until BlockNum).map(i => PopCount(isBrhVec.asUInt(i, 0))).toSeq // number of branches in the ahead blocks
//   // private val branchCnt = aheadBranchCnt(BlockNum) // count of branches in this cycle

//   // for (i <- 0 until BlockNum) { // enq branch outcome to branchMap
//   //   when(isBrhVec(i)) {
//   //     val mapIdx = Mux(branchMapFull, 0.U, branchMapPtr) + aheadBranchCnt(i)
//   //     branchMap(mapIdx) := !Itype.isBranchTaken(itypeVec(i))
//   //   }
//   // }

//   // val branches = WireInit(0.U(5.W)) // number of branches in this packet
//   // when(branchMapFull) {
//   //   branches         := branchMapPtr + 1.U
//   //   branchMapPtrNext := branchCnt
//   // }.otherwise {
//   //   branches         := branchMapPtr + branchCnt + 1.U
//   //   branchMapPtrNext := branchMapPtr + branchCnt
//   // }
//   // // branch map end

//   /**
//  * Format3 Payload
//  * 30: Synchronisation, Start of tracing, or resync.
//  * 31: Trap, Exception or interrupt cause and trap handler address.
//  * 32: Context, Context change.
//  * 33: Support, Supporting information for the decoder.
//  *
//  * Format2: don't support implict return
//  *  differential address and no branch information.
//  *
//  * Format1: don't support implict return
//  *  includes branch information and may include differential address.
//  *
//  * Format0: don't support jump target cache and branch predict now
//  *  00: no address; 00 address
//  *  01: no branchmap; branchmap
//  */

//   val f30En    = WireInit(false.B)
//   val f31En    = WireInit(false.B)
//   val f32En    = WireInit(false.B)
//   val f33En    = WireInit(false.B)
//   val f2En     = WireInit(false.B)
//   val f1AddrEn = WireInit(false.B)
//   val f1En     = WireInit(false.B)

//   val f30    = Wire(new Format30)
//   val f31    = Wire(new Format31)
//   val f32    = Wire(new Format32)
//   val f33    = Wire(new Format33)
//   val f2     = Wire(new Format2)
//   val f1Addr = Wire(new Format1)
//   val f1     = Wire(new Format1NoAddress)

//   val traceFirst = RegInit(false.B)
//   f30En := (start || traceFirst && tracing) && qualified
//   when(start && !qualified || f31En) {
//     traceFirst := true.B
//   }.elsewhen(f30En) {
//     traceFirst := false.B
//   }

//   f31En := tracing && qualified(0) && (itypeIsExp || itypeIsInt)
//   f32En := tracing && qualified && (scontext =/= preScontext)

//   f30.format    := 3.U
//   f30.subformat := 0.U
//   f30.branch    := !Itype.isBranchTaken(block0.bits.itype) // 0: branch taken, 1: branch not taken or other type
//   f30.privilege := priv
//   f30.time.foreach(_ := time)
//   f30.context.foreach(_ := scontext)
//   f30.address := block0.bits.iaddr

//   f31.format    := 3.U
//   f31.subformat := 1.U
//   f31.branch    := !Itype.isBranchTaken(block0.bits.itype) // exp/int can't occur on branch instruction
//   f31.privilege := priv
//   f31.time.foreach(_ := time)
//   f31.context.foreach(_ := scontext)
//   f31.ecause    := Mux(preIsUpdiscon, cause, preCause)
//   f31.interrupt := Mux(preIsUpdiscon, itypeIsInt, preIsInt)
//   f31.thaddr    := !itypeIsTrap
//   f31.address   := block0.bits.iaddr
//   f31.tval      := Mux(preIsUpdiscon, tval, preTval) // only for exception, omitted for int

//   f32.format    := 3.U
//   f32.subformat := 2.U
//   f32.privilege := priv
//   f32.time.foreach(_ := time)
//   f32.context.foreach(_ := scontext)

//   val qual_stausEnVec = VecInit(Seq.fill(4)(false.B))
//   f33.format       := 3.U
//   f33.subformat    := 3.U
//   f33.ienable      := true.B
//   f33.encoder_mode := 0.U
//   f33.qual_status := Mux1H(
//     qual_stausEnVec,
//     Seq(QualStatus.No_change, QualStatus.Ended_req, QualStatus.Trace_lost, QualStatus.Ended_ntr)
//   )
//   f33.sijump     := io.fromCtrl.features.EnSiJump.asBool
//   f33.imRet      := io.fromCtrl.features.EnImRet.asBool
//   f33.imExp      := io.fromCtrl.features.NoTrapDiff.asBool
//   f33.branchPred := io.fromCtrl.features.EnBranchPred.asBool
//   f33.jumpCache  := io.fromCtrl.features.EnJumpTargetCache.asBool
//   f33.fullAddr   := io.fromCtrl.features.NoAddrDiff.asBool
//   // don't support data trace now
//   f33.denable  := io.fromCtrl.dTraceEn
//   f33.dloss    := false.B
//   f33.doptions := 0.U

//   // notify or updiscon
//   f2.format := 2.U
//   // force to generate a packet containing the address of the last instruction in the notifyBlock
//   f2.address := notifyBlock.bits.iaddr + (notifyBlock.bits.iretire - notifyBlock.bits.ilastsize)
//   f2.notifyTrigger := Mux(
//     hasNotify,
//     !f2.address.head(0),
//     f2.address.head(0)
//   )
//   f2.updiscon := f2.notifyTrigger // todo: fix

//   // f2 + branch_map is not empty
//   f1Addr := 0.U.asTypeOf(f1Addr)

//   // branch_map is full
//   f1 := 0.U.asTypeOf(f1)

//   def extend(raw: UInt): UInt = { //将 所有的payload扩展到统一宽度
//     val len   = raw.getWidth
//     val unify = Fill(payload_max_width - len, raw.head(1)) ## raw
//     unify
//   }

//   val payload = Mux1H(
//     Seq(
//       f30En,
//       f31En,
//       f32En,
//       f33En,
//       f2En,
//       f1AddrEn,
//       f1En
//     ),
//     Seq(
//       extend(f30.asUInt),
//       extend(f31.asUInt),
//       extend(f32.asUInt),
//       extend(f33.asUInt),
//       extend(f2.asUInt),
//       extend(f1Addr.asUInt),
//       extend(f1.asUInt)
//     )
//   )

//   dontTouch(payload)

//   // wrap payload into packet
//   val packet     = Wire(new NormalPacket)
//   val pos        = (payload_max_width + 1).U - Lzc(Mux(payload.head(1).asBool, ~payload, payload))
//   val payloadLen = (pos >> 3) + pos(2, 0).orR

//   packet.header.extend        := false.B
//   packet.header.flow          := 0.U
//   packet.header.length        := payloadLen
//   packet.timestamp            := time
//   packet.srcid                := io.fromCtrl.srcid
//   packet.payload.traceType    := 0.U // instruction trace
//   packet.payload.tracePayload := payload

//   /**
//    * packet gen todo: delete
//    */
//   io.packet.valid     := payloadLen =/= 0.U && tracing
//   io.packet.bits.len  := payloadLen + 2.U // payLoad + srcid + header
//   io.packet.bits.data := packet.asUInt.asTypeOf(io.packet.bits.data)
// }
