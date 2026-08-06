#include "ms51_debug_uart.h"

#include <ctype.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "sdkconfig.h"

static const char *TAG = "ms51_debug_uart";

#define MS51_DEBUG_UART_PORT UART_NUM_1
#define MS51_DEBUG_RX_BUFFER_SIZE 1024u
#define MS51_DEBUG_TX_BUFFER_SIZE 512u
#define MS51_DEBUG_READ_BUFFER_SIZE 64u
#define MS51_DEBUG_LINE_SIZE 128u
#define MS51_DEBUG_TASK_STACK_SIZE 3072u
#define MS51_DEBUG_TASK_PRIORITY 4u
#define MS51_DEBUG_READ_WAIT_MS 25u

#define MS51_RUNTIME_REQUEST_SOF 0xA5u
#define MS51_RUNTIME_RESPONSE_SOF 0xA6u
#define MS51_RUNTIME_COMMAND_GET 0x47u
#define MS51_RUNTIME_COMMAND_SET 0x53u
#define MS51_RUNTIME_REQUEST_MAX_SIZE (1u + 1u + 1u + 1u + MS51_RUNTIME_CONFIG_SIZE + 2u)
#define MS51_RUNTIME_RESPONSE_TIMEOUT_MS 3000u

#if CONFIG_MS51_DEBUG_UART_ENABLE
#define MS51_DEBUG_UART_ENABLED true
#define MS51_DEBUG_UART_BAUD_RATE CONFIG_MS51_DEBUG_UART_BAUD
#else
#define MS51_DEBUG_UART_ENABLED false
#define MS51_DEBUG_UART_BAUD_RATE 115200
#endif

typedef enum {
    MS51_RUNTIME_RX_WAIT_SOF = 0,
    MS51_RUNTIME_RX_COMMAND,
    MS51_RUNTIME_RX_SEQUENCE,
    MS51_RUNTIME_RX_STATUS,
    MS51_RUNTIME_RX_LENGTH,
    MS51_RUNTIME_RX_PAYLOAD,
    MS51_RUNTIME_RX_CRC_HIGH,
    MS51_RUNTIME_RX_CRC_LOW,
} ms51_runtime_rx_state_t;

typedef struct {
    SemaphoreHandle_t mutex;
    SemaphoreHandle_t command_mutex;
    SemaphoreHandle_t response_semaphore;
    TaskHandle_t task;
    bool initialized;
    bool enabled;
    bool receiver_attached;
    bool paused_for_icp;
    uint32_t baud_rate;
    uint32_t bytes_received;
    uint32_t lines_received;
    uint32_t dropped_lines;
    uint64_t last_rx_us;
    char line[MS51_DEBUG_LINE_SIZE];
    size_t line_length;
    bool line_overflow;
    ms51_debug_variable_t variables[MS51_DEBUG_MAX_VARIABLES];
    ms51_debug_log_line_t logs[MS51_DEBUG_MAX_LOG_LINES];
    size_t log_count;
    size_t next_log;

    ms51_runtime_rx_state_t runtime_rx_state;
    uint8_t runtime_rx_command;
    uint8_t runtime_rx_sequence;
    uint8_t runtime_rx_status;
    uint8_t runtime_rx_length;
    uint8_t runtime_rx_index;
    uint16_t runtime_rx_crc;
    uint16_t runtime_rx_received_crc;
    uint8_t runtime_rx_payload[MS51_RUNTIME_CONFIG_SIZE];

    bool runtime_waiting;
    uint8_t runtime_expected_command;
    uint8_t runtime_expected_sequence;
    bool runtime_response_ready;
    uint8_t runtime_response_status;
    size_t runtime_response_length;
    uint8_t runtime_response[MS51_RUNTIME_CONFIG_SIZE];
    uint8_t next_runtime_sequence;
} ms51_debug_uart_state_t;

static ms51_debug_uart_state_t s_state;

/* GPIO5 and GPIO6 are shared with ICP_CLK and ICP_DAT.  Resetting the GPIO
 * matrix on both pins is essential before another peripheral assumes either
 * signal; changing just the direction can leave a UART route active. */
