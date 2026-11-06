#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define xdata
#define code
#define eeprom_init_val 2
#define TRY_TIME_UNIT 6
static struct {
    uint8_t init;
    uint16_t try_time;
    uint8_t check_box[7], shutdown;
    uint32_t tried_time;
    uint8_t protected_settings[100], usage_mark, usage_token[16];
} eData;
static uint16_t shutdown_timeout;
#define shutdown_timeout_refresh() (shutdown_timeout=0)
static uint8_t saved[sizeof(eData)];
static unsigned commits;
static int fail_write;
static void AP_EEPROM_Commit(void) {
    commits++;memcpy(saved,&eData,sizeof(eData));if(fail_write)saved[12]^=0x80;
}
#define USAGE_READ_SAVED(i) saved[i]
#include "../../MS51_code/260502 add PIN/usage_renew.c"
static uint8_t packet[217];
static void setup(void) {
    memset(&eData,0x55,sizeof(eData));eData.init=2;eData.try_time=10;
    eData.check_box[6]=0;eData.tried_time=0;eData.shutdown=1;
    eData.usage_mark=0xff;shutdown_timeout=123;commits=0;fail_write=0;
    memset(packet,0,sizeof(packet));memcpy(packet,"\xe3TRY1",5);packet[6]=12;packet[7]=0;
    for(unsigned i=8;i<24;i++)packet[i]=(uint8_t)i;
}
int main(void) {
    uint8_t before[sizeof(eData)],expected[sizeof(eData)];
    setup();memcpy(before,&eData,sizeof(eData));
    assert(usage_renew_apply(packet)==1 && commits==1 && usage_reset_required==1);
    assert(eData.try_time==12 && !eData.shutdown && !eData.tried_time && !shutdown_timeout);
    memcpy(expected,&eData,sizeof(eData));
    for(unsigned i=0;i<100;i++)assert(eData.protected_settings[i]==0x55);
    assert(memcmp(saved,&eData,sizeof(eData))==0);
    assert(usage_renew_apply(packet)==2 && commits==1 && usage_reset_required==0);
    // Simulate a reboot and elapsed time: repeating the same token preserves it.
    memcpy(&eData,saved,sizeof(eData));eData.tried_time=3;shutdown_timeout=50;
    assert(usage_renew_apply(packet)==2 && eData.tried_time==3 && shutdown_timeout==50 && commits==1);
    // A new grant while running doesn't restart the normal operation loop.
    packet[8]++;packet[7]=1;packet[6]=7;
    assert(usage_renew_apply(packet)==1 && eData.try_time==7 && eData.check_box[6]==1);
    assert(!eData.tried_time && !usage_reset_required && commits==2);
    setup();memcpy(before,&eData,sizeof(eData));fail_write=1;
    assert(!usage_renew_apply(packet) && !usage_reset_required);
    assert(memcmp(before,&eData,sizeof(eData))==0);
    // Zero permanently removes the TRY limit; it also releases an expired TRY.
    setup();packet[6]=0;
    assert(usage_renew_apply(packet)==1 && commits==1 && usage_reset_required==1);
    assert(!eData.try_time && !eData.shutdown && !eData.tried_time && !shutdown_timeout);
    for(unsigned i=0;i<100;i++)assert(eData.protected_settings[i]==0x55);
    memset(&eData,0,sizeof(eData));memcpy(&eData,saved,sizeof(eData));
    assert(!eData.try_time && !eData.shutdown);
    assert(usage_renew_apply(packet)==2 && commits==1 && !usage_reset_required);
    // Unlimited can be changed to a finite period with a different receipt.
    packet[8]++;packet[6]=1;
    assert(usage_renew_apply(packet)==1 && eData.try_time==1 && !usage_reset_required && commits==2);
    // Removing the limit while running preserves the operation, without reset.
    setup();eData.shutdown=0;eData.tried_time=50;packet[6]=0;
    assert(usage_renew_apply(packet)==1 && !eData.try_time && !eData.tried_time && !usage_reset_required);
    setup();packet[6]=0;fail_write=1;memcpy(before,&eData,sizeof(eData));
    assert(!usage_renew_apply(packet) && memcmp(before,&eData,sizeof(eData))==0);
    setup();packet[5]=3;packet[6]=232;assert(!usage_renew_apply(packet) && !commits);
    setup();packet[7]=2;assert(!usage_renew_apply(packet) && !commits);
    setup();packet[216]=1;assert(!usage_renew_apply(packet) && !commits);
    setup();packet[1]='X';assert(!usage_renew_apply(packet) && !commits);
    setup();memset(packet+8,0,16);assert(!usage_renew_apply(packet) && !commits);
    setup();memset(packet+8,255,16);assert(!usage_renew_apply(packet) && !commits);
    puts("PASS: finite/unlimited TRY grants, expired/running, reboot receipt, settings preservation, EEPROM readback failure, bounds and malformed envelopes");
    return 0;
}
