#include "ms51_storage.h"

#include <ctype.h>
#include <inttypes.h>
#include <stddef.h>
#include <string.h>

#include "esp_crc.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "ms51_storage";

#define STORAGE_PARTITION_LABEL "ms51fw"
#define STORAGE_SLOT_COUNT 2u
#define STORAGE_SLOT_SIZE UINT32_C(0xA000)
#define STORAGE_METADATA_SIZE UINT32_C(0x1000)
#define STORAGE_PAYLOAD_OFFSET STORAGE_METADATA_SIZE
#define STORAGE_COVERAGE_OFFSET STORAGE_PAYLOAD_OFFSET
#define STORAGE_DATA_OFFSET (STORAGE_COVERAGE_OFFSET + MS51_IMAGE_COVERAGE_SIZE)
#define STORAGE_PAYLOAD_SIZE (MS51_IMAGE_COVERAGE_SIZE + MS51_IMAGE_MAX_SIZE)
#define STORAGE_MAGIC UINT32_C(0x4D353146) /* M51F */
#define STORAGE_VERSION UINT32_C(2)
#define STORAGE_COMMIT UINT32_C(0xC051FC0A)

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t sequence;
    uint32_t image_size;
    uint32_t covered_size;
    uint32_t image_crc32;
    uint32_t format;
    char filename[MS51_STORAGE_FILENAME_SIZE];
    uint32_t header_crc32;
    uint32_t commit;
} storage_header_t;

_Static_assert(STORAGE_PAYLOAD_OFFSET + STORAGE_PAYLOAD_SIZE == STORAGE_SLOT_SIZE,
               "storage slot layout is invalid");
_Static_assert(sizeof(storage_header_t) < STORAGE_METADATA_SIZE,
               "storage header does not fit metadata sector");

static const esp_partition_t *s_partition;
static SemaphoreHandle_t s_mutex;
static ms51_storage_info_t s_active_info;
static int s_active_slot = -1;
static unsigned s_mapping_count;
static bool s_storage_degraded;

static size_t slot_offset(int slot)
{
    return (size_t)slot * STORAGE_SLOT_SIZE;
}

static uint32_t header_crc(const storage_header_t *header)
{
    return esp_crc32_le(0, (const uint8_t *)header,
                        offsetof(storage_header_t, header_crc32));
}

static esp_err_t flash_crc(size_t offset, size_t length, uint32_t *crc_out)
{
    uint8_t buffer[512];
    uint32_t crc = 0;
    size_t done = 0;

    while (done < length) {
        size_t chunk = length - done;
        if (chunk > sizeof(buffer)) {
            chunk = sizeof(buffer);
        }
        const esp_err_t error = esp_partition_read(s_partition, offset + done, buffer, chunk);
        if (error != ESP_OK) {
            return error;
        }
        crc = esp_crc32_le(crc, buffer, chunk);
        done += chunk;
        vTaskDelay(pdMS_TO_TICKS(1));
    }
    *crc_out = crc;
    return ESP_OK;
}

static void copy_sanitized_filename(char destination[MS51_STORAGE_FILENAME_SIZE],
                                    const char *source)
{
    memset(destination, 0, MS51_STORAGE_FILENAME_SIZE);
    if (source == NULL || source[0] == '\0') {
        strlcpy(destination, "ms51_app.bin", MS51_STORAGE_FILENAME_SIZE);
        return;
    }

    size_t output = 0;
    for (size_t input = 0; source[input] != '\0' && output + 1 < MS51_STORAGE_FILENAME_SIZE;
         ++input) {
        const unsigned char value = (unsigned char)source[input];
        destination[output++] = (isalnum(value) || value == '.' || value == '_' ||
                                 value == '-')
                                    ? (char)value
                                    : '_';
    }
    if (output == 0) {
        strlcpy(destination, "ms51_app.bin", MS51_STORAGE_FILENAME_SIZE);
    }
}

static bool format_is_valid(uint32_t format)
{
    return format == MS51_IMAGE_FORMAT_BINARY || format == MS51_IMAGE_FORMAT_INTEL_HEX;
}

static bool coverage_is_valid(const uint8_t *coverage, size_t image_size,
                              size_t covered_size)
{
    if (coverage == NULL || image_size == 0 || image_size > MS51_IMAGE_MAX_SIZE ||
        covered_size == 0 || covered_size > image_size) {
        return false;
    }

    size_t counted = 0;
    for (size_t address = 0; address < MS51_IMAGE_MAX_SIZE; ++address) {
        if ((coverage[address >> 3] & (uint8_t)(1u << (address & 7u))) == 0) {
            continue;
        }
        if (address >= image_size) {
            return false;
        }
        ++counted;
    }
    return counted == covered_size;
}

