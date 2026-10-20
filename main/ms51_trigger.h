#pragma once
#include <stddef.h>
#include "esp_err.h"
esp_err_t ms51_trigger_init(void);
/* Caller holds ms51_operation_lock. Values: SIG=0, 10k=1, 20k=2, 50k=3. */
esp_err_t ms51_trigger_configure(unsigned source, int threshold_centi);
esp_err_t ms51_thermal_configure(unsigned source, int cutoff_centi, int defrost_centi);
void ms51_trigger_json(char *output, size_t size);
