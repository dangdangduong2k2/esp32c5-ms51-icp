#pragma once
#include "esp_err.h"
typedef int gpio_num_t;
static inline esp_err_t gpio_reset_pin(gpio_num_t gpio) { (void)gpio; return ESP_OK; }
