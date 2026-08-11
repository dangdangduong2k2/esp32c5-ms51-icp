#include "ms51_web.h"

#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_check.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_heap_caps.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "esp_wifi_default.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "lwip/inet.h"
#include "mdns.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "sdkconfig.h"

#include "ms51_operation.h"
#include "ms51_debug_uart.h"
#include "ms51_image.h"
#include "ms51_programmer.h"
#include "ms51_storage.h"

static const char *TAG = "ms51_web";

#define HTTP_UPLOAD_BUFFER_SIZE 2048u
#define HTTP_JSON_BODY_SIZE 1024u
#define JOB_TASK_STACK_SIZE 6144u
#define JOB_TASK_PRIORITY 5u
#define UPLINK_SSID_MAX_LENGTH 32u
#define UPLINK_PASSWORD_MAX_LENGTH 63u
#define UPLINK_MAX_RETRIES 5u
#define WIFI_SCAN_MAX_NETWORKS 20u
#define WIFI_SCAN_START_RETRIES 10u
#define WIFI_SCAN_RETRY_DELAY_MS 150u
#define DHCPS_OFFER_DNS 0x02u
#define UPLINK_NVS_NAMESPACE "uplink"
#define UPLINK_NVS_SSID_KEY "ssid"
#define UPLINK_NVS_PASSWORD_KEY "password"
#define MDNS_HOSTNAME "ms51"
#define MDNS_HOSTNAME_FQDN MDNS_HOSTNAME ".local"
#define MDNS_INSTANCE_NAME "MS51 Controller"

extern const char web_index_start[] asm("_binary_index_html_start");

typedef enum {
    JOB_PROGRAM = 1,
    JOB_VERIFY,
    JOB_PROGRAM_FULL,
    JOB_MASS_ERASE,
} job_type_t;

typedef struct {
    job_type_t type;
    uint32_t generation;
    uint32_t id;
} job_request_t;

typedef struct {
    bool busy;
    uint32_t job_id;
    esp_err_t last_error;
    char operation[24];
    char message[160];
} web_state_t;

typedef enum {
    UPLINK_NOT_CONFIGURED = 0,
    UPLINK_CONNECTING,
    UPLINK_CONNECTED,
    UPLINK_FAILED,
} uplink_connection_state_t;

typedef struct {
    bool configured;
    bool nat_enabled;
    bool dns_ready;
    uint8_t retries;
    uint8_t last_reason;
    int8_t rssi;
    uplink_connection_state_t connection_state;
    char ssid[UPLINK_SSID_MAX_LENGTH + 1u];
    char ip[16];
    char gateway[16];
} uplink_state_t;

static httpd_handle_t s_server;
static SemaphoreHandle_t s_state_mutex;
static SemaphoreHandle_t s_uplink_mutex;
static SemaphoreHandle_t s_wifi_operation_mutex;
static QueueHandle_t s_job_queue;
static TaskHandle_t s_job_task;
static esp_netif_t *s_ap_netif;
static esp_netif_t *s_sta_netif;
static bool s_netif_initialized;
static bool s_event_loop_created;
static bool s_wifi_initialized;
static bool s_wifi_handler_registered;
static bool s_ip_handler_registered;
static bool s_wifi_started;
static bool s_mdns_started;
static web_state_t s_state;
static uplink_state_t s_uplink;
static char s_ap_ip[16] = "192.168.4.1";

static const char *job_name(job_type_t type)
{
    switch (type) {
    case JOB_PROGRAM:
        return "program";
    case JOB_VERIFY:
        return "verify";
    case JOB_PROGRAM_FULL:
        return "program-full";
    case JOB_MASS_ERASE:
        return "erase";
    default:
        return "unknown";
    }
}

static const char *job_running_message(job_type_t type)
{
    switch (type) {
    case JOB_PROGRAM:
        return "Đang nạp firmware và giữ nguyên APROM phía sau...";
    case JOB_VERIFY:
        return "Đang kiểm tra firmware trong MS51...";
    case JOB_PROGRAM_FULL:
        return "Đang nạp toàn bộ APROM...";
    case JOB_MASS_ERASE:
        return "Đang xóa và xác minh toàn bộ chip...";
    default:
        return "Đang xử lý...";
    }
}

static const char *job_success_message(job_type_t type)
{
    switch (type) {
    case JOB_PROGRAM:
        return "Nạp firmware MS51 thành công.";
    case JOB_VERIFY:
        return "Firmware trong MS51 khớp file đã tải lên.";
    case JOB_PROGRAM_FULL:
        return "Nạp toàn bộ APROM thành công.";
    case JOB_MASS_ERASE:
        return "Đã xóa và xác minh toàn bộ flash MS51.";
    default:
        return "Thao tác hoàn tất.";
    }
}

static void state_set_message(const char *message, esp_err_t error)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    strlcpy(s_state.message, message, sizeof(s_state.message));
    s_state.last_error = error;
    xSemaphoreGive(s_state_mutex);
}

static bool state_is_busy(void)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    const bool busy = s_state.busy;
    xSemaphoreGive(s_state_mutex);
    return busy;
}

static web_state_t state_snapshot(void)
{
    web_state_t state;
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    state = s_state;
    xSemaphoreGive(s_state_mutex);
    return state;
}

static const char *uplink_connection_state_name(uplink_connection_state_t state)
{
    switch (state) {
    case UPLINK_CONNECTING:
        return "connecting";
    case UPLINK_CONNECTED:
        return "connected";
    case UPLINK_FAILED:
        return "failed";
    case UPLINK_NOT_CONFIGURED:
    default:
        return "not_configured";
    }
}

static uplink_state_t uplink_snapshot(void)
{
    uplink_state_t uplink;
    memset(&uplink, 0, sizeof(uplink));
    if (s_uplink_mutex == NULL) {
        return uplink;
    }
    xSemaphoreTake(s_uplink_mutex, portMAX_DELAY);
    uplink = s_uplink;
    xSemaphoreGive(s_uplink_mutex);
    return uplink;
}

static void uplink_set_not_configured(void)
{
    if (s_uplink_mutex == NULL) {
        return;
    }
    xSemaphoreTake(s_uplink_mutex, portMAX_DELAY);
    memset(&s_uplink, 0, sizeof(s_uplink));
    s_uplink.connection_state = UPLINK_NOT_CONFIGURED;
    xSemaphoreGive(s_uplink_mutex);
}

static void uplink_set_connecting(const char *ssid)
{
    if (s_uplink_mutex == NULL) {
        return;
    }
    xSemaphoreTake(s_uplink_mutex, portMAX_DELAY);
    s_uplink.configured = true;
    s_uplink.nat_enabled = false;
    s_uplink.dns_ready = false;
    s_uplink.retries = 0;
    s_uplink.last_reason = 0;
    s_uplink.rssi = 0;
    s_uplink.connection_state = UPLINK_CONNECTING;
    strlcpy(s_uplink.ssid, ssid, sizeof(s_uplink.ssid));
    s_uplink.ip[0] = '\0';
    s_uplink.gateway[0] = '\0';
    xSemaphoreGive(s_uplink_mutex);
}

static bool uplink_retry_after_disconnect(uint8_t reason)
{
    bool retry = false;
    if (s_uplink_mutex == NULL) {
        return false;
    }
    xSemaphoreTake(s_uplink_mutex, portMAX_DELAY);
    s_uplink.nat_enabled = false;
    s_uplink.dns_ready = false;
    s_uplink.ip[0] = '\0';
    s_uplink.gateway[0] = '\0';
    s_uplink.rssi = 0;
    s_uplink.last_reason = reason;
    if (s_uplink.configured && s_uplink.retries < UPLINK_MAX_RETRIES) {
        ++s_uplink.retries;
        s_uplink.connection_state = UPLINK_CONNECTING;
        retry = true;
    } else if (s_uplink.configured) {
        s_uplink.connection_state = UPLINK_FAILED;
    } else {
        s_uplink.connection_state = UPLINK_NOT_CONFIGURED;
    }
    xSemaphoreGive(s_uplink_mutex);
    return retry;
}

static void uplink_set_failed(uint8_t reason)
{
    if (s_uplink_mutex == NULL) {
        return;
    }
    xSemaphoreTake(s_uplink_mutex, portMAX_DELAY);
    s_uplink.nat_enabled = false;
    s_uplink.dns_ready = false;
    s_uplink.last_reason = reason;
    s_uplink.connection_state = s_uplink.configured ? UPLINK_FAILED :
                                                     UPLINK_NOT_CONFIGURED;
    xSemaphoreGive(s_uplink_mutex);
}

static void uplink_set_connected(const esp_netif_ip_info_t *ip_info, int8_t rssi,
                                 bool dns_ready, bool nat_enabled)
{
    if (s_uplink_mutex == NULL) {
        return;
    }
    xSemaphoreTake(s_uplink_mutex, portMAX_DELAY);
    s_uplink.connection_state = UPLINK_CONNECTED;
    s_uplink.retries = 0;
    s_uplink.last_reason = 0;
    s_uplink.rssi = rssi;
    s_uplink.dns_ready = dns_ready;
    s_uplink.nat_enabled = nat_enabled;
    inet_ntoa_r(ip_info->ip.addr, s_uplink.ip, sizeof(s_uplink.ip));
    inet_ntoa_r(ip_info->gw.addr, s_uplink.gateway, sizeof(s_uplink.gateway));
    xSemaphoreGive(s_uplink_mutex);
}

/* The built-in AP remains a recovery path, but mDNS gives users a stable URL
 * after both the phone and ESP have moved to the same external Wi-Fi. */
static esp_err_t start_mdns(void)
{
    if (s_mdns_started) {
        return ESP_OK;
    }

    esp_err_t error = mdns_init();
    if (error != ESP_OK) {
        return error;
    }
    error = mdns_hostname_set(MDNS_HOSTNAME);
    if (error != ESP_OK) {
        mdns_free();
        return error;
    }
    error = mdns_instance_name_set(MDNS_INSTANCE_NAME);
    if (error != ESP_OK) {
        mdns_free();
        return error;
    }

    mdns_txt_item_t service_txt[] = {
        {"path", "/"},
    };
    error = mdns_service_add(MDNS_INSTANCE_NAME, "_http", "_tcp", 80,
                             service_txt, sizeof(service_txt) / sizeof(service_txt[0]));
    if (error != ESP_OK) {
        mdns_free();
        return error;
    }
    s_mdns_started = true;
    return ESP_OK;
}

