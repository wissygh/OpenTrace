    .section .text.init, "ax"
    .option norelax
    .global _start
_start:
    csrw mie, zero
1:  j 1b
    .balign 4
    .global trace_begin
trace_begin:
    li a0, 0
    li t0, 4
2:  c.addi a0, 3
    c.addi t0, -1
    bnez t0, 2b
    la t1, callee
    jalr ra, t1, 0
    c.addi a0, 5
    j trace_done
callee:
    c.addi a0, 1
    c.jr ra
    .global trace_done
trace_done:
    j trace_done
