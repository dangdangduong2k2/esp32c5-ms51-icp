#include "ms51_trigger.h"
#include "ms51_analog.h"
#include "ms51_debug_uart.h"
#include "ms51_operation.h"
#include "sdkconfig.h"
#include "nvs.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <stdio.h>
#include <string.h>
#include <stdint.h>

static portMUX_TYPE guard = portMUX_INITIALIZER_UNLOCKED;
static unsigned source;
static int threshold = 2500;
static int defrost_threshold;
static bool thermal_enabled;
static int64_t thermal_seen;
static int64_t last_ack;
static bool ready;
static const char *names[] = { "SIG", "10K", "20K", "50K" };
typedef struct { uint32_t magic, source; int32_t threshold; } config_t;
typedef struct { uint32_t magic, source; int32_t threshold, defrost; uint32_t enabled; } thermal_config_t;
static bool supported(unsigned selected) {
    if (selected==0) return true;
#if CONFIG_MS51_ANALOG_IO1 || CONFIG_MS51_ANALOG_IO6 || CONFIG_MS51_ANALOG_IO5
    if(selected==1) return true;
#endif
#if CONFIG_MS51_NTC_20K_IO5 || CONFIG_MS51_NTC_20K_IO6
    if(selected==2) return true;
#endif
#if CONFIG_MS51_NTC_50K_IO4
    if(selected==3) return true;
#endif
    return false;
}
static esp_err_t save_config(unsigned selected, int limit, int defrost, bool thermal) {
    if (!ready) return ESP_ERR_INVALID_STATE;
    if (!supported(selected) || limit < -5500 || limit > 12500) return ESP_ERR_INVALID_ARG;
    portENTER_CRITICAL(&guard);
    bool same=source==selected && threshold==limit && thermal_enabled==thermal && defrost_threshold==defrost;
    portEXIT_CRITICAL(&guard);
    if(same) return ESP_OK;
    thermal_config_t data={0x53494732,selected,limit,defrost,thermal};
    nvs_handle_t handle;
    esp_err_t error=nvs_open("ms51_trigger",NVS_READWRITE,&handle);
    if(error!=ESP_OK) return error;
    error=nvs_set_blob(handle,"config",&data,sizeof(data));
    if(error==ESP_OK) error=nvs_commit(handle);
    nvs_close(handle);
    if(error==ESP_OK) {
        portENTER_CRITICAL(&guard);
        source=selected; threshold=limit; defrost_threshold=defrost; thermal_enabled=thermal; last_ack=0;
        portEXIT_CRITICAL(&guard);
    }
    return error;
}
esp_err_t ms51_trigger_configure(unsigned selected, int limit) {
    return save_config(selected,limit,0,false);
}
esp_err_t ms51_thermal_configure(unsigned selected, int limit, int defrost) {
    if(selected<1 || selected>2 || !supported(3) || defrost < -5500 || defrost > 12500)
        return ESP_ERR_INVALID_ARG;
    portENTER_CRITICAL(&guard);
    int64_t seen=thermal_seen;
    portEXIT_CRITICAL(&guard);
    if(!seen || esp_timer_get_time()-seen>=3000000) return ESP_ERR_INVALID_STATE;
    return save_config(selected,limit,defrost,true);
}
static void trigger_task(void *unused) {
    (void)unused;
    while(true) {
        // Do not occupy UART with unsupported commands on older MS51 software.
        ms51_debug_uart_snapshot_t snapshot;
        bool capable=false, dual=false;
        if(ms51_debug_uart_get_snapshot(&snapshot)==ESP_OK) {
            for(size_t i=0;i<snapshot.variable_count;i++) {
                if(!strcmp(snapshot.variables[i].name,"sig_source") &&
                    esp_timer_get_time()-snapshot.variables[i].updated_us<2500000) capable=true;
                if(!strcmp(snapshot.variables[i].name,"thermal_v") &&
                    esp_timer_get_time()-snapshot.variables[i].updated_us<2500000) dual=true;
            }
        }
        portENTER_CRITICAL(&guard);
        thermal_seen=dual ? esp_timer_get_time() : 0;
        portEXIT_CRITICAL(&guard);
        if(capable && ms51_operation_lock(0)==ESP_OK) {
            portENTER_CRITICAL(&guard);
            unsigned selected=source; int limit=threshold, defrost=defrost_threshold; bool thermal=thermal_enabled;
            portEXIT_CRITICAL(&guard);
            int temperature=0;
            bool active=selected!=0 && ms51_analog_temperature(selected,&temperature) && temperature>limit;
            uint8_t status=0xff;
            int monitoring=0;
            bool valid=ms51_analog_temperature(3,&monitoring);
            esp_err_t error;
            if(thermal && dual)
                error=ms51_debug_uart_set_thermal(selected,active,valid,valid && monitoring<defrost,&status);
            else if(!thermal)
                error=ms51_debug_uart_set_trigger(selected,active,&status);
            else error=ESP_ERR_NOT_SUPPORTED;
            portENTER_CRITICAL(&guard);
            last_ack=error==ESP_OK && status==0 ? esp_timer_get_time() : 0;
            portEXIT_CRITICAL(&guard);
            ms51_operation_unlock();
        }
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}
esp_err_t ms51_trigger_init(void) {
    nvs_handle_t handle;
    esp_err_t error=nvs_open("ms51_trigger",NVS_READWRITE,&handle);
    if(error!=ESP_OK) return error;
    thermal_config_t data={0}; size_t size=sizeof(data);
    error=nvs_get_blob(handle,"config",&data,&size);
    nvs_close(handle);
    bool legacy=size==sizeof(config_t) && data.magic==0x53494731;
    bool current=size==sizeof(data) && data.magic==0x53494732 && data.enabled<=1 &&
        data.defrost>=-5500 && data.defrost<=12500 &&
        (!data.enabled || (data.source>=1 && data.source<=2 && supported(3)));
    if(error==ESP_OK && (legacy || current) &&
        supported(data.source) && data.threshold>=-5500 && data.threshold<=12500) {
        source=data.source; threshold=data.threshold;
        if(current) { defrost_threshold=data.defrost; thermal_enabled=data.enabled; }
    }
    if(xTaskCreate(trigger_task,"sig_trigger",4096,NULL,1,NULL)!=pdPASS) return ESP_ERR_NO_MEM;
    ready=true;
    return ESP_OK;
}
void ms51_trigger_json(char *output,size_t size) {
    portENTER_CRITICAL(&guard);
    unsigned selected=source; int limit=threshold; int64_t ack=last_ack; bool enabled=ready;
    int defrost=defrost_threshold; bool thermal=thermal_enabled; int64_t seen=thermal_seen;
    portEXIT_CRITICAL(&guard);
    int temperature=0;
    bool valid=selected!=0 && ms51_analog_temperature(selected,&temperature);
    bool synced=ack && esp_timer_get_time()-ack<3000000;
    int monitoring=0; bool monitor_valid=ms51_analog_temperature(3,&monitoring);
    bool capable=seen && esp_timer_get_time()-seen<3000000;
    snprintf(output,size,"{\"enabled\":%s,\"source\":\"%s\",\"threshold_centi\":%d,\"sensor_valid\":%s,\"active\":%s,\"synced\":%s,"
        "\"thermal_supported\":%s,\"thermal_enabled\":%s,\"defrost_centi\":%d,\"defrost_valid\":%s,\"defrost_active\":%s}",
        enabled?"true":"false",names[selected],limit,valid?"true":"false",
        selected==0 ? "null" : valid && temperature>limit ? "true":"false",synced?"true":"false",
        capable?"true":"false",thermal?"true":"false",defrost,monitor_valid?"true":"false",
        thermal && monitor_valid && monitoring<defrost?"true":"false");
}