static void stop_mdns(void)
{
    if (s_mdns_started) {
        mdns_free();
        s_mdns_started = false;
    }
}

static bool json_escape(const char *input, char *output, size_t output_size)
{
    size_t used = 0;
    for (size_t index = 0; input[index] != '\0'; ++index) {
        const unsigned char value = (unsigned char)input[index];
        const char *escape = NULL;
        if (value == '"') {
            escape = "\\\"";
        } else if (value == '\\') {
            escape = "\\\\";
        } else if (value == '\n') {
            escape = "\\n";
        } else if (value == '\r') {
            escape = "\\r";
        } else if (value == '\t') {
            escape = "\\t";
        }

        if (escape != NULL) {
            const size_t length = strlen(escape);
            if (used + length + 1 > output_size) {
                return false;
            }
            memcpy(output + used, escape, length);
            used += length;
        } else if (value >= 0x20) {
            if (used + 2 > output_size) {
                return false;
            }
            output[used++] = (char)value;
        }
    }
    output[used] = '\0';
    return true;
}

static esp_err_t send_json(httpd_req_t *request, const char *payload,
                           const char *status)
{
    if (status != NULL) {
        httpd_resp_set_status(request, status);
    }
    httpd_resp_set_type(request, "application/json; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, payload);
}

static esp_err_t send_error_json(httpd_req_t *request, const char *status,
                                 const char *message, esp_err_t error)
{
    char escaped_message[384];
    char escaped_error[96];
    char payload[576];
    if (!json_escape(message, escaped_message, sizeof(escaped_message)) ||
        !json_escape(esp_err_to_name(error), escaped_error, sizeof(escaped_error))) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR,
                                   "JSON response too large");
    }
    snprintf(payload, sizeof(payload),
             "{\"ok\":false,\"message\":\"%s\",\"error\":\"%s\"}",
             escaped_message, escaped_error);
    return send_json(request, payload, status);
}

static bool content_type_is_json(httpd_req_t *request)
{
    char value[64];
    if (httpd_req_get_hdr_value_str(request, "Content-Type", value,
                                    sizeof(value)) != ESP_OK) {
        return false;
    }
    return strncmp(value, "application/json", strlen("application/json")) == 0;
}

static bool content_type_is_firmware(httpd_req_t *request)
{
    char value[64];
    if (httpd_req_get_hdr_value_str(request, "Content-Type", value,
                                    sizeof(value)) != ESP_OK) {
        return false;
    }
    return strncmp(value, "application/octet-stream", strlen("application/octet-stream")) ==
               0 ||
           strncmp(value, "text/plain", strlen("text/plain")) == 0 ||
           strncmp(value, "text/x-ihex", strlen("text/x-ihex")) == 0 ||
           strncmp(value, "application/x-ihex", strlen("application/x-ihex")) == 0;
}

static esp_err_t receive_json(httpd_req_t *request, char *body, size_t body_size)
{
    if (!content_type_is_json(request) || request->content_len == 0 ||
        request->content_len >= body_size) {
        return ESP_ERR_INVALID_ARG;
    }

    size_t received_total = 0;
    unsigned timeout_count = 0;
    while (received_total < request->content_len) {
        const int received = httpd_req_recv(request, body + received_total,
                                            request->content_len - received_total);
        if (received == HTTPD_SOCK_ERR_TIMEOUT && timeout_count++ < 3) {
            continue;
        }
        if (received <= 0) {
            return ESP_ERR_INVALID_RESPONSE;
        }
        received_total += (size_t)received;
    }
    body[received_total] = '\0';

    const char *start = body;
    while (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n') {
        ++start;
    }
    const char *end = body + received_total;
    while (end > start && (end[-1] == ' ' || end[-1] == '\t' ||
                           end[-1] == '\r' || end[-1] == '\n')) {
        --end;
    }
    if (end - start < 2 || *start != '{' || end[-1] != '}') {
        return ESP_ERR_INVALID_ARG;
    }
    return ESP_OK;
}

static const char *json_value(const char *json, const char *key)
{
    char pattern[48];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char *position = strstr(json, pattern);
    if (position == NULL) {
        return NULL;
    }
    position += strlen(pattern);
    while (*position == ' ' || *position == '\t' || *position == '\r' ||
           *position == '\n') {
        ++position;
    }
    if (*position++ != ':') {
        return NULL;
    }
    while (*position == ' ' || *position == '\t' || *position == '\r' ||
           *position == '\n') {
        ++position;
    }
    return position;
}

/* Copy one JSON string without accepting a pointer into the request body.  The
 * regular endpoint parser only needs numbers and fixed text, but Wi-Fi names
 * and passwords can legitimately contain spaces, quotes and backslashes. */
static bool json_string_copy(const char *json, const char *key, char *output,
                             size_t output_size)
{
    const char *input = json_value(json, key);
    if (input == NULL || output_size == 0 || *input++ != '"') {
        return false;
    }

    size_t used = 0;
    while (*input != '\0') {
        unsigned char value = (unsigned char)*input++;
        if (value == '"') {
            output[used] = '\0';
            return true;
        }
        if (value < 0x20u) {
            return false;
        }
        if (value == '\\') {
            const char escaped = *input++;
            switch (escaped) {
            case '"':
            case '\\':
            case '/':
                value = (unsigned char)escaped;
                break;
            case 'b':
                value = '\b';
                break;
            case 'f':
                value = '\f';
                break;
            case 'n':
                value = '\n';
                break;
            case 'r':
                value = '\r';
                break;
            case 't':
                value = '\t';
                break;
            default:
                /* Browsers normally send UTF-8 directly.  Reject unsupported
                 * unicode escapes rather than storing a different password. */
                return false;
            }
        }
        if (used + 1u >= output_size) {
            return false;
        }
        output[used++] = (char)value;
    }
    return false;
}

static uint32_t json_generation(const char *json)
{
    const char *value = json_value(json, "generation");
    if (value == NULL) {
        return 0;
    }
    char *end = NULL;
    const unsigned long parsed = strtoul(value, &end, 10);
    if (end == value || parsed == 0 || parsed > UINT32_MAX) {
        return 0;
    }
    return (uint32_t)parsed;
}

static bool json_confirmed(const char *json)
{
    const char *value = json_value(json, "confirm");
    if (value == NULL || *value++ != '"') {
        return false;
    }
    static const char confirmation[] = "CONFIRM";
    return strncmp(value, confirmation, sizeof(confirmation) - 1) == 0 &&
           value[sizeof(confirmation) - 1] == '"';
}

static bool state_begin_runtime_operation(const char *operation, const char *message)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (s_state.busy) {
        xSemaphoreGive(s_state_mutex);
        return false;
    }
    s_state.busy = true;
    s_state.last_error = ESP_OK;
    strlcpy(s_state.operation, operation, sizeof(s_state.operation));
    strlcpy(s_state.message, message, sizeof(s_state.message));
    xSemaphoreGive(s_state_mutex);
    return true;
}

static void state_finish_runtime_operation(const char *message, esp_err_t error)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.busy = false;
    s_state.last_error = error;
    s_state.operation[0] = '\0';
    strlcpy(s_state.message, message, sizeof(s_state.message));
    xSemaphoreGive(s_state_mutex);
}

static esp_err_t root_handler(httpd_req_t *request)
{
    httpd_resp_set_type(request, "text/html; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, web_index_start);
}

static esp_err_t status_handler(httpd_req_t *request)
{
    ms51_storage_info_t image;
    ms51_storage_get_info(&image);
    const web_state_t state = state_snapshot();
    const uplink_state_t uplink = uplink_snapshot();

    char message[384];
    char operation[64];
    char last_error[96];
    char ssid[96];
    char uplink_ssid[96];
    char uplink_ip[32];
    char uplink_gateway[32];
    char filename[160];
    char format[32];
    if (!json_escape(state.message, message, sizeof(message)) ||
        !json_escape(state.operation, operation, sizeof(operation)) ||
        !json_escape(esp_err_to_name(state.last_error), last_error,
                     sizeof(last_error)) ||
        !json_escape(CONFIG_MS51_WIFI_SSID, ssid, sizeof(ssid)) ||
        !json_escape(uplink.ssid, uplink_ssid, sizeof(uplink_ssid)) ||
        !json_escape(uplink.ip, uplink_ip, sizeof(uplink_ip)) ||
        !json_escape(uplink.gateway, uplink_gateway, sizeof(uplink_gateway)) ||
        !json_escape(image.filename, filename, sizeof(filename)) ||
        !json_escape(ms51_image_format_name(image.format), format, sizeof(format))) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR,
                                   "JSON response too large");
    }

    // JSON-escaped status text can be substantially larger than its source
    // buffers.  Keep this response comfortably above the compiler-calculated
    // maximum as the image metadata adds format and coverage fields.
    char payload[2048];
    if (image.valid) {
        snprintf(payload, sizeof(payload),
                 "{\"ok\":true,\"busy\":%s,\"job_id\":%" PRIu32
                 ",\"operation\":\"%s\",\"last_message\":\"%s\""
                 ",\"last_error\":\"%s\",\"wifi\":{\"ssid\":\"%s\""
                 ",\"ip\":\"%s\",\"uplink\":{\"configured\":%s,\"state\":\"%s\""
                 ",\"ssid\":\"%s\",\"ip\":\"%s\",\"gateway\":\"%s\""
                 ",\"rssi\":%d,\"dns_ready\":%s,\"nat_enabled\":%s"
                 ",\"hostname\":\"%s\",\"mdns_ready\":%s}}"
                 ",\"image\":{\"valid\":true,\"name\":\"%s\""
                 ",\"size\":%u,\"covered_size\":%u,\"format\":\"%s\""
                 ",\"crc32\":%" PRIu32 ",\"generation\":%" PRIu32 "}}",
                 state.busy ? "true" : "false", state.job_id, operation, message,
                 last_error, ssid, s_ap_ip, uplink.configured ? "true" : "false",
                 uplink_connection_state_name(uplink.connection_state), uplink_ssid,
                 uplink_ip, uplink_gateway, (int)uplink.rssi,
                 uplink.dns_ready ? "true" : "false",
                 uplink.nat_enabled ? "true" : "false", MDNS_HOSTNAME_FQDN,
                 s_mdns_started ? "true" : "false", filename, (unsigned)image.size,
                 (unsigned)image.covered_size, format, image.crc32, image.generation);
    } else {
        snprintf(payload, sizeof(payload),
                 "{\"ok\":true,\"busy\":%s,\"job_id\":%" PRIu32
                 ",\"operation\":\"%s\",\"last_message\":\"%s\""
                 ",\"last_error\":\"%s\",\"wifi\":{\"ssid\":\"%s\""
                 ",\"ip\":\"%s\",\"uplink\":{\"configured\":%s,\"state\":\"%s\""
                 ",\"ssid\":\"%s\",\"ip\":\"%s\",\"gateway\":\"%s\""
                 ",\"rssi\":%d,\"dns_ready\":%s,\"nat_enabled\":%s"
                 ",\"hostname\":\"%s\",\"mdns_ready\":%s}}"
                 ",\"image\":{\"valid\":false}}",
                 state.busy ? "true" : "false", state.job_id, operation, message,
                 last_error, ssid, s_ap_ip, uplink.configured ? "true" : "false",
                 uplink_connection_state_name(uplink.connection_state), uplink_ssid,
                 uplink_ip, uplink_gateway, (int)uplink.rssi,
                 uplink.dns_ready ? "true" : "false",
                 uplink.nat_enabled ? "true" : "false", MDNS_HOSTNAME_FQDN,
                 s_mdns_started ? "true" : "false");
    }
    return send_json(request, payload, NULL);
}

