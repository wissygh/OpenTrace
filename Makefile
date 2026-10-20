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

.PHONY: test-trace test-decoder

test-decoder:
	python3 -m unittest discover -s decoder/tests -v

test-trace:
	mill -i OpenTrace.test.testOnly trace.component.FifoSpec trace.component.PacketArbiterSpec trace.component.BlockBufferSpec trace.component.PacketGenSpec trace.component.SRAMSinkCoreSpec trace.component.TraceATBAdapterSpec trace.MultiHartTraceSpec
	python3 -m unittest discover -s decoder/tests -v
	python3 -m decoder.check_rtl_capture build/validation/two-hart-from-core.bin
