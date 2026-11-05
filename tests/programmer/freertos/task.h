#pragma once
static inline void vTaskDelay(unsigned ticks) { (void)ticks; }
static inline int xTaskCreate(void (*task)(void *),const char *name,unsigned stack,void *arg,unsigned priority,void *handle) {
    (void)task;(void)name;(void)stack;(void)arg;(void)priority;(void)handle;return 1;
}