static void timestamp_age_json(uint64_t timestamp, char output[16])
{
    if (timestamp == 0) {
        strlcpy(output, "null", 16);
        return;
    }
    const int64_t now = esp_timer_get_time();
    const uint64_t age_us = now > (int64_t)timestamp ? (uint64_t)now - timestamp : 0;
    const uint64_t age_ms = age_us / 1000u;
    snprintf(output, 16, "%" PRIu32,
             age_ms > UINT32_MAX ? UINT32_MAX : (uint32_t)age_ms);
}

static esp_err_t debug_handler(httpd_req_t *request)
{
    ms51_debug_uart_snapshot_t snapshot;
    const esp_err_t snapshot_error = ms51_debug_uart_get_snapshot(&snapshot);
    if (snapshot_error != ESP_OK) {
        return send_error_json(request, "503 Service Unavailable",
                               "Runtime debug receiver is unavailable.", snapshot_error);
    }

    httpd_resp_set_type(request, "application/json; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");

    char last_age[16];
    timestamp_age_json(snapshot.last_rx_us, last_age);
    char chunk[256];
    const int header_length = snprintf(
        chunk, sizeof(chunk),
        "{\"ok\":true,\"enabled\":%s,\"receiver_attached\":%s,\"paused_for_icp\":%s"
        ",\"baud\":%" PRIu32 ",\"bytes\":%" PRIu32 ",\"lines\":%" PRIu32
        ",\"dropped_lines\":%" PRIu32 ",\"last_rx_age_ms\":%s,\"variables\":[",
        snapshot.enabled ? "true" : "false", snapshot.receiver_attached ? "true" : "false",
        snapshot.paused_for_icp ? "true" : "false", snapshot.baud_rate,
        snapshot.bytes_received, snapshot.lines_received, snapshot.dropped_lines, last_age);
    if (header_length < 0 || (size_t)header_length >= sizeof(chunk) ||
        httpd_resp_send_chunk(request, chunk, (size_t)header_length) != ESP_OK) {
        return ESP_FAIL;
    }

    for (size_t index = 0; index < snapshot.variable_count; ++index) {
        char name[MS51_DEBUG_VARIABLE_NAME_SIZE * 2u + 1u];
        char value[MS51_DEBUG_VARIABLE_VALUE_SIZE * 2u + 1u];
        char age[16];
        if (!json_escape(snapshot.variables[index].name, name, sizeof(name)) ||
            !json_escape(snapshot.variables[index].value, value, sizeof(value))) {
            return ESP_FAIL;
        }
        timestamp_age_json(snapshot.variables[index].updated_us, age);
        const int length = snprintf(chunk, sizeof(chunk),
                                    "%s{\"name\":\"%s\",\"value\":\"%s\",\"age_ms\":%s}",
                                    index == 0 ? "" : ",", name, value, age);
        if (length < 0 || (size_t)length >= sizeof(chunk) ||
            httpd_resp_send_chunk(request, chunk, (size_t)length) != ESP_OK) {
            return ESP_FAIL;
        }
    }

    if (httpd_resp_send_chunk(request, "],\"logs\":[", strlen("],\"logs\":[")) != ESP_OK) {
        return ESP_FAIL;
    }
    for (size_t index = 0; index < snapshot.log_count; ++index) {
        char text[MS51_DEBUG_LOG_TEXT_SIZE * 2u + 1u];
        char age[16];
        if (!json_escape(snapshot.logs[index].text, text, sizeof(text))) {
            return ESP_FAIL;
        }
        timestamp_age_json(snapshot.logs[index].received_us, age);
        const int length = snprintf(chunk, sizeof(chunk),
                                    "%s{\"text\":\"%s\",\"age_ms\":%s}",
                                    index == 0 ? "" : ",", text, age);
        if (length < 0 || (size_t)length >= sizeof(chunk) ||
            httpd_resp_send_chunk(request, chunk, (size_t)length) != ESP_OK) {
            return ESP_FAIL;
        }
    }
    if (httpd_resp_send_chunk(request, "]}", strlen("]}")) != ESP_OK) {
        return ESP_FAIL;
    }
    return httpd_resp_send_chunk(request, NULL, 0);
}

static esp_err_t info_handler(httpd_req_t *request)
{
    char body[HTTP_JSON_BODY_SIZE];
    if (receive_json(request, body, sizeof(body)) != ESP_OK) {
        return send_error_json(request, "400 Bad Request",
                               "Yêu cầu phải là JSON hợp lệ.", ESP_ERR_INVALID_ARG);
    }
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict",
                               "ESP32 đang thực hiện một thao tác khác.",
                               ESP_ERR_INVALID_STATE);
    }

    ms51_device_info_t info;
    const esp_err_t error = ms51_programmer_try_get_info(&info);
    if (error == ESP_ERR_TIMEOUT) {
        return send_error_json(request, "409 Conflict",
                               "ESP32 đang thực hiện một thao tác khác.", error);
    }
    if (error != ESP_OK) {
        char message[128];
        snprintf(message, sizeof(message), "Không đọc được MS51: %s",
                 esp_err_to_name(error));
        state_set_message(message, error);
        return send_error_json(request, "422 Unprocessable Entity", message, error);
    }

    const uint32_t part_id = ((uint32_t)info.identity.product_id << 16) |
                             info.identity.device_id;
    char payload[640];
    snprintf(payload, sizeof(payload),
             "{\"ok\":true,\"message\":\"Đã đọc thông tin MS51.\""
             ",\"pdid\":%" PRIu32 ",\"device_id\":%u,\"product_id\":%u"
             ",\"company_id\":%u,\"locked\":%s,\"aprom_size\":%u"
             ",\"ldrom_size\":%u,\"config\":[%u,%u,%u,%u,%u]}",
             part_id, info.identity.device_id, info.identity.product_id,
             info.identity.company_id, info.locked ? "true" : "false",
             (unsigned)info.aprom_size, (unsigned)info.ldrom_size,
             info.config[0], info.config[1], info.config[2], info.config[3],
             info.config[4]);
    state_set_message("Đã đọc thông tin MS51.", ESP_OK);
    return send_json(request, payload, NULL);
}

static esp_err_t reset_handler(httpd_req_t *request)
{
    char body[HTTP_JSON_BODY_SIZE];
    if (receive_json(request, body, sizeof(body)) != ESP_OK) {
        return send_error_json(request, "400 Bad Request",
                               "Yêu cầu phải là JSON hợp lệ.", ESP_ERR_INVALID_ARG);
    }
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict",
                               "ESP32 đang thực hiện một thao tác khác.",
                               ESP_ERR_INVALID_STATE);
    }

    const esp_err_t error = ms51_programmer_try_reset();
    if (error == ESP_ERR_TIMEOUT) {
        return send_error_json(request, "409 Conflict",
                               "ESP32 đang thực hiện một thao tác khác.", error);
    }
    if (error != ESP_OK) {
        return send_error_json(request, "422 Unprocessable Entity",
                               "Không reset được MS51.", error);
    }
    state_set_message("Đã reset MS51.", ESP_OK);
    return send_json(request,
                     "{\"ok\":true,\"message\":\"Đã reset MS51.\"}", NULL);
}

static esp_err_t queue_job(job_type_t type, uint32_t requested_generation,
                           uint32_t *job_id_out)
{
    ms51_storage_info_t image;
    if (type != JOB_MASS_ERASE) {
        if (ms51_storage_get_info(&image) != ESP_OK || !image.valid ||
            requested_generation == 0 || requested_generation != image.generation) {
            return ESP_ERR_INVALID_STATE;
        }
    }

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (s_state.busy) {
        xSemaphoreGive(s_state_mutex);
        return ESP_ERR_TIMEOUT;
    }

    job_request_t job = {
        .type = type,
        .generation = requested_generation,
        .id = s_state.job_id + 1,
    };
    s_state.busy = true;
    s_state.job_id = job.id;
    s_state.last_error = ESP_OK;
    strlcpy(s_state.operation, job_name(type), sizeof(s_state.operation));
    strlcpy(s_state.message, job_running_message(type), sizeof(s_state.message));
    xSemaphoreGive(s_state_mutex);

    if (xQueueSend(s_job_queue, &job, 0) != pdTRUE) {
        xSemaphoreTake(s_state_mutex, portMAX_DELAY);
        s_state.busy = false;
        s_state.last_error = ESP_ERR_NO_MEM;
        strlcpy(s_state.message, "Không tạo được tác vụ nạp.",
                sizeof(s_state.message));
        xSemaphoreGive(s_state_mutex);
        return ESP_ERR_NO_MEM;
    }
    *job_id_out = job.id;
    return ESP_OK;
}

