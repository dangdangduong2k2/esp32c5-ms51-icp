#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_console.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "sdkconfig.h"

#include "ms51_firmware.h"
#include "ms51_debug_uart.h"
#include "ms51_operation.h"
#include "ms51_programmer.h"
#include "ms51_storage.h"
#include "ms51_web.h"

static const char *TAG = "ms51_app";

typedef struct {
    ms51_image_t image;
    ms51_storage_image_t stored;
} selected_image_t;

static esp_err_t acquire_selected_image(selected_image_t *selected)
{
    memset(selected, 0, sizeof(*selected));
    esp_err_t error = ms51_storage_acquire_image(0, &selected->stored);
    if (error == ESP_OK) {
        selected->image.data = (uint8_t *)selected->stored.data;
        selected->image.coverage = (uint8_t *)selected->stored.coverage;
        selected->image.size = selected->stored.size;
        selected->image.covered_size = selected->stored.covered_size;
        selected->image.format = selected->stored.format;
        return ESP_OK;
    }
    if (error == ESP_ERR_NOT_FOUND && g_ms51_firmware_size > 0) {
        selected->image.data = (uint8_t *)g_ms51_firmware;
        selected->image.size = g_ms51_firmware_size;
        selected->image.covered_size = g_ms51_firmware_size;
        selected->image.format = MS51_IMAGE_FORMAT_BINARY;
        return ESP_OK;
    }
    return error;
}

/* The normal commands prioritize a web-uploaded image.  This explicit path
 * is useful for a field-programming build that embeds a known-good project
 * image while preserving any uploaded image stored in ESP flash. */
static esp_err_t acquire_embedded_image(selected_image_t *selected)
{
    memset(selected, 0, sizeof(*selected));
    if (g_ms51_firmware_size == 0) {
        return ESP_ERR_NOT_FOUND;
    }
    selected->image.data = (uint8_t *)g_ms51_firmware;
    selected->image.size = g_ms51_firmware_size;
    selected->image.covered_size = g_ms51_firmware_size;
    selected->image.format = MS51_IMAGE_FORMAT_BINARY;
    return ESP_OK;
}

static void release_selected_image(selected_image_t *selected)
{
    ms51_storage_release_image(&selected->stored);
}

static esp_err_t commit_embedded_image(ms51_storage_info_t *info)
{
    if (g_ms51_firmware_size == 0 || g_ms51_firmware_size > MS51_IMAGE_MAX_SIZE) {
        return ESP_ERR_NOT_FOUND;
    }

    uint8_t *coverage = heap_caps_calloc(1, MS51_IMAGE_COVERAGE_SIZE, MALLOC_CAP_8BIT);
    if (coverage == NULL) {
        return ESP_ERR_NO_MEM;
    }
    const size_t complete_bytes = g_ms51_firmware_size / 8u;
    memset(coverage, 0xFF, complete_bytes);
    const unsigned remaining_bits = (unsigned)(g_ms51_firmware_size & 7u);
    if (remaining_bits != 0) {
        coverage[complete_bytes] = (uint8_t)((1u << remaining_bits) - 1u);
    }

    const ms51_image_t image = {
        .data = (uint8_t *)g_ms51_firmware,
        .coverage = coverage,
        .size = g_ms51_firmware_size,
        .covered_size = g_ms51_firmware_size,
        .format = MS51_IMAGE_FORMAT_BINARY,
    };
    esp_err_t error = ms51_operation_lock(portMAX_DELAY);
    if (error == ESP_OK) {
        error = ms51_storage_commit_image("ms51_runtime_uart.bin", &image, info);
        ms51_operation_unlock();
    }
    heap_caps_free(coverage);
    return error;
}

static void log_result(const char *operation, esp_err_t error)
{
    if (error == ESP_OK) {
        ESP_LOGI(TAG, "%s: OK", operation);
    } else {
        ESP_LOGE(TAG, "%s: %s (0x%x)", operation, esp_err_to_name(error), error);
    }
}

