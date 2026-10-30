#pragma once
#include "cJSON.h"
cJSON *ms51_ble_request(const char *method, const char *path, const cJSON *body);