static esp_err_t validate_coverage_on_flash(int slot, size_t image_size,
                                            size_t expected_covered_size)
{
    uint8_t buffer[256];
    size_t counted = 0;
    size_t offset = 0;
    const size_t coverage_base = slot_offset(slot) + STORAGE_COVERAGE_OFFSET;

    while (offset < MS51_IMAGE_COVERAGE_SIZE) {
        size_t chunk = MS51_IMAGE_COVERAGE_SIZE - offset;
        if (chunk > sizeof(buffer)) {
            chunk = sizeof(buffer);
        }
        const esp_err_t error = esp_partition_read(s_partition, coverage_base + offset, buffer,
                                                   chunk);
        if (error != ESP_OK) {
            return error;
        }
        for (size_t index = 0; index < chunk; ++index) {
            uint8_t bits = buffer[index];
            for (unsigned bit = 0; bit < 8; ++bit) {
                if ((bits & (uint8_t)(1u << bit)) == 0) {
                    continue;
                }
                const size_t address = (offset + index) * 8u + bit;
                if (address >= image_size) {
                    return ESP_ERR_INVALID_STATE;
                }
                ++counted;
            }
        }
        offset += chunk;
    }
    return counted == expected_covered_size ? ESP_OK : ESP_ERR_INVALID_STATE;
}

static esp_err_t inspect_slot(int slot, ms51_storage_info_t *info)
{
    storage_header_t header;
    esp_err_t error = esp_partition_read(s_partition, slot_offset(slot), &header, sizeof(header));
    if (error != ESP_OK) {
        return error;
    }

    if (header.commit != STORAGE_COMMIT || header.magic != STORAGE_MAGIC ||
        header.version != STORAGE_VERSION || header.image_size == 0 ||
        header.image_size > MS51_IMAGE_MAX_SIZE || header.covered_size == 0 ||
        header.covered_size > header.image_size || !format_is_valid(header.format) ||
        header.header_crc32 != header_crc(&header)) {
        return ESP_ERR_NOT_FOUND;
    }

    uint32_t payload_crc = 0;
    error = flash_crc(slot_offset(slot) + STORAGE_PAYLOAD_OFFSET, STORAGE_PAYLOAD_SIZE,
                      &payload_crc);
    if (error != ESP_OK) {
        return error;
    }
    if (payload_crc != header.image_crc32) {
        ESP_LOGW(TAG, "slot %d CRC mismatch: header=%08" PRIX32 ", flash=%08" PRIX32,
                 slot, header.image_crc32, payload_crc);
        return ESP_ERR_INVALID_CRC;
    }

    error = validate_coverage_on_flash(slot, header.image_size, header.covered_size);
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "slot %d has invalid coverage metadata", slot);
        return error;
    }

    memset(info, 0, sizeof(*info));
    info->valid = true;
    memcpy(info->filename, header.filename, sizeof(header.filename));
    info->filename[sizeof(info->filename) - 1] = '\0';
    info->size = header.image_size;
    info->covered_size = header.covered_size;
    info->crc32 = header.image_crc32;
    info->format = (ms51_image_format_t)header.format;
    info->generation = header.sequence;
    return ESP_OK;
}

static bool sequence_is_newer(uint32_t candidate, uint32_t current)
{
    return (int32_t)(candidate - current) > 0;
}

static uint32_t next_generation(uint32_t current)
{
    const uint32_t next = current + 1;
    return next == 0 ? 1 : next;
}

