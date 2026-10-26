# Clang PCH files embed absolute test directories. Reusing them through ccache
# after a Mill sandbox/test-name move can reference removed files.
export CCACHE_DISABLE ?= 1

compile:
	mill -i OpenTrace.compile

verilog:
	mill -i OpenTrace.test.runMain trace.TraceMain

clean:
	rm -rf ./build

init:
	git submodule update --init
	cd rocket-chip && git submodule update --init cde hardfloat

bump:
	git submodule foreach "git fetch origin&&git checkout master&&git reset --hard origin/master"

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

check-format:
	mill OpenTrace.checkFormat

reformat:
	mill OpenTrace.reformat

.PHONY: test-trace test-decoder test-atb test-encoder test-control test-integration test-smem check-rtl check-smem-rtl check-hpm-rtl check-pib-rtl check-limits-rtl test-pib test-system test-funnel test-single-hart test-xiangshan-sba test-packet-types test-acceptance test-xiangshan-sba-pressure test-xiangshan-ingress check-ingress-rtl check-rtl-lint

.PHONY: test-discovery
test-discovery:
	@echo "[Table42] 实际Encoder只读发现CSR→80byte快照→decoder参数和完整退休流"
	mill -i OpenTrace.test.testOnly trace.component.EncoderDiscoverySpec
	python3 -m decoder.tests.check_discovery_capture build/validation/encoder-discovery

.PHONY: test-jump-cache
test-jump-cache:
	@echo "[jump-cache] 符号压缩后按实际packet长度择优；collision/epoch/prediction/反压及decoder"
	mill -i OpenTrace.test.testOnly trace.component.PacketJumpCacheSpec trace.component.PacketJumpCacheCostSpec trace.component.PacketBranchPredictionSpec
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-jump-cache
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-branch-pred

.PHONY: test-implicit-return
test-implicit-return:
	@echo "[implicit-return] 返回栈/packet/实际Encoder→Funnel→SRAM；逐条核对递归/失配/压缩/组合"
	mill -i OpenTrace.test.testOnly trace.component.TraceReturnStackSpec trace.component.ImplicitReturnCodecSpec trace.component.PacketPrivilegeReturnSpec trace.component.PacketImplicitReturnSpec trace.component.ImplicitReturnSramSpec
	python3 -m decoder.tests.check_implicit_return build/validation/implicit-return
	python3 -m decoder.tests.check_implicit_return build/validation/requirements13-xret/packet

.PHONY: test-implicit-exception
test-implicit-exception:
	@echo "[implicit-exception] NoTrapAddr CSR→handler地址省略/EPC保留/tval打包→真实SRAM→逐条decoder"
	mill -i OpenTrace.test.testOnly trace.component.PacketImplicitExceptionSpec trace.component.ImplicitExceptionSramSpec trace.component.EncoderCapabilitiesSpec
	python3 -m decoder.tests.check_implicit_exception build/validation/implicit-exception

.PHONY: test-hart-stop
test-hart-stop:
	@echo "[hart-stop] 真实积压退休→Debug/reset有序停止→resume同步，检查全部退休与trace-end"
	mill -i OpenTrace.test.testOnly trace.component.HartStopSramSpec
	python3 -m decoder.tests.check_hart_stop build/validation/hart-stop

XIANGSHAN_EMU ?= $(CURDIR)/delp/XiangShan-trace/build/emu
XIANGSHAN_SBA_OUTPUT ?= $(CURDIR)/delp/tmp/xiangshan-sba-run
XIANGSHAN_SBA_PRESSURE_OUTPUT ?= $(CURDIR)/delp/tmp/xiangshan-sba-pressure-run
XIANGSHAN_SINK ?= atb
XIANGSHAN_SINK_OUTPUT ?= $(CURDIR)/delp/tmp/xiangshan-$(XIANGSHAN_SINK)-run
XIANGSHAN_SINK_ARGS ?=
XIANGSHAN_EXCEPTION_OUTPUT ?= $(CURDIR)/delp/tmp/xiangshan-exception-run

.PHONY: test-xiangshan-sink test-xiangshan-exception
test-xiangshan-sink:
	@echo "[XS-sink] 外部JTAG/SBA→实际$(XIANGSHAN_SINK)输出→解封装/完整执行流→独立ROB"
	python3 -m decoder.tests.run_xiangshan_sba --emu "$(XIANGSHAN_EMU)" --output "$(XIANGSHAN_SINK_OUTPUT)" --sink "$(XIANGSHAN_SINK)" --discovery $(XIANGSHAN_SINK_ARGS)

