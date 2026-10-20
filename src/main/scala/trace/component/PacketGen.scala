package trace.component

import chisel3._
import chisel3.util._
import trace.TraceParameters
import trace.control._
import trace.interface._

class EncoderCtrlInterface extends Bundle with TraceParameters {
  val filterRes  = Bool()
  val fifoIsFull = Bool()
  val teCtrl     = new TeControlBundle
  val teInstFeat = new TeInstFeaturesBundle
  val teDataCtrl = new TeDataControlBundle
}

class PacketGen extends Module with TraceParameters {
  validateImplementationParameters()
  require(packet_header_time_p == 0 && src_bits_p > 0 && src_bits_p % 8 == 0)
  val io = IO(new Bundle {
    val block      = Input(new BlockBufferEntry)
    val blockReady = Output(Bool())
    val fromCtrl   = Input(new EncoderCtrlInterface)
    val packet     = DecoupledIO(new PacketEnq(maxPackets))
    val syncReq    = Input(Bool())
    val flushReq   = Input(Bool())
    val flushAck   = Output(Bool())
    val empty      = Output(Bool())
    val stopReq    = Input(Bool())
  })
  val control = io.fromCtrl.teCtrl
  val configuredOptions = Cat(
    io.fromCtrl.teInstFeat.NoAddrDiff.asBool,
    io.fromCtrl.teInstFeat.EnJumpTargetCache.asBool,
    io.fromCtrl.teInstFeat.EnBranchPred.asBool,
    io.fromCtrl.teInstFeat.NoTrapAddr.asBool,
    io.fromCtrl.teInstFeat.EnImRet.asBool,
    io.fromCtrl.teInstFeat.EnSiJump.asBool
  )
  val projectedTraceOn = io.block.valid && io.block.trigger === TriggerAction.TraceOn &&
    control.Enable.asBool && control.InstTrigEnable.asBool
  val metadataOnly = io.block.iretire === 0.U && !Itype.isTrap(io.block.itype)
  val projectedTraceOff = io.block.valid && metadataOnly &&
    (io.block.trigger === TriggerAction.TraceOff || io.block.traceOffAfter) &&
    control.Enable.asBool && control.InstTrigEnable.asBool
  val requestedTracing = control.Enable.asBool && !projectedTraceOff &&
    (control.InstTracing.asBool || projectedTraceOn)
  val requestedConfig      = Cat(control.Enable.asBool, requestedTracing, configuredOptions)
  val activeConfig         = RegInit(0.U(8.W))
  val running              = activeConfig(7) && activeConfig(6)
  val configurationChanged = requestedConfig =/= activeConfig
  val configPending        = RegInit(false.B)
  val pendingConfig        = Reg(UInt(8.W))
  when(configurationChanged && !configPending) {
    configPending := true.B
    pendingConfig := requestedConfig
  }
  val nextConfig  = Mux(configPending, pendingConfig, requestedConfig)
  val stopPending = RegInit(false.B)
  when(io.stopReq && !RegNext(io.stopReq, false.B))(stopPending := true.B)
  val endPending          = RegInit(false.B)
  val endingConfig        = Reg(UInt(8.W))
  val endingChangesConfig = RegInit(false.B)
  val generated           = Wire(Decoupled(new PacketEnq(maxPackets)))
  val output              = Module(new Queue(new PacketEnq(maxPackets), 1, pipe = true))
  output.io.enq <> generated
  io.packet <> output.io.deq
  val flushing    = RegInit(false.B)
  val oldPending  = RegInit(false.B)
  val mayGenerate = !flushing || oldPending
  val canAdvance  = mayGenerate && (!generated.valid || generated.ready) && (!io.packet.valid || io.packet.ready)
  // Snapshot both the queued packet and a generated packet blocked behind it.
  // After this barrier, the encoder FIFO can snapshot its own byte frontier.
  when(io.flushReq && !flushing) {
    flushing   := true.B
    oldPending := generated.valid && !generated.ready
  }.elsewhen(flushing && generated.fire) {
    oldPending := false.B
  }
  when(!io.flushReq) { flushing := false.B; oldPending := false.B }
  io.flushAck := reset.asBool || (flushing && !oldPending && !output.io.deq.valid)
  io.empty := !output.io.deq.valid && !generated.valid && !configPending &&
    !configurationChanged && !endPending && !stopPending
  val cur               = RegInit(0.U.asTypeOf(new BlockBufferEntry))
  val previous          = RegInit(0.U.asTypeOf(new BlockBufferEntry))
  val qualified         = RegInit(false.B)
  val previousQualified = RegInit(false.B)
  val tail              = RegInit(false.B)
  val trapPhase         = RegInit(false.B)
  val contextReported   = RegInit(false.B)
  val replayCurrent     = WireDefault(false.B)
  val lastHalfwords     = 1.U << cur.ilastsize
  val lastPC            = cur.iaddr + ((cur.iretire - lastHalfwords) << 1)
  // Chapter 9.3 applies the algorithm to a block's first and last instructions.
  // The prefix has no control discontinuity; only the tail owns itype/notify.
  val firstOfMulti   = cur.valid && qualified && !trapPhase && cur.iretire > lastHalfwords && !tail
  val retiringTrap   = Itype.isTrap(cur.itype) && cur.iretire =/= 0.U && !trapPhase
  val lastBeforeTrap = retiringTrap && !firstOfMulti
  val internalStep   = firstOfMulti || lastBeforeTrap
  val address        = Mux(trapPhase, cur.iaddr + (cur.iretire << 1), Mux(tail, lastPC, cur.iaddr))
  val kind           = Mux(firstOfMulti || retiringTrap, Itype.None, cur.itype)
  val curNotify      = !firstOfMulti && !trapPhase && (cur.trigger === TriggerAction.Notify || cur.notifyRequested)
  val branch         = qualified && Itype.isBranchType(kind)
  val taken          = Itype.isBranchTaken(kind)
  val closing        = configPending || configurationChanged || stopPending || io.stopReq
  val hasNext        = internalStep || io.block.valid || closing
  val nextQualifiedInput = running && !closing && io.fromCtrl.filterRes && !io.block.traceLost &&
    !metadataOnly && !io.stopReq && !io.block.hartIsReset && io.block.priv =/= Priv.D
  val nextQualified = Mux(internalStep, qualified, nextQualifiedInput)
  val nextTrap = lastBeforeTrap || (!internalStep && io.block.valid &&
    Itype.isTrap(io.block.itype) && io.block.iretire === 0.U)
  val nextBoundary = !internalStep && (cur.priv =/= io.block.priv ||
    io.block.ctype === Ctype.Precisely || io.block.ctype === Ctype.AsyncDiscontinuity)
  val previousTrap = previousQualified && !tail && !trapPhase && Itype.isTrap(previous.itype)
  val previousJump = previousQualified && !tail && !trapPhase &&
    (Itype.isUninferable(previous.itype) || previous.itype === Itype.ExpIntReturn)
  val endCurrent = cur.valid && qualified && !internalStep &&
    (closing || (io.block.valid && !io.block.traceLost &&
      (!nextQualifiedInput || io.block.ctype === Ctype.AsyncDiscontinuity)))
  val lossWanted = cur.valid && cur.traceLost
  val supportWanted =
    lossWanted || endPending || ((!cur.valid || !qualified) && (configPending || configurationChanged))
  io.blockReady := canAdvance && !internalStep && !replayCurrent && !supportWanted && (!closing || !qualified) && !endCurrent
  val step = cur.valid && hasNext && canAdvance && !supportWanted && !replayCurrent
  when(step) { tail := firstOfMulti; trapPhase := lastBeforeTrap }
  when(io.block.valid && io.blockReady) {
    cur               := io.block
    previous          := cur
    qualified         := nextQualifiedInput
    previousQualified := qualified
    contextReported   := false.B
  }

