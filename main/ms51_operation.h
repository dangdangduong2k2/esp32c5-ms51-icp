#pragma once

#include "esp_err.h"
#include "freertos/FreeRTOS.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Initialize the shared gate for target programming and firmware uploads. */
esp_err_t ms51_operation_init(void);

/** Acquire the shared gate. Use zero ticks for a non-blocking web request. */
esp_err_t ms51_operation_lock(TickType_t timeout_ticks);

void ms51_operation_unlock(void);

#ifdef __cplusplus
}
#endif
