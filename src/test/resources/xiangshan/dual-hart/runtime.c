#include <klib.h>

/* This record lives in each separately linked hart image. It is read through
 * external Debug SBA; the benchmark never touches trace control registers. */
struct benchmark_record {
  uint32_t magic, hart, iterations, seedcrc, crclist, crcmatrix, crcstate, crcfinal;
  uint32_t errors, log_bytes;
  uint64_t cycle_begin, cycle_end, instret_begin, instret_end;
  char log[2048];
};
volatile struct benchmark_record trace_result;

void *memcpy(void *dest, const void *src, size_t count) {
  unsigned char *d = dest; const unsigned char *s = src;
  while (count--) *d++ = *s++;
  return dest;
}
void *memset(void *dest, int value, size_t count) {
  unsigned char *d = dest;
  while (count--) *d++ = value;
  return dest;
}
size_t strlen(const char *text) { const char *p = text; while (*p) ++p; return p - text; }
static int has(const char *text, const char *part) {
  for (; *text; ++text) {
    const char *a = text, *b = part;
    while (*a && *b && *a == *b) { ++a; ++b; }
    if (!*b) return 1;
  }
  return 0;
}
static uint64_t counter(int instret) {
#ifdef __riscv
  uint64_t value;
  if (instret) __asm__ volatile("rdinstret %0" : "=r"(value));
  else __asm__ volatile("rdcycle %0" : "=r"(value));
  return value;
#else
  static uint64_t value; return ++value;
#endif
}
uint32_t uptime(void) { return (uint32_t)counter(0); }
/* The original AM port initializes device access here. This private workload
 * uses rdcycle and per-hart RAM records, so there are no devices to configure. */
void _ioe_init(void) {}
static void emit(char value) {
  unsigned n = trace_result.log_bytes;
  if (n < sizeof(trace_result.log) - 1) {
    trace_result.log[n] = value;
    trace_result.log_bytes = n + 1;
    trace_result.log[n + 1] = 0;
  } else ++trace_result.errors;
}
static void emit_number(uint64_t value, unsigned base, unsigned width) {
  char digits[32]; unsigned count = 0;
  do { digits[count++] = "0123456789abcdef"[value % base]; value /= base; } while (value);
  while (width > count) { emit('0'); --width; }
  while (count) emit(digits[--count]);
}
int printf(const char *format, ...) {
  va_list args, values; va_start(args, format); va_copy(values, args);
  /* Keep time-dependent decimal rendering out of the trace-density comparison.
   * Actual hardware counters are exported separately in s1/s2. The benchmark
   * calls and CRC computation are unchanged, and no score is fabricated. */
  if (has(format, "Total time") || has(format, "Iterations/mSec") ||
      has(format, "Finished in") || has(format, "Iterations/Sec")) {
    const char *s = "[timing retained in hardware counters; no score]\n";
    while (*s) emit(*s++);
    va_end(values); va_end(args); return 0;
  }
  if (has(format, "seedcrc")) trace_result.seedcrc = va_arg(values, unsigned);
  else if (has(format, "crclist")) { (void)va_arg(values, unsigned); trace_result.crclist = va_arg(values, unsigned); }
  else if (has(format, "crcmatrix")) { (void)va_arg(values, unsigned); trace_result.crcmatrix = va_arg(values, unsigned); }
  else if (has(format, "crcstate")) { (void)va_arg(values, unsigned); trace_result.crcstate = va_arg(values, unsigned); }
  else if (has(format, "crcfinal")) { (void)va_arg(values, unsigned); trace_result.crcfinal = va_arg(values, unsigned); }
  else if (has(format, "Iterations       :")) trace_result.iterations = va_arg(values, unsigned);
  if (has(format, "ERROR") || has(format, "Errors detected") || has(format, "Cannot validate")) ++trace_result.errors;
  va_end(values);
  for (const char *p = format; *p; ++p) {
    if (*p != '%') { emit(*p); continue; }
    ++p; unsigned width = 0;
    while (*p >= '0' && *p <= '9') { width = width * 10 + *p - '0'; ++p; }
    if (*p == '.') { ++p; while (*p >= '0' && *p <= '9') ++p; }
    if (*p == 's') { const char *s = va_arg(args, const char *); while (*s) emit(*s++); }
    else if (*p == 'd') {
      int n = va_arg(args, int); if (n < 0) emit('-');
      emit_number(n < 0 ? -(int64_t)n : n, 10, width);
    } else if (*p == 'u' || *p == 'x') emit_number(va_arg(args, unsigned), *p == 'x' ? 16 : 10, width);
    else if (*p == 'f') {
      (void)va_arg(args, double);
      /* Preserve the original call, but do not advertise a standardized score
       * from this short trace-density workload or debugger-distorted timing. */
      const char *s = "[not scored]"; while (*s) emit(*s++);
    } else if (*p == '%') emit('%');
    else { emit('?'); ++trace_result.errors; }
  }
  va_end(args); return 0;
}
int coremark_main(int argc, char **argv);
void benchmark_run(void) {
  uint64_t hart = 0;
#ifdef __riscv
  __asm__ volatile("csrr %0, mhartid" : "=r"(hart));
#endif
  trace_result.hart = hart;
  trace_result.cycle_begin = counter(0);
  trace_result.instret_begin = counter(1);
  int result = coremark_main(0, (char **)0);
  trace_result.instret_end = counter(1);
  trace_result.cycle_end = counter(0);
  if (result) ++trace_result.errors;
  __asm__ volatile("" ::: "memory");
  trace_result.magic = 0x434d4f54;
}
