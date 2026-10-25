OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS {
  . = TRACE_BASE;
  .text : { *(.text.init) *(.text*) }
  .rodata : { *(.rodata*) *(.srodata*) }
  .data : { *(.data*) *(.sdata*) }
  . = ALIGN(8);
  __bss_start = .;
  .bss : { *(.bss*) *(.sbss*) *(COMMON) }
  . = ALIGN(8);
  __bss_end = .;
  ASSERT(__bss_end < TRACE_BASE + 0x80000, "benchmark exceeds private region")
  __stack_top = TRACE_BASE + 0xf0000;
  /DISCARD/ : { *(.comment) *(.eh_frame*) }
}
