#include "ms51_ble_policy.h"
#include "ms51_cloud.h"
#include "ms51_debug_uart.h"
#include "ms51_operation.h"
#include "esp_timer.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

typedef struct { const char *name; unsigned offset, bytes, maximum, flag; } field_t;
/* These are the physical menu visibility flags, not H/CF or LCD/LED values. */
static const field_t fields[] = {
    {"coolCl",1,4,999,0}, {"coolOp",5,4,999,0},
    {"defrostCl",9,4,999,0}, {"defrostOp",13,4,999,0},
    {"stLed",0x12,1,90,0xc3}, {"stLcd",0xc4,1,90,0xc3},
    {"df",0x13,1,1,0x27}, {"end",0x14,1,1,0x28},
    {"coolStart",0x15,1,1,0}, {"defrostStart",0x16,1,1,0},
    {"hdf",0x1d,1,1,0x2b}, {"hcfMode",0x1c,1,1,0x2a},
    {"displayMode",0x17,1,1,0x29}
};
static bool field_enabled(const field_t *f,const uint8_t *raw) {
    if(f->flag && raw[f->flag]!=1)return false;
    /* A fixed LCD/LED board exposes only its own ST. If the physical screen
     * selector is available, both ST values can be configured. */
    if(f->offset==0x12)return raw[0x29]==1 || raw[0x17]==1;
    if(f->offset==0xc4)return raw[0x29]==1 || raw[0x17]==0;
    return true;
}
static cJSON *failure(const char *message) {
    cJSON *r=cJSON_CreateObject(); cJSON_AddBoolToObject(r,"ok",false);
    cJSON_AddStringToObject(r,"message",message); return r;
}
static bool local_settings_supported(void) {
    ms51_debug_uart_snapshot_t *s=malloc(sizeof(*s)); if(!s)return false;
    bool supported=false;
    if(ms51_debug_uart_get_snapshot(s)==ESP_OK) for(size_t i=0;i<s->variable_count;i++)
        if(!strcmp(s->variables[i].name,"local_settings") &&
           (!strcmp(s->variables[i].value,"1") || !strcmp(s->variables[i].value,"2")) &&
           esp_timer_get_time()-(int64_t)s->variables[i].updated_us<5000000) supported=true;
    free(s); return supported;
}
static cJSON *settings(const cJSON *body) {
    bool supported=local_settings_supported();
    if(body && !supported) return failure("Cần cập nhật MS51 để lưu qua Bluetooth mà vẫn giữ bộ đếm bảo vệ.");
    if(ms51_operation_lock(MS51_OPERATION_FOREGROUND_WAIT)!=ESP_OK)
        return failure("Thiết bị đang bận. Thử lại sau.");
    uint8_t raw[MS51_RUNTIME_CONFIG_SIZE], status=255;
    esp_err_t err=ms51_debug_uart_get_runtime_config(raw,&status);
    const char *error=NULL;
    if(err!=ESP_OK || status!=MS51_RUNTIME_CONFIG_STATUS_OK || raw[0]!=2)
        error="Chưa đọc được cài đặt từ bộ điều khiển.";
    const cJSON *values=body?cJSON_GetObjectItemCaseSensitive(body,"values"):NULL;
    if(body && (!cJSON_IsObject(values) || !values->child)) error="Chưa có cài đặt cần lưu.";
    if(!error && body) {
        const cJSON *v;
        cJSON_ArrayForEach(v,values) {
            const field_t *f=NULL;
            for(unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++)
                if(v->string && !strcmp(v->string,fields[i].name)) { f=&fields[i]; break; }
            if(!f || !field_enabled(f,raw)) { error="Mục này chưa được bật trên mạch. Hãy đọc lại cài đặt."; break; }
            if(!cJSON_IsNumber(v) || !isfinite(v->valuedouble) || v->valuedouble<0 ||
               v->valuedouble>f->maximum || floor(v->valuedouble)!=v->valuedouble) {
                error="Giá trị cài đặt ngoài giới hạn."; break;
            }
            uint32_t n=(uint32_t)v->valuedouble;
            if(f->bytes==4 && n<1) { error="Thời gian phải từ 1 trở lên."; break; }
            for(unsigned b=0;b<f->bytes;b++) raw[f->offset+b]=(uint8_t)(n>>(8*(f->bytes-b-1)));
        }
        if(!error) {
            err=ms51_debug_uart_set_local_config(raw,&status);
            if(err!=ESP_OK || status!=MS51_RUNTIME_CONFIG_STATUS_OK)
                error="Bộ điều khiển chưa xác nhận lưu. Hãy đọc lại cài đặt trước khi thử lại.";
        }
    }
    ms51_operation_unlock();
    if(error) return failure(error);
    cJSON *r=cJSON_CreateObject(); cJSON_AddBoolToObject(r,"ok",true);
    cJSON_AddBoolToObject(r,"settings_write_supported",supported);
    cJSON_AddStringToObject(r,"policy_source","controller_menu");
    cJSON *visible=cJSON_AddArrayToObject(r,"visible");
    for(unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++)
        if(field_enabled(&fields[i],raw))
            cJSON_AddItemToArray(visible,cJSON_CreateString(fields[i].name));
    /* Never disclose controller passwords, limits on attempts, or elapsed counters. */
    memset(raw+0xc5,0,MS51_RUNTIME_CONFIG_SIZE-0xc5);
    char hex[MS51_RUNTIME_CONFIG_SIZE*2+1]; const char *digits="0123456789abcdef";
    for(unsigned i=0;i<sizeof(raw);i++) { hex[i*2]=digits[raw[i]>>4]; hex[i*2+1]=digits[raw[i]&15]; }
    hex[sizeof(hex)-1]=0; cJSON_AddStringToObject(r,"data",hex);
    return r;
}
cJSON *ms51_ble_request(const char *method,const char *path,const cJSON *body) {
    bool get=!strcmp(method,"GET"), post=!strcmp(method,"POST");
    if(get && !strcmp(path,"/api/runtime-config")) return settings(NULL);
    if(post && !strcmp(path,"/api/ble/settings")) return settings(body);
    if(get && !strcmp(path,"/api/status")) {
        cJSON *status=ms51_local_request(method,path,body);
        if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(status,"ok")))
            cJSON_AddBoolToObject(status,"ble_thermal_write_supported",true);
        return status;
    }
    bool allowed=(get && (!strcmp(path,"/api/status") || !strcmp(path,"/api/debug") ||
                         !strcmp(path,"/api/uplink/scan"))) ||
                 (post && (!strcmp(path,"/api/mode") || !strcmp(path,"/api/unlock") || !strcmp(path,"/api/thermal") ||
                           !strcmp(path,"/api/uplink") || !strcmp(path,"/api/cloud/pair")));
    if(!allowed) return failure("Chức năng này cần kết nối Internet và quyền từ tài khoản.");
    return ms51_local_request(method,path,body);
}