test-xiangshan-exception:
	@echo "[XS-exception] 外部SBA启用NoTrapAddr；普通ecall/illegal/handler/mret完整路径与真实mtvec核验"
	python3 -m decoder.tests.run_xiangshan_sba --emu "$(XIANGSHAN_EMU)" --output "$(XIANGSHAN_EXCEPTION_OUTPUT)" --trap --features 2 --discovery

test-xiangshan-sba:
	@echo "[XS-SBA] 启用已有emu的JTAG；外部SBA配置/停止/读SRAM，再与ROB采样对拍"
	python3 -m decoder.tests.run_xiangshan_sba --emu "$(XIANGSHAN_EMU)" --output "$(XIANGSHAN_SBA_OUTPUT)"

test-xiangshan-sba-pressure:
	@echo "[XS-SBA-pressure] 现有emu：外部SBA延迟启用SRAM，验证真实core stall与ROB退休流"
	python3 -m decoder.tests.run_xiangshan_sba --emu "$(XIANGSHAN_EMU)" --output "$(XIANGSHAN_SBA_PRESSURE_OUTPUT)" --pressure

test-packet-types:
	@echo "[mixed-packets] 3种seed反压：全部instruction格式→真实FIFO/SRAM→独立wire/PC参考与decoder"
	mill -i OpenTrace.test.testOnly trace.component.PacketMixedFormatsSpec
	python3 -m decoder.tests.check_mixed_capture build/validation/mixed-packets

test-xiangshan-ingress:
	@echo "[XS-ingress] 复用用户现有TraceBuffer.sv，比较默认与64-entry/16-cycle预算；不编emu"
	mill -i OpenTrace.test.testOnly trace.component.XiangShanIngressSpec

check-ingress-rtl:
	mill -i OpenTrace.test.runMain trace.TraceIngressMain
	python3 -m decoder.tests.check_generated_rtl build/rtl --smem --hpm --pib --limits --ingress

check-rtl-lint:
	@echo "[rtl-lint] 根据生成manifest检查所有profile的pin连接与宽度截断"
	python3 -m decoder.tests.lint_generated_rtl

test-acceptance:
	@echo "[single-acceptance] 实际Encoder/Funnel/SRAM：控制事件、1拍stall反馈、真实overflow→decoder"
	mill -i OpenTrace.test.testOnly trace.component.SingleHartAcceptanceSpec
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/controls
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/stall
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/stall-16
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/overflow

test-single-hart:
	@echo "[single-hart] 实际Trace/SRAM，SoCMisc CPU/SBA配置与decoder完整执行流"
	mill -i OpenTrace.test.testOnly trace.SingleHartSramSpec
	python3 -m decoder.tests.check_single_hart_sram build/validation

test-funnel:
	@echo "[Funnel-test] 1/3/16源公平性、包边界、输入屏蔽、Active关闭与Disable自动排空"
	mill -i OpenTrace.test.testOnly trace.component.PacketArbiterSpec trace.component.SourcePacketStagerSpec trace.component.FunnelResetSpec trace.component.FunnelInputSpec trace.component.FunnelDrainSpec

check-limits-rtl:
	@echo "[Funnel-limit] 3 hart非2次幂与16 hart上限实际TL/SRAM顶层"
	mill -i OpenTrace.test.testOnly trace.TraceParametersSpec
	mill -i OpenTrace.test.runMain trace.TraceLimitsMain
	python3 -m decoder.tests.check_generated_rtl build/rtl --smem --hpm --pib --limits

check-rtl:
	@echo "[rtl-check] 1/4 编译并检查合法/非法 profile"
	mill -i OpenTrace.compile
	mill -i OpenTrace.test.testOnly trace.TraceParametersSpec
	@echo "[rtl-check] 2/4 生成默认 TL/AXI/APB"
	mill -i OpenTrace.test.runMain trace.TraceMain
	@echo "[rtl-check] 3/4 生成双 hart SRAM/ATB 变体"
	mill -i OpenTrace.test.runMain trace.TraceVariantsMain
	@echo "[rtl-check] 4/4 核对公共端口、ATB 标准边界和生成文件清单"
	python3 -m decoder.tests.check_generated_rtl build/rtl

