#include "ms51_image.h"

#include <ctype.h>
#include <string.h>

#include "esp_heap_caps.h"

static int hex_nibble(uint8_t value)
{
    if (value >= '0' && value <= '9') {
        return value - '0';
    }
    if (value >= 'A' && value <= 'F') {
        return value - 'A' + 10;
    }
    if (value >= 'a' && value <= 'f') {
        return value - 'a' + 10;
    }
    return -1;
}

static esp_err_t read_hex_byte(const uint8_t *source, size_t source_size, size_t *offset,
                               uint8_t *value)
{
    if (*offset > source_size || source_size - *offset < 2) {
        return ESP_ERR_INVALID_SIZE;
    }
    const int high = hex_nibble(source[*offset]);
    const int low = hex_nibble(source[*offset + 1]);
    if (high < 0 || low < 0) {
        return ESP_ERR_INVALID_ARG;
    }
    *value = (uint8_t)((high << 4) | low);
    *offset += 2;
    return ESP_OK;
}

static bool has_extension(const char *filename, const char *extension)
{
    if (filename == NULL || extension == NULL) {
        return false;
    }
    const size_t filename_length = strlen(filename);
    const size_t extension_length = strlen(extension);
    if (filename_length < extension_length) {
        return false;
    }
    const char *suffix = filename + filename_length - extension_length;
    for (size_t index = 0; index < extension_length; ++index) {
        if (tolower((unsigned char)suffix[index]) !=
            tolower((unsigned char)extension[index])) {
            return false;
        }
    }
    return true;
}

bool ms51_image_filename_is_intel_hex(const char *filename)
{
    return has_extension(filename, ".hex") || has_extension(filename, ".ihex") ||
           has_extension(filename, ".ihx");
}

const char *ms51_image_format_name(ms51_image_format_t format)
{
    return format == MS51_IMAGE_FORMAT_INTEL_HEX ? "intel-hex" : "bin";
}

static esp_err_t image_allocate(ms51_image_t *image, ms51_image_format_t format)
{
    memset(image, 0, sizeof(*image));
    uint8_t *allocation = heap_caps_calloc(1, MS51_IMAGE_COVERAGE_SIZE + MS51_IMAGE_MAX_SIZE,
                                           MALLOC_CAP_8BIT);
    if (allocation == NULL) {
        return ESP_ERR_NO_MEM;
    }
    image->allocation = allocation;
    image->coverage = allocation;
    image->data = allocation + MS51_IMAGE_COVERAGE_SIZE;
    memset(image->data, 0xFF, MS51_IMAGE_MAX_SIZE);
    image->format = format;
    return ESP_OK;
}

void ms51_image_free(ms51_image_t *image)
{
    if (image == NULL) {
        return;
    }
    heap_caps_free(image->allocation);
    memset(image, 0, sizeof(*image));
}

bool ms51_image_address_is_covered(const ms51_image_t *image, size_t address)
{
    if (image == NULL || image->data == NULL || address >= image->size) {
        return false;
    }
    if (image->coverage == NULL) {
        return true;
    }
    return (image->coverage[address >> 3] & (uint8_t)(1u << (address & 7u))) != 0;
}

static esp_err_t mark_data(ms51_image_t *image, uint32_t address, const uint8_t *data,
                           size_t length)
{
    if (address > MS51_IMAGE_MAX_SIZE || length > MS51_IMAGE_MAX_SIZE - address) {
        return ESP_ERR_INVALID_SIZE;
    }
    for (size_t index = 0; index < length; ++index) {
        const size_t target = (size_t)address + index;
        const uint8_t bit = (uint8_t)(1u << (target & 7u));
        if ((image->coverage[target >> 3] & bit) != 0) {
            return ESP_ERR_INVALID_STATE;
        }
        image->coverage[target >> 3] |= bit;
        image->data[target] = data[index];
    }
    image->covered_size += length;
    const size_t end = (size_t)address + length;
    if (end > image->size) {
        image->size = end;
    }
    return ESP_OK;
}

