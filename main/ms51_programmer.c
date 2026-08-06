#include "ms51_programmer.h"

#include <inttypes.h>
#include <string.h>

#include "esp_check.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"

#include "ms51_operation.h"
#include "ms51_debug_uart.h"

static const char *TAG = "ms51_programmer";
static bool s_uart_handoff_active;

static esp_err_t reset_runtime_uart_pins(void)
{
    esp_err_t error = gpio_reset_pin((gpio_num_t)CONFIG_MS51_CLK_GPIO);
    if (error != ESP_OK) {
        return error;
    }
    return gpio_reset_pin((gpio_num_t)CONFIG_MS51_DAT_GPIO);
}

static size_t ldrom_size_from_config(uint8_t config1)
{
    const uint8_t selector = config1 & 0x07u;
    if (selector <= 3) {
        return 4u * 1024u;
    }
    return (size_t)(7u - selector) * 1024u;
}

static uint32_t full_part_id(const ms51_icp_identity_t *identity)
{
    return ((uint32_t)identity->product_id << 16) | identity->device_id;
}

static bool has_expected_part_id(const ms51_icp_identity_t *identity)
{
    return full_part_id(identity) == MS51FC0AE_PART_ID;
}

static bool has_expected_identity(const ms51_icp_identity_t *identity)
{
    return has_expected_part_id(identity) && identity->company_id == MS51_NUVOTON_CID;
}

static esp_err_t lock_programmer(void)
{
    return ms51_operation_lock(portMAX_DELAY);
}

static void unlock_programmer(void)
{
    ms51_operation_unlock();
}

/* The existing hardware multiplexes the two ICP wires as UART1 while MS51
 * runs: GPIO5/P0.2 is RXD and GPIO6/P1.6 is TXD.  There is never a
 * simultaneous owner: before ICP both GPIO matrix routes are detached and the
 * MS51 is held in reset before ESP32 drives CLK or DAT. */
static esp_err_t open_icp_session(ms51_icp_identity_t *identity)
{
    ESP_RETURN_ON_FALSE(!s_uart_handoff_active, ESP_ERR_INVALID_STATE, TAG,
                        "UART handoff is already active");

    esp_err_t error = ms51_debug_uart_pause_for_icp();
    if (error != ESP_OK) {
        return error;
    }
    s_uart_handoff_active = true;

    /* The UART module resets both pins to floating inputs.  Do it once more at
     * the programmer boundary so a future change cannot leave a GPIO matrix
     * route behind when ICP takes ownership. */
    error = reset_runtime_uart_pins();
    if (error == ESP_OK) {
        error = ms51_icp_open(identity);
    }
    if (error == ESP_OK) {
        return ESP_OK;
    }

    const esp_err_t reset_error = reset_runtime_uart_pins();
    if (reset_error != ESP_OK) {
        ESP_LOGW(TAG, "could not reset runtime UART pins after failed ICP handoff: %s",
                 esp_err_to_name(reset_error));
    }
    const esp_err_t resume_error = ms51_debug_uart_resume_after_icp();
    if (resume_error != ESP_OK) {
        ESP_LOGE(TAG, "could not restore UART RX after failed ICP handoff: %s",
                 esp_err_to_name(resume_error));
    }
    s_uart_handoff_active = false;
    return error;
}

static esp_err_t read_info_in_session(ms51_device_info_t *info)
{
    ESP_RETURN_ON_ERROR(ms51_icp_read_flash(MS51_CONFIG_ADDRESS, info->config,
                                             sizeof(info->config)),
                        TAG, "could not read configuration bytes");
    /* A security-locked MS51 can mask CID. Treat that conservatively as locked,
     * while still allowing the explicit, confirmed mass-erase recovery flow. */
    info->locked = info->identity.company_id != MS51_NUVOTON_CID ||
                   (info->config[0] & (1u << 1)) == 0;
    info->ldrom_size = ldrom_size_from_config(info->config[1]);
    info->aprom_size = MS51_FLASH_SIZE - info->ldrom_size;
    return ESP_OK;
}

