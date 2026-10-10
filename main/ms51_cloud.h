#pragma once
#include <stdbool.h>
#include "esp_err.h"

esp_err_t ms51_cloud_start(void);
/* Accept only the fixed trusted service, MAC and credentials issued over HTTPS. */
esp_err_t ms51_cloud_pair(const char *json);
bool ms51_cloud_configured(void);
bool ms51_cloud_online(void);
bool ms51_cloud_clock_ready(void);