static esp_err_t release_runtime_uart_pins(void)
{
    const gpio_num_t clock_pin = (gpio_num_t)CONFIG_MS51_CLK_GPIO;
    const gpio_num_t data_pin = (gpio_num_t)CONFIG_MS51_DAT_GPIO;
    esp_err_t error = gpio_reset_pin(clock_pin);
    if (error != ESP_OK) {
        return error;
    }
    error = gpio_set_direction(clock_pin, GPIO_MODE_INPUT);
    if (error != ESP_OK) {
        return error;
    }
    error = gpio_reset_pin(data_pin);
    if (error != ESP_OK) {
        return error;
    }
    return gpio_set_direction(data_pin, GPIO_MODE_INPUT);
}

static uint16_t crc16_update(uint16_t crc, uint8_t value)
{
    crc ^= (uint16_t)value << 8;
    for (unsigned bit_index = 0; bit_index < 8u; ++bit_index) {
        crc = (crc & 0x8000u) != 0u ? (uint16_t)((crc << 1) ^ 0x1021u)
                                     : (uint16_t)(crc << 1);
    }
    return crc;
}

static void reset_partial_line_locked(void)
{
    s_state.line_length = 0;
    s_state.line_overflow = false;
    s_state.line[0] = '\0';
}

static void reset_runtime_parser_locked(void)
{
    s_state.runtime_rx_state = MS51_RUNTIME_RX_WAIT_SOF;
    s_state.runtime_rx_command = 0;
    s_state.runtime_rx_sequence = 0;
    s_state.runtime_rx_status = 0;
    s_state.runtime_rx_length = 0;
    s_state.runtime_rx_index = 0;
    s_state.runtime_rx_crc = 0xFFFFu;
    s_state.runtime_rx_received_crc = 0;
}

static char *trim_text(char *text)
{
    while (*text != '\0' && isspace((unsigned char)*text)) {
        ++text;
    }
    char *end = text + strlen(text);
    while (end > text && isspace((unsigned char)end[-1])) {
        *--end = '\0';
    }
    return text;
}

static bool variable_name_is_valid(const char *name)
{
    if (name == NULL || name[0] == '\0' ||
        strlen(name) >= MS51_DEBUG_VARIABLE_NAME_SIZE) {
        return false;
    }
    for (size_t index = 0; name[index] != '\0'; ++index) {
        const unsigned char value = (unsigned char)name[index];
        if (!isalnum(value) && value != '_' && value != '-' && value != '.' &&
            value != '[' && value != ']') {
            return false;
        }
    }
    return true;
}

static void append_log_locked(const char *text, uint64_t timestamp)
{
    if (text == NULL || text[0] == '\0') {
        return;
    }
    ms51_debug_log_line_t *entry = &s_state.logs[s_state.next_log];
    strlcpy(entry->text, text, sizeof(entry->text));
    entry->received_us = timestamp;
    s_state.next_log = (s_state.next_log + 1u) % MS51_DEBUG_MAX_LOG_LINES;
    if (s_state.log_count < MS51_DEBUG_MAX_LOG_LINES) {
        ++s_state.log_count;
    }
}

static void store_variable_locked(const char *name, const char *value, uint64_t timestamp)
{
    if (!variable_name_is_valid(name) || value == NULL || value[0] == '\0') {
        ++s_state.dropped_lines;
        return;
    }

    size_t selected = MS51_DEBUG_MAX_VARIABLES;
    size_t oldest = 0;
    for (size_t index = 0; index < MS51_DEBUG_MAX_VARIABLES; ++index) {
        if (strcmp(s_state.variables[index].name, name) == 0) {
            selected = index;
            break;
        }
        if (s_state.variables[index].name[0] == '\0' &&
            selected == MS51_DEBUG_MAX_VARIABLES) {
            selected = index;
        }
        if (s_state.variables[index].updated_us < s_state.variables[oldest].updated_us) {
            oldest = index;
        }
    }
    if (selected == MS51_DEBUG_MAX_VARIABLES) {
        selected = oldest;
    }

    ms51_debug_variable_t *entry = &s_state.variables[selected];
    strlcpy(entry->name, name, sizeof(entry->name));
    strlcpy(entry->value, value, sizeof(entry->value));
    entry->updated_us = timestamp;
}