static esp_err_t job_handler(httpd_req_t *request)
{
    const job_type_t type = (job_type_t)(intptr_t)request->user_ctx;
    char body[HTTP_JSON_BODY_SIZE];
    if (receive_json(request, body, sizeof(body)) != ESP_OK) {
        return send_error_json(request, "400 Bad Request",
                               "Yêu cầu phải là JSON hợp lệ.", ESP_ERR_INVALID_ARG);
    }

    if ((type == JOB_PROGRAM_FULL || type == JOB_MASS_ERASE) &&
        !json_confirmed(body)) {
        return send_error_json(request, "400 Bad Request",
                               "Thiếu xác nhận CONFIRM.", ESP_ERR_INVALID_ARG);
    }
    const uint32_t generation = json_generation(body);

    uint32_t job_id = 0;
    const esp_err_t error = queue_job(type, generation, &job_id);
    if (error == ESP_ERR_TIMEOUT) {
        return send_error_json(request, "409 Conflict",
                               "ESP32 đang thực hiện một thao tác khác.", error);
    }
    if (error != ESP_OK) {
        return send_error_json(request, "409 Conflict",
                               "Firmware đã thay đổi hoặc chưa được tải lên.", error);
    }

    char escaped_message[256];
    char payload[384];
    json_escape(job_running_message(type), escaped_message, sizeof(escaped_message));
    snprintf(payload, sizeof(payload),
             "{\"ok\":true,\"accepted\":true,\"job_id\":%" PRIu32
             ",\"message\":\"%s\"}",
             job_id, escaped_message);
    return send_json(request, payload, "202 Accepted");
}

static int hex_value(char value)
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

static bool runtime_config_hex_decode(const char *text,
                                      uint8_t config[MS51_RUNTIME_CONFIG_SIZE])
{
    if (text == NULL || *text++ != '"') {
        return false;
    }
    const char *closing_quote = strchr(text, '"');
    const size_t expected_length = MS51_RUNTIME_CONFIG_SIZE * 2u;
    if (closing_quote == NULL || (size_t)(closing_quote - text) != expected_length) {
        return false;
    }
    for (size_t index = 0; index < MS51_RUNTIME_CONFIG_SIZE; ++index) {
        const int high = hex_value(text[index * 2u]);
        const int low = hex_value(text[index * 2u + 1u]);
        if (high < 0 || low < 0) {
            return false;
        }
        config[index] = (uint8_t)((high << 4) | low);
    }
    return true;
}

static bool runtime_config_hex_encode(const uint8_t config[MS51_RUNTIME_CONFIG_SIZE],
                                      char *output, size_t output_size)
{
    static const char digits[] = "0123456789ABCDEF";
    const size_t length = MS51_RUNTIME_CONFIG_SIZE * 2u;
    if (output == NULL || output_size <= length) {
        return false;
    }
    for (size_t index = 0; index < MS51_RUNTIME_CONFIG_SIZE; ++index) {
        output[index * 2u] = digits[config[index] >> 4];
        output[index * 2u + 1u] = digits[config[index] & 0x0Fu];
    }
    output[length] = '\0';
    return true;
}

static const char *runtime_config_status_message(uint8_t status)
{
    switch (status) {
    case MS51_RUNTIME_CONFIG_STATUS_OK:
        return "MS51 đã xác nhận cấu hình.";
    case MS51_RUNTIME_CONFIG_STATUS_BAD_FRAME:
        return "MS51 từ chối gói UART do CRC hoặc khung không hợp lệ.";
    case MS51_RUNTIME_CONFIG_STATUS_BAD_CONFIG:
        return "MS51 từ chối cấu hình vì có giá trị ngoài giới hạn.";
    default:
        return "MS51 trả về trạng thái cấu hình không xác định.";
    }
}

static esp_err_t runtime_config_get_handler(httpd_req_t *request)
{
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict", "ESP32 đang bận với thao tác khác.",
                               ESP_ERR_INVALID_STATE);
    }

    esp_err_t error = ms51_operation_lock(0);
    if (error != ESP_OK || !state_begin_runtime_operation("runtime-read",
                                                            "Đang đọc cấu hình MS51 qua UART...")) {
        if (error == ESP_OK) {
            ms51_operation_unlock();
        }
        return send_error_json(request, "409 Conflict", "ESP32 đang bận với thao tác khác.",
                               error == ESP_OK ? ESP_ERR_TIMEOUT : error);
    }

    uint8_t config[MS51_RUNTIME_CONFIG_SIZE];
    uint8_t target_status = 0xFFu;
    error = ms51_debug_uart_get_runtime_config(config, &target_status);
    const char *message = error == ESP_OK ? runtime_config_status_message(target_status)
                                          : "Không nhận được phản hồi cấu hình từ MS51.";
    const esp_err_t result_error = error != ESP_OK ? error :
                                   (target_status == MS51_RUNTIME_CONFIG_STATUS_OK ? ESP_OK :
                                                                                       ESP_ERR_INVALID_RESPONSE);
    state_finish_runtime_operation(message, result_error);
    ms51_operation_unlock();

    if (error != ESP_OK) {
        return send_error_json(request, "422 Unprocessable Entity", message, error);
    }
    if (target_status != MS51_RUNTIME_CONFIG_STATUS_OK) {
        return send_error_json(request, "422 Unprocessable Entity", message,
                               ESP_ERR_INVALID_RESPONSE);
    }

    char encoded[MS51_RUNTIME_CONFIG_SIZE * 2u + 1u];
    char payload[640];
    if (!runtime_config_hex_encode(config, encoded, sizeof(encoded))) {
        return send_error_json(request, "500 Internal Server Error",
                               "Không thể mã hóa cấu hình MS51.", ESP_FAIL);
    }
    snprintf(payload, sizeof(payload),
             "{\"ok\":true,\"message\":\"Đã đọc cấu hình MS51 qua UART.\",\"data\":\"%s\"}",
             encoded);
    return send_json(request, payload, NULL);
}

static esp_err_t runtime_config_set_handler(httpd_req_t *request)
{
    char body[HTTP_JSON_BODY_SIZE];
    if (receive_json(request, body, sizeof(body)) != ESP_OK) {
        return send_error_json(request, "400 Bad Request", "Yêu cầu phải là JSON hợp lệ.",
                               ESP_ERR_INVALID_ARG);
    }

    uint8_t config[MS51_RUNTIME_CONFIG_SIZE];
    if (!runtime_config_hex_decode(json_value(body, "data"), config)) {
        return send_error_json(request, "400 Bad Request",
                               "Dữ liệu cấu hình phải có đúng 217 byte dạng HEX.",
                               ESP_ERR_INVALID_ARG);
    }
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict", "ESP32 đang bận với thao tác khác.",
                               ESP_ERR_INVALID_STATE);
    }

    esp_err_t error = ms51_operation_lock(0);
    if (error != ESP_OK || !state_begin_runtime_operation("runtime-write",
                                                            "Đang lưu cấu hình MS51 qua UART...")) {
        if (error == ESP_OK) {
            ms51_operation_unlock();
        }
        return send_error_json(request, "409 Conflict", "ESP32 đang bận với thao tác khác.",
                               error == ESP_OK ? ESP_ERR_TIMEOUT : error);
    }

    uint8_t target_status = 0xFFu;
    error = ms51_debug_uart_set_runtime_config(config, &target_status);
    const char *message = error == ESP_OK ? runtime_config_status_message(target_status)
                                          : "Không nhận được ACK lưu cấu hình từ MS51.";
    const esp_err_t result_error = error != ESP_OK ? error :
                                   (target_status == MS51_RUNTIME_CONFIG_STATUS_OK ? ESP_OK :
                                                                                       ESP_ERR_INVALID_RESPONSE);
    state_finish_runtime_operation(message, result_error);
    ms51_operation_unlock();

    if (error != ESP_OK) {
        return send_error_json(request, "422 Unprocessable Entity", message, error);
    }
    if (target_status != MS51_RUNTIME_CONFIG_STATUS_OK) {
        return send_error_json(request, "422 Unprocessable Entity", message,
                               ESP_ERR_INVALID_RESPONSE);
    }
    return send_json(request,
                     "{\"ok\":true,\"message\":\"Đã lưu EEPROM và MS51 đang khởi động lại để áp dụng cấu hình.\"}",
                     NULL);
}

static void decode_filename(const char *encoded, char *decoded, size_t decoded_size)
{
    size_t output = 0;
    for (size_t input = 0; encoded[input] != '\0' && output + 1 < decoded_size;) {
        if (encoded[input] == '%' && encoded[input + 1] != '\0' &&
            encoded[input + 2] != '\0') {
            const int high = hex_value(encoded[input + 1]);
            const int low = hex_value(encoded[input + 2]);
            if (high >= 0 && low >= 0) {
                decoded[output++] = (char)((high << 4) | low);
                input += 3;
                continue;
            }
        }
        const char value = encoded[input++];
        decoded[output++] = value == '+' ? ' ' : value;
    }
    decoded[output] = '\0';
}

