#include "ms51_analog.h"
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "ms51_ntc.h"
#include "sdkconfig.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#if CONFIG_MS51_ANALOG_IO1 || CONFIG_MS51_ANALOG_IO6 || CONFIG_MS51_ANALOG_IO5
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "soc/adc_channel.h"
#if CONFIG_MS51_ANALOG_IO5
#define NTC_GPIO 5
#define NTC_CHANNEL ADC_CHANNEL_4
_Static_assert(ADC1_CHANNEL_4_GPIO_NUM == NTC_GPIO, "IO5 must be ADC1 channel 4");
#elif CONFIG_MS51_ANALOG_IO6
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
#if CONFIG_MS51_NTC_50K_IO4
#if CONFIG_MS51_DAT_GPIO == 4 || CONFIG_MS51_CLK_GPIO == 4 || CONFIG_MS51_RST_GPIO == 4
#error "IO4 NTC conflicts with MS51 pins"
#endif
_Static_assert(ADC1_CHANNEL_3_GPIO_NUM == 4, "IO4 must be ADC1 channel 3");
#endif
#if CONFIG_MS51_NTC_20K_IO6
#define NTC20_GPIO 6
#define NTC20_CHANNEL ADC_CHANNEL_5
_Static_assert(ADC1_CHANNEL_5_GPIO_NUM == NTC20_GPIO, "IO6 must be ADC1 channel 5");
#if CONFIG_MS51_DAT_GPIO == 6 || CONFIG_MS51_CLK_GPIO == 6 || CONFIG_MS51_RST_GPIO == 6
#error "IO6 NTC conflicts with MS51 pins"
#endif
#elif CONFIG_MS51_NTC_20K_IO5
#define NTC20_GPIO 5
#define NTC20_CHANNEL ADC_CHANNEL_4
#if CONFIG_MS51_DAT_GPIO == 5 || CONFIG_MS51_CLK_GPIO == 5 || CONFIG_MS51_RST_GPIO == 5
#error "IO5 NTC conflicts with MS51 pins"
#endif
_Static_assert(ADC1_CHANNEL_4_GPIO_NUM == 5, "IO5 must be ADC1 channel 4");
#endif
static const adc_channel_t channels[] = { NTC_CHANNEL,
#if CONFIG_MS51_NTC_50K_IO4
    ADC_CHANNEL_3,
#endif
#if CONFIG_MS51_NTC_20K_IO5 || CONFIG_MS51_NTC_20K_IO6
    NTC20_CHANNEL,
#endif
};
static const int gpios[] = { NTC_GPIO,
#if CONFIG_MS51_NTC_50K_IO4
    4,
#endif
#if CONFIG_MS51_NTC_20K_IO5 || CONFIG_MS51_NTC_20K_IO6
    NTC20_GPIO,
#endif
};
static const int nominal[] = { 10000,
#if CONFIG_MS51_NTC_50K_IO4
    50000,
#endif
#if CONFIG_MS51_NTC_20K_IO5 || CONFIG_MS51_NTC_20K_IO6
    20000,
#endif
};
#define SENSOR_COUNT (sizeof(channels)/sizeof(channels[0]))
static adc_cali_handle_t s_calibration[SENSOR_COUNT];
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static struct { bool valid; int raw; int mv; int64_t at; } s_sample[SENSOR_COUNT];

