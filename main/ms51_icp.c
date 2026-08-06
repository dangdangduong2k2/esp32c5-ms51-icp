/*
 * MS51 1T-8051 ICP transport for ESP32-S3.
 *
 * The wire protocol in this file is not I2C. It is based on the MIT-licensed
 * NuMicro-8051-prog implementation by Steve Markgraf and Nikita Lita, which
 * reports hardware testing with MS51FC0AE. Nuvoton's public TRM documents the
 * three ICP signals but does not publish this command-level protocol.
 * See THIRD_PARTY_NOTICES.md before redistributing this file.
 */

#include "ms51_icp.h"

#include <inttypes.h>

#include "driver/gpio.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_rom_sys.h"
#include "freertos/FreeRTOS.h"
#include "freertos/portmacro.h"
#include "sdkconfig.h"

static const char *TAG = "ms51_icp";

enum {
    ICP_CMD_READ_FLASH = 0x00,
    ICP_CMD_READ_UID = 0x04,
    ICP_CMD_READ_CID = 0x0B,
    ICP_CMD_READ_DEVICE_ID = 0x0C,
    ICP_CMD_WRITE_FLASH = 0x21,
    ICP_CMD_PAGE_ERASE = 0x22,
    ICP_CMD_MASS_ERASE = 0x26,
};

#define ICP_COMMAND_BITS 24u
#define ICP_COMMAND_DATA_MASK UINT32_C(0x3FFFF)
#define ICP_COMMAND_OPCODE_MASK UINT32_C(0x3F)

#define ICP_RESET_SEQUENCE UINT32_C(0x9E1CB6)
#define ICP_RESET_SEQUENCE_BITS 25u /* Leading zero followed by the 24-bit value. */
#define ICP_ENTRY_BITS UINT32_C(0x5AA503)
#define ICP_EXIT_BITS UINT32_C(0x0F78F0)

#define ICP_ENTRY_HALF_BIT_US 60u
#define ICP_RESET_LEVEL_US 10000u
#define ICP_PROGRAM_US 25u
#define ICP_PROGRAM_HOLD_US 5u
#define ICP_PAGE_ERASE_US 6000u
#define ICP_PAGE_ERASE_HOLD_US 100u
#define ICP_MASS_ERASE_US 65000u
#define ICP_MASS_ERASE_HOLD_US 1000u

static bool s_open;
static portMUX_TYPE s_gpio_mux = portMUX_INITIALIZER_UNLOCKED;

static inline void delay_us(uint32_t microseconds)
{
    if (microseconds != 0) {
        esp_rom_delay_us(microseconds);
    }
}

static inline void set_rst(int level)
{
    gpio_set_level((gpio_num_t)CONFIG_MS51_RST_GPIO, level);
}

static inline void set_clk(int level)
{
    gpio_set_level((gpio_num_t)CONFIG_MS51_CLK_GPIO, level);
}

static inline void set_dat(int level)
{
    gpio_set_level((gpio_num_t)CONFIG_MS51_DAT_GPIO, level);
}

static inline int get_dat(void)
{
    return gpio_get_level((gpio_num_t)CONFIG_MS51_DAT_GPIO);
}

static esp_err_t set_dat_direction(gpio_mode_t mode)
{
    return gpio_set_direction((gpio_num_t)CONFIG_MS51_DAT_GPIO, mode);
}

static esp_err_t validate_gpio_configuration(void)
{
    const gpio_num_t rst = (gpio_num_t)CONFIG_MS51_RST_GPIO;
    const gpio_num_t clk = (gpio_num_t)CONFIG_MS51_CLK_GPIO;
    const gpio_num_t dat = (gpio_num_t)CONFIG_MS51_DAT_GPIO;

    ESP_RETURN_ON_FALSE(GPIO_IS_VALID_OUTPUT_GPIO(rst), ESP_ERR_INVALID_ARG, TAG,
                        "GPIO%d cannot drive nRESET", rst);
    ESP_RETURN_ON_FALSE(GPIO_IS_VALID_OUTPUT_GPIO(clk), ESP_ERR_INVALID_ARG, TAG,
                        "GPIO%d cannot drive ICP_CLK", clk);
    ESP_RETURN_ON_FALSE(GPIO_IS_VALID_OUTPUT_GPIO(dat), ESP_ERR_INVALID_ARG, TAG,
                        "GPIO%d cannot drive ICP_DAT", dat);
    ESP_RETURN_ON_FALSE(rst != clk && rst != dat && clk != dat, ESP_ERR_INVALID_ARG, TAG,
                        "RST, CLK and DAT GPIOs must be different");
    return ESP_OK;
}