  val syncRequested = RegInit(false.B)
  val forceSync     = RegInit(false.B)
  when(io.syncReq && running)(syncRequested := true.B)
  when(canAdvance)(forceSync := syncRequested)
  val resyncCount = RegInit(0.U(20.W))
  val syncMax     = (1.U(20.W) << (control.InstSyncMax.asUInt +& 4.U))(19, 0)
  val syncRequired =
    (!tail && !trapPhase && (!previousQualified || cur.priv =/= previous.priv ||
      cur.ctype === Ctype.Precisely || cur.ctype === Ctype.AsyncDiscontinuity)) ||
      forceSync || resyncCount >= syncMax
  val trapReported = RegInit(false.B)
  val reference    = RegInit(0.U(iaddress_width_p.W))
  // Outcomes before the current logical instruction, in execution order.
  val branchMap            = RegInit(0.U(31.W))
  val branchCount          = RegInit(0.U(5.W))
  val count                = branchCount +& branch.asUInt
  val map                  = branchMap | (Mux(branch && !taken, 1.U(31.W), 0.U(31.W)) << branchCount)
  val payload              = WireDefault(0.U(payload_max_width.W))
  val emit                 = WireDefault(false.B)
  val consumesBranches     = WireDefault(false.B)
  val syncPacket           = WireDefault(false.B)
  val carriesAddress       = WireDefault(false.B)
  val reportedAddress      = WireDefault(address)
  val supportPacket        = WireDefault(false.B)
  val supportChangesConfig = WireDefault(false.B)
  val supportConfig        = WireDefault(activeConfig)
  val finishesCurrent      = WireDefault(false.B)

