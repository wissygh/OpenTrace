package trace

import chisel3._
import utils._


/**
 * Optional and run-time configurable mode, 1h:
 *   Delta address mode
 *   Full address mode
 *   Implicit exception mode
 *   Sequentially inferable jump mode
 *   Branch prediction mode
 *   Jump target cache mode
 */

//object ConfigMode extends NamedUInt(6) {
//  def deltaAddr  = 1.U
//  def fullAddr   = 2.U
//  def imExp      = 4.U
//  def sijump     = 8.U
//  def BranchPred = 16.U
//  def JumpCache  = 32.U
//}

/**
 * components including:
 * 1. encoder
 * 2. funnel
 * 3. atbBridge
 * 4. pib
 * 5. ramsink
 *
 * hart(interface) ---> encoder   \              / pib(off-chip)
 *                                 \           /
 *                                  \        /
 * hart(interface) ---> encoder ----> funnel ----> atbBrideg(bus)
 *                                   /      \
 *                                 /         \
 * hart(interface) ---> encoder  /            \ ramsink(on-chip)
 */