static esp_err_t configure_owned_pins(void)
{
    ESP_RETURN_ON_ERROR(validate_gpio_configuration(), TAG, "invalid GPIO configuration");

    /*
     * Keep DAT high-impedance until the target is held in reset: P1.6 is an
     * ordinary application GPIO outside ICP and could otherwise drive against
     * the ESP32. CLK must be driven low before the reset key, however; the
     * MS51 ICP entry sequence samples the reset waveform relative to a low
     * clock, and a passive pull-up on CLK makes entry fail.
     */
    const uint64_t mask = (UINT64_C(1) << CONFIG_MS51_RST_GPIO) |
                          (UINT64_C(1) << CONFIG_MS51_CLK_GPIO) |
                          (UINT64_C(1) << CONFIG_MS51_DAT_GPIO);
    const gpio_config_t config = {
        .pin_bit_mask = mask,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&config), TAG, "GPIO configuration failed");

    set_rst(0);
    set_clk(0);
    set_dat(0);
    ESP_RETURN_ON_ERROR(gpio_set_direction((gpio_num_t)CONFIG_MS51_RST_GPIO,
                                           GPIO_MODE_OUTPUT),
                        TAG, "could not assert target reset");
    ESP_RETURN_ON_ERROR(gpio_set_direction((gpio_num_t)CONFIG_MS51_CLK_GPIO,
                                           GPIO_MODE_OUTPUT),
                        TAG, "could not drive ICP clock low before reset key");
    return ESP_OK;
}

static esp_err_t enable_icp_data_pins(void)
{
    ESP_RETURN_ON_ERROR(gpio_set_direction((gpio_num_t)CONFIG_MS51_CLK_GPIO,
                                           GPIO_MODE_OUTPUT),
                        TAG, "could not drive ICP clock");
    ESP_RETURN_ON_ERROR(gpio_set_direction((gpio_num_t)CONFIG_MS51_DAT_GPIO,
                                           GPIO_MODE_OUTPUT),
                        TAG, "could not drive ICP data");
    return ESP_OK;
}

static void release_pins(void)
{
    gpio_set_direction((gpio_num_t)CONFIG_MS51_CLK_GPIO, GPIO_MODE_INPUT);
    gpio_set_direction((gpio_num_t)CONFIG_MS51_DAT_GPIO, GPIO_MODE_INPUT);
    gpio_set_direction((gpio_num_t)CONFIG_MS51_RST_GPIO, GPIO_MODE_INPUT);
}

static void bitsend(uint32_t data, unsigned bit_count, uint32_t half_bit_us, bool atomic)
{
    set_dat_direction(GPIO_MODE_OUTPUT);

    if (atomic) {
        portENTER_CRITICAL(&s_gpio_mux);
    }
    for (int bit = (int)bit_count - 1; bit >= 0; --bit) {
        set_dat((data >> bit) & 1u);
        delay_us(half_bit_us);
        set_clk(1);
        delay_us(half_bit_us);
        set_clk(0);
    }
    if (atomic) {
        portEXIT_CRITICAL(&s_gpio_mux);
    }
}

static void send_command(uint8_t command, uint32_t data)
{
    const uint32_t frame = ((data & ICP_COMMAND_DATA_MASK) << 6) |
                           (command & ICP_COMMAND_OPCODE_MASK);
    bitsend(frame, ICP_COMMAND_BITS, CONFIG_MS51_BIT_DELAY_US, true);
}

static void send_reset_sequence(void)
{
    /* The first level is zero (bit 24), then the 24-bit reset key follows. */
    for (int bit = (int)ICP_RESET_SEQUENCE_BITS - 1; bit >= 0; --bit) {
        set_rst((ICP_RESET_SEQUENCE >> bit) & 1u);
        delay_us(ICP_RESET_LEVEL_US);
    }
}

