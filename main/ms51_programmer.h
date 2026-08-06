#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#include "ms51_icp.h"
#include "ms51_image.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    ms51_icp_identity_t identity;
    uint8_t config[MS51_CONFIG_SIZE];
    size_t ldrom_size;
    size_t aprom_size;
    bool locked;
} ms51_device_info_t;

esp_err_t ms51_programmer_init(void);
esp_err_t ms51_programmer_get_info(ms51_device_info_t *info);
/** Non-blocking web variant; returns ESP_ERR_TIMEOUT if the target is busy. */
esp_err_t ms51_programmer_try_get_info(ms51_device_info_t *info);
/** Read an unlocked APROM range through an exclusive ICP session. */
esp_err_t ms51_programmer_read_aprom(uint32_t address, uint8_t *data, size_t length);
/** Update only APROM bytes explicitly covered by an image. */
esp_err_t ms51_programmer_program_image(const ms51_image_t *image, bool verify);
/** Replace all APROM; uncovered bytes become erased (0xFF). */
esp_err_t ms51_programmer_program_image_full(const ms51_image_t *image, bool verify);
/** Compare only APROM bytes explicitly covered by an image. */
esp_err_t ms51_programmer_verify_image(const ms51_image_t *image);
/** Update only addresses covered by the image, preserving all bytes after it. */
esp_err_t ms51_programmer_program(const uint8_t *image, size_t image_size, bool verify);
/** Replace all APROM; bytes after the image become erased (0xFF). */
esp_err_t ms51_programmer_program_full(const uint8_t *image, size_t image_size, bool verify);
/** Compare only addresses covered by the image. */
esp_err_t ms51_programmer_verify(const uint8_t *image, size_t image_size);
esp_err_t ms51_programmer_mass_erase(void);
esp_err_t ms51_programmer_reset(void);
/** Non-blocking web variant; returns ESP_ERR_TIMEOUT if the target is busy. */
esp_err_t ms51_programmer_try_reset(void);

#ifdef __cplusplus
}
#endif
