#pragma once
#include <stdbool.h>
#include "esp_err.h"
#include "cJSON.h"
/* Shared loopback transport; callers must enforce their own route policy. */
cJSON *ms51_local_request(const char *method, const char *path, const cJSON *body);

esp_err_t ms51_cloud_start(void);
/* Accept only the fixed trusted service, MAC and credentials issued over HTTPS. */
esp_err_t ms51_cloud_pair(const char *json);
bool ms51_cloud_configured(void);
bool ms51_cloud_online(void);
bool ms51_cloud_clock_ready(void);
