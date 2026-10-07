#include "ms51_operation.h"

#include "freertos/semphr.h"

static SemaphoreHandle_t s_operation_mutex;

esp_err_t ms51_operation_init(void)
{
    if (s_operation_mutex != NULL) {
        return ESP_OK;
    }
    s_operation_mutex = xSemaphoreCreateMutex();
    return s_operation_mutex != NULL ? ESP_OK : ESP_ERR_NO_MEM;
}

esp_err_t ms51_operation_lock(TickType_t timeout_ticks)
{
    if (s_operation_mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    return xSemaphoreTake(s_operation_mutex, timeout_ticks) == pdTRUE
               ? ESP_OK
               : ESP_ERR_TIMEOUT;
}

void ms51_operation_unlock(void)
{
    if (s_operation_mutex != NULL) {
        xSemaphoreGive(s_operation_mutex);
    }
}