static void print_device_info(const ms51_device_info_t *info)
{
    const uint32_t part_id = ((uint32_t)info->identity.product_id << 16) |
                             info->identity.device_id;
    printf("MS51 target\n");
    printf("  PDID       : 0x%08" PRIX32 "%s\n", part_id,
           part_id == MS51FC0AE_PART_ID ? " (MS51FC0AE)" : "");
    printf("  DID / PID  : 0x%04" PRIX16 " / 0x%04" PRIX16 "\n",
           info->identity.device_id, info->identity.product_id);
    printf("  CID        : 0x%02" PRIX8 "%s\n", info->identity.company_id,
           info->identity.company_id == MS51_NUVOTON_CID ? " (Nuvoton)" : "");
    printf("  CONFIG     :");
    for (size_t index = 0; index < sizeof(info->config); ++index) {
        printf(" %02X", info->config[index]);
    }
    printf("\n");
    printf("  Security   : %s\n", info->locked ? "LOCKED" : "unlocked");
    printf("  APROM      : %u bytes\n", (unsigned)info->aprom_size);
    printf("  LDROM      : %u bytes\n", (unsigned)info->ldrom_size);
    fflush(stdout);
}

static int command_info(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    ms51_device_info_t info;
    const esp_err_t error = ms51_programmer_get_info(&info);
    if (error == ESP_OK) {
        print_device_info(&info);
    } else {
        log_result("info", error);
    }
    return error == ESP_OK ? 0 : 1;
}

static int command_debug(int argc, char **argv)
{
    (void)argc;
    (void)argv;

    ms51_debug_uart_snapshot_t snapshot;
    const esp_err_t error = ms51_debug_uart_get_snapshot(&snapshot);
    if (error != ESP_OK) {
        log_result("runtime UART debug", error);
        return 1;
    }

    printf("MS51 runtime UART\n");
    printf("  State      : %s%s\n", snapshot.enabled ? "enabled" : "disabled",
           snapshot.paused_for_icp ? " (paused for ICP)" :
           snapshot.receiver_attached ? " (listening)" : " (detached)");
    printf("  Baud       : %" PRIu32 "\n", snapshot.baud_rate);
    printf("  RX / lines : %" PRIu32 " / %" PRIu32 "\n", snapshot.bytes_received,
           snapshot.lines_received);
    printf("  Dropped    : %" PRIu32 "\n", snapshot.dropped_lines);
    for (size_t index = 0; index < snapshot.variable_count; ++index) {
        printf("  %s = %s\n", snapshot.variables[index].name,
               snapshot.variables[index].value);
    }
    for (size_t index = 0; index < snapshot.log_count; ++index) {
        printf("  log: %s\n", snapshot.logs[index].text);
    }
    fflush(stdout);
    return 0;
}

static int command_dump(int argc, char **argv)
{
    if (argc != 2) {
        printf("Usage: dump <APROM address, for example 0x80>\n");
        return 1;
    }
    char *end = NULL;
    const unsigned long parsed = strtoul(argv[1], &end, 0);
    if (end == argv[1] || *end != '\0' || parsed > UINT32_MAX) {
        printf("Invalid APROM address: %s\n", argv[1]);
        return 1;
    }

    uint8_t data[16];
    const esp_err_t error = ms51_programmer_read_aprom((uint32_t)parsed, data, sizeof(data));
    if (error != ESP_OK) {
        log_result("APROM dump", error);
        return 1;
    }
    printf("APROM 0x%04lX:", parsed);
    for (size_t index = 0; index < sizeof(data); ++index) {
        printf(" %02X", data[index]);
    }
    printf("\n");
    fflush(stdout);
    return 0;
}

static int command_image(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    ms51_storage_info_t stored;
    ESP_ERROR_CHECK_WITHOUT_ABORT(ms51_storage_get_info(&stored));
    printf("MS51 image\n");
    if (stored.valid) {
        printf("  Source : web upload (%s)\n", stored.filename);
        printf("  Range  : %u bytes\n", (unsigned)stored.size);
        printf("  Covered: %u bytes\n", (unsigned)stored.covered_size);
        printf("  Format : %s\n", ms51_image_format_name(stored.format));
        printf("  CRC32  : 0x%08" PRIX32 "\n", stored.crc32);
        printf("  Gen    : %" PRIu32 "\n", stored.generation);
    } else {
        printf("  Source : %s\n", g_ms51_firmware_source);
        printf("  Size   : %u bytes\n", (unsigned)g_ms51_firmware_size);
        printf("  CRC32  : 0x%08" PRIX32 "\n", g_ms51_firmware_crc32);
    }
    fflush(stdout);
    return 0;
}