static esp_err_t open_checked_session(ms51_device_info_t *info)
{
    memset(info, 0, sizeof(*info));
    ESP_RETURN_ON_ERROR(open_icp_session(&info->identity), TAG, "could not enter ICP mode");

    /* The three-wire entry is timing-sensitive. Re-enter a couple of times
     * before rejecting the target, and preserve a masked CID as a possible
     * security-lock state when DID/PID still identify an MS51FC0AE. */
    for (unsigned attempt = 1; attempt < 3 && !has_expected_identity(&info->identity);
         ++attempt) {
        ESP_LOGW(TAG,
                 "ICP identity retry %u: DID=0x%04" PRIX16 ", PID=0x%04" PRIX16
                 ", CID=0x%02" PRIX8,
                 attempt, info->identity.device_id, info->identity.product_id,
                 info->identity.company_id);
        const esp_err_t retry_error = ms51_icp_reenter(&info->identity);
        if (retry_error != ESP_OK) {
            ms51_icp_close();
            return retry_error;
        }
    }

    const uint32_t part_id = full_part_id(&info->identity);
    if (!has_expected_part_id(&info->identity)) {
        ESP_LOGE(TAG,
                 "Unexpected target: PDID=0x%08" PRIX32
                 " (DID=0x%04" PRIX16 ", PID=0x%04" PRIX16 ", CID=0x%02" PRIX8 ")",
                 part_id, info->identity.device_id, info->identity.product_id,
                 info->identity.company_id);
        ms51_icp_close();
        return ESP_ERR_NOT_FOUND;
    }
    if (info->identity.company_id != MS51_NUVOTON_CID) {
        ESP_LOGW(TAG, "MS51 CID is masked (0x%02" PRIX8 "); treating target as locked",
                 info->identity.company_id);
    }

    esp_err_t error = read_info_in_session(info);
    if (error != ESP_OK) {
        ms51_icp_close();
        return error;
    }

    /* The MS51FC0AE responds to READ_FLASH reliably after the initial
     * configuration read and one standard ICP re-entry pulse.  Keep the
     * re-entry inside every complete programmer session so UART handoff and
     * all subsequent APROM reads/writes use the same proven state. */
    error = ms51_icp_reenter(&info->identity);
    if (error != ESP_OK) {
        ms51_icp_close();
        return error;
    }
    if (!has_expected_identity(&info->identity)) {
        ms51_icp_close();
        return ESP_ERR_NOT_FOUND;
    }
    return ESP_OK;
}

static void close_session(void)
{
    if (ms51_icp_is_open()) {
        ms51_icp_close();
    }
    if (!s_uart_handoff_active) {
        return;
    }

    const esp_err_t reset_error = reset_runtime_uart_pins();
    if (reset_error != ESP_OK) {
        ESP_LOGW(TAG, "could not release runtime UART pins from ICP: %s",
                 esp_err_to_name(reset_error));
    }
    const esp_err_t resume_error = ms51_debug_uart_resume_after_icp();
    if (resume_error != ESP_OK) {
        ESP_LOGE(TAG, "could not restore UART RX after ICP: %s",
                 esp_err_to_name(resume_error));
    }
    s_uart_handoff_active = false;
}

static bool page_has_covered_data(const ms51_image_t *image, size_t address)
{
    if (address >= image->size) {
        return false;
    }
    size_t length = image->size - address;
    if (length > MS51_FLASH_PAGE_SIZE) {
        length = MS51_FLASH_PAGE_SIZE;
    }
    for (size_t index = 0; index < length; ++index) {
        if (ms51_image_address_is_covered(image, address + index)) {
            return true;
        }
    }
    return false;
}

static void expected_page(const ms51_image_t *image, size_t address, bool erase_trailing,
                          const uint8_t current[MS51_FLASH_PAGE_SIZE],
                          uint8_t page[MS51_FLASH_PAGE_SIZE])
{
    if (erase_trailing) {
        memset(page, 0xFF, MS51_FLASH_PAGE_SIZE);
    } else {
        memcpy(page, current, MS51_FLASH_PAGE_SIZE);
    }
    if (address >= image->size) {
        return;
    }
    size_t copy_length = image->size - address;
    if (copy_length > MS51_FLASH_PAGE_SIZE) {
        copy_length = MS51_FLASH_PAGE_SIZE;
    }
    for (size_t index = 0; index < copy_length; ++index) {
        if (ms51_image_address_is_covered(image, address + index)) {
            page[index] = image->data[address + index];
        }
    }
}

static size_t programmed_prefix_length(const uint8_t page[MS51_FLASH_PAGE_SIZE])
{
    size_t length = MS51_FLASH_PAGE_SIZE;
    while (length > 0 && page[length - 1] == 0xFF) {
        --length;
    }
    return length;
}