#if 0
static esp_err_t upload_handler_legacy(httpd_req_t *request)
{
    if (!content_type_is_binary(request)) {
        return send_error_json(request, "415 Unsupported Media Type",
                               "Upload phải dùng application/octet-stream.",
                               ESP_ERR_INVALID_ARG);
    }
    if (request->content_len == 0 ||
        request->content_len > MS51_STORAGE_MAX_IMAGE_SIZE) {
        return send_error_json(request, "413 Content Too Large",
                               "File BIN phải từ 1 đến 32768 byte.",
                               ESP_ERR_INVALID_SIZE);
    }
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict",
                               "ESP32 đang thực hiện một thao tác khác.",
                               ESP_ERR_INVALID_STATE);
    }

    char encoded_name[192] = "ms51_app.bin";
    const size_t header_length = httpd_req_get_hdr_value_len(request, "X-Filename");
    if (header_length > 0 && header_length < sizeof(encoded_name)) {
        httpd_req_get_hdr_value_str(request, "X-Filename", encoded_name,
                                    sizeof(encoded_name));
    }
    char filename[MS51_STORAGE_FILENAME_SIZE];
    decode_filename(encoded_name, filename, sizeof(filename));

    esp_err_t error = ms51_operation_lock(0);
    if (error != ESP_OK) {
        return send_error_json(request, "409 Conflict",
                               "Bộ nạp đang bận.", error);
    }

    error = ms51_storage_begin_upload(request->content_len);
    if (error != ESP_OK) {
        ms51_operation_unlock();
        return send_error_json(request, "409 Conflict",
                               "Không thể bắt đầu lưu firmware.", error);
    }

    uint8_t buffer[HTTP_UPLOAD_BUFFER_SIZE];
    size_t received_total = 0;
    unsigned timeout_count = 0;
    while (received_total < request->content_len) {
        size_t wanted = request->content_len - received_total;
        if (wanted > sizeof(buffer)) {
            wanted = sizeof(buffer);
        }
        const int received = httpd_req_recv(request, (char *)buffer, wanted);
        if (received == HTTPD_SOCK_ERR_TIMEOUT) {
            if (++timeout_count <= 3) {
                continue;
            }
            error = ESP_ERR_TIMEOUT;
            break;
        }
        if (received <= 0) {
            ESP_LOGW(TAG, "firmware upload connection ended after %u/%u byte(s)",
                     (unsigned)received_total, (unsigned)request->content_len);
            error = ESP_ERR_INVALID_RESPONSE;
            break;
        }
        timeout_count = 0;
        error = ms51_storage_write_upload(received_total, buffer, (size_t)received);
        if (error != ESP_OK) {
            break;
        }
        received_total += (size_t)received;
    }

    ms51_storage_info_t info;
    if (error == ESP_OK) {
        error = ms51_storage_finish_upload(filename, &info);
    }
    if (error != ESP_OK) {
        ms51_storage_abort_upload();
        ms51_operation_unlock();
        state_set_message("Tải/lưu firmware thất bại; hãy tải lại trạng thái trước khi nạp.", error);
        return send_error_json(request, "500 Internal Server Error",
                               "Tải/lưu firmware thất bại; hãy tải lại trạng thái trước khi nạp.",
                               error);
    }
    ms51_operation_unlock();

    char message[160];
    snprintf(message, sizeof(message), "Đã lưu %s (%u byte, CRC32 %08" PRIX32 ").",
             info.filename, (unsigned)info.size, info.crc32);
    state_set_message(message, ESP_OK);

    char escaped_message[384];
    char escaped_filename[160];
    char payload[768];
    json_escape(message, escaped_message, sizeof(escaped_message));
    json_escape(info.filename, escaped_filename, sizeof(escaped_filename));
    snprintf(payload, sizeof(payload),
             "{\"ok\":true,\"message\":\"%s\",\"image\":{\"valid\":true"
             ",\"name\":\"%s\",\"size\":%u,\"crc32\":%" PRIu32
             ",\"generation\":%" PRIu32 "}}",
             escaped_message, escaped_filename, (unsigned)info.size, info.crc32,
             info.generation);
    return send_json(request, payload, "201 Created");
}

#endif

static esp_err_t upload_handler(httpd_req_t *request)
{
    if (!content_type_is_firmware(request)) {
        return send_error_json(request, "415 Unsupported Media Type",
                               "Upload must contain BIN or Intel HEX firmware.",
                               ESP_ERR_INVALID_ARG);
    }
    if (request->content_len == 0 || request->content_len > MS51_IMAGE_MAX_UPLOAD_SIZE) {
        return send_error_json(request, "413 Content Too Large",
                               "BIN is limited to 32 KB; Intel HEX is limited to 96 KB.",
                               ESP_ERR_INVALID_SIZE);
    }
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict", "ESP32 is busy with another job.",
                               ESP_ERR_INVALID_STATE);
    }

    char encoded_name[192] = "ms51_app.bin";
    const size_t header_length = httpd_req_get_hdr_value_len(request, "X-Filename");
    if (header_length > 0 && header_length < sizeof(encoded_name)) {
        httpd_req_get_hdr_value_str(request, "X-Filename", encoded_name, sizeof(encoded_name));
    }
    char filename[MS51_STORAGE_FILENAME_SIZE];
    decode_filename(encoded_name, filename, sizeof(filename));
    const bool is_hex = ms51_image_filename_is_intel_hex(filename);
    if (!is_hex && request->content_len > MS51_IMAGE_MAX_SIZE) {
        return send_error_json(request, "413 Content Too Large", "BIN is limited to 32768 bytes.",
                               ESP_ERR_INVALID_SIZE);
    }

    esp_err_t error = ms51_operation_lock(0);
    if (error != ESP_OK) {
        return send_error_json(request, "409 Conflict", "Programmer is busy.", error);
    }

    uint8_t *source = heap_caps_malloc(request->content_len, MALLOC_CAP_8BIT);
    if (source == NULL) {
        ms51_operation_unlock();
        return send_error_json(request, "503 Service Unavailable",
                               "ESP32 does not have enough memory for this upload.",
                               ESP_ERR_NO_MEM);
    }

    size_t received_total = 0;
    unsigned timeout_count = 0;
    while (received_total < request->content_len) {
        size_t wanted = request->content_len - received_total;
        if (wanted > HTTP_UPLOAD_BUFFER_SIZE) {
            wanted = HTTP_UPLOAD_BUFFER_SIZE;
        }
        const int received = httpd_req_recv(request, (char *)source + received_total, wanted);
        if (received == HTTPD_SOCK_ERR_TIMEOUT) {
            if (++timeout_count <= 3) {
                continue;
            }
            error = ESP_ERR_TIMEOUT;
            break;
        }
        if (received <= 0) {
            ESP_LOGW(TAG, "firmware upload connection ended after %u/%u byte(s)",
                     (unsigned)received_total, (unsigned)request->content_len);
            error = ESP_ERR_INVALID_RESPONSE;
            break;
        }
        timeout_count = 0;
        received_total += (size_t)received;
    }

    ms51_image_t image;
    memset(&image, 0, sizeof(image));
    if (error == ESP_OK) {
        error = ms51_image_parse_upload(source, received_total, filename, &image);
    }
    heap_caps_free(source);

    ms51_storage_info_t info;
    if (error == ESP_OK) {
        error = ms51_storage_commit_image(filename, &image, &info);
    }
    ms51_image_free(&image);
    ms51_operation_unlock();

    if (error != ESP_OK) {
        const char *message = NULL;
        if (error == ESP_ERR_INVALID_RESPONSE || error == ESP_ERR_TIMEOUT) {
            message = "Firmware upload was interrupted. Reconnect to the ESP32 Wi-Fi and upload again.";
        } else {
            message = is_hex ? "Invalid Intel HEX: check checksum, EOF, and APROM addresses."
                             : "Could not store BIN firmware; upload it again.";
        }
        state_set_message(message, error);
        return send_error_json(request, error == ESP_ERR_NO_MEM ? "503 Service Unavailable"
                                                                : "422 Unprocessable Entity",
                               message, error);
    }

    char message[160];
    snprintf(message, sizeof(message), "Stored %s: %u covered byte(s), %s.", info.filename,
             (unsigned)info.covered_size, ms51_image_format_name(info.format));
    state_set_message(message, ESP_OK);

    char escaped_message[384];
    char escaped_filename[160];
    char payload[768];
    json_escape(message, escaped_message, sizeof(escaped_message));
    json_escape(info.filename, escaped_filename, sizeof(escaped_filename));
    snprintf(payload, sizeof(payload),
             "{\"ok\":true,\"message\":\"%s\",\"image\":{\"valid\":true"
             ",\"name\":\"%s\",\"size\":%u,\"covered_size\":%u,\"format\":\"%s\""
             ",\"crc32\":%" PRIu32 ",\"generation\":%" PRIu32 "}}",
             escaped_message, escaped_filename, (unsigned)info.size,
             (unsigned)info.covered_size, ms51_image_format_name(info.format), info.crc32,
             info.generation);
    return send_json(request, payload, "201 Created");
}

static bool uplink_credentials_are_valid(const char *ssid, const char *password)
{
    const size_t ssid_length = strlen(ssid);
    const size_t password_length = strlen(password);
    return ssid_length > 0 && ssid_length <= UPLINK_SSID_MAX_LENGTH &&
           password_length <= UPLINK_PASSWORD_MAX_LENGTH &&
           (password_length == 0 || password_length >= 8);
}

static void uplink_make_station_config(const char *ssid, const char *password,
                                       wifi_config_t *config)
{
    memset(config, 0, sizeof(*config));
    memcpy(config->sta.ssid, ssid, strlen(ssid));
    strlcpy((char *)config->sta.password, password, sizeof(config->sta.password));
    config->sta.scan_method = WIFI_ALL_CHANNEL_SCAN;
    config->sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;
    config->sta.failure_retry_cnt = 0;
    config->sta.threshold.authmode = WIFI_AUTH_OPEN;
    config->sta.pmf_cfg.capable = true;
    config->sta.pmf_cfg.required = false;
}

static esp_err_t uplink_save_credentials(const char *ssid, const char *password)
{
    nvs_handle_t handle;
    esp_err_t error = nvs_open(UPLINK_NVS_NAMESPACE, NVS_READWRITE, &handle);
    if (error != ESP_OK) {
        return error;
    }
    error = nvs_set_str(handle, UPLINK_NVS_SSID_KEY, ssid);
    if (error == ESP_OK) {
        error = nvs_set_str(handle, UPLINK_NVS_PASSWORD_KEY, password);
    }
    if (error == ESP_OK) {
        error = nvs_commit(handle);
    }
    nvs_close(handle);
    return error;
}

static esp_err_t uplink_clear_credentials(void)
{
    nvs_handle_t handle;
    esp_err_t error = nvs_open(UPLINK_NVS_NAMESPACE, NVS_READWRITE, &handle);
    if (error == ESP_ERR_NVS_NOT_FOUND) {
        return ESP_OK;
    }
    if (error != ESP_OK) {
        return error;
    }

    error = nvs_erase_key(handle, UPLINK_NVS_SSID_KEY);
    if (error == ESP_ERR_NVS_NOT_FOUND) {
        error = ESP_OK;
    }
    if (error == ESP_OK) {
        error = nvs_erase_key(handle, UPLINK_NVS_PASSWORD_KEY);
        if (error == ESP_ERR_NVS_NOT_FOUND) {
            error = ESP_OK;
        }
    }
    if (error == ESP_OK) {
        error = nvs_commit(handle);
    }
    nvs_close(handle);
    return error;
}

/* A malformed remembered network must not prevent the local programming AP
 * from starting.  It is intentionally ignored and can be replaced in the UI. */
static void uplink_load_credentials(wifi_config_t *station_config)
{
    memset(station_config, 0, sizeof(*station_config));
    nvs_handle_t handle;
    esp_err_t error = nvs_open(UPLINK_NVS_NAMESPACE, NVS_READONLY, &handle);
    if (error == ESP_ERR_NVS_NOT_FOUND) {
        uplink_set_not_configured();
        return;
    }
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "Could not open saved Internet Wi-Fi: %s", esp_err_to_name(error));
        uplink_set_not_configured();
        return;
    }

    char ssid[UPLINK_SSID_MAX_LENGTH + 1u];
    char password[UPLINK_PASSWORD_MAX_LENGTH + 1u];
    size_t ssid_size = sizeof(ssid);
    size_t password_size = sizeof(password);
    error = nvs_get_str(handle, UPLINK_NVS_SSID_KEY, ssid, &ssid_size);
    if (error == ESP_OK) {
        error = nvs_get_str(handle, UPLINK_NVS_PASSWORD_KEY, password, &password_size);
    }
    nvs_close(handle);
    if (error != ESP_OK || !uplink_credentials_are_valid(ssid, password)) {
        ESP_LOGW(TAG, "Saved Internet Wi-Fi is incomplete or invalid");
        uplink_set_not_configured();
        return;
    }

    uplink_make_station_config(ssid, password, station_config);
    uplink_set_connecting(ssid);
}