static void finish_line_locked(uint64_t timestamp)
{
    if (s_state.line_overflow) {
        ++s_state.dropped_lines;
        reset_partial_line_locked();
        return;
    }
    s_state.line[s_state.line_length] = '\0';
    char *line = trim_text(s_state.line);
    if (line[0] == '\0') {
        reset_partial_line_locked();
        return;
    }

    ++s_state.lines_received;
    if (strncmp(line, "@var,", 5) == 0) {
        char *name = trim_text(line + 5);
        char *separator = strchr(name, ',');
        if (separator == NULL) {
            ++s_state.dropped_lines;
        } else {
            *separator = '\0';
            name = trim_text(name);
            char *value = trim_text(separator + 1);
            store_variable_locked(name, value, timestamp);
        }
    } else if (strncmp(line, "@log,", 5) == 0) {
        append_log_locked(trim_text(line + 5), timestamp);
    } else {
        append_log_locked(line, timestamp);
    }
    reset_partial_line_locked();
}

static void finish_runtime_response_locked(void)
{
    if (s_state.runtime_rx_received_crc != s_state.runtime_rx_crc) {
        ++s_state.dropped_lines;
        return;
    }
    if (!s_state.runtime_waiting ||
        s_state.runtime_rx_command != s_state.runtime_expected_command ||
        s_state.runtime_rx_sequence != s_state.runtime_expected_sequence) {
        return;
    }

    s_state.runtime_response_status = s_state.runtime_rx_status;
    s_state.runtime_response_length = s_state.runtime_rx_length;
    if (s_state.runtime_rx_length != 0u) {
        memcpy(s_state.runtime_response, s_state.runtime_rx_payload,
               s_state.runtime_rx_length);
    }
    s_state.runtime_response_ready = true;
    s_state.runtime_waiting = false;
    xSemaphoreGive(s_state.response_semaphore);
}

/* Returns true when byte belongs to a binary runtime-config response. */
static bool process_runtime_response_byte_locked(uint8_t byte)
{
    switch (s_state.runtime_rx_state) {
    case MS51_RUNTIME_RX_WAIT_SOF:
        if (byte != MS51_RUNTIME_RESPONSE_SOF) {
            return false;
        }
        reset_partial_line_locked();
        s_state.runtime_rx_state = MS51_RUNTIME_RX_COMMAND;
        s_state.runtime_rx_crc = 0xFFFFu;
        return true;

    case MS51_RUNTIME_RX_COMMAND:
        if (byte != MS51_RUNTIME_COMMAND_GET && byte != MS51_RUNTIME_COMMAND_SET) {
            ++s_state.dropped_lines;
            reset_runtime_parser_locked();
            return true;
        }
        s_state.runtime_rx_command = byte;
        s_state.runtime_rx_crc = crc16_update(s_state.runtime_rx_crc, byte);
        s_state.runtime_rx_state = MS51_RUNTIME_RX_SEQUENCE;
        return true;

    case MS51_RUNTIME_RX_SEQUENCE:
        s_state.runtime_rx_sequence = byte;
        s_state.runtime_rx_crc = crc16_update(s_state.runtime_rx_crc, byte);
        s_state.runtime_rx_state = MS51_RUNTIME_RX_STATUS;
        return true;

    case MS51_RUNTIME_RX_STATUS:
        s_state.runtime_rx_status = byte;
        s_state.runtime_rx_crc = crc16_update(s_state.runtime_rx_crc, byte);
        s_state.runtime_rx_state = MS51_RUNTIME_RX_LENGTH;
        return true;

    case MS51_RUNTIME_RX_LENGTH:
        if (byte > MS51_RUNTIME_CONFIG_SIZE) {
            ++s_state.dropped_lines;
            reset_runtime_parser_locked();
            return true;
        }
        s_state.runtime_rx_length = byte;
        s_state.runtime_rx_index = 0;
        s_state.runtime_rx_crc = crc16_update(s_state.runtime_rx_crc, byte);
        s_state.runtime_rx_state = byte == 0u ? MS51_RUNTIME_RX_CRC_HIGH
                                              : MS51_RUNTIME_RX_PAYLOAD;
        return true;

    case MS51_RUNTIME_RX_PAYLOAD:
        s_state.runtime_rx_payload[s_state.runtime_rx_index++] = byte;
        s_state.runtime_rx_crc = crc16_update(s_state.runtime_rx_crc, byte);
        if (s_state.runtime_rx_index >= s_state.runtime_rx_length) {
            s_state.runtime_rx_state = MS51_RUNTIME_RX_CRC_HIGH;
        }
        return true;

    case MS51_RUNTIME_RX_CRC_HIGH:
        s_state.runtime_rx_received_crc = (uint16_t)byte << 8;
        s_state.runtime_rx_state = MS51_RUNTIME_RX_CRC_LOW;
        return true;

    case MS51_RUNTIME_RX_CRC_LOW:
        s_state.runtime_rx_received_crc |= byte;
        finish_runtime_response_locked();
        reset_runtime_parser_locked();
        return true;

    default:
        reset_runtime_parser_locked();
        return true;
    }
}

