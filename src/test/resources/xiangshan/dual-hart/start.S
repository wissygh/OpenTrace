    .section .text.init, "ax"
    .option norelax
    .option norvc
    .global _start
_start:
    csrw mie, zero
    j .
    .global trace_begin
trace_begin:
    la sp, __stack_top
    li t0, 0x6000
    csrs mstatus, t0
    csrwi fcsr, 0
    la t0, __bss_start
    la t1, __bss_end
1:  bgeu t0, t1, 2f
    sd zero, 0(t0)
    addi t0, t0, 8
    j 1b
2:  call benchmark_run
    # Default SRAM-profile Debug SBA exposes trace CSR, not cached DRAM. Export
    # actual benchmark results in architectural registers before the breakpoint.
    la t0, trace_result
    lw a0, 32(t0)
    lw a1, 12(t0)
    lw a2, 16(t0)
    lw a3, 20(t0)
    lw a4, 24(t0)
    lw a5, 28(t0)
    lw a6, 8(t0)
    lw a7, 0(t0)
    lw s0, 4(t0)
    ld t1, 40(t0)
    ld s1, 48(t0)
    sub s1, s1, t1
    ld t1, 56(t0)
    ld s2, 64(t0)
    sub s2, s2, t1
    .global trace_done
trace_done:
    j trace_done