static esp_err_t configure_ap_dhcp_dns(const esp_netif_dns_info_t *dns)
{
    if (s_ap_netif == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t error = esp_netif_dhcps_stop(s_ap_netif);
    if (error != ESP_OK && error != ESP_ERR_ESP_NETIF_DHCP_ALREADY_STOPPED) {
        return error;
    }

    const uint8_t offer_dns = dns != NULL ? DHCPS_OFFER_DNS : 0;
    error = esp_netif_dhcps_option(s_ap_netif, ESP_NETIF_OP_SET,
                                   ESP_NETIF_DOMAIN_NAME_SERVER, (void *)&offer_dns,
                                   sizeof(offer_dns));
    if (error == ESP_OK && dns != NULL) {
        error = esp_netif_set_dns_info(s_ap_netif, ESP_NETIF_DNS_MAIN,
                                       (esp_netif_dns_info_t *)dns);
    }
    const esp_err_t restart_error = esp_netif_dhcps_start(s_ap_netif);
    if (error == ESP_OK) {
        error = restart_error;
    }
    return error;
}

static void uplink_disable_sharing(void)
{
    if (s_ap_netif == NULL) {
        return;
    }
    const esp_err_t napt_error = esp_netif_napt_disable(s_ap_netif);
    if (napt_error != ESP_OK && napt_error != ESP_ERR_NOT_SUPPORTED) {
        ESP_LOGW(TAG, "Could not disable Internet sharing: %s", esp_err_to_name(napt_error));
    }
    const esp_err_t default_error = esp_netif_set_default_netif(s_ap_netif);
    if (default_error != ESP_OK) {
        ESP_LOGW(TAG, "Could not select local Wi-Fi as default: %s",
                 esp_err_to_name(default_error));
    }
    const esp_err_t dns_error = configure_ap_dhcp_dns(NULL);
    if (dns_error != ESP_OK) {
        ESP_LOGW(TAG, "Could not clear DHCP Internet DNS: %s", esp_err_to_name(dns_error));
    }
}

static esp_err_t uplink_connect(const char *ssid, const char *password)
{
    esp_err_t error = uplink_save_credentials(ssid, password);
    if (error != ESP_OK) {
        return error;
    }

    wifi_config_t station_config;
    uplink_make_station_config(ssid, password, &station_config);
    error = esp_wifi_set_config(WIFI_IF_STA, &station_config);
    if (error != ESP_OK) {
        return error;
    }

    uplink_set_connecting(ssid);
    uplink_disable_sharing();
    const esp_err_t disconnect_error = esp_wifi_disconnect();
    if (disconnect_error == ESP_OK) {
        /* WIFI_EVENT_STA_DISCONNECTED starts the new connection. */
        return ESP_OK;
    }
    if (disconnect_error != ESP_ERR_WIFI_NOT_CONNECT) {
        uplink_set_failed(0);
        return disconnect_error;
    }

    error = esp_wifi_connect();
    if (error != ESP_OK && error != ESP_ERR_WIFI_STATE) {
        uplink_set_failed(0);
        return error;
    }
    return ESP_OK;
}

static esp_err_t uplink_forget(void)
{
    const esp_err_t error = uplink_clear_credentials();
    if (error != ESP_OK) {
        return error;
    }
    uplink_set_not_configured();
    uplink_disable_sharing();
    const esp_err_t disconnect_error = esp_wifi_disconnect();
    if (disconnect_error != ESP_OK && disconnect_error != ESP_ERR_WIFI_NOT_CONNECT) {
        return disconnect_error;
    }
    return ESP_OK;
}

static bool uplink_scan_is_allowed(void)
{
    const uplink_state_t uplink = uplink_snapshot();
    return uplink.connection_state == UPLINK_NOT_CONFIGURED ||
           uplink.connection_state == UPLINK_FAILED;
}

static const char *wifi_auth_name(wifi_auth_mode_t authmode)
{
    switch (authmode) {
    case WIFI_AUTH_OPEN:
        return "Mo";
    case WIFI_AUTH_WEP:
        return "WEP";
    case WIFI_AUTH_WPA_PSK:
        return "WPA";
    case WIFI_AUTH_WPA2_PSK:
        return "WPA2";
    case WIFI_AUTH_WPA_WPA2_PSK:
        return "WPA/WPA2";
    case WIFI_AUTH_ENTERPRISE:
        return "Enterprise";
    case WIFI_AUTH_WPA3_PSK:
        return "WPA3";
    case WIFI_AUTH_WPA2_WPA3_PSK:
        return "WPA2/WPA3";
    case WIFI_AUTH_WAPI_PSK:
        return "WAPI";
    case WIFI_AUTH_OWE:
        return "OWE";
    case WIFI_AUTH_WPA3_ENT_192:
    case WIFI_AUTH_WPA3_ENTERPRISE:
    case WIFI_AUTH_WPA2_WPA3_ENTERPRISE:
    case WIFI_AUTH_WPA_ENTERPRISE:
        return "Enterprise";
    case WIFI_AUTH_DPP:
        return "DPP";
    default:
        return "Bao mat";
    }
}

static bool wifi_scan_has_ssid(const wifi_ap_record_t *records,
                               const uint16_t *selected, size_t selected_count,
                               const char *ssid)
{
    for (size_t index = 0; index < selected_count; ++index) {
        if (strcmp((const char *)records[selected[index]].ssid, ssid) == 0) {
            return true;
        }
    }
    return false;
}

/* esp_wifi_disconnect() completes asynchronously.  Directly after a user
 * forgets an uplink, the public state is already "not configured" while the
 * Wi-Fi driver can still be leaving STA_CONNECTING.  ESP-IDF rejects a scan in
 * that short window with ESP_ERR_WIFI_STATE.  Keep the HTTP request bounded,
 * but retry long enough for the radio to settle instead of making the mobile
 * app guess a delay.  The caller holds s_wifi_operation_mutex, so a connect or
 * a second scan cannot race this sequence. */
static esp_err_t wifi_scan_start_when_ready(const wifi_scan_config_t *config)
{
    esp_err_t error = ESP_ERR_WIFI_STATE;
    for (uint8_t attempt = 0; attempt < WIFI_SCAN_START_RETRIES; ++attempt) {
        error = esp_wifi_scan_start(config, true);
        if (error != ESP_ERR_WIFI_STATE) {
            return error;
        }
        if (attempt + 1u < WIFI_SCAN_START_RETRIES) {
            vTaskDelay(pdMS_TO_TICKS(WIFI_SCAN_RETRY_DELAY_MS));
        }
    }
    return error;
}

static esp_err_t wifi_scan_handler(httpd_req_t *request)
{
    if (!s_wifi_started) {
        return send_error_json(request, "503 Service Unavailable",
                               "Wi-Fi chua san sang de quet.", ESP_ERR_INVALID_STATE);
    }
    if (state_is_busy()) {
        return send_error_json(request, "409 Conflict",
                               "Khong the quet Wi-Fi trong khi dang nap MS51.",
                               ESP_ERR_INVALID_STATE);
    }
    if (!uplink_scan_is_allowed()) {
        return send_error_json(request, "409 Conflict",
                               "Hay quen Wi-Fi Internet hien tai truoc khi quet mang khac.",
                               ESP_ERR_INVALID_STATE);
    }
    if (s_wifi_operation_mutex == NULL ||
        xSemaphoreTake(s_wifi_operation_mutex, 0) != pdTRUE) {
        return send_error_json(request, "409 Conflict", "Dang quet Wi-Fi, vui long cho.",
                               ESP_ERR_TIMEOUT);
    }

    wifi_scan_config_t scan_config = {
        .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
        .scan_time = {.active = {.min = 30, .max = 70}},
        .home_chan_dwell_time = 30,
    };
    esp_err_t error = wifi_scan_start_when_ready(&scan_config);
    if (error != ESP_OK) {
        xSemaphoreGive(s_wifi_operation_mutex);
        return send_error_json(request, error == ESP_ERR_WIFI_STATE ? "409 Conflict"
                                                                     : "503 Service Unavailable",
                               error == ESP_ERR_WIFI_STATE
                                   ? "Wi-Fi Internet dang ket noi; vui long thu lai sau."
                                   : "Khong the quet Wi-Fi luc nay.",
                               error);
    }

    uint16_t total = 0;
    error = esp_wifi_scan_get_ap_num(&total);
    if (error != ESP_OK) {
        esp_wifi_clear_ap_list();
        xSemaphoreGive(s_wifi_operation_mutex);
        return send_error_json(request, "503 Service Unavailable",
                               "Khong doc duoc ket qua quet Wi-Fi.", error);
    }

    uint16_t record_count = total > WIFI_SCAN_MAX_NETWORKS ? WIFI_SCAN_MAX_NETWORKS : total;
    wifi_ap_record_t *records = NULL;
    if (record_count > 0) {
        records = heap_caps_calloc(record_count, sizeof(*records), MALLOC_CAP_8BIT);
        if (records == NULL) {
            esp_wifi_clear_ap_list();
            xSemaphoreGive(s_wifi_operation_mutex);
            return send_error_json(request, "503 Service Unavailable",
                                   "Khong du bo nho de hien thi danh sach Wi-Fi.", ESP_ERR_NO_MEM);
        }
        error = esp_wifi_scan_get_ap_records(&record_count, records);
        if (error != ESP_OK) {
            esp_wifi_clear_ap_list();
            heap_caps_free(records);
            xSemaphoreGive(s_wifi_operation_mutex);
            return send_error_json(request, "503 Service Unavailable",
                                   "Khong doc duoc ket qua quet Wi-Fi.", error);
        }
    } else {
        esp_wifi_clear_ap_list();
    }
    xSemaphoreGive(s_wifi_operation_mutex);

    httpd_resp_set_type(request, "application/json; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    error = httpd_resp_send_chunk(request, "{\"ok\":true,\"networks\":[",
                                  strlen("{\"ok\":true,\"networks\":["));
    bool first = true;
    uint16_t selected[WIFI_SCAN_MAX_NETWORKS];
    size_t selected_count = 0;
    for (uint16_t index = 0; error == ESP_OK && index < record_count; ++index) {
        const char *ssid = (const char *)records[index].ssid;
        if (*ssid == '\0' || wifi_scan_has_ssid(records, selected, selected_count, ssid)) {
            continue;
        }
        char escaped_ssid[UPLINK_SSID_MAX_LENGTH * 2u + 1u];
        if (!json_escape(ssid, escaped_ssid, sizeof(escaped_ssid))) {
            continue;
        }
        char item[192];
        const int length = snprintf(item, sizeof(item),
                                    "%s{\"ssid\":\"%s\",\"rssi\":%d,\"channel\":%u,\"auth\":\"%s\"}",
                                    first ? "" : ",", escaped_ssid, (int)records[index].rssi,
                                    (unsigned)records[index].primary,
                                    wifi_auth_name(records[index].authmode));
        if (length < 0 || (size_t)length >= sizeof(item)) {
            continue;
        }
        error = httpd_resp_send_chunk(request, item, (size_t)length);
        if (error == ESP_OK) {
            selected[selected_count++] = index;
            first = false;
        }
    }
    if (error == ESP_OK) {
        error = httpd_resp_send_chunk(request, "]}", strlen("]}"));
    }
    if (error == ESP_OK) {
        error = httpd_resp_send_chunk(request, NULL, 0);
    }
    heap_caps_free(records);
    return error;
}

static esp_err_t uplink_handler(httpd_req_t *request)
{
    char body[HTTP_JSON_BODY_SIZE];
    if (receive_json(request, body, sizeof(body)) != ESP_OK) {
        return send_error_json(request, "400 Bad Request", "Yeu cau phai la JSON hop le.",
                               ESP_ERR_INVALID_ARG);
    }

    char action[16];
    if (!json_string_copy(body, "action", action, sizeof(action))) {
        return send_error_json(request, "400 Bad Request", "Thieu hanh dong Wi-Fi.",
                               ESP_ERR_INVALID_ARG);
    }
    if (strcmp(action, "connect") == 0) {
        char ssid[UPLINK_SSID_MAX_LENGTH + 1u];
        char password[UPLINK_PASSWORD_MAX_LENGTH + 1u];
        if (!json_string_copy(body, "ssid", ssid, sizeof(ssid)) ||
            !json_string_copy(body, "password", password, sizeof(password)) ||
            !uplink_credentials_are_valid(ssid, password)) {
            return send_error_json(request, "400 Bad Request",
                                   "Ten Wi-Fi phai co 1-32 ky tu; mat khau de trong hoac 8-63 ky tu.",
                                   ESP_ERR_INVALID_ARG);
        }
        if (s_wifi_operation_mutex == NULL ||
            xSemaphoreTake(s_wifi_operation_mutex, 0) != pdTRUE) {
            return send_error_json(request, "409 Conflict", "Dang quet Wi-Fi, vui long cho.",
                                   ESP_ERR_TIMEOUT);
        }
        const esp_err_t error = uplink_connect(ssid, password);
        xSemaphoreGive(s_wifi_operation_mutex);
        if (error != ESP_OK) {
            return send_error_json(request, "503 Service Unavailable",
                                   "Khong the bat dau ket noi Wi-Fi Internet.", error);
        }
        return send_json(request,
                         "{\"ok\":true,\"accepted\":true,\"message\":\"Da luu Wi-Fi va dang ket noi Internet.\"}",
                         "202 Accepted");
    }
    if (strcmp(action, "forget") == 0) {
        if (s_wifi_operation_mutex == NULL ||
            xSemaphoreTake(s_wifi_operation_mutex, 0) != pdTRUE) {
            return send_error_json(request, "409 Conflict", "Dang quet Wi-Fi, vui long cho.",
                                   ESP_ERR_TIMEOUT);
        }
        const esp_err_t error = uplink_forget();
        xSemaphoreGive(s_wifi_operation_mutex);
        if (error != ESP_OK) {
            return send_error_json(request, "503 Service Unavailable",
                                   "Khong the xoa Wi-Fi Internet da luu.", error);
        }
        return send_json(request,
                         "{\"ok\":true,\"message\":\"Da xoa Wi-Fi Internet da luu.\"}", NULL);
    }
    return send_error_json(request, "400 Bad Request", "Hanh dong Wi-Fi khong hop le.",
                           ESP_ERR_INVALID_ARG);
}

static void job_worker(void *argument)
{
    (void)argument;
    job_request_t job;
    while (xQueueReceive(s_job_queue, &job, portMAX_DELAY) == pdTRUE) {
        esp_err_t error = ESP_OK;
        ms51_storage_image_t image;
        memset(&image, 0, sizeof(image));

        if (job.type != JOB_MASS_ERASE) {
            error = ms51_storage_acquire_image(job.generation, &image);
        }
        if (error == ESP_OK) {
            const ms51_image_t image_view = {
                .data = (uint8_t *)image.data,
                .coverage = (uint8_t *)image.coverage,
                .size = image.size,
                .covered_size = image.covered_size,
                .format = image.format,
            };
            switch (job.type) {
            case JOB_PROGRAM:
                error = ms51_programmer_program_image(&image_view,
                                                      CONFIG_MS51_VERIFY_AFTER_PROGRAM);
                break;
            case JOB_VERIFY:
                error = ms51_programmer_verify_image(&image_view);
                break;
            case JOB_PROGRAM_FULL:
                error = ms51_programmer_program_image_full(
                    &image_view, CONFIG_MS51_VERIFY_AFTER_PROGRAM);
                break;
            case JOB_MASS_ERASE:
                error = ms51_programmer_mass_erase();
                break;
            default:
                error = ESP_ERR_INVALID_ARG;
                break;
            }
        }
        ms51_storage_release_image(&image);

        char message[160];
        if (error == ESP_OK) {
            strlcpy(message, job_success_message(job.type), sizeof(message));
        } else {
            snprintf(message, sizeof(message), "%s thất bại: %s.",
                     job_name(job.type), esp_err_to_name(error));
        }

        xSemaphoreTake(s_state_mutex, portMAX_DELAY);
        s_state.busy = false;
        s_state.last_error = error;
        strlcpy(s_state.message, message, sizeof(s_state.message));
        xSemaphoreGive(s_state_mutex);
        ESP_LOGI(TAG, "job %" PRIu32 " (%s): %s", job.id, job_name(job.type),
                 message);
    }
    vTaskDelete(NULL);
}

static void wifi_event_handler(void *argument, esp_event_base_t event_base,
                               int32_t event_id, void *event_data)
{
    (void)argument;
    if (event_base != WIFI_EVENT) {
        return;
    }
    if (event_id == WIFI_EVENT_AP_STACONNECTED) {
        const wifi_event_ap_staconnected_t *event = event_data;
        ESP_LOGI(TAG, "Wi-Fi client " MACSTR " connected (AID=%d)",
                 MAC2STR(event->mac), event->aid);
    } else if (event_id == WIFI_EVENT_AP_STADISCONNECTED) {
        const wifi_event_ap_stadisconnected_t *event = event_data;
        ESP_LOGI(TAG, "Wi-Fi client " MACSTR " disconnected (AID=%d)",
                 MAC2STR(event->mac), event->aid);
    } else if (event_id == WIFI_EVENT_STA_START) {
        const uplink_state_t uplink = uplink_snapshot();
        if (uplink.configured) {
            const esp_err_t error = esp_wifi_connect();
            if (error != ESP_OK && error != ESP_ERR_WIFI_STATE) {
                ESP_LOGW(TAG, "Could not start saved Internet Wi-Fi: %s",
                         esp_err_to_name(error));
                uplink_set_failed(0);
            }
        }
    } else if (event_id == WIFI_EVENT_STA_DISCONNECTED) {
        const wifi_event_sta_disconnected_t *event = event_data;
        if (!s_wifi_started) {
            return;
        }
        uplink_disable_sharing();
        const bool retry = uplink_retry_after_disconnect(event->reason);
        ESP_LOGW(TAG, "Internet Wi-Fi disconnected (reason=%u, retry=%s)",
                 (unsigned)event->reason, retry ? "yes" : "no");
        if (retry) {
            const esp_err_t error = esp_wifi_connect();
            if (error != ESP_OK && error != ESP_ERR_WIFI_STATE) {
                ESP_LOGW(TAG, "Could not retry Internet Wi-Fi: %s", esp_err_to_name(error));
                uplink_set_failed(event->reason);
            }
        }
    }
}

static void ip_event_handler(void *argument, esp_event_base_t event_base,
                             int32_t event_id, void *event_data)
{
    (void)argument;
    if (event_base != IP_EVENT || event_id != IP_EVENT_STA_GOT_IP ||
        !s_wifi_started || s_sta_netif == NULL || s_ap_netif == NULL) {
        return;
    }
    if (!uplink_snapshot().configured) {
        return;
    }

    const ip_event_got_ip_t *event = event_data;
    esp_netif_dns_info_t dns;
    const esp_err_t get_dns_error = esp_netif_get_dns_info(s_sta_netif,
                                                            ESP_NETIF_DNS_MAIN, &dns);
    const esp_err_t dns_error = get_dns_error == ESP_OK ? configure_ap_dhcp_dns(&dns)
                                                         : get_dns_error;
    if (dns_error != ESP_OK) {
        ESP_LOGW(TAG, "Could not give upstream DNS to AP clients: %s",
                 esp_err_to_name(dns_error));
    }

    const esp_err_t default_error = esp_netif_set_default_netif(s_sta_netif);
    if (default_error != ESP_OK) {
        ESP_LOGW(TAG, "Could not select Internet Wi-Fi as default: %s",
                 esp_err_to_name(default_error));
    }
    const esp_err_t napt_error = esp_netif_napt_enable(s_ap_netif);
    if (napt_error != ESP_OK) {
        ESP_LOGW(TAG, "Could not enable Internet sharing: %s", esp_err_to_name(napt_error));
    }

    wifi_ap_record_t ap_record;
    memset(&ap_record, 0, sizeof(ap_record));
    const int8_t rssi = esp_wifi_sta_get_ap_info(&ap_record) == ESP_OK ? ap_record.rssi : 0;
    uplink_set_connected(&event->ip_info, rssi, dns_error == ESP_OK,
                         napt_error == ESP_OK);
    ESP_LOGI(TAG, "Internet Wi-Fi connected: " IPSTR ", sharing=%s",
             IP2STR(&event->ip_info.ip), napt_error == ESP_OK ? "on" : "off");
}

static esp_err_t start_wifi_ap(void)
{
    const size_t ssid_length = strlen(CONFIG_MS51_WIFI_SSID);
    const size_t password_length = strlen(CONFIG_MS51_WIFI_PASSWORD);
    if (ssid_length == 0 || ssid_length > 32 ||
        password_length < 8 || password_length > 63) {
        ESP_LOGE(TAG, "Wi-Fi SSID/password length is invalid");
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t error = nvs_flash_init();
    if (error == ESP_ERR_NVS_NO_FREE_PAGES || error == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_RETURN_ON_ERROR(nvs_flash_erase(), TAG, "could not recover NVS");
        error = nvs_flash_init();
    }
    ESP_RETURN_ON_ERROR(error, TAG, "NVS initialization failed");
    ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "network stack initialization failed");
    s_netif_initialized = true;
    ESP_RETURN_ON_ERROR(esp_event_loop_create_default(), TAG,
                        "event loop initialization failed");
    s_event_loop_created = true;

    s_ap_netif = esp_netif_create_default_wifi_ap();
    if (s_ap_netif == NULL) {
        return ESP_ERR_NO_MEM;
    }
    s_sta_netif = esp_netif_create_default_wifi_sta();
    if (s_sta_netif == NULL) {
        return ESP_ERR_NO_MEM;
    }
    error = esp_netif_set_hostname(s_sta_netif, MDNS_HOSTNAME);
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "Could not set station hostname: %s", esp_err_to_name(error));
    }

    const wifi_init_config_t init_config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&init_config), TAG, "Wi-Fi init failed");
    s_wifi_initialized = true;
    ESP_RETURN_ON_ERROR(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                    wifi_event_handler, NULL),
                        TAG, "Wi-Fi event handler registration failed");
    s_wifi_handler_registered = true;
    ESP_RETURN_ON_ERROR(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                    ip_event_handler, NULL),
                        TAG, "IP event handler registration failed");
    s_ip_handler_registered = true;
    ESP_RETURN_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), TAG,
                        "Wi-Fi storage setup failed");

    wifi_config_t wifi_config;
    memset(&wifi_config, 0, sizeof(wifi_config));
    memcpy(wifi_config.ap.ssid, CONFIG_MS51_WIFI_SSID, ssid_length);
    memcpy(wifi_config.ap.password, CONFIG_MS51_WIFI_PASSWORD, password_length);
    wifi_config.ap.ssid_len = ssid_length;
    wifi_config.ap.channel = CONFIG_MS51_WIFI_CHANNEL;
    wifi_config.ap.max_connection = 4;
    wifi_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    wifi_config.ap.pmf_cfg.capable = true;
    wifi_config.ap.pmf_cfg.required = false;

    wifi_config_t station_config;
    uplink_load_credentials(&station_config);
    const bool has_uplink = uplink_snapshot().configured;

    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_APSTA), TAG,
                        "could not select AP+STA mode");
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_AP, &wifi_config), TAG,
                        "could not configure AP");
    if (has_uplink) {
        ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_STA, &station_config), TAG,
                            "could not configure saved Internet Wi-Fi");
    }
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "could not start AP");
    s_wifi_started = true;
    ESP_RETURN_ON_ERROR(esp_wifi_set_max_tx_power(CONFIG_MS51_WIFI_TX_POWER_QDBM), TAG,
                        "could not limit AP transmit power");

    error = start_mdns();
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "mDNS is unavailable; use the displayed LAN IP: %s",
                 esp_err_to_name(error));
    }

    esp_netif_ip_info_t ip_info;
    ESP_RETURN_ON_ERROR(esp_netif_get_ip_info(s_ap_netif, &ip_info), TAG,
                        "could not read AP address");
    inet_ntoa_r(ip_info.ip.addr, s_ap_ip, sizeof(s_ap_ip));

    /* Do not advertise a captive portal while this AP is used as an Internet
     * router.  DNS is enabled only after the upstream Wi-Fi has an address. */
    error = configure_ap_dhcp_dns(NULL);
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "Could not prepare DHCP DNS: %s", esp_err_to_name(error));
    }
    ESP_LOGI(TAG, "Wi-Fi AP+STA ready: fallback=http://%s, LAN=http://%s, TX=%d qdBm",
             s_ap_ip, MDNS_HOSTNAME_FQDN, CONFIG_MS51_WIFI_TX_POWER_QDBM);
    return ESP_OK;
}

