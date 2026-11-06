#pragma once
#include <stddef.h>
#include <stdbool.h>
#include "esp_err.h"
esp_err_t ms51_trigger_init(void);
/* Caller holds ms51_operation_lock. Values: SIG=0, 10k=1, 20k=2, 50k=3. */
esp_err_t ms51_trigger_configure(unsigned source, int threshold_centi);
/* Thermal roles v2: source selects monitoring 10k/20k; cutoff always uses 50k.
 * The MS51 receives only the resulting booleans, keeping its current protocol. */
esp_err_t ms51_thermal_configure(unsigned source, int cutoff_centi, int defrost_centi, bool safety, bool defrost_enabled);
void ms51_trigger_json(char *output, size_t size);
