#pragma once
#include "esp_err.h"
/* Account-independent, encrypted local transport. Cloud permissions do not apply here. */
esp_err_t ms51_ble_start(void);