static uint8_t read_byte(bool end)
{
    set_dat_direction(GPIO_MODE_INPUT);
    delay_us(CONFIG_MS51_BIT_DELAY_US);

    uint8_t value = 0;
    portENTER_CRITICAL(&s_gpio_mux);
    for (int bit = 7; bit >= 0; --bit) {
        delay_us(CONFIG_MS51_BIT_DELAY_US);
        const int state = get_dat();
        set_clk(1);
        delay_us(CONFIG_MS51_BIT_DELAY_US);
        set_clk(0);
        value |= (uint8_t)(state << bit);
    }
    portEXIT_CRITICAL(&s_gpio_mux);

    set_dat_direction(GPIO_MODE_OUTPUT);
    delay_us(CONFIG_MS51_BIT_DELAY_US);
    set_dat(end ? 1 : 0);
    delay_us(CONFIG_MS51_BIT_DELAY_US);
    set_clk(1);
    delay_us(CONFIG_MS51_BIT_DELAY_US);
    set_clk(0);
    delay_us(CONFIG_MS51_BIT_DELAY_US);
    set_dat(0);
    return value;
}

static void write_byte(uint8_t value, bool end, uint32_t operation_us, uint32_t hold_us)
{
    bitsend(value, 8, CONFIG_MS51_BIT_DELAY_US, true);
    set_dat(end ? 1 : 0);
    delay_us(operation_us);
    set_clk(1);
    delay_us(hold_us);
    set_dat(0);
    set_clk(0);
}

static uint16_t read_device_word(uint32_t selector)
{
    send_command(ICP_CMD_READ_DEVICE_ID, selector);
    const uint8_t low = read_byte(false);
    const uint8_t high = read_byte(true);
    return ((uint16_t)high << 8) | low;
}

static uint8_t read_company_id(void)
{
    send_command(ICP_CMD_READ_CID, 0);
    return read_byte(true);
}

static void read_identity(ms51_icp_identity_t *identity)
{
    identity->device_id = read_device_word(0);
    identity->product_id = read_device_word(2);
    identity->company_id = read_company_id();
}

esp_err_t ms51_icp_open(ms51_icp_identity_t *identity)
{
    ESP_RETURN_ON_FALSE(identity != NULL, ESP_ERR_INVALID_ARG, TAG, "identity is NULL");
    ESP_RETURN_ON_FALSE(!s_open, ESP_ERR_INVALID_STATE, TAG, "ICP session is already open");
    ESP_RETURN_ON_ERROR(configure_owned_pins(), TAG, "could not claim ICP pins");

    delay_us(12000);
    send_reset_sequence();
    set_rst(0);
    delay_us(100);
    esp_err_t error = enable_icp_data_pins();
    if (error != ESP_OK) {
        release_pins();
        return error;
    }
    bitsend(ICP_ENTRY_BITS, 24, ICP_ENTRY_HALF_BIT_US, true);
    delay_us(10);

    s_open = true;
    read_identity(identity);

    ESP_LOGD(TAG, "ICP identity: DID=0x%04" PRIX16 ", PID=0x%04" PRIX16
                  ", CID=0x%02" PRIX8,
             identity->device_id, identity->product_id, identity->company_id);
    return ESP_OK;
}

esp_err_t ms51_icp_reenter(ms51_icp_identity_t *identity)
{
    ESP_RETURN_ON_FALSE(identity != NULL, ESP_ERR_INVALID_ARG, TAG, "identity is NULL");
    ESP_RETURN_ON_FALSE(s_open, ESP_ERR_INVALID_STATE, TAG, "ICP session is closed");

    /* Match the proven re-entry timing used by NuMicro-8051-prog. This is
     * useful when a target needs a second entry pulse after power-up or erase. */
    set_rst(1);
    delay_us(5000);
    set_rst(0);
    delay_us(1000);
    bitsend(ICP_ENTRY_BITS, 24, ICP_ENTRY_HALF_BIT_US, true);
    delay_us(10);
    read_identity(identity);
    return ESP_OK;
}

void ms51_icp_close(void)
{
    if (!s_open) {
        release_pins();
        return;
    }

    set_rst(1);
    delay_us(5000);
    set_rst(0);
    delay_us(10000);
    bitsend(ICP_EXIT_BITS, 24, ICP_ENTRY_HALF_BIT_US, true);
    delay_us(500);
    set_rst(1);
    delay_us(100);
    release_pins();
    s_open = false;
}

