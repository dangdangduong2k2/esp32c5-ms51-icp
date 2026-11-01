#pragma once
#include <assert.h>
typedef int *SemaphoreHandle_t;
static inline SemaphoreHandle_t xSemaphoreCreateMutex(void) { static int held; return &held; }
static inline void xSemaphoreTake(SemaphoreHandle_t s, unsigned wait) { (void)wait; assert(!*s); *s=1; }
static inline void xSemaphoreGive(SemaphoreHandle_t s) { assert(*s); *s=0; }