static esp_err_t scan_slots(void)
{
    ms51_storage_info_t slot_info[STORAGE_SLOT_COUNT];
    bool valid[STORAGE_SLOT_COUNT] = {false, false};

    for (int slot = 0; slot < (int)STORAGE_SLOT_COUNT; ++slot) {
        const esp_err_t error = inspect_slot(slot, &slot_info[slot]);
        valid[slot] = error == ESP_OK;
        if (error != ESP_OK && error != ESP_ERR_NOT_FOUND && error != ESP_ERR_INVALID_CRC &&
            error != ESP_ERR_INVALID_STATE) {
            ESP_LOGE(TAG, "could not inspect storage slot %d: %s", slot,
                     esp_err_to_name(error));
            memset(&s_active_info, 0, sizeof(s_active_info));
            s_active_slot = -1;
            s_storage_degraded = true;
            return error;
        }
    }

    memset(&s_active_info, 0, sizeof(s_active_info));
    s_active_slot = -1;
    if (valid[0]) {
        s_active_slot = 0;
        s_active_info = slot_info[0];
    }
    if (valid[1] && (s_active_slot < 0 ||
                     sequence_is_newer(slot_info[1].generation,
                                       s_active_info.generation))) {
        s_active_slot = 1;
        s_active_info = slot_info[1];
    }
    s_storage_degraded = false;
    return s_active_slot >= 0 ? ESP_OK : ESP_ERR_NOT_FOUND;
}