bool ms51_icp_is_open(void)
{
    return s_open;
}

esp_err_t ms51_icp_read_flash(uint32_t address, void *data, size_t length)
{
    ESP_RETURN_ON_FALSE(s_open, ESP_ERR_INVALID_STATE, TAG, "ICP session is closed");
    ESP_RETURN_ON_FALSE(data != NULL, ESP_ERR_INVALID_ARG, TAG, "read buffer is NULL");
    ESP_RETURN_ON_FALSE(length > 0 && length <= UINT32_MAX, ESP_ERR_INVALID_SIZE, TAG,
                        "invalid read length");
    ESP_RETURN_ON_FALSE(address <= ICP_COMMAND_DATA_MASK &&
                            length - 1 <= ICP_COMMAND_DATA_MASK - address,
                        ESP_ERR_INVALID_ARG, TAG, "ICP read address is out of range");

    uint8_t *bytes = data;
    send_command(ICP_CMD_READ_FLASH, address);
    for (size_t index = 0; index < length; ++index) {
        bytes[index] = read_byte(index == length - 1);
    }
    return ESP_OK;
}

esp_err_t ms51_icp_write_flash(uint32_t address, const void *data, size_t length)
{
    ESP_RETURN_ON_FALSE(s_open, ESP_ERR_INVALID_STATE, TAG, "ICP session is closed");
    ESP_RETURN_ON_FALSE(data != NULL, ESP_ERR_INVALID_ARG, TAG, "write buffer is NULL");
    ESP_RETURN_ON_FALSE(length > 0 && length <= UINT32_MAX, ESP_ERR_INVALID_SIZE, TAG,
                        "invalid write length");
    ESP_RETURN_ON_FALSE(address <= ICP_COMMAND_DATA_MASK &&
                            length - 1 <= ICP_COMMAND_DATA_MASK - address,
                        ESP_ERR_INVALID_ARG, TAG, "ICP write address is out of range");

    const uint8_t *bytes = data;
    send_command(ICP_CMD_WRITE_FLASH, address);
    for (size_t index = 0; index < length; ++index) {
        write_byte(bytes[index], index == length - 1, ICP_PROGRAM_US, ICP_PROGRAM_HOLD_US);
    }
    return ESP_OK;
}

esp_err_t ms51_icp_page_erase(uint32_t address)
{
    ESP_RETURN_ON_FALSE(s_open, ESP_ERR_INVALID_STATE, TAG, "ICP session is closed");
    ESP_RETURN_ON_FALSE(address <= ICP_COMMAND_DATA_MASK, ESP_ERR_INVALID_ARG, TAG,
                        "ICP erase address is out of range");

    send_command(ICP_CMD_PAGE_ERASE, address);
    write_byte(0xFF, true, ICP_PAGE_ERASE_US, ICP_PAGE_ERASE_HOLD_US);
    return ESP_OK;
}

esp_err_t ms51_icp_mass_erase(void)
{
    ESP_RETURN_ON_FALSE(s_open, ESP_ERR_INVALID_STATE, TAG, "ICP session is closed");
    send_command(ICP_CMD_MASS_ERASE, UINT32_C(0x3A5A5));
    write_byte(0xFF, true, ICP_MASS_ERASE_US, ICP_MASS_ERASE_HOLD_US);
    /* Some 1T-8051 parts do not answer immediately after a whole-chip erase. */
    delay_us(500000);
    return ESP_OK;
}

esp_err_t ms51_icp_reset_target(void)
{
    ESP_RETURN_ON_FALSE(!s_open, ESP_ERR_INVALID_STATE, TAG,
                        "cannot reset target while an ICP session is open");
    ESP_RETURN_ON_ERROR(validate_gpio_configuration(), TAG, "invalid GPIO configuration");

    const gpio_config_t reset_config = {
        .pin_bit_mask = UINT64_C(1) << CONFIG_MS51_RST_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&reset_config), TAG, "could not drive reset pin");
    set_rst(0);
    delay_us(20000);
    gpio_set_direction((gpio_num_t)CONFIG_MS51_RST_GPIO, GPIO_MODE_INPUT);
    return ESP_OK;
}