  def emitPayload(value: UInt): Unit = {
    payload := ETracePacketCodec.extend(value, payload_max_width)
    emit    := true.B
  }
  def synchronization(): Unit = {
    val packet = Wire(new Format30)
    packet.format    := 3.U
    packet.subformat := 0.U
    packet.branch    := !taken
    packet.privilege := cur.priv
    packet.time.foreach(_ := cur.time)
    packet.context.foreach(_ := cur.context)
    packet.address := address >> iaddress_lsb_p
    emitPayload(packet.asUInt)
    syncPacket       := true.B
    consumesBranches := true.B
    carriesAddress   := true.B
  }
  def trap(
      cause:     UInt,
      interrupt: Bool,
      value:     UInt,
      handler:   Bool,
      pc:        UInt = address,
      metadata:  BlockBufferEntry = cur
  ): Unit = {
    val packet = Wire(new Format31)
    packet.format    := 3.U
    packet.subformat := 1.U
    packet.branch    := !taken
    packet.privilege := metadata.priv
    packet.time.foreach(_ := metadata.time)
    packet.context.foreach(_ := metadata.context)
    packet.ecause    := cause
    packet.interrupt := interrupt
    packet.thaddr    := handler
    packet.address   := pc >> iaddress_lsb_p
    packet.tval      := Mux(interrupt, Fill(iaddress_width_p, pc.head(1)), value)
    reportedAddress  := pc
    emitPayload(packet.asUInt)
    syncPacket       := true.B
    consumesBranches := true.B
    carriesAddress   := true.B
  }
  def addressPacket(requestedNotify: Bool, beforeSync: Bool): Unit = {
    val delta = Wire(UInt(iaddress_width_p.W))
    delta := Mux(activeConfig(5), address, address - reference)
    payload := ETracePacketCodec.address(
      count(4, 0),
      map(30, 0),
      delta(iaddress_width_p - 1, iaddress_lsb_p),
      requestedNotify,
      beforeSync,
      payload_max_width
    )
    emit             := true.B
    consumesBranches := true.B
    carriesAddress   := true.B
  }

