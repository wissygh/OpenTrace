    .section .text.init, "ax"
    .option norelax
    .option norvc
    .global _start
_start:
    csrw mie, zero
1:  j 1b
    .global trace_begin
trace_begin:
    la t0, trace_handler
    csrw mtvec, t0
    li a0, 0
    ecall
    .global trace_after_ecall
trace_after_ecall:
    addi a0, a0, 1
    .word 0x00000000             # illegal instruction; never retires
    .global trace_after_illegal
trace_after_illegal:
    addi a0, a0, 2
    j trace_done
    .global trace_handler
trace_handler:
    csrr t1, mcause
    csrr t2, mepc
    addi t2, t2, 4
    csrw mepc, t2
    addi a0, a0, 10
    mret
    .global trace_done
trace_done:
    j trace_done
