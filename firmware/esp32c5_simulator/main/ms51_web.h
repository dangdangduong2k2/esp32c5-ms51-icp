#pragma once

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Start the password-protected SoftAP, Internet sharing, and firmware web interface. */
esp_err_t ms51_web_start(void);

#ifdef __cplusplus
}
#endif
