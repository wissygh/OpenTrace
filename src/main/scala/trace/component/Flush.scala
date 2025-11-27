package trace.component

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import trace.TraceParameters
import trace.interface._
import trace.NodeType



class Flush(node: NodeType) extends Module with TraceParameters {

  val io = IO(new Bundle {
    val ctrlDisable = Input(Bool())
    val flushSelfComp = Input(Bool())
    val flushState = Output(UInt(2.W))
    val flushSlave = Option.when(!node.isSource)(DecoupledIO(new Bundle {})) // to source
    val flushMatser = Option.when(!node.isSink)(Flipped(DecoupledIO(new Bundle {}))) // from sink
  })
  /**
    * state machine
    */

  val idel :: flushSource :: flushSelf :: flushAll :: Nil = Enum(4)
  val stateReg = RegInit(idel)
  val stateNext = WireInit(stateReg)

  if(node.isSource) {
    switch(stateReg) {
      is(idel) {
        when(io.ctrlDisable || io.flushMatser.get.valid) {
          stateNext := flushSelf
        }
      }
      is(flushSelf) {
        when(io.flushSelfComp) {
          stateNext := flushAll
        }
      }
      is(flushAll) {
        when(io.flushSelfComp) {
          stateNext := idel
        }
      }
    } 
  } else if(node.isFunnel) {
    switch(stateReg) {
      is(idel) {
        when(io.ctrlDisable || io.flushMatser.get.valid) {
          stateNext := flushSource
        }
      }
      is(flushSource) {
        when(io.flushSlave.get.ready){
          stateNext := flushSelf
        }
      }
      is(flushSelf) {
        when(io.flushSelfComp) {
          stateNext := flushAll
        }
      }
      is(flushAll) { // one cycle
        when(io.flushSelfComp) {
          stateNext := idel
        }
      }
    }
  } else if(node.isSink) {
    switch(stateReg) {
      is(idel) {
        when(io.ctrlDisable || io.flushMatser.get.valid) {
          stateNext := flushSource
        }
      }
      is(flushSource) {
        when(io.flushSlave.get.ready){
          stateNext := flushAll
        }
      }
    }
  }

  io.flushState := stateReg.asUInt
  io.flushSlave.foreach(_.valid := stateReg === flushSource)
  io.flushMatser.foreach(_.ready := stateReg === flushAll)
}