static esp_err_t parse_intel_hex(const uint8_t *source, size_t source_size,
                                 ms51_image_t *image)
{
    uint32_t base_address = 0;
    bool seen_eof = false;
    size_t offset = 0;

    while (offset < source_size) {
        while (offset < source_size && (source[offset] == '\r' || source[offset] == '\n')) {
            ++offset;
        }
        if (offset == source_size) {
            break;
        }
        if (seen_eof || source[offset++] != ':') {
            return ESP_ERR_INVALID_ARG;
        }

        uint8_t count = 0;
        uint8_t address_high = 0;
        uint8_t address_low = 0;
        uint8_t type = 0;
        esp_err_t error = read_hex_byte(source, source_size, &offset, &count);
        if (error == ESP_OK) {
            error = read_hex_byte(source, source_size, &offset, &address_high);
        }
        if (error == ESP_OK) {
            error = read_hex_byte(source, source_size, &offset, &address_low);
        }
        if (error == ESP_OK) {
            error = read_hex_byte(source, source_size, &offset, &type);
        }
        if (error != ESP_OK) {
            return error;
        }

        uint8_t data[UINT8_MAX];
        uint8_t checksum = (uint8_t)(count + address_high + address_low + type);
        for (uint16_t index = 0; index < count; ++index) {
            error = read_hex_byte(source, source_size, &offset, &data[index]);
            if (error != ESP_OK) {
                return error;
            }
            checksum = (uint8_t)(checksum + data[index]);
        }
        uint8_t record_checksum = 0;
        error = read_hex_byte(source, source_size, &offset, &record_checksum);
        if (error != ESP_OK || (uint8_t)(checksum + record_checksum) != 0) {
            return error == ESP_OK ? ESP_ERR_INVALID_CRC : error;
        }
        if (offset < source_size && source[offset] != '\r' && source[offset] != '\n') {
            return ESP_ERR_INVALID_ARG;
        }

        const uint16_t address = ((uint16_t)address_high << 8) | address_low;
        switch (type) {
        case 0x00: {
            /* A zero-length data record carries no APROM bytes.  In
             * particular, do not let one at a high address extend the image
             * range and make an otherwise valid image fail the APROM-size
             * check. */
            if (count == 0) {
                break;
            }
            const uint64_t absolute = (uint64_t)base_address + address;
            if (absolute >= MS51_IMAGE_MAX_SIZE ||
                count > MS51_IMAGE_MAX_SIZE - (size_t)absolute) {
                return ESP_ERR_INVALID_SIZE;
            }
            error = mark_data(image, (uint32_t)absolute, data, count);
            if (error != ESP_OK) {
                return error;
            }
            break;
        }
        case 0x01:
            if (count != 0 || address != 0) {
                return ESP_ERR_INVALID_ARG;
            }
            seen_eof = true;
            break;
        case 0x02:
            if (count != 2 || address != 0) {
                return ESP_ERR_INVALID_ARG;
            }
            base_address = ((uint32_t)(((uint16_t)data[0] << 8) | data[1])) << 4;
            break;
        case 0x03:
        case 0x05:
            if (count != 4 || address != 0) {
                return ESP_ERR_INVALID_ARG;
            }
            break;
        case 0x04:
            if (count != 2 || address != 0) {
                return ESP_ERR_INVALID_ARG;
            }
            base_address = ((uint32_t)(((uint16_t)data[0] << 8) | data[1])) << 16;
            break;
        default:
            return ESP_ERR_NOT_SUPPORTED;
        }
    }

    return seen_eof && image->covered_size > 0 ? ESP_OK : ESP_ERR_INVALID_ARG;
}

esp_err_t ms51_image_parse_upload(const uint8_t *source, size_t source_size,
                                  const char *filename, ms51_image_t *image)
{
    if (source == NULL || source_size == 0 || image == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    const ms51_image_format_t format = ms51_image_filename_is_intel_hex(filename)
                                           ? MS51_IMAGE_FORMAT_INTEL_HEX
                                           : MS51_IMAGE_FORMAT_BINARY;
    if ((format == MS51_IMAGE_FORMAT_BINARY && source_size > MS51_IMAGE_MAX_SIZE) ||
        (format == MS51_IMAGE_FORMAT_INTEL_HEX && source_size > MS51_IMAGE_MAX_UPLOAD_SIZE)) {
        return ESP_ERR_INVALID_SIZE;
    }

    esp_err_t error = image_allocate(image, format);
    if (error != ESP_OK) {
        return error;
    }
    if (format == MS51_IMAGE_FORMAT_BINARY) {
        error = mark_data(image, 0, source, source_size);
    } else {
        error = parse_intel_hex(source, source_size, image);
    }
    if (error != ESP_OK) {
        ms51_image_free(image);
    }
    return error;
}