static int command_program(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    selected_image_t image;
    esp_err_t error = acquire_selected_image(&image);
    if (error == ESP_OK) {
        error = ms51_programmer_program_image(&image.image, CONFIG_MS51_VERIFY_AFTER_PROGRAM);
    }
    release_selected_image(&image);
    log_result("program", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_program_embedded(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    selected_image_t image;
    esp_err_t error = acquire_embedded_image(&image);
    if (error == ESP_OK) {
        printf("Programming embedded image: %s (%u bytes)\n", g_ms51_firmware_source,
               (unsigned)g_ms51_firmware_size);
        error = ms51_programmer_program_image(&image.image, CONFIG_MS51_VERIFY_AFTER_PROGRAM);
    }
    log_result("program embedded", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_activate_embedded(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    ms51_storage_info_t info;
    const esp_err_t error = commit_embedded_image(&info);
    if (error == ESP_OK) {
        printf("Web image is now %s (%u bytes, generation %" PRIu32 ")\n", info.filename,
               (unsigned)info.size, info.generation);
    }
    log_result("activate embedded image", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_program_full(int argc, char **argv)
{
    if (argc != 2 || strcmp(argv[1], "CONFIRM") != 0) {
        printf("Refusing to erase trailing APROM. Use exactly: program-full CONFIRM\n");
        return 1;
    }
    selected_image_t image;
    esp_err_t error = acquire_selected_image(&image);
    if (error == ESP_OK) {
        error = ms51_programmer_program_image_full(&image.image,
                                                    CONFIG_MS51_VERIFY_AFTER_PROGRAM);
    }
    release_selected_image(&image);
    log_result("full APROM program", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_verify(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    selected_image_t image;
    esp_err_t error = acquire_selected_image(&image);
    if (error == ESP_OK) {
        error = ms51_programmer_verify_image(&image.image);
    }
    release_selected_image(&image);
    log_result("verify", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_verify_embedded(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    selected_image_t image;
    esp_err_t error = acquire_embedded_image(&image);
    if (error == ESP_OK) {
        printf("Verifying embedded image: %s (%u bytes)\n", g_ms51_firmware_source,
               (unsigned)g_ms51_firmware_size);
        error = ms51_programmer_verify_image(&image.image);
    }
    log_result("verify embedded", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_erase(int argc, char **argv)
{
    if (argc != 2 || strcmp(argv[1], "CONFIRM") != 0) {
        printf("Refusing destructive erase. Use exactly: erase CONFIRM\n");
        return 1;
    }
    const esp_err_t error = ms51_programmer_mass_erase();
    log_result("mass erase", error);
    return error == ESP_OK ? 0 : 1;
}

static int command_reset(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    const esp_err_t error = ms51_programmer_reset();
    log_result("target reset", error);
    return error == ESP_OK ? 0 : 1;
}

static esp_err_t register_commands(void)
{
    const esp_console_cmd_t commands[] = {
        {
            .command = "info",
            .help = "Identify the MS51 and read CONFIG/APROM/LDROM state",
            .hint = NULL,
            .func = command_info,
            .argtable = NULL,
        },
        {
            .command = "debug",
            .help = "Show runtime UART telemetry received from the MS51",
            .hint = NULL,
            .func = command_debug,
            .argtable = NULL,
        },
        {
            .command = "dump",
            .help = "Read 16 bytes from an APROM address through ICP",
            .hint = "<address>",
            .func = command_dump,
            .argtable = NULL,
        },
        {
            .command = "image",
            .help = "Show the MS51 image embedded in this ESP32 build",
            .hint = NULL,
            .func = command_image,
            .argtable = NULL,
        },
        {
            .command = "program",
            .help = "Update only image-covered APROM bytes; preserve the rest",
            .hint = NULL,
            .func = command_program,
            .argtable = NULL,
        },
        {
            .command = "program-embedded",
            .help = "Program the image embedded in this ESP32 build (ignores web upload)",
            .hint = NULL,
            .func = command_program_embedded,
            .argtable = NULL,
        },
        {
            .command = "activate-embedded",
            .help = "Make the embedded image the default image used by the web UI",
            .hint = NULL,
            .func = command_activate_embedded,
            .argtable = NULL,
        },
        {
            .command = "program-full",
            .help = "Replace all APROM and erase bytes after the image; requires CONFIRM",
            .hint = "<CONFIRM>",
            .func = command_program_full,
            .argtable = NULL,
        },
        {
            .command = "verify",
            .help = "Compare the image-covered APROM bytes",
            .hint = NULL,
            .func = command_verify,
            .argtable = NULL,
        },
        {
            .command = "verify-embedded",
            .help = "Verify the image embedded in this ESP32 build",
            .hint = NULL,
            .func = command_verify_embedded,
            .argtable = NULL,
        },
        {
            .command = "erase",
            .help = "Whole-chip erase; requires the argument CONFIRM",
            .hint = "<CONFIRM>",
            .func = command_erase,
            .argtable = NULL,
        },
        {
            .command = "reset",
            .help = "Pulse MS51 nRESET and release the target",
            .hint = NULL,
            .func = command_reset,
            .argtable = NULL,
        },
    };

    for (size_t index = 0; index < sizeof(commands) / sizeof(commands[0]); ++index) {
        const esp_err_t error = esp_console_cmd_register(&commands[index]);
        if (error != ESP_OK) {
            return error;
        }
    }
    return ESP_OK;
}

static void start_console(void)
{
    esp_console_repl_t *repl = NULL;
    esp_console_repl_config_t repl_config = ESP_CONSOLE_REPL_CONFIG_DEFAULT();
    repl_config.prompt = "ms51> ";
    repl_config.max_cmdline_length = 128;

    ESP_ERROR_CHECK(esp_console_register_help_command());
    ESP_ERROR_CHECK(register_commands());

#if defined(CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG)
    esp_console_dev_usb_serial_jtag_config_t device_config =
        ESP_CONSOLE_DEV_USB_SERIAL_JTAG_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_serial_jtag(&device_config, &repl_config, &repl));
#elif defined(CONFIG_ESP_CONSOLE_UART_DEFAULT) || defined(CONFIG_ESP_CONSOLE_UART_CUSTOM)
    esp_console_dev_uart_config_t device_config = ESP_CONSOLE_DEV_UART_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_uart(&device_config, &repl_config, &repl));
#else
#error "Select USB Serial/JTAG or UART as the primary ESP-IDF console"
#endif

    ESP_ERROR_CHECK(esp_console_start_repl(repl));
}

void app_main(void)
{
    ESP_ERROR_CHECK(ms51_programmer_init());
    ESP_ERROR_CHECK(ms51_storage_init());
    ESP_ERROR_CHECK(ms51_debug_uart_init());

    ESP_LOGI(TAG, "ESP32-S3 -> MS51FC0AE ICP programmer");
    ESP_LOGI(TAG, "Pins: GPIO%d=nRESET, GPIO%d=ICP_CLK, GPIO%d=ICP_DAT",
             CONFIG_MS51_RST_GPIO, CONFIG_MS51_CLK_GPIO, CONFIG_MS51_DAT_GPIO);
    ESP_LOGW(TAG, "ICP_DAT/ICP_CLK are not an I2C bus; target and ESP32 must use safe 3.3 V levels");
    ESP_LOGI(TAG,
             "MS51 runtime UART: GPIO%d TX -> P0.2 RXD1, GPIO%d RX <- P1.6 TXD1; "
             "both pins are released during ICP",
             CONFIG_MS51_CLK_GPIO, CONFIG_MS51_DAT_GPIO);

#if CONFIG_MS51_AUTO_PROGRAM
    if (g_ms51_firmware_size > 0) {
        ESP_LOGI(TAG, "Auto-program image: %u bytes, CRC32=0x%08" PRIX32,
                 (unsigned)g_ms51_firmware_size, g_ms51_firmware_crc32);
        const esp_err_t error = ms51_programmer_program(
            g_ms51_firmware, g_ms51_firmware_size, CONFIG_MS51_VERIFY_AFTER_PROGRAM);
        log_result("startup auto-program", error);
    } else {
        ESP_LOGW(TAG, "No firmware/ms51_app.bin was embedded; auto-program skipped");
    }
#endif

    start_console();

    const esp_err_t web_error = ms51_web_start();
    if (web_error != ESP_OK) {
        ESP_LOGE(TAG, "Wi-Fi/web disabled: %s; USB console remains available",
                 esp_err_to_name(web_error));
    }
}
