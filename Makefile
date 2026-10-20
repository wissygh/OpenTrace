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

.PHONY: test-trace test-decoder test-atb test-encoder

test-encoder:
	@echo "[encoder-golden] 1/3 独立位布局、CSR 生命周期与实际 PacketGen 对拍"
	mill -i OpenTrace.test.testOnly trace.component.ETraceCodecSpec trace.component.PacketGenSpec trace.component.PacketEncodingSpec trace.component.PacketLifecycleSpec trace.component.PacketTrapSpec trace.component.TraceLossSpec
	@echo "[encoder-golden] 2/3 实际 RTL 捕获交给 decoder，比较退休 PC 前缀"
	python3 -m decoder.tests.check_packetgen_capture build/validation
	@echo "[encoder-golden] 3/3 完整停止/重启捕获，比较全部退休指令"
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-lifecycle
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-loss
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-traps

test-atb:
	@echo "[ATB-test] AF/SYNCREQ/ATCLKEN/ATWAKEUP 与多时钟端到端验证"
	mill -i OpenTrace.test.testOnly trace.component.ATBProtocolSpec trace.component.ATBCrossingSpec trace.component.TraceATBAdapterSpec trace.component.PacketGenSpec

test-decoder:
	@echo "[trace-test] Python decoder 单元测试开始：encapsulation / multihart / execution"
	python3 -m unittest discover -s decoder/tests -v

test-trace:
	@echo "[trace-test] 1/4 Scala RTL 行为与编码 golden 测试开始"
	mill -i OpenTrace.test.testOnly trace.component.FifoSpec trace.component.PacketArbiterSpec trace.component.BlockBufferSpec trace.component.BlockBackpressureSpec trace.component.PacketGenSpec trace.component.ETraceCodecSpec trace.component.PacketEncodingSpec trace.component.PacketLifecycleSpec trace.component.PacketTrapSpec trace.component.TraceLossSpec trace.component.SRAMSinkCoreSpec trace.component.TraceATBAdapterSpec trace.component.ATBProtocolSpec trace.component.ATBCrossingSpec trace.MultiHartTraceSpec
	@echo "[trace-test] 2/4 Python decoder 单元测试开始"
	python3 -m unittest discover -s decoder/tests -v
	@echo "[trace-test] 3/4 双核启动 RTL->decoder 联动检查"
	python3 -m decoder.check_rtl_capture build/validation/two-hart-from-core.bin
	@echo "[trace-test] 4/4 分支/跳转/压缩指令 RTL->decoder 执行流对拍"
	python3 -m decoder.tests.check_packetgen_capture build/validation
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-lifecycle
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-loss
	python3 -m decoder.tests.check_packetgen_capture build/validation packetgen-traps
