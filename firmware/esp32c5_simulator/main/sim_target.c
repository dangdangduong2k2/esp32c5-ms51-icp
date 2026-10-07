#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nvs.h"
#include "ms51_programmer.h"
#include "ms51_debug_uart.h"
#include "ms51_operation.h"
#include "sim_defaults.h"

/* Only RAM/NVS are touched. This component never initializes GPIO or UART. */
static uint8_t aprom[MS51_FLASH_SIZE];
static uint8_t runtime[MS51_RUNTIME_CONFIG_SIZE];
static SemaphoreHandle_t model;
static int64_t started_us;
static uint32_t reset_count;
static char latest_log[80] = "SIM: ESP32-C5 emulates MS51; no physical target or relays.";

static void log_event(const char *text) {
    xSemaphoreTake(model, portMAX_DELAY);
    strlcpy(latest_log, text, sizeof(latest_log));
    xSemaphoreGive(model);
    ESP_LOGI("SIM", "%s", text);
}
static uint32_t u32be(const uint8_t *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
esp_err_t ms51_programmer_init(void) {
    model=xSemaphoreCreateMutex();
    if (!model) return ESP_ERR_NO_MEM;
    memset(aprom, 0xff, sizeof(aprom));
    memcpy(runtime, default_config, sizeof(runtime));
    /* Fresh demo: 25 simulated cooling minutes and 8 defrost minutes. */
    runtime[1]=runtime[2]=runtime[3]=0; runtime[4]=25;
    runtime[9]=runtime[10]=runtime[11]=0; runtime[12]=8;
    nvs_handle_t handle;
    if (nvs_open("ms51_sim", NVS_READONLY, &handle)==ESP_OK) {
        size_t size=sizeof(runtime); uint8_t saved[sizeof(runtime)];
        if(nvs_get_blob(handle,"config",saved,&size)==ESP_OK && size==sizeof(runtime) && saved[0]==2)
            memcpy(runtime,saved,size);
        nvs_close(handle);
    }
    started_us=esp_timer_get_time();
    return ms51_operation_init();
}
static esp_err_t info_impl(ms51_device_info_t *info, TickType_t timeout) {
    if (!info) return ESP_ERR_INVALID_ARG;
    esp_err_t error=ms51_operation_lock(timeout); if(error!=ESP_OK) return error;
    memset(info,0,sizeof(*info));
    info->identity=(ms51_icp_identity_t){MS51FC0AE_DEVICE_ID,MS51FC0AE_PRODUCT_ID,MS51_NUVOTON_CID};
    memset(info->config,0xff,sizeof(info->config));
    info->aprom_size=MS51_FLASH_SIZE;
    ms51_operation_unlock();
    log_event("SIM: read emulated MS51FC0AE identity, APROM 32 KiB.");
    return ESP_OK;
}
esp_err_t ms51_programmer_get_info(ms51_device_info_t *i){return info_impl(i,portMAX_DELAY);}
esp_err_t ms51_programmer_try_get_info(ms51_device_info_t *i){return info_impl(i,0);}
esp_err_t ms51_programmer_read_aprom(uint32_t address,uint8_t *data,size_t length){
    if(!data || address>sizeof(aprom) || length>sizeof(aprom)-address) return ESP_ERR_INVALID_ARG;
    esp_err_t e=ms51_operation_lock(portMAX_DELAY); if(e!=ESP_OK)return e;
    memcpy(data,aprom+address,length); ms51_operation_unlock(); return ESP_OK;
}
static esp_err_t image_op(const ms51_image_t *image,bool full,bool verify_only){
    if(!image || !image->data || !image->size || image->size>sizeof(aprom)) return ESP_ERR_INVALID_SIZE;
    esp_err_t e=ms51_operation_lock(portMAX_DELAY); if(e!=ESP_OK)return e;
    vTaskDelay(pdMS_TO_TICKS(700));
    if(full)memset(aprom,0xff,sizeof(aprom));
    for(size_t i=0;i<image->size;i++){
        if(!ms51_image_address_is_covered(image,i))continue;
        if(verify_only){if(aprom[i]!=image->data[i]){e=ESP_FAIL;break;}}
        else aprom[i]=image->data[i];
    }
    ms51_operation_unlock();
    log_event(e!=ESP_OK ? "SIM: verify mismatch. Program the uploaded file first."
              : verify_only ? "SIM: verify passed against emulated APROM."
              : full ? "SIM: full program completed in emulated APROM."
              : "SIM: program completed; unrelated emulated APROM bytes preserved.");
    return e;
}
esp_err_t ms51_programmer_program_image(const ms51_image_t *i,bool verify){(void)verify;return image_op(i,false,false);}
esp_err_t ms51_programmer_program_image_full(const ms51_image_t *i,bool verify){(void)verify;return image_op(i,true,false);}
esp_err_t ms51_programmer_verify_image(const ms51_image_t *i){return image_op(i,false,true);}
static ms51_image_t dense(const uint8_t *d,size_t n){return(ms51_image_t){.data=(uint8_t*)d,.size=n,.covered_size=n};}
esp_err_t ms51_programmer_program(const uint8_t *d,size_t n,bool v){ms51_image_t i=dense(d,n);return ms51_programmer_program_image(&i,v);}
esp_err_t ms51_programmer_program_full(const uint8_t *d,size_t n,bool v){ms51_image_t i=dense(d,n);return ms51_programmer_program_image_full(&i,v);}
esp_err_t ms51_programmer_verify(const uint8_t *d,size_t n){ms51_image_t i=dense(d,n);return ms51_programmer_verify_image(&i);}
esp_err_t ms51_programmer_mass_erase(void){
    esp_err_t e=ms51_operation_lock(portMAX_DELAY); if(e!=ESP_OK)return e;
    vTaskDelay(pdMS_TO_TICKS(700)); memset(aprom,0xff,sizeof(aprom)); ms51_operation_unlock();
    log_event("SIM: erased emulated APROM only; ESP32 firmware is intact."); return ESP_OK;
}
static esp_err_t reset_impl(TickType_t ticks){
    esp_err_t e=ms51_operation_lock(ticks); if(e!=ESP_OK)return e;
    xSemaphoreTake(model,portMAX_DELAY); started_us=esp_timer_get_time();reset_count++;xSemaphoreGive(model);
    ms51_operation_unlock();log_event("SIM: MS51 reset simulated; timer restarted, ESP stays online.");return ESP_OK;
}
esp_err_t ms51_programmer_reset(void){return reset_impl(portMAX_DELAY);}
esp_err_t ms51_programmer_try_reset(void){return reset_impl(0);}
esp_err_t ms51_debug_uart_init(void){return ESP_OK;}
esp_err_t ms51_debug_uart_pause_for_icp(void){return ESP_OK;}
esp_err_t ms51_debug_uart_resume_after_icp(void){return ESP_OK;}
static void variable(ms51_debug_uart_snapshot_t *s,const char *name,const char *value,int64_t now){
    ms51_debug_variable_t *v=&s->variables[s->variable_count++];
    strlcpy(v->name,name,sizeof(v->name));strlcpy(v->value,value,sizeof(v->value));v->updated_us=now;
}
static void number(ms51_debug_uart_snapshot_t *s,const char *name,uint32_t value,int64_t now){
    char text[24];snprintf(text,sizeof(text),"%lu",(unsigned long)value);variable(s,name,text,now);
}
esp_err_t ms51_debug_uart_get_snapshot(ms51_debug_uart_snapshot_t *s){
    if(!s)return ESP_ERR_INVALID_ARG;
    memset(s,0,sizeof(*s));int64_t now=esp_timer_get_time();
    xSemaphoreTake(model,portMAX_DELAY);
    uint32_t elapsed=(uint32_t)((now-started_us)/1000000);
    uint32_t cool=u32be(runtime+1),defrost=u32be(runtime+9);
    if(cool<1 || cool>999)cool=25;
    if(defrost<1 || defrost>999)defrost=8;
    uint32_t position=elapsed%(cool+defrost);bool cooling=position<cool;
    s->enabled=true;s->receiver_attached=true;s->baud_rate=115200;
    s->lines_received=elapsed*10;s->bytes_received=elapsed*160;s->last_rx_us=now;
    variable(s,"mode",cooling?"COOL [SIM]":"DEFROST [SIM]",now);
    number(s,"green_min",cooling?cool-position:0,now);
    number(s,"red_min",cooling?defrost:cool+defrost-position,now);
    number(s,"relay1",cooling,now);number(s,"relay2",!cooling,now);number(s,"relay3",cooling,now);
    number(s,"uptime_s",elapsed,now);number(s,"reset_count",reset_count,now);
    variable(s,"simulation","ESP32-C5 / no MS51",now);
    s->log_count=3;
    strlcpy(s->logs[0].text,"SIMULATION: no MS51 connected. GPIO/UART are not driven.",80);
    strlcpy(s->logs[1].text,"SIM speed: 1 real second = 1 timer minute. No NTC measurement.",80);
    strlcpy(s->logs[2].text,latest_log,80);
    for(int i=0;i<3;i++)s->logs[i].received_us=now;
    xSemaphoreGive(model);return ESP_OK;
}
esp_err_t ms51_debug_uart_get_runtime_config(uint8_t data[217],uint8_t *status){
    if(!data||!status)return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(model,portMAX_DELAY);memcpy(data,runtime,217);xSemaphoreGive(model);
    *status=MS51_RUNTIME_CONFIG_STATUS_OK;return ESP_OK;
}
esp_err_t ms51_debug_uart_set_runtime_config(const uint8_t data[217],uint8_t *status){
    if(!data||!status)return ESP_ERR_INVALID_ARG;
    *status=MS51_RUNTIME_CONFIG_STATUS_BAD_CONFIG;
    if(data[0]!=2 || u32be(data+1)<1 || u32be(data+1)>999 || u32be(data+9)<1 || u32be(data+9)>999)return ESP_OK;
    nvs_handle_t handle;esp_err_t e=nvs_open("ms51_sim",NVS_READWRITE,&handle);
    if(e!=ESP_OK)return e;
    e=nvs_set_blob(handle,"config",data,217);if(e==ESP_OK)e=nvs_commit(handle);nvs_close(handle);
    if(e!=ESP_OK)return e;
    xSemaphoreTake(model,portMAX_DELAY);memcpy(runtime,data,217);started_us=esp_timer_get_time();reset_count++;xSemaphoreGive(model);
    *status=MS51_RUNTIME_CONFIG_STATUS_OK;log_event("SIM: 217-byte config saved in ESP NVS; simulated timer restarted.");return ESP_OK;
}