esp_err_t ms51_storage_init(void)
{
    if (s_mutex == NULL) {
        s_mutex = xSemaphoreCreateMutex();
        if (s_mutex == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }

    s_partition = esp_partition_find_first(ESP_PARTITION_TYPE_DATA,
                                            ESP_PARTITION_SUBTYPE_ANY,
                                            STORAGE_PARTITION_LABEL);
    if (s_partition == NULL || s_partition->size < STORAGE_SLOT_COUNT * STORAGE_SLOT_SIZE) {
        ESP_LOGE(TAG, "partition '%s' is missing or smaller than 0x%X bytes",
                 STORAGE_PARTITION_LABEL, STORAGE_SLOT_COUNT * STORAGE_SLOT_SIZE);
        return ESP_ERR_NOT_FOUND;
    }

    const esp_err_t scan_error = scan_slots();
    if (scan_error != ESP_OK && scan_error != ESP_ERR_NOT_FOUND) {
        return scan_error;
    }

    if (s_active_slot >= 0) {
        ESP_LOGI(TAG, "loaded %s: %u byte(s), %u covered, %s, CRC32=%08" PRIX32
                      ", generation=%" PRIu32,
                 s_active_info.filename, (unsigned)s_active_info.size,
                 (unsigned)s_active_info.covered_size,
                 ms51_image_format_name(s_active_info.format), s_active_info.crc32,
                 s_active_info.generation);
    } else {
        ESP_LOGI(TAG, "no uploaded MS51 image is stored yet");
    }
    return ESP_OK;
}

esp_err_t ms51_storage_get_info(ms51_storage_info_t *info)
{
    if (info == NULL || s_mutex == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    *info = s_active_info;
    xSemaphoreGive(s_mutex);
    return ESP_OK;
}

esp_err_t ms51_storage_commit_image(const char *filename, const ms51_image_t *image,
                                    ms51_storage_info_t *info)
{
    if (image == NULL || image->data == NULL || image->coverage == NULL || s_mutex == NULL ||
        s_partition == NULL || !format_is_valid(image->format) ||
        !coverage_is_valid(image->coverage, image->size, image->covered_size)) {
        return ESP_ERR_INVALID_ARG;
    }

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    if (s_mapping_count != 0 || s_storage_degraded) {
        xSemaphoreGive(s_mutex);
        return ESP_ERR_INVALID_STATE;
    }

    const int target_slot = s_active_slot == 0 ? 1 : 0;
    const size_t base = slot_offset(target_slot);
    esp_err_t error = esp_partition_erase_range(s_partition, base, STORAGE_SLOT_SIZE);
    if (error == ESP_OK) {
        error = esp_partition_write(s_partition, base + STORAGE_COVERAGE_OFFSET,
                                    image->coverage, MS51_IMAGE_COVERAGE_SIZE);
    }
    if (error == ESP_OK) {
        error = esp_partition_write(s_partition, base + STORAGE_DATA_OFFSET,
                                    image->data, MS51_IMAGE_MAX_SIZE);
    }

    uint32_t payload_crc = 0;
    if (error == ESP_OK) {
        error = flash_crc(base + STORAGE_PAYLOAD_OFFSET, STORAGE_PAYLOAD_SIZE, &payload_crc);
    }

    storage_header_t header;
    memset(&header, 0, sizeof(header));
    if (error == ESP_OK) {
        header.magic = STORAGE_MAGIC;
        header.version = STORAGE_VERSION;
        header.sequence = s_active_slot < 0 ? 1 : next_generation(s_active_info.generation);
        header.image_size = image->size;
        header.covered_size = image->covered_size;
        header.image_crc32 = payload_crc;
        header.format = image->format;
        copy_sanitized_filename(header.filename, filename);
        header.header_crc32 = header_crc(&header);
        header.commit = UINT32_MAX;

        error = esp_partition_write(s_partition, base, &header, sizeof(header));
    }
    bool committed = false;
    if (error == ESP_OK) {
        const uint32_t commit = STORAGE_COMMIT;
        error = esp_partition_write(s_partition, base + offsetof(storage_header_t, commit),
                                    &commit, sizeof(commit));
        committed = error == ESP_OK;
    }

    ms51_storage_info_t new_info;
    if (error == ESP_OK) {
        error = inspect_slot(target_slot, &new_info);
    }
    if (error == ESP_OK) {
        s_active_slot = target_slot;
        s_active_info = new_info;
        if (info != NULL) {
            *info = new_info;
        }
    } else if (committed) {
        const esp_err_t scan_error = scan_slots();
        if (scan_error == ESP_OK && s_active_slot == target_slot) {
            error = ESP_OK;
            if (info != NULL) {
                *info = s_active_info;
            }
        }
    }

    xSemaphoreGive(s_mutex);
    return error;
}

esp_err_t ms51_storage_acquire_image(uint32_t expected_generation,
                                     ms51_storage_image_t *image)
{
    if (image == NULL || s_mutex == NULL || s_partition == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (image->acquired) {
        return ESP_ERR_INVALID_STATE;
    }
    memset(image, 0, sizeof(*image));

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    if (!s_active_info.valid) {
        xSemaphoreGive(s_mutex);
        return ESP_ERR_NOT_FOUND;
    }
    if (expected_generation != 0 && expected_generation != s_active_info.generation) {
        xSemaphoreGive(s_mutex);
        return ESP_ERR_INVALID_STATE;
    }

    /* Validate the sparse map again at the API boundary.  This keeps a
     * caller from receiving a malformed map even if the active metadata was
     * populated by future code rather than the normal boot/commit paths. */
    esp_err_t error = validate_coverage_on_flash(s_active_slot, s_active_info.size,
                                                 s_active_info.covered_size);
    if (error != ESP_OK) {
        xSemaphoreGive(s_mutex);
        return error;
    }

    const void *mapped = NULL;
    error = esp_partition_mmap(
        s_partition, slot_offset(s_active_slot) + STORAGE_PAYLOAD_OFFSET, STORAGE_PAYLOAD_SIZE,
        ESP_PARTITION_MMAP_DATA, &mapped, &image->mmap_handle);
    if (error == ESP_OK) {
        const uint32_t mapped_crc = esp_crc32_le(0, mapped, STORAGE_PAYLOAD_SIZE);
        if (mapped_crc != s_active_info.crc32) {
            ESP_LOGE(TAG, "active image CRC mismatch: expected=%08" PRIX32
                          ", mapped=%08" PRIX32,
                     s_active_info.crc32, mapped_crc);
            esp_partition_munmap(image->mmap_handle);
            memset(image, 0, sizeof(*image));
            const int corrupt_slot = s_active_slot;
            memset(&s_active_info, 0, sizeof(s_active_info));
            s_active_slot = -1;
            const esp_err_t recovery = scan_slots();
            if (recovery == ESP_OK) {
                ESP_LOGW(TAG, "fell back from corrupt slot %d to valid slot %d",
                         corrupt_slot, s_active_slot);
            } else if (recovery != ESP_ERR_NOT_FOUND) {
                ESP_LOGE(TAG, "storage recovery scan failed: %s", esp_err_to_name(recovery));
            }
            xSemaphoreGive(s_mutex);
            return ESP_ERR_INVALID_CRC;
        }
        const uint8_t *payload = mapped;
        image->coverage = payload;
        image->data = payload + MS51_IMAGE_COVERAGE_SIZE;
        image->size = s_active_info.size;
        image->covered_size = s_active_info.covered_size;
        image->crc32 = s_active_info.crc32;
        image->format = s_active_info.format;
        image->generation = s_active_info.generation;
        image->acquired = true;
        ++s_mapping_count;
    }
    xSemaphoreGive(s_mutex);
    return error;
}

void ms51_storage_release_image(ms51_storage_image_t *image)
{
    if (image == NULL || !image->acquired) {
        return;
    }
    esp_partition_munmap(image->mmap_handle);

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    if (s_mapping_count > 0) {
        --s_mapping_count;
    }
    xSemaphoreGive(s_mutex);
    memset(image, 0, sizeof(*image));
}