static void process_byte_locked(uint8_t byte)
{
    ++s_state.bytes_received;
    const uint64_t timestamp = (uint64_t)esp_timer_get_time();
    s_state.last_rx_us = timestamp;

    if (process_runtime_response_byte_locked(byte)) {
        return;
    }
    if (byte == '\n') {
        finish_line_locked(timestamp);
        return;
    }
    if (byte == '\r') {
        return;
    }
    if (byte < 0x20u || byte > 0x7Eu) {
        s_state.line_overflow = true;
        return;
    }
    if (s_state.line_length + 1u >= sizeof(s_state.line)) {
        s_state.line_overflow = true;
        return;
    }
    s_state.line[s_state.line_length++] = (char)byte;
}

static esp_err_t attach_receiver_locked(void)
{
    if (!s_state.enabled || s_state.receiver_attached) {
        return ESP_OK;
    }

    const uart_config_t config = {
        .baud_rate = (int)s_state.baud_rate,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    bool driver_installed = false;
    esp_err_t error = release_runtime_uart_pins();
    if (error == ESP_OK) {
        error = uart_driver_install(MS51_DEBUG_UART_PORT, MS51_DEBUG_RX_BUFFER_SIZE,
                                    MS51_DEBUG_TX_BUFFER_SIZE, 0, NULL, 0);
        driver_installed = error == ESP_OK;
    }
    if (error == ESP_OK) {
        error = uart_param_config(MS51_DEBUG_UART_PORT, &config);
    }
    if (error == ESP_OK) {
        /* Runtime UART: ESP GPIO5 -> MS51 P0.2 RXD1; MS51 P1.6 TXD1 -> GPIO6. */
        error = uart_set_pin(MS51_DEBUG_UART_PORT, CONFIG_MS51_CLK_GPIO,
                             CONFIG_MS51_DAT_GPIO, UART_PIN_NO_CHANGE,
                             UART_PIN_NO_CHANGE);
    }
    if (error == ESP_OK) {
        error = uart_flush_input(MS51_DEBUG_UART_PORT);
    }
    if (error != ESP_OK) {
        if (driver_installed) {
            uart_driver_delete(MS51_DEBUG_UART_PORT);
        }
        release_runtime_uart_pins();
        return error;
    }

    s_state.receiver_attached = true;
    s_state.paused_for_icp = false;
    reset_partial_line_locked();
    reset_runtime_parser_locked();
    return ESP_OK;
}

static esp_err_t detach_receiver_locked(void)
{
    s_state.paused_for_icp = true;
    reset_partial_line_locked();
    reset_runtime_parser_locked();
    s_state.runtime_waiting = false;
    s_state.runtime_response_ready = false;
    if (!s_state.enabled || !s_state.receiver_attached) {
        return release_runtime_uart_pins();
    }
    esp_err_t error = uart_driver_delete(MS51_DEBUG_UART_PORT);
    if (error == ESP_OK) {
        s_state.receiver_attached = false;
        error = release_runtime_uart_pins();
    }
    return error;
}

static void receiver_task(void *argument)
{
    (void)argument;
    uint8_t buffer[MS51_DEBUG_READ_BUFFER_SIZE];

    for (;;) {
        int received = 0;
        xSemaphoreTake(s_state.mutex, portMAX_DELAY);
        if (s_state.receiver_attached) {
            /* Holding the mutex makes uart_driver_delete safe in the ICP handoff. */
            received = uart_read_bytes(MS51_DEBUG_UART_PORT, buffer, sizeof(buffer),
                                       pdMS_TO_TICKS(MS51_DEBUG_READ_WAIT_MS));
            for (int index = 0; index < received; ++index) {
                process_byte_locked(buffer[index]);
            }
        }
        xSemaphoreGive(s_state.mutex);

        if (received <= 0) {
            vTaskDelay(pdMS_TO_TICKS(5));
        }
    }
}

static esp_err_t runtime_config_request(uint8_t command, const uint8_t *payload,
                                        size_t payload_length, uint8_t *result,
                                        uint8_t *target_status)
{
    if (!s_state.initialized || !s_state.enabled || s_state.command_mutex == NULL ||
        s_state.response_semaphore == NULL || target_status == NULL ||
        (payload_length != 0u && payload == NULL)) {
        return ESP_ERR_INVALID_STATE;
    }
    if ((command == MS51_RUNTIME_COMMAND_GET && payload_length != 0u) ||
        (command == MS51_RUNTIME_COMMAND_SET && payload_length != MS51_RUNTIME_CONFIG_SIZE) ||
        (command != MS51_RUNTIME_COMMAND_GET && command != MS51_RUNTIME_COMMAND_SET)) {
        return ESP_ERR_INVALID_ARG;
    }

    if (xSemaphoreTake(s_state.command_mutex,
                       pdMS_TO_TICKS(MS51_RUNTIME_RESPONSE_TIMEOUT_MS)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    uint8_t frame[MS51_RUNTIME_REQUEST_MAX_SIZE];
    uint8_t sequence = 0;
    size_t frame_length = 0;
    esp_err_t error = ESP_OK;

    xSemaphoreTake(s_state.mutex, portMAX_DELAY);
    if (!s_state.receiver_attached) {
        error = ESP_ERR_INVALID_STATE;
    } else {
        while (xSemaphoreTake(s_state.response_semaphore, 0) == pdTRUE) {
        }
        sequence = ++s_state.next_runtime_sequence;
        if (sequence == 0u) {
            sequence = ++s_state.next_runtime_sequence;
        }
        s_state.runtime_waiting = true;
        s_state.runtime_expected_command = command;
        s_state.runtime_expected_sequence = sequence;
        s_state.runtime_response_ready = false;
        s_state.runtime_response_length = 0;
        reset_runtime_parser_locked();

        uint16_t crc = 0xFFFFu;
        frame[frame_length++] = MS51_RUNTIME_REQUEST_SOF;
        frame[frame_length++] = command;
        crc = crc16_update(crc, command);
        frame[frame_length++] = sequence;
        crc = crc16_update(crc, sequence);
        frame[frame_length++] = (uint8_t)payload_length;
        crc = crc16_update(crc, (uint8_t)payload_length);
        for (size_t index = 0; index < payload_length; ++index) {
            frame[frame_length++] = payload[index];
            crc = crc16_update(crc, payload[index]);
        }
        frame[frame_length++] = (uint8_t)(crc >> 8);
        frame[frame_length++] = (uint8_t)crc;

        const int written = uart_write_bytes(MS51_DEBUG_UART_PORT, (const char *)frame,
                                             frame_length);
        if (written < 0 || (size_t)written != frame_length) {
            s_state.runtime_waiting = false;
            error = ESP_FAIL;
        }
    }
    xSemaphoreGive(s_state.mutex);

    if (error == ESP_OK &&
        uart_wait_tx_done(MS51_DEBUG_UART_PORT,
                          pdMS_TO_TICKS(MS51_RUNTIME_RESPONSE_TIMEOUT_MS)) != ESP_OK) {
        error = ESP_ERR_TIMEOUT;
    }
    if (error == ESP_OK &&
        xSemaphoreTake(s_state.response_semaphore,
                       pdMS_TO_TICKS(MS51_RUNTIME_RESPONSE_TIMEOUT_MS)) != pdTRUE) {
        error = ESP_ERR_TIMEOUT;
    }

    xSemaphoreTake(s_state.mutex, portMAX_DELAY);
    if (error == ESP_OK) {
        if (!s_state.runtime_response_ready) {
            error = ESP_ERR_INVALID_RESPONSE;
        } else {
            *target_status = s_state.runtime_response_status;
            if (s_state.runtime_response_status == MS51_RUNTIME_CONFIG_STATUS_OK &&
                command == MS51_RUNTIME_COMMAND_GET) {
                if (s_state.runtime_response_length != MS51_RUNTIME_CONFIG_SIZE ||
                    result == NULL) {
                    error = ESP_ERR_INVALID_RESPONSE;
                } else {
                    memcpy(result, s_state.runtime_response, MS51_RUNTIME_CONFIG_SIZE);
                }
            } else if (s_state.runtime_response_length != 0u) {
                error = ESP_ERR_INVALID_RESPONSE;
            }
        }
    }
    s_state.runtime_waiting = false;
    s_state.runtime_response_ready = false;
    xSemaphoreGive(s_state.mutex);
    xSemaphoreGive(s_state.command_mutex);
    return error;
}

esp_err_t ms51_debug_uart_init(void)
{
    if (s_state.initialized) {
        return ESP_OK;
    }

    s_state.mutex = xSemaphoreCreateMutex();
    s_state.command_mutex = xSemaphoreCreateMutex();
    s_state.response_semaphore = xSemaphoreCreateBinary();
    if (s_state.mutex == NULL || s_state.command_mutex == NULL ||
        s_state.response_semaphore == NULL) {
        if (s_state.mutex != NULL) {
            vSemaphoreDelete(s_state.mutex);
        }
        if (s_state.command_mutex != NULL) {
            vSemaphoreDelete(s_state.command_mutex);
        }
        if (s_state.response_semaphore != NULL) {
            vSemaphoreDelete(s_state.response_semaphore);
        }
        memset(&s_state, 0, sizeof(s_state));
        return ESP_ERR_NO_MEM;
    }
    s_state.enabled = MS51_DEBUG_UART_ENABLED;
    s_state.baud_rate = MS51_DEBUG_UART_BAUD_RATE;
    s_state.initialized = true;

    xSemaphoreTake(s_state.mutex, portMAX_DELAY);
    const esp_err_t attach_error = attach_receiver_locked();
    xSemaphoreGive(s_state.mutex);
    if (attach_error != ESP_OK) {
        s_state.initialized = false;
        vSemaphoreDelete(s_state.response_semaphore);
        vSemaphoreDelete(s_state.command_mutex);
        vSemaphoreDelete(s_state.mutex);
        memset(&s_state, 0, sizeof(s_state));
        return attach_error;
    }

    if (s_state.enabled &&
        xTaskCreate(receiver_task, "ms51_debug", MS51_DEBUG_TASK_STACK_SIZE, NULL,
                    MS51_DEBUG_TASK_PRIORITY, &s_state.task) != pdPASS) {
        xSemaphoreTake(s_state.mutex, portMAX_DELAY);
        detach_receiver_locked();
        xSemaphoreGive(s_state.mutex);
        s_state.initialized = false;
        vSemaphoreDelete(s_state.response_semaphore);
        vSemaphoreDelete(s_state.command_mutex);
        vSemaphoreDelete(s_state.mutex);
        memset(&s_state, 0, sizeof(s_state));
        return ESP_ERR_NO_MEM;
    }

    ESP_LOGI(TAG, "%s: GPIO%d TX -> MS51 P0.2, GPIO%d RX <- MS51 P1.6 at %u baud",
             s_state.enabled ? "runtime UART enabled" : "runtime UART disabled",
             CONFIG_MS51_CLK_GPIO, CONFIG_MS51_DAT_GPIO, (unsigned)s_state.baud_rate);
    return ESP_OK;
}

esp_err_t ms51_debug_uart_pause_for_icp(void)
{
    if (!s_state.initialized || !s_state.enabled) {
        return release_runtime_uart_pins();
    }
    xSemaphoreTake(s_state.command_mutex, portMAX_DELAY);
    xSemaphoreTake(s_state.mutex, portMAX_DELAY);
    const esp_err_t error = detach_receiver_locked();
    xSemaphoreGive(s_state.mutex);
    xSemaphoreGive(s_state.command_mutex);
    if (error != ESP_OK) {
        ESP_LOGE(TAG, "could not release GPIO%d/GPIO%d for ICP: %s",
                 CONFIG_MS51_CLK_GPIO, CONFIG_MS51_DAT_GPIO, esp_err_to_name(error));
    }
    return error;
}

esp_err_t ms51_debug_uart_resume_after_icp(void)
{
    if (!s_state.initialized || !s_state.enabled) {
        return ESP_OK;
    }
    xSemaphoreTake(s_state.command_mutex, portMAX_DELAY);
    xSemaphoreTake(s_state.mutex, portMAX_DELAY);
    const esp_err_t error = attach_receiver_locked();
    xSemaphoreGive(s_state.mutex);
    xSemaphoreGive(s_state.command_mutex);
    if (error != ESP_OK) {
        ESP_LOGE(TAG, "could not restore UART on GPIO%d/GPIO%d after ICP: %s",
                 CONFIG_MS51_CLK_GPIO, CONFIG_MS51_DAT_GPIO, esp_err_to_name(error));
    }
    return error;
}

esp_err_t ms51_debug_uart_get_snapshot(ms51_debug_uart_snapshot_t *snapshot)
{
    if (snapshot == NULL || !s_state.initialized || s_state.mutex == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    memset(snapshot, 0, sizeof(*snapshot));
    xSemaphoreTake(s_state.mutex, portMAX_DELAY);
    snapshot->enabled = s_state.enabled;
    snapshot->receiver_attached = s_state.receiver_attached;
    snapshot->paused_for_icp = s_state.paused_for_icp;
    snapshot->baud_rate = s_state.baud_rate;
    snapshot->bytes_received = s_state.bytes_received;
    snapshot->lines_received = s_state.lines_received;
    snapshot->dropped_lines = s_state.dropped_lines;
    snapshot->last_rx_us = s_state.last_rx_us;

    for (size_t index = 0; index < MS51_DEBUG_MAX_VARIABLES; ++index) {
        if (s_state.variables[index].name[0] != '\0') {
            snapshot->variables[snapshot->variable_count++] = s_state.variables[index];
        }
    }
    const size_t first_log = (s_state.next_log + MS51_DEBUG_MAX_LOG_LINES -
                              s_state.log_count) %
                             MS51_DEBUG_MAX_LOG_LINES;
    for (size_t index = 0; index < s_state.log_count; ++index) {
        snapshot->logs[index] =
            s_state.logs[(first_log + index) % MS51_DEBUG_MAX_LOG_LINES];
    }
    snapshot->log_count = s_state.log_count;
    xSemaphoreGive(s_state.mutex);
    return ESP_OK;
}

esp_err_t ms51_debug_uart_get_runtime_config(uint8_t config[MS51_RUNTIME_CONFIG_SIZE],
                                             uint8_t *target_status)
{
    if (config == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    return runtime_config_request(MS51_RUNTIME_COMMAND_GET, NULL, 0, config,
                                  target_status);
}

esp_err_t ms51_debug_uart_set_runtime_config(
    const uint8_t config[MS51_RUNTIME_CONFIG_SIZE], uint8_t *target_status)
{
    if (config == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    return runtime_config_request(MS51_RUNTIME_COMMAND_SET, config,
                                  MS51_RUNTIME_CONFIG_SIZE, NULL, target_status);
}