static void log_page_verify_mismatch(size_t page_address,
                                     const uint8_t expected[MS51_FLASH_PAGE_SIZE],
                                     const uint8_t actual[MS51_FLASH_PAGE_SIZE],
                                     unsigned attempt)
{
    for (size_t index = 0; index < MS51_FLASH_PAGE_SIZE; ++index) {
        if (expected[index] != actual[index]) {
            ESP_LOGW(TAG,
                     "Verify mismatch at APROM 0x%04X: expected 0x%02X, got 0x%02X "
                     "(attempt %u/%u)",
                     (unsigned)(page_address + index), expected[index], actual[index], attempt + 1,
                     CONFIG_MS51_PROGRAM_RETRIES + 1);
            return;
        }
    }
}

static esp_err_t validate_image(const ms51_image_t *image)
{
    ESP_RETURN_ON_FALSE(image != NULL && image->data != NULL, ESP_ERR_INVALID_ARG, TAG,
                        "image is NULL");
    ESP_RETURN_ON_FALSE(image->size > 0 && image->covered_size > 0,
                        ESP_ERR_INVALID_SIZE, TAG,
                        "no MS51 firmware image is embedded");
    ESP_RETURN_ON_FALSE(image->size <= MS51_FLASH_SIZE &&
                            image->covered_size <= image->size,
                        ESP_ERR_INVALID_SIZE, TAG,
                        "image is larger than the physical flash");
    return ESP_OK;
}

static ms51_image_t dense_image(const uint8_t *data, size_t size)
{
    return (ms51_image_t){
        .data = (uint8_t *)data,
        .coverage = NULL,
        .size = size,
        .covered_size = size,
        .format = MS51_IMAGE_FORMAT_BINARY,
        .allocation = NULL,
    };
}

esp_err_t ms51_programmer_init(void)
{
    return ms51_operation_init();
}

esp_err_t ms51_programmer_get_info(ms51_device_info_t *info)
{
    ESP_RETURN_ON_FALSE(info != NULL, ESP_ERR_INVALID_ARG, TAG, "info is NULL");
    ESP_RETURN_ON_ERROR(lock_programmer(), TAG, "lock failed");

    esp_err_t error = open_checked_session(info);
    close_session();
    unlock_programmer();
    return error;
}

esp_err_t ms51_programmer_try_get_info(ms51_device_info_t *info)
{
    ESP_RETURN_ON_FALSE(info != NULL, ESP_ERR_INVALID_ARG, TAG, "info is NULL");
    ESP_RETURN_ON_ERROR(ms51_operation_lock(0), TAG, "programmer is busy");

    esp_err_t error = open_checked_session(info);
    close_session();
    unlock_programmer();
    return error;
}

esp_err_t ms51_programmer_read_aprom(uint32_t address, uint8_t *data, size_t length)
{
    ESP_RETURN_ON_FALSE(data != NULL && length > 0, ESP_ERR_INVALID_ARG, TAG,
                        "read buffer is invalid");
    ESP_RETURN_ON_ERROR(lock_programmer(), TAG, "lock failed");

    esp_err_t error = ESP_OK;
    ms51_device_info_t info;
    error = open_checked_session(&info);
    if (error != ESP_OK) {
        goto done;
    }
    if (info.locked) {
        error = ESP_ERR_INVALID_STATE;
        goto done;
    }
    if (address >= info.aprom_size || length > info.aprom_size - address) {
        error = ESP_ERR_INVALID_SIZE;
        goto done;
    }
    error = ms51_icp_read_flash(address, data, length);

done:
    close_session();
    unlock_programmer();
    return error;
}

