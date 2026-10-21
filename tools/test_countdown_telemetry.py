#!/usr/bin/env python3
"""Test the actual MS51 timer-state function, including CF's fixed zero display."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'MS51_code/260502 add PIN/telemetry.c').read_text(encoding='utf-8')
a=source.index('static const char code *telemetry_countdown_active(void)')
b=source.index('static void telemetry_build_snapshot',a)
prefix=r'''
#include <stdint.h>
#include <assert.h>
#include <string.h>
#define code
#define COOL 0
#define ICE_FLUSH 1
#define OP 0
#define CL 1
#define SW_IDLE 2
#define SW_COOL 0
#define SW_ICE_FLUSH 1
unsigned program,program_init,progress,hcf_mode_timeout,countdown,show_his,popup_timeout,switch_state;
struct {unsigned shutdown,HCF,hDF;} eData;
typedef struct {unsigned numeric,value;} TM1650_class;
TM1650_class GREEN={1,0},RED={1,5};
unsigned telemetry_display_number(TM1650_class *d,uint16_t *value){*value=d->value;return d->numeric;}
'''
checks=r'''
int main(void){
 program_init=1;countdown=6000;
 for(unsigned cf=0;cf<2;cf++)for(unsigned hdf=0;hdf<2;hdf++)for(unsigned mode=0;mode<2;mode++){
  eData.HCF=cf;eData.hDF=hdf;program=mode;switch_state=mode;progress=OP;
  GREEN.value=0;assert(!strcmp(telemetry_countdown_active(),"GREEN"));
  countdown=5999;assert(!strcmp(telemetry_countdown_active(),"GREEN")); // same digit, next tick
  GREEN.value=5;assert(!strcmp(telemetry_countdown_active(),"GREEN"));
  progress=CL;assert(!strcmp(telemetry_countdown_active(),"RED"));
 }
 eData.HCF=1;progress=OP;hcf_mode_timeout=1;assert(!strcmp(telemetry_countdown_active(),"NONE"));
 hcf_mode_timeout=0;assert(!strcmp(telemetry_countdown_active(),"GREEN"));
 unsigned *blocked[]={&eData.shutdown,&show_his,&popup_timeout};
 for(unsigned i=0;i<3;i++){*blocked[i]=1;assert(!strcmp(telemetry_countdown_active(),"NONE"));*blocked[i]=0;}
 countdown=0;assert(!strcmp(telemetry_countdown_active(),"NONE"));countdown=1;
 program_init=0;assert(!strcmp(telemetry_countdown_active(),"NONE"));program_init=1;
 for(program=2;program<9;program++){assert(!strcmp(telemetry_countdown_active(),"NONE"));}
 program=COOL;
 GREEN.numeric=0;assert(!strcmp(telemetry_countdown_active(),"NONE"));GREEN.numeric=1;
 progress=CL;RED.numeric=0;assert(!strcmp(telemetry_countdown_active(),"NONE"));
 return 0;
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'test.c').write_text(prefix+source[a:b]+checks)
 subprocess.run(['gcc','-std=c99','-Wall','-Wextra','-Werror',str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
print('PASS: CF/H OP counts with zero/unchanged digits; CL, standalone defrost and hDF; actual hold, stopped, settings, history, popup and nonnumeric display do not blink')
