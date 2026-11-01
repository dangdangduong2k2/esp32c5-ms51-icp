#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MS51_IMAGE_MAX_SIZE (32u * 1024u)
#define MS51_IMAGE_COVERAGE_SIZE ((MS51_IMAGE_MAX_SIZE + 7u) / 8u)
/* A 32 KiB Intel HEX image using conventional 16-byte records is about 90 KiB. */
#define MS51_IMAGE_MAX_UPLOAD_SIZE (96u * 1024u)

typedef enum {
    MS51_IMAGE_FORMAT_BINARY = 0,
    MS51_IMAGE_FORMAT_INTEL_HEX = 1,
} ms51_image_format_t;

/* Sink receives checksum-validated records at their absolute APROM offsets. */
typedef esp_err_t (*ms51_image_writer_t)(void *context, size_t address,
                                        const uint8_t *data, size_t length);

/* Canonical APROM image. A set coverage bit means the corresponding byte was
 * explicitly present in the uploaded firmware. */
typedef struct {
    uint8_t *data;
    uint8_t *coverage;
    size_t size;
    size_t covered_size;
    ms51_image_format_t format;
    void *allocation;
    ms51_image_writer_t writer;
    void *writer_context;
} ms51_image_t;

bool ms51_image_filename_is_intel_hex(const char *filename);
const char *ms51_image_format_name(ms51_image_format_t format);

/** Parse a BIN or Intel HEX upload into a sparse-safe canonical APROM image. */
esp_err_t ms51_image_parse_upload(const uint8_t *source, size_t source_size,
                                  const char *filename, ms51_image_t *image);
void ms51_image_free(ms51_image_t *image);

/* Bounded-memory HTTP reader: returns bytes read, or <=0 on interruption. */
typedef int (*ms51_image_reader_t)(void *context, uint8_t *buffer, size_t maximum);
esp_err_t ms51_image_parse_stream(ms51_image_reader_t reader, void *context,
                                  size_t source_size, const char *filename, ms51_image_t *image);

/* Uses only a 4 KiB coverage allocation. The sink must be an uncommitted,
 * erased target; callers activate it only after this function succeeds. */
esp_err_t ms51_image_parse_to_sink(ms51_image_reader_t reader, void *context,
                                   size_t source_size, const char *filename,
                                   ms51_image_writer_t writer, void *writer_context,
                                   ms51_image_t *image);

bool ms51_image_address_is_covered(const ms51_image_t *image, size_t address);

#ifdef __cplusplus
}
#endif