static esp_err_t program_image(const ms51_image_t *image, bool verify, bool erase_trailing)
{
    ESP_RETURN_ON_ERROR(validate_image(image), TAG, "invalid firmware image");
    ESP_RETURN_ON_ERROR(lock_programmer(), TAG, "lock failed");

    esp_err_t error = ESP_OK;
    ms51_device_info_t info;
    size_t changed_pages = 0;
    size_t skipped_pages = 0;
    uint8_t wanted[MS51_FLASH_PAGE_SIZE];
    uint8_t actual[MS51_FLASH_PAGE_SIZE];

    error = open_checked_session(&info);
    if (error != ESP_OK) {
        goto done;
    }
    if (info.locked) {
        ESP_LOGE(TAG,
                 "Target security lock is enabled. Automatic programming will not mass-erase it; "
                 "run 'erase CONFIRM' explicitly first");
        error = ESP_ERR_INVALID_STATE;
        goto done;
    }
    if (image->size > info.aprom_size) {
        ESP_LOGE(TAG, "Image reaches 0x%04X but current CONFIG leaves only %u bytes of APROM",
                 (unsigned)(image->size - 1), (unsigned)info.aprom_size);
        error = ESP_ERR_INVALID_SIZE;
        goto done;
    }

    const size_t page_count = erase_trailing
                                  ? info.aprom_size / MS51_FLASH_PAGE_SIZE
                                  : (image->size + MS51_FLASH_PAGE_SIZE - 1) /
                                        MS51_FLASH_PAGE_SIZE;
    ESP_LOGI(TAG, "Programming %u covered byte(s) through 0x%04X into %u-byte APROM "
                  "(%u page(s), %s)",
             (unsigned)image->covered_size, (unsigned)(image->size - 1),
             (unsigned)info.aprom_size, (unsigned)page_count,
             erase_trailing ? "full replacement" : "preserve trailing APROM");

    for (size_t page_index = 0; page_index < page_count; ++page_index) {
        const size_t address = page_index * MS51_FLASH_PAGE_SIZE;
        if (!erase_trailing && !page_has_covered_data(image, address)) {
            ++skipped_pages;
            continue;
        }
        error = ms51_icp_read_flash((uint32_t)address, actual, sizeof(actual));
        if (error != ESP_OK) {
            goto done;
        }
        expected_page(image, address, erase_trailing, actual, wanted);
        if (memcmp(wanted, actual, sizeof(wanted)) == 0) {
            ++skipped_pages;
            vTaskDelay(1);
            continue;
        }

        bool page_ok = false;
        const size_t write_length = programmed_prefix_length(wanted);
        for (unsigned attempt = 0; attempt <= CONFIG_MS51_PROGRAM_RETRIES; ++attempt) {
            error = ms51_icp_page_erase((uint32_t)address);
            if (error != ESP_OK) {
                goto done;
            }
            if (write_length > 0) {
                error = ms51_icp_write_flash((uint32_t)address, wanted, write_length);
                if (error != ESP_OK) {
                    goto done;
                }
            }

            if (!verify) {
                page_ok = true;
                break;
            }
            error = ms51_icp_read_flash((uint32_t)address, actual, sizeof(actual));
            if (error != ESP_OK) {
                goto done;
            }
            if (memcmp(wanted, actual, sizeof(wanted)) == 0) {
                page_ok = true;
                break;
            }
            log_page_verify_mismatch(address, wanted, actual, attempt);
        }
        if (!page_ok) {
            ESP_LOGE(TAG, "Could not program APROM page at 0x%04X", (unsigned)address);
            error = ESP_ERR_INVALID_RESPONSE;
            goto done;
        }

        ++changed_pages;
        if ((page_index & 0x0Fu) == 0 || page_index + 1 == page_count) {
            ESP_LOGI(TAG, "Progress: %u/%u pages", (unsigned)(page_index + 1),
                     (unsigned)page_count);
        }
        vTaskDelay(1);
    }

    ESP_LOGI(TAG, "MS51 programming complete: %u page(s) changed, %u already matched",
             (unsigned)changed_pages, (unsigned)skipped_pages);

done:
    close_session();
    unlock_programmer();
    return error;
}

esp_err_t ms51_programmer_program_image(const ms51_image_t *image, bool verify)
{
    return program_image(image, verify, false);
}

esp_err_t ms51_programmer_program_image_full(const ms51_image_t *image, bool verify)
{
    return program_image(image, verify, true);
}

