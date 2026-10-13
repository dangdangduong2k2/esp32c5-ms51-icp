#!/usr/bin/env python3
"""Run the MS51 mode ownership logic with a host C compiler (CC or gcc)."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'MS51_code/260502 add PIN/main.c').read_text(encoding='utf-8')
start=source.index('static uint8_t mode_owner_valid')
end=source.index('void ADC_update(void)',start)
prefix = """
typedef unsigned char uint8_t;
typedef enum {COOL,ICE_FLUSH,IDLE,SETTING} program_t;
typedef enum {SW_COOL,SW_ICE_FLUSH,SW_IDLE} switch_t;
struct {uint8_t mode_owner_mark,mode_owner_value,mode_owner_inverse,shutdown,pin_locked;} eData;
program_t program; switch_t switch_state,physical_switch_state,mode_candidate;
uint8_t mode_from_app,physical_switch_ready,mode_candidate_count,password_gate;
int commits,restarts;
void AP_EEPROM_Commit(void) { commits++; }
void switch_program(program_t p) { program=p; restarts++; }
"""
checks = """
#define CHECK(x,n) if(!(x))return n
int run_tests(void) {
 eData.mode_owner_mark=255; eData.mode_owner_value=255; eData.mode_owner_inverse=255;
 program=IDLE; mode_restore_owner(); mode_observe_switch(SW_ICE_FLUSH);
 CHECK(!mode_from_app && switch_state==SW_ICE_FLUSH,1);
 CHECK(mode_select_app(SW_COOL),2); CHECK(mode_from_app && program==COOL && commits==1,3);
 mode_observe_switch(SW_ICE_FLUSH); CHECK(switch_state==SW_COOL,4);
 CHECK(mode_select_app(SW_COOL) && commits==1 && restarts==1,5);
 /* Restart with switch already elsewhere: only establish baseline. */
 physical_switch_ready=0; mode_from_app=0; program=IDLE; switch_state=SW_IDLE;
 mode_restore_owner(); mode_observe_switch(SW_IDLE);
 CHECK(mode_from_app && switch_state==SW_COOL,6);
 mode_observe_switch(SW_ICE_FLUSH); mode_observe_switch(SW_IDLE);
 CHECK(mode_from_app && switch_state==SW_COOL,7);
 mode_observe_switch(SW_ICE_FLUSH);mode_observe_switch(SW_ICE_FLUSH);mode_observe_switch(SW_ICE_FLUSH);
 CHECK(!mode_from_app && switch_state==SW_ICE_FLUSH && eData.mode_owner_value==0,8);
 physical_switch_ready=0; mode_restore_owner(); mode_observe_switch(SW_COOL);
 CHECK(!mode_from_app && switch_state==SW_COOL,9);
 program=SETTING; CHECK(!mode_select_app(SW_COOL),10); program=IDLE;
 eData.pin_locked=1; CHECK(!mode_select_app(SW_COOL),11);eData.pin_locked=0;
 eData.shutdown=1; CHECK(!mode_select_app(SW_COOL),12);eData.shutdown=0;
 password_gate=1; CHECK(!mode_select_app(SW_COOL),13);password_gate=0;
 CHECK(!mode_select_app(2),14);
 mode_observe_switch(SW_IDLE);CHECK(!mode_select_app(SW_COOL),15);mode_observe_switch(SW_COOL);
 CHECK(mode_select_app(SW_ICE_FLUSH),16);
 physical_switch_ready=0;mode_from_app=0;mode_restore_owner();mode_observe_switch(SW_COOL);
 CHECK(mode_from_app && switch_state==SW_ICE_FLUSH,17);
 eData.mode_owner_inverse=0;mode_restore_owner();CHECK(!mode_from_app,18);
 return 0;
}

int main(void) { return run_tests(); }
"""
with tempfile.TemporaryDirectory() as directory:
    path=Path(directory); (path/'test.c').write_text(prefix+source[start:end]+checks,encoding='utf-8')
    subprocess.run([os.environ.get('CC','gcc'),'-std=c99','-Wall','-Wextra','-Werror',str(path/'test.c'),'-o',str(path/'test')],check=True)
    subprocess.run([str(path/'test')],check=True)
print('PASS: 18 mode ownership, restart, debounce, lock and idempotency assertions')
