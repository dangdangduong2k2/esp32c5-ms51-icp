#pragma once

#include "esp_err.h"
#include "freertos/FreeRTOS.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Initialize the shared gate for target programming and firmware uploads. */
esp_err_t ms51_operation_init(void);

/* Foreground requests can wait for a short background SIG heartbeat to finish.
 * Long programming/upload operations still return busy after this bounded wait. */
#define MS51_OPERATION_FOREGROUND_WAIT pdMS_TO_TICKS(750)

/** Acquire the shared gate. Background workers use zero ticks. */
esp_err_t ms51_operation_lock(TickType_t timeout_ticks);

void ms51_operation_unlock(void);

#ifdef __cplusplus
}
#endif