esp_err_t ms51_programmer_verify_image(const ms51_image_t *image)
{
    ESP_RETURN_ON_ERROR(validate_image(image), TAG, "invalid firmware image");
    ESP_RETURN_ON_ERROR(lock_programmer(), TAG, "lock failed");

    esp_err_t error = ESP_OK;
    ms51_device_info_t info;
    uint8_t actual[MS51_FLASH_PAGE_SIZE];

    error = open_checked_session(&info);
    if (error != ESP_OK) {
        goto done;
    }
    if (info.locked) {
        ESP_LOGE(TAG, "Target is locked; APROM cannot be verified through ICP");
        error = ESP_ERR_INVALID_STATE;
        goto done;
    }
    if (image->size > info.aprom_size) {
        error = ESP_ERR_INVALID_SIZE;
        goto done;
    }

    for (size_t address = 0; address < image->size; address += MS51_FLASH_PAGE_SIZE) {
        if (!page_has_covered_data(image, address)) {
            continue;
        }
        error = ms51_icp_read_flash((uint32_t)address, actual, sizeof(actual));
        if (error != ESP_OK) {
            goto done;
        }
        const size_t compare_length = image->size - address < MS51_FLASH_PAGE_SIZE
                                          ? image->size - address
                                          : MS51_FLASH_PAGE_SIZE;
        for (size_t offset = 0; offset < compare_length; ++offset) {
            if (!ms51_image_address_is_covered(image, address + offset)) {
                continue;
            }
            if (image->data[address + offset] != actual[offset]) {
                ESP_LOGE(TAG, "Verify mismatch at 0x%04X: expected 0x%02X, read 0x%02X",
                         (unsigned)(address + offset), image->data[address + offset],
                         actual[offset]);
                error = ESP_ERR_INVALID_CRC;
                goto done;
            }
        }
        vTaskDelay(1);
    }
    ESP_LOGI(TAG, "Image verification passed (%u covered byte(s))",
             (unsigned)image->covered_size);

done:
    close_session();
    unlock_programmer();
    return error;
}

esp_err_t ms51_programmer_program(const uint8_t *image, size_t image_size, bool verify)
{
    const ms51_image_t view = dense_image(image, image_size);
    return ms51_programmer_program_image(&view, verify);
}

esp_err_t ms51_programmer_program_full(const uint8_t *image, size_t image_size, bool verify)
{
    const ms51_image_t view = dense_image(image, image_size);
    return ms51_programmer_program_image_full(&view, verify);
}

esp_err_t ms51_programmer_verify(const uint8_t *image, size_t image_size)
{
    const ms51_image_t view = dense_image(image, image_size);
    return ms51_programmer_verify_image(&view);
}

esp_err_t ms51_programmer_mass_erase(void)
{
    ESP_RETURN_ON_ERROR(lock_programmer(), TAG, "lock failed");

    esp_err_t error = ESP_OK;
    ms51_device_info_t info;
    error = open_checked_session(&info);
    if (error != ESP_OK) {
        goto done;
    }

    ESP_LOGW(TAG, "Mass erase destroys APROM, LDROM and CONFIG (SPROM is not erased)");
    error = ms51_icp_mass_erase();
    close_session();
    if (error != ESP_OK) {
        goto done;
    }

    /* Re-enter and prove that CONFIG and all physical program flash erased. */
    vTaskDelay(pdMS_TO_TICKS(20));
    error = open_checked_session(&info);
    if (error != ESP_OK) {
        goto done;
    }
    for (size_t index = 0; index < sizeof(info.config); ++index) {
        if (info.config[index] != 0xFF) {
            ESP_LOGE(TAG, "Mass-erase verification failed: CONFIG[%u]=0x%02X",
                     (unsigned)index, info.config[index]);
            error = ESP_ERR_INVALID_RESPONSE;
            goto done;
        }
    }

    uint8_t page[MS51_FLASH_PAGE_SIZE];
    for (size_t address = 0; address < MS51_FLASH_SIZE;
         address += MS51_FLASH_PAGE_SIZE) {
        error = ms51_icp_read_flash((uint32_t)address, page, sizeof(page));
        if (error != ESP_OK) {
            goto done;
        }
        for (size_t index = 0; index < sizeof(page); ++index) {
            if (page[index] != 0xFF) {
                ESP_LOGE(TAG, "Mass-erase verification failed at 0x%04X: 0x%02X",
                         (unsigned)(address + index), page[index]);
                error = ESP_ERR_INVALID_RESPONSE;
                goto done;
            }
        }
        vTaskDelay(1);
    }
    ESP_LOGI(TAG, "Whole-chip erase verified");

done:
    close_session();
    unlock_programmer();
    return error;
}

esp_err_t ms51_programmer_reset(void)
{
    ESP_RETURN_ON_ERROR(lock_programmer(), TAG, "lock failed");
    const esp_err_t error = ms51_icp_reset_target();
    unlock_programmer();
    return error;
}

esp_err_t ms51_programmer_try_reset(void)
{
    ESP_RETURN_ON_ERROR(ms51_operation_lock(0), TAG, "programmer is busy");
    const esp_err_t error = ms51_icp_reset_target();
    unlock_programmer();
    return error;
}
