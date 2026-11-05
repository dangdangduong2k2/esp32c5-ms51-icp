/* Exercise the real programmer boundary with a fake ICP target. No device I/O. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../../main/ms51_programmer.c"

size_t image_test_allocated, image_test_limit = SIZE_MAX;

static ms51_icp_identity_t target;
static uint8_t config[5];
static bool opened, paused, pending, unstable, erase_fails;
static unsigned config_reads, erase_count, prepare_count, run_count;

esp_err_t ms51_operation_init(void) { return ESP_OK; }
esp_err_t ms51_operation_lock(TickType_t ticks) { (void)ticks;return ESP_OK; }
void ms51_operation_unlock(void) {}
esp_err_t ms51_debug_uart_pause_for_icp(void) { assert(!paused);paused=true;return ESP_OK; }
esp_err_t ms51_debug_uart_resume_after_icp(void) { paused=false;return ESP_OK; }
esp_err_t ms51_icp_open(ms51_icp_identity_t *id) { opened=true;*id=target;return ESP_OK; }
esp_err_t ms51_icp_reenter(ms51_icp_identity_t *id) { assert(opened);*id=target;return ESP_OK; }
bool ms51_icp_is_open(void) { return opened; }
void ms51_icp_close(void) { opened=false; }
void ms51_icp_set_recovery_guard(bool value) { (void)value; }
esp_err_t ms51_icp_hold_target(void) { return ESP_OK; }
esp_err_t ms51_icp_check_identity(void) { return ESP_OK; }
esp_err_t ms51_icp_reset_target(void) { return ESP_OK; }
esp_err_t ms51_icp_read_flash(uint32_t address,void *buffer,size_t length) {
    assert(opened);
    if(address==MS51_CONFIG_ADDRESS) {
        assert(length==sizeof(config));memcpy(buffer,config,length);
        if(unstable && ++config_reads%2==0)((uint8_t *)buffer)[0]^=2;
    } else { assert((config[0]&2)!=0);memset(buffer,0xff,length); }
    return ESP_OK;
}
esp_err_t ms51_icp_write_flash(uint32_t address,const void *data,size_t length) {
    (void)address;(void)data;(void)length;assert(0);return ESP_ERR_INVALID_STATE;
}
esp_err_t ms51_icp_page_erase(uint32_t address) { (void)address;assert(0);return ESP_ERR_INVALID_STATE; }
esp_err_t ms51_icp_mass_erase(void) {
    assert(opened);erase_count++;
    if(erase_fails)return ESP_ERR_INVALID_RESPONSE;
    memset(config,255,sizeof(config));target.company_id=MS51_NUVOTON_CID;return ESP_OK;
}
bool ms51_recovery_pending(void) { return pending; }
bool ms51_recovery_available(void) { return true; }
const char *ms51_recovery_state(void) { return "idle"; }
esp_err_t ms51_recovery_init(void) { return ESP_OK; }
esp_err_t ms51_recovery_prepare(const ms51_image_t *image,bool full,size_t size,const uint8_t *cfg,const ms51_recovery_io_t *io) {
    (void)image;(void)full;(void)size;(void)cfg;(void)io;prepare_count++;return ESP_OK;
}
esp_err_t ms51_recovery_run(size_t size,const uint8_t *cfg,const ms51_recovery_io_t *io) {
    (void)size;(void)cfg;(void)io;run_count++;return ESP_OK;
}

static void reset_target(bool locked,uint8_t cid) {
    target=(ms51_icp_identity_t){MS51FC0AE_DEVICE_ID,MS51FC0AE_PRODUCT_ID,cid};
    memset(config,255,sizeof(config));if(locked)config[0]&=(uint8_t)~2;
    opened=paused=pending=unstable=erase_fails=false;
    config_reads=erase_count=prepare_count=run_count=0;
    assert(!s_uart_handoff_active);
}
static void released(void) { assert(!opened && !paused && !s_uart_handoff_active); }
int main(void) {
    ms51_device_info_t info;uint8_t data=0x42,readback;
    reset_target(true,255);
    assert(ms51_programmer_get_info(&info)==ESP_OK && info.locked && info.config[0]==0xfd);released();
    assert(ms51_programmer_try_get_info(&info)==ESP_OK && info.locked);released();
    assert(ms51_programmer_program(&data,1,true)==ESP_ERR_INVALID_STATE);released();
    assert(ms51_programmer_verify(&data,1)==ESP_ERR_INVALID_STATE);released();
    assert(ms51_programmer_read_aprom(0,&readback,1)==ESP_ERR_INVALID_STATE);released();
    assert(!erase_count && !prepare_count && !run_count);
    assert(ms51_programmer_mass_erase()==ESP_OK && erase_count==1);released();
    assert(ms51_programmer_get_info(&info)==ESP_OK && !info.locked);released();
    assert(ms51_programmer_program(&data,1,true)==ESP_OK && prepare_count==1 && run_count==1);released();

    reset_target(false,255);
    assert(ms51_programmer_get_info(&info)==ESP_ERR_INVALID_RESPONSE);released();
    assert(ms51_programmer_mass_erase()==ESP_ERR_INVALID_RESPONSE && !erase_count);released();
    reset_target(true,0x10);
    assert(ms51_programmer_mass_erase()==ESP_ERR_INVALID_RESPONSE && !erase_count);released();
    reset_target(true,255);unstable=true;
    assert(ms51_programmer_mass_erase()==ESP_ERR_INVALID_RESPONSE && !erase_count);released();
    reset_target(true,255);target.device_id=0x1234;
    assert(ms51_programmer_mass_erase()==ESP_ERR_NOT_FOUND && !erase_count);released();
    reset_target(true,255);pending=true;
    assert(ms51_programmer_mass_erase()==ESP_ERR_INVALID_STATE && !erase_count);released();
    assert(ms51_programmer_get_info(&info)==ESP_ERR_INVALID_STATE);released();
    reset_target(true,255);erase_fails=true;
    assert(ms51_programmer_mass_erase()==ESP_ERR_INVALID_RESPONSE && erase_count==1);released();
    assert(config[0]==0xfd && !prepare_count);
    reset_target(true,MS51_NUVOTON_CID);
    assert(ms51_programmer_get_info(&info)==ESP_OK && info.locked);released();
    assert(ms51_programmer_program(&data,1,true)==ESP_ERR_INVALID_STATE);released();
    puts("PASS: locked target diagnosis, no implicit erase/program/read, explicit unlock and verify, unstable CONFIG, missing CID, wrong chip, pending recovery, failed erase, UART cleanup");
    return 0;
}