static void sample_task(void *unused)
{
    (void)unused;
    while (true) {
        for (unsigned index = 0; index < SENSOR_COUNT; ++index) {
            int sum = 0, raw = 0, mv = 0;
            esp_err_t error = ESP_OK;
            for (unsigned i = 0; i < 16; ++i) {
                error = adc_oneshot_read(s_adc, channels[index], &raw);
                if (error != ESP_OK) break;
                sum += raw;
            }
            if (error == ESP_OK) {
                raw = (sum + 8) / 16;
                error = adc_cali_raw_to_voltage(s_calibration[index], raw, &mv);
            }
            const int64_t now = esp_timer_get_time();
            portENTER_CRITICAL(&s_lock);
            s_sample[index].valid = error == ESP_OK;
            s_sample[index].raw = raw; s_sample[index].mv = mv; s_sample[index].at = now;
            portEXIT_CRITICAL(&s_lock);
        }
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

esp_err_t ms51_analog_init(void)
{
    adc_oneshot_unit_init_cfg_t unit = { .unit_id = ADC_UNIT_1 };
    esp_err_t error = adc_oneshot_new_unit(&unit, &s_adc);
    if (error != ESP_OK) return error;
    for (unsigned index = 0; index < SENSOR_COUNT; ++index) {
        adc_oneshot_chan_cfg_t channel = { .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_12 };
        error = adc_oneshot_config_channel(s_adc, channels[index], &channel);
        if (error != ESP_OK) goto failed;
        adc_cali_curve_fitting_config_t calibration = {
            .unit_id = ADC_UNIT_1, .chan = channels[index],
            .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_12,
        };
        error = adc_cali_create_scheme_curve_fitting(&calibration, &s_calibration[index]);
        if (error != ESP_OK) goto failed; // never fabricate volts without calibration
    }
    if (xTaskCreate(sample_task, "ntc_adc", 3072, NULL, 1, NULL) != pdPASS) {
        error = ESP_ERR_NO_MEM;
        goto failed;
    }
    return ESP_OK;
failed:
    for (unsigned i = 0; i < SENSOR_COUNT; ++i) {
        if (s_calibration[i]) adc_cali_delete_scheme_curve_fitting(s_calibration[i]);
        s_calibration[i] = NULL;
    }
    adc_oneshot_del_unit(s_adc); s_adc = NULL;
    return error;
}

static void sensor_json(unsigned index, char *output, size_t size)
{
    portENTER_CRITICAL(&s_lock);
    const bool valid = s_sample[index].valid;
    const int raw = s_sample[index].raw, mv = s_sample[index].mv;
    const int64_t at = s_sample[index].at;
    portEXIT_CRITICAL(&s_lock);
    const int64_t age = at ? (esp_timer_get_time() - at) / 1000 : -1;
    if (!valid || age < 0 || age > 2000) {
        snprintf(output, size, "{\"enabled\":true,\"gpio\":%d,\"valid\":false,\"millivolts\":null,\"age_ms\":null}", gpios[index]);
        return;
    }
    int centi_celsius;
    const char *sensor = ms51_ntc_temperature(mv, raw, nominal[index], &centi_celsius);
    char temperature[16] = "null";
    if (strcmp(sensor, "ok") == 0) snprintf(temperature, sizeof(temperature), "%d", centi_celsius);
    snprintf(output, size, "{\"enabled\":true,\"gpio\":%d,\"valid\":true,\"raw\":%d,\"millivolts\":%d,\"age_ms\":%d,\"sensor\":\"%s\",\"temperature_centi\":%s}",
             gpios[index], raw, mv, (int)age, sensor, temperature);
}
void ms51_analog_json(char *output, size_t size) { sensor_json(0, output, size); }
void ms51_analog_50k_json(char *output, size_t size) {
#if CONFIG_MS51_NTC_50K_IO4
    sensor_json(1, output, size);
#else
    snprintf(output, size, "{\"enabled\":false,\"valid\":false}");
#endif
}

void ms51_analog_20k_json(char *output, size_t size) {
#if CONFIG_MS51_NTC_20K_IO5 || CONFIG_MS51_NTC_20K_IO6
    sensor_json(SENSOR_COUNT - 1, output, size);
#else
    snprintf(output,size,"{\"enabled\":false,\"valid\":false}");
#endif
}
bool ms51_analog_temperature(unsigned source, int *temperature) {
    int wanted = source == 1 ? 10000 : source == 2 ? 20000 : source == 3 ? 50000 : 0;
    for (unsigned i=0;i<SENSOR_COUNT;i++) if(nominal[i]==wanted) {
        portENTER_CRITICAL(&s_lock);
        bool valid=s_sample[i].valid; int raw=s_sample[i].raw,mv=s_sample[i].mv; int64_t at=s_sample[i].at;
        portEXIT_CRITICAL(&s_lock);
        return valid && at && esp_timer_get_time()-at < 2000000 &&
            strcmp(ms51_ntc_temperature(mv,raw,nominal[i],temperature),"ok")==0;
    }
    return false;
}

#else
esp_err_t ms51_analog_init(void) { return ESP_OK; }
void ms51_analog_json(char *output, size_t size)
{
    snprintf(output, size, "{\"enabled\":false,\"valid\":false}");
}
void ms51_analog_50k_json(char *output, size_t size) { ms51_analog_json(output, size); }
void ms51_analog_20k_json(char *output, size_t size) { ms51_analog_json(output, size); }
bool ms51_analog_temperature(unsigned source, int *temperature) { (void)source; (void)temperature; return false; }
#endif