  when(cur.valid && hasNext && qualified && running && !supportWanted) {
    when(previousTrap) {
      when(Itype.isTrap(kind)) {
        when(!trapReported) {
          // The prior handler never retired. Report that trap before consuming
          // the new event; its cause/privilege must not be overwritten.
          trap(previous.cause, Itype.isInt(previous.itype), previous.tval, false.B, previous.iaddr, previous)
          replayCurrent := true.B
        }.otherwise {
          trap(cur.cause, Itype.isInt(kind), cur.tval, false.B)
        }
        when(generated.fire)(trapReported := true.B)
      }.elsewhen(trapReported) {
        synchronization()
        when(generated.fire)(trapReported := false.B)
      }.otherwise {
        trap(previous.cause, Itype.isInt(previous.itype), previous.tval, true.B)
      }
    }.elsewhen(Itype.isTrap(kind)) {
      when(previousJump || (!previousQualified && !trapPhase) || endCurrent) {
        trap(cur.cause, Itype.isInt(kind), cur.tval, false.B)
        when(generated.fire)(trapReported := true.B)
      }
    }.elsewhen(syncRequired && branchCount === 0.U) {
      synchronization()
    }.elsewhen(!tail && !trapPhase && !contextReported && cur.ctype === Ctype.Imprecisely) {
      // A higher-priority address/notify must not swallow an imprecise event.
      // Serialize the metadata, then replay the same logical instruction.
      val packet = Wire(new Format32)
      packet.format    := 3.U
      packet.subformat := 2.U
      packet.privilege := cur.priv
      packet.time.foreach(_ := cur.time)
      packet.context.foreach(_ := cur.context)
      emitPayload(packet.asUInt)
      replayCurrent := true.B
      when(generated.fire)(contextReported := true.B)
    }.elsewhen(previousJump) {
      addressPacket(false.B, nextTrap || nextBoundary || syncRequired || endCurrent)
    }.elsewhen(curNotify || nextTrap || nextBoundary || !nextQualified || syncRequired || endCurrent) {
      // If history is pending, flush it with an address before the next sync.
      addressPacket(curNotify, false.B)
    }.elsewhen(count === 31.U) {
      payload          := ETracePacketCodec.branchOnly(map(30, 0), payload_max_width)
      emit             := true.B
      consumesBranches := true.B
    }
    finishesCurrent := endCurrent && !replayCurrent
  }.elsewhen(supportWanted) {
    supportConfig        := Mux(lossWanted, activeConfig, Mux(endPending, endingConfig, nextConfig))
    supportChangesConfig := !lossWanted && Mux(endPending, endingChangesConfig, true.B)
    val packet = Wire(new Format33)
    packet.format       := 3.U
    packet.subformat    := 3.U
    packet.ienable      := supportConfig(7) && supportConfig(6)
    packet.encoder_mode := 0.U
    packet.qual_status := Mux(
      lossWanted,
      QualStatus.Trace_lost,
      Mux(endPending, QualStatus.Ended_req, QualStatus.No_change)
    )
    packet.sijump     := supportConfig(0)
    packet.imRet      := supportConfig(1)
    packet.imExp      := supportConfig(2)
    packet.branchPred := supportConfig(3)
    packet.jumpCache  := supportConfig(4)
    packet.fullAddr   := supportConfig(5)
    packet.denable    := false.B
    packet.dloss      := false.B
    packet.doptions   := 0.U
    emitPayload(packet.asUInt)
    supportPacket := true.B
  }

  when(step && cur.valid) {
    when(!qualified || consumesBranches) {
      branchMap   := 0.U
      branchCount := 0.U
    }.otherwise {
      assert(count <= 31.U)
      branchMap   := map
      branchCount := count
    }
  }
  when(generated.fire) {
    when(finishesCurrent) {
      cur.valid           := false.B
      qualified           := false.B
      previousQualified   := false.B
      tail                := false.B
      trapPhase           := false.B
      endPending          := true.B
      endingConfig        := Mux(configPending || configurationChanged, nextConfig, activeConfig)
      endingChangesConfig := configPending || configurationChanged
    }
    when(supportPacket) {
      activeConfig := supportConfig
      when(supportChangesConfig)(configPending := false.B)
      endPending        := false.B
      stopPending       := false.B
      previousQualified := false.B
      branchMap         := 0.U
      branchCount       := 0.U
      trapReported      := false.B
      when(lossWanted) {
        // Consume the metadata exactly once, when its support packet is queued.
        cur.valid    := false.B
        qualified    := false.B
        tail         := false.B
        trapPhase    := false.B
        trapReported := false.B
        reference    := 0.U
        resyncCount  := 0.U
      }
    }
    when(carriesAddress)(reference := reportedAddress)
    when(syncPacket) {
      resyncCount   := 0.U
      syncRequested := false.B
      forceSync     := false.B
    }.elsewhen(resyncCount < syncMax) {
      resyncCount := resyncCount + 1.U
    }
  }
  val source = Wire(UInt(src_bits_p.W))
  source          := io.fromCtrl.teInstFeat.SrcID.asUInt
  generated.bits  := ETracePacketCodec.encapsulate(payload, source, maxPackets)
  generated.valid := mayGenerate && emit
  when(stopPending && (!cur.valid || !qualified) && !endPending)(stopPending := false.B)
}