static void stop_wifi_ap(void)
{
    stop_mdns();
    if (s_wifi_started) {
        s_wifi_started = false;
        uplink_disable_sharing();
        esp_wifi_stop();
    }
    if (s_ip_handler_registered) {
        esp_event_handler_unregister(IP_EVENT, IP_EVENT_STA_GOT_IP, ip_event_handler);
        s_ip_handler_registered = false;
    }
    if (s_wifi_handler_registered) {
        esp_event_handler_unregister(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                     wifi_event_handler);
        s_wifi_handler_registered = false;
    }
    if (s_sta_netif != NULL) {
        esp_netif_destroy_default_wifi(s_sta_netif);
        s_sta_netif = NULL;
    }
    if (s_ap_netif != NULL) {
        esp_netif_destroy_default_wifi(s_ap_netif);
        s_ap_netif = NULL;
    }
    if (s_wifi_initialized) {
        esp_wifi_deinit();
        s_wifi_initialized = false;
    }
    if (s_event_loop_created) {
        esp_event_loop_delete_default();
        s_event_loop_created = false;
    }
    if (s_netif_initialized) {
        esp_netif_deinit();
        s_netif_initialized = false;
    }
}

static void release_web_resources(void)
{
    if (s_job_task != NULL) {
        vTaskDelete(s_job_task);
        s_job_task = NULL;
    }
    if (s_job_queue != NULL) {
        vQueueDelete(s_job_queue);
        s_job_queue = NULL;
    }
    stop_wifi_ap();
    if (s_state_mutex != NULL) {
        vSemaphoreDelete(s_state_mutex);
        s_state_mutex = NULL;
    }
    if (s_uplink_mutex != NULL) {
        vSemaphoreDelete(s_uplink_mutex);
        s_uplink_mutex = NULL;
    }
    if (s_wifi_operation_mutex != NULL) {
        vSemaphoreDelete(s_wifi_operation_mutex);
        s_wifi_operation_mutex = NULL;
    }
}