test-encoder:
	@echo "[encoder-golden] 1/3 独立位布局、CSR 生命周期与实际 PacketGen 对拍"
	mill -i OpenTrace.test.testOnly trace.component.ETraceCodecSpec trace.component.PacketGenSpec trace.component.PacketEncodingSpec trace.component.InstructionReferenceSpec trace.component.PacketLifecycleSpec trace.component.PacketTrapSpec trace.component.PacketContextSpec trace.component.PacketFormat3Spec trace.component.PacketSupportSpec trace.component.PacketJumpCacheSpec trace.component.PacketBranchPredictionSpec trace.component.PacketMixedFormatsSpec trace.component.EncoderCapabilitiesSpec trace.component.EncoderTimestampSpec trace.component.EncoderStatisticsSpec trace.component.PIBSerializerSpec trace.component.TraceComparatorSpec trace.component.TraceFilterLogicSpec trace.component.TraceTrapFilterSpec trace.component.TraceLossSpec
	@echo "[encoder-golden] 2/3 实际 RTL 捕获交给 decoder，比较退休 PC 前缀"
	python3 -m decoder.tests.check_packetgen_capture build/validation
	@echo "[encoder-golden] 3/3 停止/丢失/异常/context/support 捕获，比较完整退休流"
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-lifecycle
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-loss
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-traps
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-context
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-support
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-jump-cache
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-branch-pred
	python3 -m decoder.tests.check_mixed_capture build/validation/mixed-packets

check-smem-rtl:
	@echo "[SMEM-rtl] TL/AXI/APB 双 hart SMEM，显式测试地址窗口"
	mill -i OpenTrace.test.runMain trace.TraceSMEMMain
	python3 -m decoder.tests.check_generated_rtl build/rtl --smem

test-smem:
	@echo "[SMEM-test] packet 提交/StopOnWrap/错误/abort 及 AXI AW/W/B 交互"
	mill -i OpenTrace.test.testOnly trace.component.SMEMSinkCoreSpec trace.component.SMEMAXIWriterSpec trace.component.SMEMControlSpec trace.component.TraceSinkPacketGuardSpec trace.component.SourcePacketStagerSpec trace.component.FunnelResetSpec trace.component.FunnelInputSpec trace.component.FunnelDrainSpec

test-atb:
	@echo "[ATB-test] AF/SYNCREQ/ATCLKEN/ATWAKEUP 与多时钟端到端验证"
	mill -i OpenTrace.test.testOnly trace.component.TraceAlignmentSpec trace.component.ATBProtocolSpec trace.component.ATBCrossingSpec trace.component.ATBClockRequestSpec trace.component.TraceATBAdapterSpec trace.component.PacketGenSpec

check-hpm-rtl:
	@echo "[core-HPM-rtl] 双 hart HPM 接口与默认端口不变检查"
	mill -i OpenTrace.test.runMain trace.TraceHPMMain
	python3 -m decoder.tests.check_generated_rtl build/rtl --smem --hpm

check-pib-rtl:
	@echo "[PIB-rtl] 三种控制总线的双 hart 独占 PIB 输出及默认端口检查"
	mill -i OpenTrace.test.runMain trace.TracePIBMain
	python3 -m decoder.tests.check_generated_rtl build/rtl --smem --hpm --pib

test-pib:
	@echo "[PIB-test] serializer/clock-center/UART/Manchester/calibration/CSR/Mictor"
	mill -i OpenTrace.test.testOnly trace.component.PIBSerializerSpec trace.control.PIBControlSpec

test-control:
	@echo "[control-test] Active/CSR 响应反压、复位恢复与能力发现"
	mill -i OpenTrace.test.testOnly trace.control.TraceControlEndpointSpec trace.component.SRAMControlSpec trace.control.ComponentDiscoverySpec trace.control.ControlRegisterMapSpec trace.control.CoreHPMSpec trace.control.PIBControlSpec trace.component.EncoderCapabilitiesSpec trace.component.EncoderTimestampSpec trace.component.EncoderStatisticsSpec trace.component.PIBSerializerSpec trace.component.TraceComparatorSpec trace.component.TraceFilterLogicSpec trace.component.TraceTrapFilterSpec

test-system:
	@echo "[trace-system] 实际TL配置/四时钟/encoder/控制与数据CDC/ATB/AF/decoder"
	mill -i OpenTrace.test.testOnly trace.TraceSystemSpec
	python3 -m decoder.tests.check_trace_system build/validation
	python3 -m decoder.tests.check_trace_system build/validation trace-system-reset
	python3 -m decoder.tests.check_trace_system build/validation trace-system-funnel-reset
	python3 -m decoder.tests.check_trace_system build/validation trace-system-mask
	python3 -m decoder.tests.check_trace_system build/validation trace-system-clock-gate

