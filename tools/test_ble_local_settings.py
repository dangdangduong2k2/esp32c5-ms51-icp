"""Compile the actual MS51 local-settings handlers with fake EEPROM and PIN gate.

Set CC to a host C compiler. Set TEST_WORK to a directory on D: on Windows.
No physical controller, credentials, network or flash writes are used.
"""
from pathlib import Path
import os,re,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'MS51_code/260502 add PIN/telemetry.c').read_text(encoding='utf-8')
def function(name):
    match=re.search(r'^static[^\n]*\b'+name+r'\(',source,re.M)
    assert match,name
    start=match.start();body=source.index('{',start);depth=1;end=body+1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]+'\n'
prefix=r'''
#include <stdint.h>
#include <string.h>
#include <assert.h>
#include <stdio.h>
#define xdata
#define TELEMETRY_CONFIG_SIZE 217
#define eeprom_init_val 2
static uint8_t eData[217],telemetry_rx_payload[217],gate;
static int commits;
static uint8_t password_remote_state(void){return gate;}
static void AP_EEPROM_Commit(void){commits++;}
'''
body=r'''
static void put16(uint8_t *b,unsigned at,unsigned n){b[at]=n>>8;b[at+1]=n;}
static void put32(uint8_t *b,unsigned at,unsigned n){b[at]=n>>24;b[at+1]=n>>16;b[at+2]=n>>8;b[at+3]=n;}
static void reset(void){
 memset(eData,0,217);eData[0]=2;
 for(int i=0;i<4;i++){put32(eData,1+i*4,5);put16(eData,0xab+i*2,1);put16(eData,0xb7+i*2,999);}
 eData[0x11]=70;eData[0x12]=eData[0xc4]=50;put16(eData,0x22,10);
 put16(eData,0xbf,90);put16(eData,0xc1,90);
 eData[0xd4]=1;put16(eData,0xc5,123);put16(eData,0xc7,24);put16(eData,0xcc,987);put16(eData,0xd0,10);eData[0xd6]=3;
 put16(eData,0xc9,59);eData[0xcf]=1;eData[0xd7]=1;
 memcpy(telemetry_rx_payload,eData,217);commits=gate=0;
}
int main(void){
 uint8_t before[217];reset();memcpy(before,eData,217);
 put32(telemetry_rx_payload,1,7);memset(telemetry_rx_payload+0xc5,0,20);
 telemetry_rx_payload[0x18]=9; /* admin field stays unchanged */
 assert(telemetry_apply_local_settings());assert(commits==1);assert(telemetry_read_u32_be(eData+1)==7);
 for(int i=0;i<217;i++)if(!(i>=1&&i<=4))assert(eData[i]==before[i]);
 reset();gate=1;memcpy(before,eData,217);put32(telemetry_rx_payload,1,7);
 assert(!telemetry_apply_local_settings());assert(!commits && !memcmp(before,eData,217));
 reset();put32(telemetry_rx_payload,1,1000);memcpy(before,eData,217);
 assert(!telemetry_apply_local_settings());assert(!commits && !memcmp(before,eData,217));
 reset();put16(eData,0xb7,6);put32(telemetry_rx_payload,1,7);memcpy(before,eData,217);
 assert(!telemetry_apply_local_settings());assert(!commits && !memcmp(before,eData,217));
 reset();eData[0x29]=eData[0x2a]=1;telemetry_rx_payload[0x17]=telemetry_rx_payload[0x1c]=1;
 assert(telemetry_apply_local_settings());assert(eData[0x17]==1 && eData[0x1c]==1);
 reset();telemetry_rx_payload[0x1d]=1;assert(!telemetry_apply_local_settings());assert(!commits);
 reset();eData[0x2b]=1;telemetry_rx_payload[0x1d]=1;assert(telemetry_apply_local_settings());assert(eData[0x1d]==1);
 for(int flags=0;flags<64;flags++)for(int mode=0;mode<2;mode++){
  reset();for(int i=0;i<5;i++)eData[0x27+i]=(flags>>i)&1;
  eData[0xc3]=(flags>>5)&1;eData[0x17]=mode;
  assert(telemetry_local_editable(0x13)==((flags&1)!=0));
  assert(telemetry_local_editable(0x14)==((flags&2)!=0));
  assert(telemetry_local_editable(0x17)==((flags&4)!=0));
  assert(telemetry_local_editable(0x1c)==((flags&8)!=0));
  assert(telemetry_local_editable(0x1d)==((flags&16)!=0));
  assert(telemetry_local_editable(0x12)==((flags&32)!=0 && ((flags&4)!=0 || mode==1)));
  assert(telemetry_local_editable(0xc4)==((flags&32)!=0 && ((flags&4)!=0 || mode==0)));
  assert(!telemetry_local_editable(0x27) && !telemetry_local_editable(0xc5));
 }
 reset();telemetry_rx_payload[0x13]=1;put32(telemetry_rx_payload,1,7);memcpy(before,eData,217);
 assert(!telemetry_apply_local_settings());assert(!commits && !memcmp(before,eData,217));
 reset();telemetry_rx_payload[0x12]=91;assert(!telemetry_apply_local_settings());assert(!commits);
 puts("PASS: actual MS51 handlers preserve passwords/counters, enforce PIN gate, physical flags and limits, reject atomically.");return 0;
}
'''
functions=['telemetry_read_u16_be','telemetry_read_u32_be','telemetry_value_in_range_u16',
           'telemetry_config_blob_is_valid','telemetry_local_editable','telemetry_apply_local_settings']
work=os.environ.get('TEST_WORK')
with tempfile.TemporaryDirectory(dir=work,prefix='ble-native-') as tmp:
    path=Path(tmp);c=path/'test.c';exe=path/('test.exe' if os.name=='nt' else 'test')
    c.write_text(prefix+''.join(function(n) for n in functions)+body,encoding='utf-8')
    subprocess.run([os.environ.get('CC','gcc'),'-std=c99',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
