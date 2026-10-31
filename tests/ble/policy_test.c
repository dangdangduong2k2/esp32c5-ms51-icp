#include "ms51_ble_policy.h"
#include "ms51_debug_uart.h"
#include "ms51_operation.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static uint8_t current[217], written[217];
static int writes, held, supported=1, stale, thermal_requests, local_failure;
int64_t esp_timer_get_time(void){return 9000000;}
esp_err_t ms51_operation_lock(TickType_t timeout){assert(!held);held=1;return 0;}
void ms51_operation_unlock(void){assert(held);held=0;}
esp_err_t ms51_debug_uart_get_snapshot(ms51_debug_uart_snapshot_t *s){memset(s,0,sizeof(*s));s->variable_count=1;
 strcpy(s->variables[0].name,"local_settings");sprintf(s->variables[0].value,"%d",supported);s->variables[0].updated_us=stale?1:8999999;return 0;}
esp_err_t ms51_debug_uart_get_runtime_config(uint8_t *out,uint8_t *status){assert(held);memcpy(out,current,217);*status=0;return 0;}
esp_err_t ms51_debug_uart_set_local_config(const uint8_t *in,uint8_t *status){assert(held);memcpy(written,in,217);writes++;*status=0;return 0;}
/* Legacy full-image write must never be called. */
esp_err_t ms51_debug_uart_set_runtime_config(const uint8_t *in,uint8_t *status){assert(0);return 1;}
cJSON *ms51_local_request(const char *method,const char *path,const cJSON *body){
 if(local_failure)return cJSON_Parse("{\"ok\":false}");
 if(!strcmp(method,"GET") && !strcmp(path,"/api/status"))return cJSON_Parse("{\"ok\":true,\"analog\":{\"temperature_centi\":2910},\"analog20k\":{\"temperature_centi\":3020},\"analog50k\":{\"temperature_centi\":2830},\"trigger\":{\"safety_enabled\":true,\"defrost_enabled\":false}}");
 if(!strcmp(method,"POST") && !strcmp(path,"/api/thermal")){
  assert(cJSON_GetArraySize(body)==5);
  assert(!strcmp(cJSON_GetStringValue(cJSON_GetObjectItem(body,"source")),"20K"));
  assert(cJSON_GetObjectItem(body,"threshold_centi")->valueint==3150);
  assert(cJSON_GetObjectItem(body,"defrost_centi")->valueint==-500);
  assert(cJSON_IsTrue(cJSON_GetObjectItem(body,"safety_enabled")));
  assert(cJSON_IsFalse(cJSON_GetObjectItem(body,"defrost_enabled")));thermal_requests++;
 }
 return cJSON_Parse("{\"ok\":true}");
}
static cJSON *set(const char *text){cJSON *b=cJSON_Parse(text);cJSON *r=ms51_ble_request("POST","/api/ble/settings",b);cJSON_Delete(b);return r;}
static int ok(cJSON *r){int value=cJSON_IsTrue(cJSON_GetObjectItem(r,"ok"));cJSON_Delete(r);return value;}
static int visible(cJSON *r,const char *key){cJSON *v; cJSON_ArrayForEach(v,cJSON_GetObjectItem(r,"visible"))if(!strcmp(v->valuestring,key))return 1;return 0;}
int main(void){
 memset(current,0x5a,sizeof(current));current[0]=2;current[0x29]=current[0x2a]=0;
 current[0xc3]=current[0x17]=1;
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
 stale=0;supported=2;assert(ok(set("{\"values\":{\"coolCl\":8}}")));
 for(int flags=0;flags<64;flags++)for(int mode=0;mode<2;mode++){
  for(int i=0;i<5;i++)current[0x27+i]=(flags>>i)&1;
  current[0xc3]=(flags>>5)&1;current[0x17]=mode;
  cJSON *r=ms51_ble_request("GET","/api/runtime-config",NULL);
  assert(visible(r,"df")==((flags&1)!=0));assert(visible(r,"end")==((flags&2)!=0));
  assert(visible(r,"displayMode")==((flags&4)!=0));assert(visible(r,"hcfMode")==((flags&8)!=0));assert(visible(r,"hdf")==((flags&16)!=0));
  assert(visible(r,"stLed")==((flags&32)!=0 && ((flags&4)!=0 || mode==1)));
  assert(visible(r,"stLcd")==((flags&32)!=0 && ((flags&4)!=0 || mode==0)));
  assert(visible(r,"coolCl") && !visible(r,"showDf") && !visible(r,"pinPassword"));cJSON_Delete(r);
  int before=writes;
  assert(ok(set("{\"values\":{\"df\":1}}"))==((flags&1)!=0));
  assert(writes==before+((flags&1)!=0));
 }
 current[0x27]=0;int before=writes;
 assert(!ok(set("{\"values\":{\"coolCl\":9,\"df\":1}}")));assert(writes==before);
 assert(!ok(ms51_ble_request("POST","/api/runtime-config",NULL)));
 assert(!ok(ms51_ble_request("POST","/api/program",NULL)));
 cJSON *thermal=cJSON_Parse("{\"source\":\"20K\",\"threshold_centi\":3150,\"defrost_centi\":-500,\"safety_enabled\":true,\"defrost_enabled\":false}");
 assert(ok(ms51_ble_request("POST","/api/thermal",thermal)));assert(thermal_requests==1);
 local_failure=1;assert(!ok(ms51_ble_request("POST","/api/thermal",thermal)));assert(thermal_requests==1);cJSON_Delete(thermal);
 cJSON *failed=ms51_ble_request("GET","/api/status",NULL);
 assert(!cJSON_HasObjectItem(failed,"ble_thermal_write_supported"));assert(!ok(failed));local_failure=0;
 cJSON *status=ms51_ble_request("GET","/api/status",NULL);
 assert(cJSON_IsTrue(cJSON_GetObjectItem(status,"ble_thermal_write_supported")));
 assert(cJSON_GetObjectItem(cJSON_GetObjectItem(status,"analog20k"),"temperature_centi")->valueint==3020);
 assert(cJSON_GetObjectItem(cJSON_GetObjectItem(status,"analog50k"),"temperature_centi")->valueint==2830);
 assert(cJSON_GetObjectItem(cJSON_GetObjectItem(status,"analog"),"temperature_centi")->valueint==2910);
 assert(cJSON_IsFalse(cJSON_GetObjectItem(cJSON_GetObjectItem(status,"trigger"),"defrost_enabled")));assert(ok(status));
 assert(!ok(ms51_ble_request("POST","/api/trigger",NULL)));
 assert(!held);puts("PASS: C5 BLE allowlist, big-endian encoding, atomic rejection, private state, stale capability, unsupported writes.");return 0;
}
