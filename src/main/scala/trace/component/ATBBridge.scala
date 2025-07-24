package trace.component

import org.chipsalliance.cde.config.Parameters
import trace.control._

/**
 * Since the PIB supports many different modes, it is necessary to follow a particular programming
 * sequence:
 *
 * • Activate the PIB by setting trPibActive.
 *
 * • Set the trPibMode, trPibDivider, trPibClkCenter, and trPibCalibrate fields. This will set the
 * TRC_DATA outputs to the quiescent state (whether that is high or low depends on trPibMode) and
 * start TRC_CLK running.
 *
 * • Activate the receiving device, such as a trace probe. Allow time for PLL to sync up, if using a PLL
 * with a parallel PIB mode.
 *
 * • Set trPibEnable. This enables the PIB to generate output either immediately (calibration mode) or
 * when the Trace Encoder or Trace Funnel begins sending trace messages/packets.
 */


class ATBBridge(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr)
  with ATBBridgeControl
{

  connectCtrlReg()
}