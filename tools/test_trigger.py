#!/usr/bin/env python3
"""Host tests of physical SIG priority, relay 3 leases and C5 NTC conversion."""
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
uint32_t trigger_tick,now,tick,countdown,tick_hcf;
uint8_t safety_active,program_init,program,progress,hcf_mode_timeout,hcf_mode;
uint8_t switch_state,RL2_is_on,RL2_LED_skip_on,RL2_LED_had_on,is_delay;
int RED,GREEN;
void TM1650_number(int *display,int value){*display=value;}
#define COOL 0
#define ICE_FLUSH 1
#define SW_COOL 0
#define SW_ICE_FLUSH 1
#define ICE_FLUSH_OP_TIME 0
#define OP 0
#define CL 1
#define ticks_reset(t) (*(t)=now)

uint8_t relay3_legacy,relay3_override,relay3_thermal_on,RL3_output_is_on,relay3_pin;
uint32_t relay3_tick;
#define RL3_PIN 3
#define RL3_ACTIVE_LEVEL 1
#define RL_OUTPUT_LEVEL(level,on) ((on) ? (level) : !(level))
#define PIN_write(pin,value) (relay3_pin=(value))
int commits;
struct {uint8_t trigger_mark,trigger_source,trigger_inverse,hDF,HCF,check_box[1];uint16_t time[2][2],HCF_time;} eData;
#define SIG_PIN 1
#define PIN_read(pin) physical_pin
uint32_t millis(void) { return now; }
void AP_EEPROM_Commit(void) { commits++; }
"""
checks=r"""
const char *ms51_ntc_temperature(int,int,int,int*);
int main(void) {
 // Use the real expiry body, verifying every OP1 variant loads CL1 exactly once.
 for(int hdf=0;hdf<2;hdf++) for(int hcf=0;hcf<2;hcf++) {
   program_init=1;program=hdf?ICE_FLUSH:COOL;switch_state=program;
   eData.hDF=hdf;eData.HCF=hcf;eData.time[COOL][CL]=7;eData.time[ICE_FLUSH][CL]=19;
   progress=OP;countdown=99999;hcf_mode_timeout=1;RL2_is_on=RL2_LED_had_on=RL2_LED_skip_on=is_delay=1;
   assert(safety_set(1,1,1,1));safety_step();
   assert(progress==CL && countdown==4200 && !RL2_is_on && !RL2_LED_had_on && !RL2_LED_skip_on && !is_delay);
   assert(safety_set(1,1,1,1));safety_step();assert(progress==CL && countdown==4200);
 }
 // Safety only completes OP1, including a held HCF timer. It never substitutes SIG.
 program_init=1;program=COOL;progress=OP;countdown=30000;hcf_mode_timeout=1;hcf_mode=1;
 assert(safety_set(1,1,1,1));assert(safety_finish_op1());
 assert(countdown==1 && !hcf_mode_timeout && !hcf_mode && tick==now);
 // Same expiry entry used by countdown_timer; a recurring heartbeat cannot re-finish CL.
 progress=CL;countdown=4200;assert(safety_set(1,1,1,1));assert(!safety_finish_op1());assert(countdown==4200);
 progress=OP;program=ICE_FLUSH;eData.hDF=0;assert(!safety_finish_op1()); // OP2 is untouched
 eData.hDF=1;assert(safety_finish_op1()); // delegated OP1
 program=2;assert(!safety_finish_op1());
 program=COOL;program_init=0;assert(!safety_finish_op1());program_init=1;
 assert(safety_set(2,0,1,0));assert(!safety_finish_op1()); // disabled/equal/below/fault
 assert(safety_set(2,1,1,0));now+=3000;assert(!safety_finish_op1()); // lost C5
 now=0xfffffff0u;assert(safety_set(2,1,1,0));now=20;assert(safety_finish_op1());
 now=4000;assert(!safety_finish_op1());
 assert(safety_set(1,1,1,0));assert(!safety_set(3,1,1,0)); // invalid frame cannot renew lease
 assert(thermal_set(1,1,1,0));assert(!safety_finish_op1()); // legacy thermal must disarm
 assert(safety_set(1,1,1,0));assert(trigger_set(1,1));assert(!safety_finish_op1());
 safety_active=0;assert(!safety_finish_op1()); // restart
 // Neither old trigger commands nor new thermal commands can override either pin level.
 for(int level=0;level<2;level++) {
   physical_pin=level;
   for(int source=0;source<4;source++) for(int active=0;active<2;active++) {
     assert(trigger_set(source,active)); assert(signal_read()==level);
     now+=3001;assert(signal_read()==level); // expired heartbeat
     trigger_received=0;trigger_source=trigger_owner_valid()?eData.trigger_source:0;
     assert(signal_read()==level); // restored old temperature selection on restart
   }
   for(int source=1;source<=2;source++) for(int active=0;active<2;active++) {
     assert(thermal_set(source,active,0,0));assert(signal_read()==level);
   }
 }
 assert(!trigger_set(4,1));assert(!trigger_set(1,2));
 physical_pin=1;assert(signal_read()==1);
 // The final arbiter holds the physical output despite repeated mode writes.
 relay3_request(0);assert(!relay3_pin);
 assert(thermal_set(1,0,1,1));assert(relay3_pin && RL3_output_is_on);
 for(int mode=0;mode<9;mode++) { relay3_request(0); assert(relay3_pin); }
 assert(thermal_set(2,1,1,0)); assert(!relay3_pin && signal_read()==1);
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
# Compile the exact normal expiry block alongside the safety entry condition.
expiry_start=source.index('\n\t\t\t{',source.index('if (--countdown == 0)',source.index('void countdown_timer')))
expiry_end=source.index('\n\t\t\telse',expiry_start)
expiry='void safety_step(void){if(safety_finish_op1() && --countdown==0)'+source[expiry_start:expiry_end]+'}\n'
with tempfile.TemporaryDirectory() as directory:
    p=Path(directory);(p/'test.c').write_text('#include <stdlib.h>\n'+prefix+source[start:end]+expiry+checks,encoding='utf-8')
    binary=p/('test.exe' if os.name=='nt' else 'test')
    math_lib=[] if os.name=='nt' else ['-lm']
    subprocess.run([os.environ.get('CC','gcc'),'-std=c99','-Wall','-Wextra','-Werror',str(p/'test.c'),str(root/'main/ms51_ntc.c'),*math_lib,'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
print('PASS: OP1 safety eligibility, disabled/expired/legacy disarm, OP2/CL isolation, HCF hold and rollover; physical SIG at both levels despite all saved sources, trigger/thermal commands, expiry and restart; NTC conversion/faults; independent relay 3 arbitration, sensor fallback, lease expiry, rollover and invalid frames')
