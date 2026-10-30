#include "ms51_ble_policy.h"
#include "ms51_debug_uart.h"
#include "ms51_operation.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static uint8_t current[217], written[217];
static int writes, held, supported=1, stale;
int64_t esp_timer_get_time(void){return 9000000;}
esp_err_t ms51_operation_lock(TickType_t timeout){assert(!held);held=1;return 0;}
void ms51_operation_unlock(void){assert(held);held=0;}
esp_err_t ms51_debug_uart_get_snapshot(ms51_debug_uart_snapshot_t *s){memset(s,0,sizeof(*s));s->variable_count=1;
 strcpy(s->variables[0].name,"local_settings");strcpy(s->variables[0].value,supported?"1":"0");s->variables[0].updated_us=stale?1:8999999;return 0;}
esp_err_t ms51_debug_uart_get_runtime_config(uint8_t *out,uint8_t *status){assert(held);memcpy(out,current,217);*status=0;return 0;}
esp_err_t ms51_debug_uart_set_local_config(const uint8_t *in,uint8_t *status){assert(held);memcpy(written,in,217);writes++;*status=0;return 0;}
/* Legacy full-image write must never be called. */
esp_err_t ms51_debug_uart_set_runtime_config(const uint8_t *in,uint8_t *status){assert(0);return 1;}
cJSON *ms51_local_request(const char *method,const char *path,const cJSON *body){return cJSON_Parse("{\"ok\":true}");}
static cJSON *set(const char *text){cJSON *b=cJSON_Parse(text);cJSON *r=ms51_ble_request("POST","/api/ble/settings",b);cJSON_Delete(b);return r;}
static int ok(cJSON *r){int value=cJSON_IsTrue(cJSON_GetObjectItem(r,"ok"));cJSON_Delete(r);return value;}
int main(void){
 memset(current,0x5a,sizeof(current));current[0]=2;current[0x29]=current[0x2a]=0;
 assert(ok(set("{\"values\":{\"coolCl\":7,\"stLed\":50}}")));assert(writes==1);
 assert(written[1]==0 && written[2]==0 && written[3]==0 && written[4]==7 && written[0x12]==50);
 for(int i=0;i<217;i++)if(!(i>=1&&i<=4)&&i!=0x12)assert(written[i]==current[i]);
 assert(!ok(set("{\"values\":{\"pinEnable\":0}}")));
 assert(!ok(set("{\"values\":{\"displayMode\":1}}")));
 assert(!ok(set("{\"values\":{\"coolCl\":1000}}")));
 assert(!ok(set("{\"values\":{\"coolCl\":0}}")));
 assert(!ok(set("{\"values\":{\"coolCl\":1.5}}")));
 assert(!ok(set("{\"values\":{\"stLed\":91}}")));
 assert(!ok(set("{\"values\":{\"coolCl\":8,\"showHcf\":1}}")));assert(writes==1);
 current[0x29]=current[0x2a]=1;assert(ok(set("{\"values\":{\"displayMode\":1,\"hcfMode\":0}}")));assert(writes==2);
 cJSON *read=ms51_ble_request("GET","/api/runtime-config",NULL);const char *hex=cJSON_GetStringValue(cJSON_GetObjectItem(read,"data"));assert(hex);
 for(int i=0xc5*2;i<217*2;i++)assert(hex[i]=='0');cJSON_Delete(read);
 supported=0;assert(!ok(set("{\"values\":{\"coolCl\":8}}")));supported=1;stale=1;
 assert(!ok(set("{\"values\":{\"coolCl\":8}}")));assert(writes==2);
 assert(!ok(ms51_ble_request("POST","/api/runtime-config",NULL)));
 assert(!ok(ms51_ble_request("POST","/api/program",NULL)));
 assert(!ok(ms51_ble_request("POST","/api/thermal",NULL)));
 assert(ok(ms51_ble_request("GET","/api/status",NULL)));
 assert(!held);puts("PASS: C5 BLE allowlist, big-endian encoding, atomic rejection, private state, stale capability, unsupported writes.");return 0;
}
