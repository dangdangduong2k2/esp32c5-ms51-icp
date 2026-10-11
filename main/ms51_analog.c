#include "ms51_analog.h"
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "ms51_ntc.h"
#include "sdkconfig.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#if CONFIG_MS51_ANALOG_IO1 || CONFIG_MS51_ANALOG_IO6
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "soc/adc_channel.h"
#if CONFIG_MS51_ANALOG_IO6
#define NTC_GPIO 6
#define NTC_CHANNEL ADC_CHANNEL_5
_Static_assert(ADC1_CHANNEL_5_GPIO_NUM == NTC_GPIO, "IO6 must be ADC1 channel 5");
#else
#define NTC_GPIO 1
#define NTC_CHANNEL ADC_CHANNEL_0
_Static_assert(ADC1_CHANNEL_0_GPIO_NUM == NTC_GPIO, "IO1 must be ADC1 channel 0");
#endif
#if CONFIG_MS51_DAT_GPIO == NTC_GPIO || CONFIG_MS51_CLK_GPIO == NTC_GPIO || CONFIG_MS51_RST_GPIO == NTC_GPIO
#error "NTC input conflicts with MS51 programming/runtime pins"
#endif
static adc_oneshot_unit_handle_t s_adc;
static adc_cali_handle_t s_calibration;
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static struct { bool valid; int raw; int mv; int64_t at; } s_sample;

static void sample_task(void *unused)
{
    (void)unused;
    while (true) {
        int sum = 0, raw = 0, mv = 0;
        esp_err_t error = ESP_OK;
        for (unsigned i = 0; i < 16; ++i) {
            error = adc_oneshot_read(s_adc, NTC_CHANNEL, &raw);
            if (error != ESP_OK) break;
            sum += raw;
        }
        if (error == ESP_OK) {
            raw = (sum + 8) / 16;
            error = adc_cali_raw_to_voltage(s_calibration, raw, &mv);
        }
        const int64_t now = esp_timer_get_time();
        portENTER_CRITICAL(&s_lock);
        s_sample.valid = error == ESP_OK;
        s_sample.raw = raw; s_sample.mv = mv; s_sample.at = now;
        portEXIT_CRITICAL(&s_lock);
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

esp_err_t ms51_analog_init(void)
{
    adc_oneshot_unit_init_cfg_t unit = { .unit_id = ADC_UNIT_1 };
    esp_err_t error = adc_oneshot_new_unit(&unit, &s_adc);
    if (error != ESP_OK) return error;
    adc_oneshot_chan_cfg_t channel = { .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_12 };
    error = adc_oneshot_config_channel(s_adc, NTC_CHANNEL, &channel);
    if (error != ESP_OK) goto failed;
    adc_cali_curve_fitting_config_t calibration = {
        .unit_id = ADC_UNIT_1, .chan = NTC_CHANNEL,
        .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_12,
    };
    error = adc_cali_create_scheme_curve_fitting(&calibration, &s_calibration);
    if (error != ESP_OK) goto failed; // never fabricate volts without calibration
    if (xTaskCreate(sample_task, "ntc_adc", 3072, NULL, 1, NULL) != pdPASS) {
        adc_cali_delete_scheme_curve_fitting(s_calibration);
        s_calibration = NULL;
        error = ESP_ERR_NO_MEM;
        goto failed;
    }
    return ESP_OK;
failed:
    adc_oneshot_del_unit(s_adc); s_adc = NULL;
    return error;
}

void ms51_analog_json(char *output, size_t size)
{
    portENTER_CRITICAL(&s_lock);
    const bool valid = s_sample.valid;
    const int raw = s_sample.raw, mv = s_sample.mv;
    const int64_t at = s_sample.at;
    portEXIT_CRITICAL(&s_lock);
    const int64_t age = at ? (esp_timer_get_time() - at) / 1000 : -1;
    if (!valid || age < 0 || age > 2000) {
        snprintf(output, size, "{\"enabled\":true,\"gpio\":%d,\"valid\":false,\"millivolts\":null,\"age_ms\":null}", NTC_GPIO);
        return;
    }
    int centi_celsius;
    const char *sensor = ms51_ntc_temperature(mv, raw, &centi_celsius);
    char temperature[16] = "null";
    if (strcmp(sensor, "ok") == 0) snprintf(temperature, sizeof(temperature), "%d", centi_celsius);
    snprintf(output, size, "{\"enabled\":true,\"gpio\":%d,\"valid\":true,\"raw\":%d,\"millivolts\":%d,\"age_ms\":%d,\"sensor\":\"%s\",\"temperature_centi\":%s}",
             NTC_GPIO, raw, mv, (int)age, sensor, temperature);
}
#else
esp_err_t ms51_analog_init(void) { return ESP_OK; }
void ms51_analog_json(char *output, size_t size)
{
    snprintf(output, size, "{\"enabled\":false,\"valid\":false}");
}
#endif
