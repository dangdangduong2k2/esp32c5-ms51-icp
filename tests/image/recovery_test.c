#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../main/ms51_recovery.c"

size_t image_test_allocated, image_test_limit=40960;
static uint8_t flash[SAFE_SIZE],target[512],original[512],desired[512];
static esp_partition_t partition={SAFE_SIZE};
static bool disconnected;
static int cut_at=-1, mutation, fail_at=-1;
static jmp_buf power_loss;
static int point(void) { return mutation++; }
static void cut(int step) { if(step==cut_at)longjmp(power_loss,1); }
const esp_partition_t *esp_partition_find_first(int a,int b,const char *label) {
    (void)a;(void)b;assert(!strcmp(label,"ms51safe"));return &partition;
}
esp_err_t esp_partition_read(const esp_partition_t *p,size_t pos,void *out,size_t n) {
    assert(p==&partition && pos+n<=sizeof(flash));memcpy(out,flash+pos,n);return ESP_OK;
}
esp_err_t esp_partition_write(const esp_partition_t *p,size_t pos,const void *data,size_t n) {
    assert(p==&partition && pos+n<=sizeof(flash));int step=point();
    size_t count=(step==cut_at || step==fail_at)?n/2:n;
    for(size_t i=0;i<count;i++) {uint8_t b=((const uint8_t *)data)[i];assert((flash[pos+i]&b)==b);flash[pos+i]&=b;}
    cut(step);return step==fail_at?ESP_ERR_INVALID_STATE:ESP_OK;
}
esp_err_t esp_partition_erase_range(const esp_partition_t *p,size_t pos,size_t n) {
    assert(p==&partition && pos+n<=sizeof(flash));int step=point();
    memset(flash+pos,255,step==cut_at?n/2:n);cut(step);return ESP_OK;
}
static esp_err_t target_read(uint32_t pos,void *out,size_t n) {
    if(disconnected)return ESP_ERR_INVALID_RESPONSE;
    assert(pos+n<=sizeof(target));memcpy(out,target+pos,n);return ESP_OK;
}
static esp_err_t target_erase(uint32_t pos) {
    if(disconnected)return ESP_ERR_INVALID_RESPONSE;
    assert(pos+128<=sizeof(target));int step=point();
    memset(target+pos,255,step==cut_at?64:128);cut(step);
    if(step==fail_at) { disconnected=true;return ESP_ERR_INVALID_RESPONSE; }
    return ESP_OK;
}
static esp_err_t target_write(uint32_t pos,const void *data,size_t n) {
    if(disconnected)return ESP_ERR_INVALID_RESPONSE;
    assert(pos+n<=sizeof(target));int step=point();
    size_t count=(step==cut_at || step==fail_at)?n/2:n;
    for(size_t i=0;i<count;i++)target[pos+i]&=((const uint8_t *)data)[i];
    cut(step);if(step==fail_at) {disconnected=true;return ESP_ERR_INVALID_RESPONSE;}return ESP_OK;
}
static const ms51_recovery_io_t io={target_read,target_erase,target_write};
static uint8_t config[5]={0xFF,7,0xFF,0xFF,0xFF};
static uint8_t coverage[64];
static ms51_image_t image;
static void fresh(void) {
    memset(flash,255,sizeof(flash));memcpy(target,original,sizeof(target));
    mutation=0;fail_at=-1;cut_at=-1;disconnected=false;
    assert(ms51_recovery_init()==ESP_OK && !ms51_recovery_pending());
}
static void prepare(void) { assert(ms51_recovery_prepare(&image,false,sizeof(target),config,&io)==ESP_OK); }
static void finish_recovery(void) {
    disconnected=false;cut_at=-1;fail_at=-1;
    assert(ms51_recovery_init()==ESP_OK);
    for(int retry=0;retry<4 && ms51_recovery_pending();retry++)
        (void)ms51_recovery_run(sizeof(target),config,&io);
    assert(!ms51_recovery_pending());
    assert(!memcmp(target,original,sizeof(target)) || !memcmp(target,desired,sizeof(target)));
}
int main(void) {
    for(size_t i=0;i<sizeof(original);i++)original[i]=(uint8_t)(i*29+11);
    memcpy(desired,original,sizeof(desired));memset(coverage,0,sizeof(coverage));
    /* Sparse records across two pages; preserve unrelated bytes and tail settings. */
    for(size_t i=3;i<140;i++) {desired[i]^=0x5A;coverage[i>>3]|=1u<<(i&7);}
    image=(ms51_image_t){.data=desired,.coverage=coverage,.size=140,.covered_size=137};
    fresh();prepare();assert(ms51_recovery_pending());
    assert(ms51_recovery_prepare(&image,false,sizeof(target),config,&io)==ESP_ERR_INVALID_STATE);
    assert(ms51_recovery_run(sizeof(target),config,&io)==ESP_OK);
    assert(!memcmp(target,desired,sizeof(target)) && !strcmp(ms51_recovery_state(),"verified"));
    int total=mutation;
    /* Reset C5 in the middle of every flash write/erase, including ARMED and DONE. */
    for(int point=0;point<total;point++) {
        fresh();cut_at=point;
        if(!setjmp(power_loss)) {prepare();(void)ms51_recovery_run(sizeof(target),config,&io);}
        finish_recovery();
    }
    /* Lost wire at each target erase/write: rollback must survive reboot too. */
    fresh();prepare();int first_target=mutation;
    for(int point=first_target;point<total-1;point++) {
        fresh();prepare();fail_at=point;
        (void)ms51_recovery_run(sizeof(target),config,&io);
        if(disconnected) {
            assert(ms51_recovery_pending());
            assert(ms51_recovery_run(sizeof(target),config,&io)!=ESP_OK);
            assert(ms51_recovery_pending());
        }
        finish_recovery();assert(!memcmp(target,original,sizeof(target)));
    }
    /* A power cut during rollback (including its direction flag and DONE). */
    fresh();prepare();fail_at=first_target+1;(void)ms51_recovery_run(sizeof(target),config,&io);
    uint8_t rollback_flash[SAFE_SIZE],partial_target[512];
    memcpy(rollback_flash,flash,sizeof(flash));memcpy(partial_target,target,sizeof(target));
    for(int point=0;point<8;point++) {
        memcpy(flash,rollback_flash,sizeof(flash));memcpy(target,partial_target,sizeof(target));
        disconnected=false;fail_at=-1;cut_at=point;mutation=0;assert(ms51_recovery_init()==ESP_OK);
        if(!setjmp(power_loss))(void)ms51_recovery_run(sizeof(target),config,&io);
        finish_recovery();assert(!memcmp(target,original,sizeof(target)));
    }
    /* Corrupt candidate: restore original. Corrupt both snapshots: stay held. */
    fresh();prepare();flash[NEW_OFFSET+7]^=1;
    assert(ms51_recovery_run(sizeof(target),config,&io)!=ESP_OK);
    finish_recovery();assert(!memcmp(target,original,sizeof(target)));
    fresh();prepare();flash[NEW_OFFSET+7]^=1;flash[OLD_OFFSET+8]^=1;
    assert(ms51_recovery_run(sizeof(target),config,&io)!=ESP_OK);
    assert(ms51_recovery_run(sizeof(target),config,&io)!=ESP_OK && ms51_recovery_pending());
    /* Configuration mismatch and invalid journal cannot authorize writes. */
    fresh();prepare();uint8_t wrong[5]={0};int before=mutation;
    assert(ms51_recovery_run(sizeof(target),wrong,&io)!=ESP_OK && mutation==before);
    flash[0]^=1;assert(ms51_recovery_init()!=ESP_OK && ms51_recovery_pending());
    assert(ms51_recovery_run(sizeof(target),config,&io)!=ESP_OK);
    /* Every failed preparation leaves target byte-identical. */
    for(int point=1;point<first_target;point++) {
        fresh();fail_at=point;
        assert(ms51_recovery_prepare(&image,false,sizeof(target),config,&io)!=ESP_OK);
        assert(!memcmp(target,original,sizeof(target)));
        finish_recovery();
    }
    puts("PASS recovery: sparse preservation, independent snapshots, power cuts at every forward/rollback flash boundary, lost wire/reconnect, torn journal, failed snapshot, corruption, config mismatch, no mixed image released");
}
