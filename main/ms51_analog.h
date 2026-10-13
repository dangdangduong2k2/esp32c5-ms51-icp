#pragma once
#include <stddef.h>
#include <stdbool.h>
#include "esp_err.h"
esp_err_t ms51_analog_init(void);
void ms51_analog_json(char *output, size_t size);

void ms51_analog_50k_json(char *output, size_t size);

void ms51_analog_20k_json(char *output, size_t size);
bool ms51_analog_temperature(unsigned source, int *temperature);
