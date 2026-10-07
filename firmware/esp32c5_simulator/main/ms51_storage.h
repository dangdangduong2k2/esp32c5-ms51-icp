#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"
#include "esp_partition.h"

#include "ms51_image.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MS51_STORAGE_MAX_IMAGE_SIZE MS51_IMAGE_MAX_SIZE
#define MS51_STORAGE_FILENAME_SIZE 64u

typedef struct {
    bool valid;
    char filename[MS51_STORAGE_FILENAME_SIZE];
    size_t size;
    size_t covered_size;
    uint32_t crc32;
    ms51_image_format_t format;
    uint32_t generation;
} ms51_storage_info_t;

typedef struct {
    const uint8_t *data;
    const uint8_t *coverage;
    size_t size;
    size_t covered_size;
    uint32_t crc32;
    ms51_image_format_t format;
    uint32_t generation;
    esp_partition_mmap_handle_t mmap_handle;
    bool acquired;
} ms51_storage_image_t;

esp_err_t ms51_storage_init(void);
esp_err_t ms51_storage_get_info(ms51_storage_info_t *info);

/** Commit a canonical image into the inactive A/B slot, then atomically activate it. */
esp_err_t ms51_storage_commit_image(const char *filename, const ms51_image_t *image,
                                    ms51_storage_info_t *info);

/** Map the current valid image. Pass a zero-initialized handle and release it after use. */
esp_err_t ms51_storage_acquire_image(uint32_t expected_generation,
                                     ms51_storage_image_t *image);
void ms51_storage_release_image(ms51_storage_image_t *image);

#ifdef __cplusplus
}
#endif
