    .section .text.init, "ax"
    .global _start
    .option norelax
_start:
    la sp, __stack_top
    la t0, trap_handler
    csrw mtvec, t0
    csrw mie, zero
    call main
    .word 0x0005006b
1:  j 1b

    .balign 4
trap_handler:
    csrr a0, mcause
    csrr a1, mepc
    csrr a2, mtval
    call unexpected_trap
    li a0, 1
    .word 0x0005006b
2:  j 2b

    .section .text.workload, "ax"
    .global trace_workload
trace_workload:
    mv t3, ra
    li t0, 4
    li a0, 0
3:  addi a0, a0, 3
    addi t0, t0, -1
    bnez t0, 3b
    la t1, 4f
    jalr ra, t1, 0
    addi a0, a0, 5
    mv ra, t3
    ret
4:  .option push
    .option rvc
    c.addi a0, 1
    c.jr ra
    .option pop
