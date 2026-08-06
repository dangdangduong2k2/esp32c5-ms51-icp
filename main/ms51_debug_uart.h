#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MS51_DEBUG_MAX_VARIABLES 12u
#define MS51_DEBUG_MAX_LOG_LINES 8u
#define MS51_DEBUG_VARIABLE_NAME_SIZE 20u
#define MS51_DEBUG_VARIABLE_VALUE_SIZE 44u
#define MS51_DEBUG_LOG_TEXT_SIZE 80u
#define MS51_RUNTIME_CONFIG_SIZE 217u

typedef enum {
    MS51_RUNTIME_CONFIG_STATUS_OK = 0,
    MS51_RUNTIME_CONFIG_STATUS_BAD_FRAME = 1,
    MS51_RUNTIME_CONFIG_STATUS_BAD_CONFIG = 2,
} ms51_runtime_config_status_t;

typedef struct {
    char name[MS51_DEBUG_VARIABLE_NAME_SIZE];
    char value[MS51_DEBUG_VARIABLE_VALUE_SIZE];
    uint64_t updated_us;
} ms51_debug_variable_t;

typedef struct {
    char text[MS51_DEBUG_LOG_TEXT_SIZE];
    uint64_t received_us;
} ms51_debug_log_line_t;

typedef struct {
    bool enabled;
    bool receiver_attached;
    bool paused_for_icp;
    uint32_t baud_rate;
    uint32_t bytes_received;
    uint32_t lines_received;
    uint32_t dropped_lines;
    uint64_t last_rx_us;
    size_t variable_count;
    ms51_debug_variable_t variables[MS51_DEBUG_MAX_VARIABLES];
    size_t log_count;
    ms51_debug_log_line_t logs[MS51_DEBUG_MAX_LOG_LINES];
} ms51_debug_uart_snapshot_t;

/** Start UART1 over the existing runtime wiring: GPIO5 TX -> P0.2 and
 * GPIO6 RX <- P1.6.  Both pins are detached before ICP takes ownership. */
esp_err_t ms51_debug_uart_init(void);

/** Stop and detach runtime UART TX/RX before the shared CLK/DAT pins enter ICP mode. */
esp_err_t ms51_debug_uart_pause_for_icp(void);

/** Reattach the runtime UART after ICP has released CLK and DAT. */
esp_err_t ms51_debug_uart_resume_after_icp(void);

/** Copy the current variables and recent log lines without touching the MS51 target. */
esp_err_t ms51_debug_uart_get_snapshot(ms51_debug_uart_snapshot_t *snapshot);

/** Read the app.py-compatible 217-byte EEPROM image through runtime UART.
 * Callers must hold the shared MS51 operation lock for the whole request. */
esp_err_t ms51_debug_uart_get_runtime_config(uint8_t config[MS51_RUNTIME_CONFIG_SIZE],
                                             uint8_t *target_status);

/** Write the complete app.py-compatible EEPROM image through runtime UART.
 * The MS51 validates it, commits EEPROM once, replies, then restarts. */
esp_err_t ms51_debug_uart_set_runtime_config(
    const uint8_t config[MS51_RUNTIME_CONFIG_SIZE], uint8_t *target_status);

#ifdef __cplusplus
}
#endif
