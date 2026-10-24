    .section .text.init, "ax"
    .option norelax
    .option norvc
    .global _start
_start:
    csrw mie, zero
1:  j 1b
    .global trace_begin
trace_begin:
    addi a0, zero, 1
    addi a0, a0, 2
    .global trace_debug_break
trace_debug_break:
    ebreak
    .global trace_after_ebreak
trace_after_ebreak:
    addi a0, a0, 4
    addi a0, a0, 8
    j trace_done
    .global trace_handler
trace_handler:
    csrr t0, mcause
    csrr t1, mepc
    addi t1, t1, 4
    csrw mepc, t1
    mret
    .global trace_done
trace_done:
    j trace_done
