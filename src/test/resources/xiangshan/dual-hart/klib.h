#ifndef OPENTRACE_COREMARK_RUNTIME_H
#define OPENTRACE_COREMARK_RUNTIME_H
#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>
int printf(const char *format, ...);
void *memcpy(void *dest, const void *src, size_t count);
void *memset(void *dest, int value, size_t count);
size_t strlen(const char *text);
uint32_t uptime(void);
void _ioe_init(void);
#endif
