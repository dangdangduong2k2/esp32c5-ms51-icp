#!/usr/bin/env python3
"""Compile actual C5 safety task/storage code with host NVS/UART/sensor doubles."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'main/ms51_trigger.c').read_text()
source='\n'.join(line for line in source.splitlines() if not line.startswith('#include "'))
prefix=r'''
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <assert.h>
#include <setjmp.h>
typedef int esp_err_t;
typedef int portMUX_TYPE;
typedef int nvs_handle_t;
#define ESP_OK 0
#define ESP_ERR_INVALID_STATE 1
#define ESP_ERR_INVALID_ARG 2
#define ESP_ERR_NO_MEM 3
#define ESP_ERR_NOT_SUPPORTED 4
#define portMUX_INITIALIZER_UNLOCKED 0
#define portENTER_CRITICAL(x) ((void)(x))
#define portEXIT_CRITICAL(x) ((void)(x))
#define CONFIG_MS51_ANALOG_IO5 1
#define CONFIG_MS51_NTC_20K_IO6 1
#define CONFIG_MS51_NTC_50K_IO4 1
#define NVS_READWRITE 1
#define pdPASS 1
#define pdMS_TO_TICKS(x) (x)
int64_t now=10000000;
int64_t esp_timer_get_time(void){return now;}
int temps[4]={0,3000,3000,0};bool valid_sensor[4]={false,true,true,true};
bool ms51_analog_temperature(unsigned s,int *t){*t=temps[s];return valid_sensor[s];}
uint8_t stored[28];size_t stored_size;int fail_save;
int nvs_open(const char *n,int m,nvs_handle_t *h){(void)n;(void)m;*h=1;return 0;}
int nvs_get_blob(int h,const char *k,void *p,size_t *n){(void)h;(void)k;if(!stored_size)return 1;assert(*n>=stored_size);*n=stored_size;memcpy(p,stored,*n);return 0;}
int nvs_set_blob(int h,const char *k,const void *p,size_t n){(void)h;(void)k;if(fail_save)return 1;memcpy(stored,p,n);stored_size=n;return 0;}
int nvs_commit(int h){(void)h;return 0;}void nvs_close(int h){(void)h;}
typedef struct {char name[20];int64_t updated_us;} variable;
typedef struct {size_t variable_count;variable variables[3];} ms51_debug_uart_snapshot_t;
bool capability=true;
int ms51_debug_uart_get_snapshot(ms51_debug_uart_snapshot_t *s){s->variable_count=capability?3:2;strcpy(s->variables[0].name,"sig_source");strcpy(s->variables[1].name,"thermal_v");strcpy(s->variables[2].name,"safe_v");for(int i=0;i<3;i++)s->variables[i].updated_us=now;return 0;}
int ms51_operation_lock(int t){(void)t;return 0;}void ms51_operation_unlock(void){}
int cmd,last_source;bool last_active,last_valid,last_on;
int ms51_debug_uart_set_safety(unsigned s,bool a,bool v,bool on,uint8_t *status){cmd=0x56;last_source=s;last_active=a;last_valid=v;last_on=on;*status=0;return 0;}
int ms51_debug_uart_set_thermal(unsigned s,bool a,bool v,bool on,uint8_t *status){int r=ms51_debug_uart_set_safety(s,a,v,on,status);cmd=0x55;return r;}
int ms51_debug_uart_set_trigger(unsigned s,bool a,uint8_t *status){int r=ms51_debug_uart_set_safety(s,a,0,0,status);cmd=0x54;return r;}
int xTaskCreate(void (*f)(void*),const char *n,int stack,void *a,int prio,void *handle){(void)f;(void)n;(void)stack;(void)a;(void)prio;(void)handle;return 1;}
jmp_buf task_end;
void vTaskDelay(int ms){assert(ms==500);longjmp(task_end,1);}
'''
checks=r'''
void heartbeat(void){if(!setjmp(task_end))trigger_task(NULL);}
void reboot(void){source=0;threshold=2500;defrost_threshold=0;defrost_enabled=safety_enabled=thermal_enabled=ready=false;thermal_seen=safety_seen=last_ack=0;assert(ms51_trigger_init()==0);heartbeat();}
int main(void){
 reboot();assert(!safety_enabled && !thermal_enabled);
 config_t v1={0x53494731,2,3100};memcpy(stored,&v1,sizeof(v1));stored_size=sizeof(v1);
 reboot();assert(source==2 && threshold==3100 && !safety_enabled && !thermal_enabled);
 thermal_config_t v2={0x53494732,1,3000,500,1,0,0};memcpy(stored,&v2,20);stored_size=20;
 reboot();assert(thermal_enabled && !safety_enabled && threshold==3000 && defrost_threshold==500);

 assert(defrost_enabled); // old v2 preserved monitoring
 thermal_config_t v3={0x53494733,2,3000,500,1,1,0};memcpy(stored,&v3,24);stored_size=24;
 reboot();assert(defrost_enabled && safety_enabled && thermal_enabled);
 for(int safety=0;safety<2;safety++)for(int monitor=0;monitor<2;monitor++){
   temps[2]=3001;valid_sensor[3]=true;temps[3]=0;
   assert(ms51_thermal_configure(2,3000,500,safety,monitor)==0);heartbeat();
   assert(last_active==safety && last_valid==monitor && last_on==monitor);
   reboot();assert(safety_enabled==safety && defrost_enabled==monitor && last_active==safety && last_valid==monitor);
   temps[3]=500;heartbeat();assert(last_valid==monitor && !last_on); // equal threshold turns R3 off only when enabled
   valid_sensor[3]=false;heartbeat();assert(!last_valid && !last_on && last_active==safety);
 }
 valid_sensor[3]=true;temps[3]=0;
 assert(ms51_thermal_configure(1,3000,500,true,true)==0);heartbeat();assert(cmd==0x56 && !last_active && last_on); // equality, independent R3
 temps[1]=3001;heartbeat();assert(last_active);
 valid_sensor[1]=false;heartbeat();assert(!last_active && last_on); // sensor fault cannot finish OP1
 valid_sensor[1]=true;temps[1]=2999;heartbeat();assert(!last_active);
 assert(ms51_thermal_configure(2,3000,500,true,true)==0);temps[2]=3001;heartbeat();assert(last_source==2 && last_active);
 reboot();assert(safety_enabled && thermal_enabled && source==2);assert(last_active); // saved on
 assert(ms51_thermal_configure(2,3000,500,false,true)==0);heartbeat();assert(!last_active && last_on);
 reboot();assert(!safety_enabled && !last_active); // saved off
 fail_save=1;assert(ms51_thermal_configure(2,4000,500,true,true)!=0);assert(!safety_enabled && threshold==3000);fail_save=0;
 capability=false;heartbeat();assert(ms51_thermal_configure(2,3000,500,true,true)==ESP_ERR_INVALID_STATE);
 assert(ms51_thermal_configure(2,3000,500,false,true)==0);heartbeat();assert(cmd==0x55); // legacy remains available
 capability=true;heartbeat();now+=3000001;assert(ms51_thermal_configure(2,3000,500,true,true)==ESP_ERR_INVALID_STATE);
 heartbeat();assert(ms51_thermal_configure(2,3000,500,true,true)==0);
 assert(ms51_trigger_configure(0,2500)==0);assert(!safety_enabled && !thermal_enabled && !defrost_enabled); // legacy SIG path disarms
 char json[512];ms51_trigger_json(json,sizeof(json));assert(strstr(json,"\"safety_enabled\":false"));assert(json[strlen(json)-1]=='}');
 return 0;
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'test.c').write_text(prefix+source+checks)
 subprocess.run(['gcc','-std=c99','-Wall','-Wextra','-Werror',str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
print('PASS: C5 v1/v2/v3 migration, v4 independent safety/defrost persistence on/off, motor selection, strict greater-than, invalid sensor disarm, independent R3, stale/old capability rejection, NVS failure and JSON capacity')
