#!/usr/bin/env python3
"""Host tests of the actual MS51 virtual SIG functions and C5 NTC conversion."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'MS51_code/260502 add PIN/main.c').read_text(encoding='utf-8')
start=source.index('static uint8_t trigger_owner_valid')
end=source.index('/* Ownership survives reboot;',start)
prefix=r"""
#include <stdint.h>
#include <assert.h>
#include <math.h>
#include <string.h>
#define TICK_TYPE uint32_t
uint8_t EA=1,physical_pin=1,trigger_source=0,trigger_active=0,trigger_received=0;
uint32_t trigger_tick,now;
uint8_t relay3_legacy,relay3_override,relay3_thermal_on,RL3_output_is_on,relay3_pin;
uint32_t relay3_tick;
#define RL3_PIN 3
#define RL3_ACTIVE_LEVEL 1
#define RL_OUTPUT_LEVEL(level,on) ((on) ? (level) : !(level))
#define PIN_write(pin,value) (relay3_pin=(value))
int commits;
struct {uint8_t trigger_mark,trigger_source,trigger_inverse;} eData;
#define SIG_PIN 1
#define PIN_read(pin) physical_pin
uint32_t millis(void) { return now; }
void AP_EEPROM_Commit(void) { commits++; }
"""
checks=r"""
const char *ms51_ntc_temperature(int,int,int,int*);
int main(void) {
 assert(signal_read()==1);physical_pin=0;assert(signal_read()==0);
 assert(trigger_set(2,0));assert(signal_read()==1); // physical low ignored
 assert(trigger_set(2,1));assert(signal_read()==0);assert(commits==1);
 now=2999;assert(signal_read()==0);now=3000;assert(signal_read()==1);
 assert(trigger_set(2,1));assert(signal_read()==0);assert(commits==1);
 trigger_received=0;trigger_source=trigger_owner_valid()?eData.trigger_source:0;
 assert(trigger_source==2 && signal_read()==1); // reboot fails inactive until heartbeat
 assert(!trigger_set(4,1));assert(!trigger_set(1,2));assert(commits==1);
 now=0xfffffff0u;assert(trigger_set(1,1));now=20;assert(signal_read()==0);
 now=4000;assert(signal_read()==1);assert(trigger_set(0,0));assert(signal_read()==0);
 physical_pin=1;assert(signal_read()==1);
 // The final arbiter holds the physical output despite repeated mode writes.
 relay3_request(0);assert(!relay3_pin);
 assert(thermal_set(1,0,1,1));assert(relay3_pin && RL3_output_is_on);
 for(int mode=0;mode<9;mode++) { relay3_request(0); assert(relay3_pin); }
 assert(thermal_set(2,1,1,0)); assert(!relay3_pin && signal_read()==0);
 for(int mode=0;mode<9;mode++) { relay3_request(1); assert(!relay3_pin); }
 assert(thermal_set(2,1,0,0));assert(relay3_pin); // sensor fault restores latest machine demand
 assert(thermal_set(2,0,1,0));assert(!relay3_pin);
 now+=2999;relay3_refresh();assert(!relay3_pin);
 now++;relay3_refresh();assert(relay3_pin); // lost heartbeat exactly 3 seconds
 assert(!thermal_set(0,1,1,1));assert(!thermal_set(3,1,1,1));
 assert(!thermal_set(2,1,2,1));assert(!thermal_set(2,1,1,2));
 now=0xfffffff0u;assert(thermal_set(1,0,1,0));
 now=20;relay3_refresh();assert(!relay3_pin);
 now=4000;relay3_refresh();assert(relay3_pin); // timer rollover
 relay3_override=0;relay3_legacy=0;relay3_refresh();assert(!relay3_pin); // boot
 for(int i=0;i<3;i++) {
   int nominal[]={10000,20000,50000};int mv[]={1650,2200,2750};int t=0;
   assert(!strcmp(ms51_ntc_temperature(mv[i],2000,nominal[i],&t),"ok"));assert(abs(t-2500)<=1);
   assert(!strcmp(ms51_ntc_temperature(mv[i]-50,2000,nominal[i],&t),"ok"));assert(t>2500);
   assert(!strcmp(ms51_ntc_temperature(mv[i]+50,2000,nominal[i],&t),"ok"));assert(t<2500);
   assert(strcmp(ms51_ntc_temperature(0,0,nominal[i],&t),"ok"));
   assert(strcmp(ms51_ntc_temperature(3300,4095,nominal[i],&t),"ok"));
 }
 return 0;
}
"""
with tempfile.TemporaryDirectory() as directory:
    p=Path(directory);(p/'test.c').write_text('#include <stdlib.h>\n'+prefix+source[start:end]+checks,encoding='utf-8')
    subprocess.run([os.environ.get('CC','gcc'),'-std=c99','-Wall','-Wextra','-Werror',str(p/'test.c'),str(root/'main/ms51_ntc.c'),'-lm','-o',str(p/'test')],check=True)
    subprocess.run([str(p/'test')],check=True)
print('PASS: SIG, NTC conversion/faults, relay 3 arbitration across machine writes, sensor fault fallback, 3s lease timeout, clock rollover, startup and invalid frames')
