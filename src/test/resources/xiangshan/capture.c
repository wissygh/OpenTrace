#include <stdint.h>

#define ENCODER 0x20000000UL
#define FUNNEL  0x20010000UL
#define SRAM    0x20040000UL

static uint32_t read32(uintptr_t address) {
  return *(volatile uint32_t *)address;
}

static void write32(uintptr_t address, uint32_t value) {
  *(volatile uint32_t *)address = value;
  __asm__ volatile("fence iorw, iorw" ::: "memory");
}

static void putc_uart(char ch) {
  write32(0x40600004UL, (uint8_t)ch);
}

static void puts_uart(const char *text) {
  while (*text) putc_uart(*text++);
}

static void hex(uint32_t value) {
  for (int shift = 28; shift >= 0; shift -= 4)
    putc_uart("0123456789abcdef"[(value >> shift) & 15]);
}

static void record(const char *name, uint32_t value) {
  puts_uart("[OT] "); puts_uart(name); putc_uart('='); hex(value); putc_uart('\n');
}

static void fail(uint32_t code) {
  record("FAIL", code);
  register uintptr_t result __asm__("a0") = code;
  __asm__ volatile(".word 0x0005006b" : : "r"(result));
  for (;;) {}
}

void unexpected_trap(uint32_t cause, uint32_t pc, uint32_t value) {
  record("trap_pc", pc); record("trap_value", value); fail(0x100 | cause);
}

static void wait_status(uintptr_t address, uint32_t mask, uint32_t expected, uint32_t code) {
  for (unsigned n = 0; n < 4096; ++n)
    if ((read32(address) & mask) == expected) return;
  fail(code);
}

extern uint32_t trace_workload(void);

int main(void) {
  puts_uart("[OT] boot\n");
  write32(SRAM, 1); wait_status(SRAM, 1, 1, 1);
  write32(FUNNEL, 1); wait_status(FUNNEL, 1, 1, 2);
  write32(ENCODER, 1); wait_status(ENCODER, 1, 1, 3);
  record("ram_impl", read32(SRAM + 4));
  record("ram_limit", read32(SRAM + 0x18));
  write32(SRAM + 0x20, 0);
  write32(ENCODER + 0xf04, 1);
  write32(SRAM, 0x103);
  write32(FUNNEL, 3);
  puts_uart("[OT] enable\n");
  write32(ENCODER, 0x2007); /* Active, Enable, InstTracing, InstStallEna */
  uint32_t answer = trace_workload();
  write32(ENCODER, 1);
  wait_status(ENCODER, 0xb, 9, 4);
  write32(FUNNEL, 1); wait_status(FUNNEL, 0xb, 9, 5);
  write32(SRAM, 1); wait_status(SRAM, 0xb, 9, 6);
  record("answer", answer);
  record("encoder", read32(ENCODER));
  record("funnel", read32(FUNNEL));
  record("sram", read32(SRAM));
  uint32_t wp = read32(SRAM + 0x20);
  record("wp", wp);
  record("dropped", read32(ENCODER + 0xf10));
  record("loss_markers", read32(ENCODER + 0xf18));
  record("stall_cycles", read32(ENCODER + 0xf20));
  if (answer != 18) fail(7);
  if ((wp & 3) || wp == 0 || wp > 0x3fc) fail(8);
  if (read32(ENCODER + 0xf10) || read32(ENCODER + 0xf18)) fail(9);
  write32(SRAM + 0x28, 0);
  for (uint32_t offset = 0; offset < wp; offset += 4) {
    uint32_t word = read32(SRAM + 0x40);
    puts_uart("[OT-WORD] "); hex(offset); putc_uart(' '); hex(word); putc_uart('\n');
  }
  record("rp", read32(SRAM + 0x28));
  puts_uart("[OT] done\n");
  return 0;
}
