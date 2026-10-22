    .section .text.init, "ax"
    .option norelax
    .global _start
_start:
    csrw mie, zero
1:  j 1b
    .balign 4
    .global trace_begin
trace_begin:
    c.li a0, 0
    .option norvc
    addi t0, zero, 80
    la t1, trace_target
    .global trace_loop
trace_loop:
    jalr zero, t1, 0
    .option rvc
    .global trace_target
trace_target:
    c.addi a0, 1
    c.addi t0, -1
    bnez t0, trace_loop
    .global trace_done
trace_done:
    j trace_done