static esp_err_t start_http_server(void)
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.stack_size = 8192;
    config.max_uri_handlers = 14;
    config.max_open_sockets = 4;
    config.lru_purge_enable = true;
    config.recv_wait_timeout = 20;
    config.send_wait_timeout = 10;
    config.uri_match_fn = httpd_uri_match_wildcard;

    ESP_RETURN_ON_ERROR(httpd_start(&s_server, &config), TAG,
                        "HTTP server start failed");

    const httpd_uri_t handlers[] = {
        {.uri = "/api/status", .method = HTTP_GET, .handler = status_handler},
        {.uri = "/api/debug", .method = HTTP_GET, .handler = debug_handler},
        {.uri = "/api/runtime-config", .method = HTTP_GET,
         .handler = runtime_config_get_handler},
        {.uri = "/api/runtime-config", .method = HTTP_POST,
         .handler = runtime_config_set_handler},
        {.uri = "/api/uplink/scan", .method = HTTP_GET, .handler = wifi_scan_handler},
        {.uri = "/api/uplink", .method = HTTP_POST, .handler = uplink_handler},
        {.uri = "/api/upload", .method = HTTP_POST, .handler = upload_handler},
        {.uri = "/api/info", .method = HTTP_POST, .handler = info_handler},
        {.uri = "/api/reset", .method = HTTP_POST, .handler = reset_handler},
        {.uri = "/api/program", .method = HTTP_POST, .handler = job_handler,
         .user_ctx = (void *)(intptr_t)JOB_PROGRAM},
        {.uri = "/api/verify", .method = HTTP_POST, .handler = job_handler,
         .user_ctx = (void *)(intptr_t)JOB_VERIFY},
        {.uri = "/api/program-full", .method = HTTP_POST, .handler = job_handler,
         .user_ctx = (void *)(intptr_t)JOB_PROGRAM_FULL},
        {.uri = "/api/erase", .method = HTTP_POST, .handler = job_handler,
         .user_ctx = (void *)(intptr_t)JOB_MASS_ERASE},
        {.uri = "/*", .method = HTTP_GET, .handler = root_handler},
    };

    for (size_t index = 0; index < sizeof(handlers) / sizeof(handlers[0]); ++index) {
        const esp_err_t error = httpd_register_uri_handler(s_server, &handlers[index]);
        if (error != ESP_OK) {
            httpd_stop(s_server);
            s_server = NULL;
            return error;
        }
    }
    return ESP_OK;
}

esp_err_t ms51_web_start(void)
{
    if (s_server != NULL) {
        return ESP_OK;
    }

    s_state_mutex = xSemaphoreCreateMutex();
    s_uplink_mutex = xSemaphoreCreateMutex();
    s_wifi_operation_mutex = xSemaphoreCreateMutex();
    s_job_queue = xQueueCreate(1, sizeof(job_request_t));
    if (s_state_mutex == NULL || s_uplink_mutex == NULL ||
        s_wifi_operation_mutex == NULL || s_job_queue == NULL) {
        release_web_resources();
        return ESP_ERR_NO_MEM;
    }
    memset(&s_state, 0, sizeof(s_state));
    uplink_set_not_configured();
    s_state.last_error = ESP_OK;
    strlcpy(s_state.message, "Sẵn sàng. Hãy chọn một file BIN.",
            sizeof(s_state.message));

    esp_err_t error = start_wifi_ap();
    if (error != ESP_OK) {
        release_web_resources();
        return error;
    }
    if (xTaskCreate(job_worker, "ms51-web-worker", JOB_TASK_STACK_SIZE, NULL,
                    JOB_TASK_PRIORITY, &s_job_task) != pdPASS) {
        release_web_resources();
        return ESP_ERR_NO_MEM;
    }
    error = start_http_server();
    if (error != ESP_OK) {
        release_web_resources();
        return error;
    }
    return ESP_OK;
}
