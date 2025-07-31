package trace.component

import org.chipsalliance.cde.config.Parameters
import trace.control._

class ATBBridge(baseAddr: Int)(implicit p: Parameters) extends ComponentBase(baseAddr) {

  override lazy val module = new ATBBridgeImp(this)(baseAddr)
}

class ATBBridgeImp(wrapper: ATBBridge)(baseAddr: Int)(implicit p: Parameters) extends ComponentBaseImp(wrapper)(baseAddr)
  with ATBBridgeControl
{
  connectCtrlReg()
}