test-integration:
	@echo "[trace-integration] 双核完整执行流经过真实仲裁和 SRAM 存储后交给 decoder"
	mill -i OpenTrace.test.testOnly trace.MultiHartExecutionSpec
	python3 -m decoder.tests.check_multihart_flow build/validation
	python3 -m decoder.tests.check_multihart_flow build/validation two-hart-smem
	python3 -m decoder.tests.check_multihart_flow build/validation two-hart-pib

test-decoder:
	@echo "[trace-test] Python decoder 单元测试开始：encapsulation / multihart / execution"
	python3 -m unittest discover -s decoder/tests -v

test-trace:
	@echo "[trace-test] 1/4 Scala RTL 行为与编码 golden 测试开始"
	mill -i OpenTrace.test.testOnly trace.component.FifoSpec trace.component.PacketArbiterSpec trace.component.BlockBufferSpec trace.component.IngressReferenceSpec trace.component.BlockBackpressureSpec trace.component.PacketGenSpec trace.component.ETraceCodecSpec trace.component.PacketEncodingSpec trace.component.InstructionReferenceSpec trace.component.PacketLifecycleSpec trace.component.PacketTrapSpec trace.component.PacketContextSpec trace.component.PacketFormat3Spec trace.component.PacketSupportSpec trace.component.PacketJumpCacheSpec trace.component.PacketBranchPredictionSpec trace.component.PacketMixedFormatsSpec trace.component.EncoderCapabilitiesSpec trace.component.EncoderTimestampSpec trace.component.EncoderStatisticsSpec trace.component.PIBSerializerSpec trace.component.TraceComparatorSpec trace.component.TraceFilterLogicSpec trace.component.TraceTrapFilterSpec trace.component.TraceLossSpec trace.component.SRAMSinkCoreSpec trace.component.SRAMControlSpec trace.component.SMEMSinkCoreSpec trace.component.SMEMAXIWriterSpec trace.component.SMEMControlSpec trace.component.TraceSinkPacketGuardSpec trace.component.SourcePacketStagerSpec trace.component.FunnelResetSpec trace.component.FunnelInputSpec trace.component.FunnelDrainSpec trace.component.TraceATBAdapterSpec trace.component.TraceAlignmentSpec trace.component.ATBProtocolSpec trace.component.ATBCrossingSpec trace.component.ATBClockRequestSpec trace.component.TraceAsyncVariableClockSpec trace.TraceControlClockSpec trace.component.SingleHartAcceptanceSpec trace.MultiHartTraceSpec trace.MultiHartExecutionSpec trace.TraceSystemSpec trace.control.TraceControlEndpointSpec trace.control.ComponentDiscoverySpec trace.control.ControlRegisterMapSpec trace.control.CoreHPMSpec trace.control.PIBControlSpec trace.TraceParametersSpec
	@echo "[trace-test] 2/4 Python decoder 单元测试开始"
	python3 -m unittest discover -s decoder/tests -v
	@echo "[trace-test] 3/4 双核 SRAM 捕获：启动与完整执行流解码"
	python3 -m decoder.check_rtl_capture build/validation/two-hart-from-core.bin
	python3 -m decoder.tests.check_trace_system build/validation
	python3 -m decoder.tests.check_trace_system build/validation trace-system-reset
	python3 -m decoder.tests.check_trace_system build/validation trace-system-funnel-reset
	python3 -m decoder.tests.check_trace_system build/validation trace-system-mask
	python3 -m decoder.tests.check_trace_system build/validation trace-system-clock-gate
	python3 -m decoder.tests.check_multihart_flow build/validation
	python3 -m decoder.tests.check_multihart_flow build/validation two-hart-smem
	python3 -m decoder.tests.check_multihart_flow build/validation two-hart-pib
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/controls
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/stall
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/stall-16
	python3 -m decoder.tests.check_acceptance_capture build/validation/single-hart-acceptance/overflow
	@echo "[trace-test] 4/4 分支/跳转/压缩指令 RTL->decoder 执行流对拍"
	python3 -m decoder.tests.check_packetgen_capture build/validation
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-lifecycle
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-loss
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-traps
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-context
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-support
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-jump-cache
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-branch-pred
	python3 -m decoder.tests.check_mixed_capture build/validation/mixed-packets

.PHONY: check-atb-clock-request-rtl
check-atb-clock-request-rtl:
	@echo "[ATB-clock-request] 默认接口不变/可选源域请求；全停clock恢复与标准ATWAKEUP保持本域"
	mill -i OpenTrace.test.runMain trace.TraceClockRequestMain
