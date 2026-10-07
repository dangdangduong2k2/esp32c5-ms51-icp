#pragma once
#include <stddef.h>
#include "esp_err.h"
esp_err_t ms51_presence_init(void);
esp_err_t ms51_presence_start(void);
void ms51_presence_stop(void);
const char *ms51_presence_id(void);
const char *ms51_presence_hostname(void);
const char *ms51_presence_fqdn(void);
const char *ms51_presence_ap_ssid(void);
void ms51_presence_json(char *out, size_t size);
