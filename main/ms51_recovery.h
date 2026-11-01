#pragma once
#include "ms51_image.h"
#include "ms51_icp.h"

/* All mutating calls require the programmer operation lock. Status fields are
 * atomic snapshots; pending is published before any target erase. */
typedef struct {
    esp_err_t (*read)(uint32_t address, void *data, size_t length);
    esp_err_t (*erase)(uint32_t address);
    esp_err_t (*write)(uint32_t address, const void *data, size_t length);
} ms51_recovery_io_t;

esp_err_t ms51_recovery_init(void);
bool ms51_recovery_pending(void);
bool ms51_recovery_available(void);
uint32_t ms51_recovery_sequence(void);
uint32_t ms51_recovery_source_crc(void);
const char *ms51_recovery_state(void);
esp_err_t ms51_recovery_prepare(const ms51_image_t *image, bool full,
    size_t aprom_size, const uint8_t config[MS51_CONFIG_SIZE],
    const ms51_recovery_io_t *io);
/* A failed forward write durably selects rollback. Later calls restore the
 * original snapshot; no Internet, uploaded image, or heap buffer is needed. */
esp_err_t ms51_recovery_run(size_t aprom_size,
    const uint8_t config[MS51_CONFIG_SIZE], const ms51_recovery_io_t *io);
