"""Exercise the actual MS51 PIN handlers with stubbed display/EEPROM hardware."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'MS51_code/260502 add PIN/main.c').read_text(encoding='utf-8')
def function(name):
    start=s.index(name+'(');start=s.rfind('\n',0,start)+1
    body=s.index('{',start);depth=1;i=body+1
    while depth:
        depth+=(s[i]=='{')-(s[i]=='}');i+=1
    return s[start:i]+'\n'
prefix='''#include <stdint.h>
#include <assert.h>
enum {IDLE,PASSWORD_CHECK,PASSWORD_CHANGE,PASSWORD_BACKUP};
struct {uint8_t pin_enable,pin_locked,pin_backup_used,pin_request,pin_wrong_limit; uint16_t pin_password,pin_backup_password,pin_day_count;} eData;
uint8_t program,program_init,password_gate,password_change_enable,password_wrong_count;
uint16_t password_para;int commits,ticks;int btnTotal;
void lock_timeout_refresh(void){} void password_display_yes(void){} void password_display_no(void){}
void password_input_display_init(const char *text){(void)text;} void button_reset(int n){(void)n;}
void AP_EEPROM_Commit(void){commits++;} void switch_program(int p){program=p;program_init=0;}
void password_request_clear(void){eData.pin_request=0;} void password_period_tick_reset(void){ticks++;}
'''
checks='''
void reset(void){eData.pin_enable=1;eData.pin_locked=0;eData.pin_backup_used=0;eData.pin_request=1;eData.pin_wrong_limit=3;
eData.pin_password=123;eData.pin_backup_password=999;eData.pin_day_count=5;program=PASSWORD_CHECK;program_init=1;
password_gate=1;password_change_enable=0;password_wrong_count=0;commits=0;}
int main(void){
reset();assert(password_remote_state()==1);assert(password_remote_unlock(1,123)==0);
assert(program==IDLE&&!password_gate&&!eData.pin_request&&eData.pin_day_count==0&&commits==1);
assert(password_remote_unlock(1,123)==5&&commits==1);
reset();assert(password_remote_unlock(1,111)==3&&password_wrong_count==1&&commits==0);
assert(password_remote_unlock(1,111)==3&&password_wrong_count==2);
assert(password_remote_unlock(1,111)==3&&program==PASSWORD_BACKUP&&eData.pin_locked&&commits==1);
assert(password_remote_state()==2);assert(password_remote_unlock(1,999)==5);
assert(password_remote_unlock(2,999)==5);program_init=1;
assert(password_remote_unlock(2,111)==3&&eData.pin_locked);
assert(password_remote_unlock(2,999)==0&&eData.pin_backup_used&&!eData.pin_locked&&program==IDLE);
reset();eData.pin_locked=eData.pin_backup_used=1;program=PASSWORD_BACKUP;
assert(password_remote_state()==3&&password_remote_unlock(2,999)==6&&commits==0);
reset();password_gate=0;password_change_enable=1;assert(password_remote_state()==0&&password_remote_unlock(1,123)==5);
reset();eData.pin_enable=0;assert(password_remote_unlock(1,123)==5);
reset();assert(password_remote_unlock(0,123)==2&&password_remote_unlock(1,1000)==2&&commits==0);
reset();program_init=0;assert(password_remote_unlock(1,123)==5);
reset();password_para=123;password_submit_primary();assert(program==IDLE&&commits==1);
reset();eData.pin_locked=1;program=PASSWORD_BACKUP;password_para=999;password_submit_backup();assert(program==IDLE&&eData.pin_backup_used);
return 0;}
'''
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp);(p/'test.c').write_text(prefix+''.join(function(n) for n in ['password_submit_primary','password_submit_backup','password_remote_state','password_remote_unlock'])+checks)
    subprocess.run([os.getenv('CC','gcc'),'-std=c99','-Wall','-Wextra','-Werror',str(p/'test.c'),'-o',str(p/'test')],check=True)
    subprocess.run([str(p/'test')],check=True)
print('PASS: primary/backup PIN, shared physical validation, wrong-attempt limit, permanent lock, stale kind, invalid input and no duplicate unlock.')
