#include "ms51_recovery.h"
#include <stddef.h>
#include <stdatomic.h>
#include <string.h>
#include "esp_crc.h"
#include "esp_partition.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

/* Separate from upload A/B slots: never overwrite a recovery snapshot while
 * pending, even if a new HEX is uploaded. Existing partition offsets stay put. */
#define SAFE_SIZE 0x11000u
#define OLD_OFFSET 0x1000u
#define NEW_OFFSET 0x9000u
#define MAGIC 0x524D3531u
#define ARMED 0x3141524Du
#define ROLLBACK 0x31424143u
#define DONE 0x31444F4Eu
typedef struct {
    uint32_t magic, version, sequence, size, old_crc, new_crc, source_crc;
    uint8_t config[MS51_CONFIG_SIZE];
    uint8_t reserved[3];
    uint32_t header_crc;
    uint32_t armed, rollback, done;
} journal_t;
static const esp_partition_t *s_partition;
static journal_t s_header;
static atomic_bool s_pending, s_available;
static atomic_uint s_sequence, s_source_crc, s_state;
enum { IDLE, WRITING, RESTORING, VERIFIED, RESTORED, BLOCKED };

bool ms51_recovery_pending(void) { return atomic_load(&s_pending); }
bool ms51_recovery_available(void) { return atomic_load(&s_available); }
uint32_t ms51_recovery_sequence(void) { return atomic_load(&s_sequence); }
uint32_t ms51_recovery_source_crc(void) { return atomic_load(&s_source_crc); }
const char *ms51_recovery_state(void) {
    static const char *names[]={"idle","writing","restoring","verified","restored","blocked"};
    return names[atomic_load(&s_state)];
}
static uint32_t header_crc(const journal_t *h) {
    return esp_crc32_le(0,(const uint8_t *)h,offsetof(journal_t,header_crc));
}
static bool valid_header(const journal_t *h) {
    return h->magic==MAGIC && h->version==1 && h->sequence && h->size &&
        h->size<=MS51_FLASH_SIZE && !(h->size%MS51_FLASH_PAGE_SIZE) &&
        h->header_crc==header_crc(h);
}
static esp_err_t read_crc(size_t offset,size_t size,uint32_t *out) {
    uint8_t data[MS51_FLASH_PAGE_SIZE];uint32_t crc=0;
    for(size_t pos=0;pos<size;pos+=sizeof(data)) {
        esp_err_t e=esp_partition_read(s_partition,offset+pos,data,sizeof(data));
        if(e!=ESP_OK)return e;
        crc=esp_crc32_le(crc,data,sizeof(data));vTaskDelay(1);
    }
    *out=crc;return ESP_OK;
}
static esp_err_t marker(size_t offset,uint32_t value) {
    esp_err_t e=esp_partition_write(s_partition,offset,&value,sizeof(value));
    uint32_t actual=0;
    if(e==ESP_OK)e=esp_partition_read(s_partition,offset,&actual,sizeof(actual));
    return e!=ESP_OK?e:(actual==value?ESP_OK:ESP_ERR_INVALID_CRC);
}
esp_err_t ms51_recovery_init(void) {
    s_partition=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_ANY,"ms51safe");
    atomic_store(&s_available,s_partition && s_partition->size>=SAFE_SIZE);
    atomic_store(&s_pending,false);atomic_store(&s_state,IDLE);
    atomic_store(&s_sequence,0);atomic_store(&s_source_crc,0);
    if(!ms51_recovery_available())return ESP_ERR_NOT_FOUND;
    esp_err_t e=esp_partition_read(s_partition,0,&s_header,sizeof(s_header));
    if(e!=ESP_OK) { atomic_store(&s_pending,true);atomic_store(&s_state,BLOCKED);return e; }
    /* Target is untouched until ARMED is persisted and read back. Torn ARMED
     * is treated as pending; incomplete preparation with erased ARMED is safe. */
    if(s_header.armed==UINT32_MAX)return ESP_OK;
    if(!valid_header(&s_header)) {
        atomic_store(&s_pending,true);atomic_store(&s_state,BLOCKED);return ESP_ERR_INVALID_CRC;
    }
    atomic_store(&s_sequence,s_header.sequence);atomic_store(&s_source_crc,s_header.source_crc);
    bool pending=s_header.done!=DONE;
    atomic_store(&s_pending,pending);
    atomic_store(&s_state,pending?(s_header.rollback==UINT32_MAX?WRITING:RESTORING):
        (s_header.rollback==UINT32_MAX?VERIFIED:RESTORED));
    return ESP_OK;
}
static uint32_t source_crc(const ms51_image_t *image) {
    uint8_t chunk[128];uint32_t crc=0;
    for(size_t pos=0;pos<MS51_IMAGE_COVERAGE_SIZE;pos+=sizeof(chunk)) {
        memset(chunk,0,sizeof(chunk));
        for(size_t i=0;i<sizeof(chunk)*8;i++) {
            size_t address=pos*8+i;
            if(ms51_image_address_is_covered(image,address))chunk[i>>3]|=1u<<(i&7);
        }
        crc=esp_crc32_le(crc,chunk,sizeof(chunk));
    }
    for(size_t pos=0;pos<MS51_IMAGE_MAX_SIZE;pos+=sizeof(chunk)) {
        memset(chunk,255,sizeof(chunk));
        for(size_t i=0;i<sizeof(chunk) && pos+i<image->size;i++)chunk[i]=image->data[pos+i];
        crc=esp_crc32_le(crc,chunk,sizeof(chunk));
    }
    return crc;
}
esp_err_t ms51_recovery_prepare(const ms51_image_t *image,bool full,size_t size,
    const uint8_t config[MS51_CONFIG_SIZE],const ms51_recovery_io_t *io) {
    if(!ms51_recovery_available() || ms51_recovery_pending())return ESP_ERR_INVALID_STATE;
    if(!image || !image->data || !image->size || image->size>size || !size ||
        size>MS51_FLASH_SIZE || size%MS51_FLASH_PAGE_SIZE)return ESP_ERR_INVALID_SIZE;
    uint32_t sequence=ms51_recovery_sequence()+1;if(!sequence)sequence=1;
    esp_err_t e=esp_partition_erase_range(s_partition,0,SAFE_SIZE);
    if(e!=ESP_OK)return e;
    journal_t h={.magic=MAGIC,.version=1,.sequence=sequence,.size=size,
        .source_crc=source_crc(image),.armed=UINT32_MAX,.rollback=UINT32_MAX,.done=UINT32_MAX};
    memcpy(h.config,config,MS51_CONFIG_SIZE);
    uint8_t old[128],check[128],next[128];
    for(size_t pos=0;pos<size;pos+=sizeof(old)) {
        e=io->read(pos,old,sizeof(old));if(e!=ESP_OK)return e;
        e=io->read(pos,check,sizeof(check));if(e!=ESP_OK)return e;
        if(memcmp(old,check,sizeof(old)))return ESP_ERR_INVALID_CRC;
        memcpy(next,old,sizeof(next));if(full)memset(next,255,sizeof(next));
        for(size_t i=0;i<sizeof(next);i++)
            if(ms51_image_address_is_covered(image,pos+i))next[i]=image->data[pos+i];
        e=esp_partition_write(s_partition,OLD_OFFSET+pos,old,sizeof(old));if(e!=ESP_OK)return e;
        e=esp_partition_write(s_partition,NEW_OFFSET+pos,next,sizeof(next));if(e!=ESP_OK)return e;
        h.old_crc=esp_crc32_le(h.old_crc,old,sizeof(old));
        h.new_crc=esp_crc32_le(h.new_crc,next,sizeof(next));vTaskDelay(1);
    }
    uint32_t crc;
    e=read_crc(OLD_OFFSET,size,&crc);if(e!=ESP_OK || crc!=h.old_crc)return ESP_ERR_INVALID_CRC;
    e=read_crc(NEW_OFFSET,size,&crc);if(e!=ESP_OK || crc!=h.new_crc)return ESP_ERR_INVALID_CRC;
    h.header_crc=header_crc(&h);
    e=esp_partition_write(s_partition,0,&h,sizeof(h));if(e!=ESP_OK)return e;
    journal_t readback;
    e=esp_partition_read(s_partition,0,&readback,sizeof(readback));
    if(e!=ESP_OK || memcmp(&h,&readback,sizeof(h)))return ESP_ERR_INVALID_CRC;
    /* Even an uncertain marker write must keep reset asserted. */
    atomic_store(&s_pending,true);atomic_store(&s_state,WRITING);
    s_header=h;atomic_store(&s_sequence,sequence);atomic_store(&s_source_crc,h.source_crc);
    e=marker(offsetof(journal_t,armed),ARMED);
    if(e==ESP_OK)s_header.armed=ARMED;
    return e;
}
static esp_err_t restore_direction(void) {
    esp_err_t e=marker(offsetof(journal_t,rollback),ROLLBACK);
    /* Partial flag always means rollback on reboot; never resume forward. */
    s_header.rollback=ROLLBACK;atomic_store(&s_state,RESTORING);return e;
}
static esp_err_t program_snapshot(size_t offset,const ms51_recovery_io_t *io) {
    uint8_t wanted[128],actual[128];
    for(size_t pos=0;pos<s_header.size;pos+=sizeof(wanted)) {
        esp_err_t e=esp_partition_read(s_partition,offset+pos,wanted,sizeof(wanted));if(e!=ESP_OK)return e;
        e=io->read(pos,actual,sizeof(actual));if(e!=ESP_OK)return e;
        if(!memcmp(wanted,actual,sizeof(wanted))) { vTaskDelay(1);continue; }
        bool matched=false;
        for(unsigned attempt=0;attempt<3;attempt++) {
            e=io->erase(pos);if(e!=ESP_OK)return e;
            e=io->write(pos,wanted,sizeof(wanted));if(e!=ESP_OK)return e;
            e=io->read(pos,actual,sizeof(actual));if(e!=ESP_OK)return e;
            if(!memcmp(wanted,actual,sizeof(wanted))) { matched=true;break; }
        }
        if(!matched)return ESP_ERR_INVALID_CRC;
        vTaskDelay(1);
    }
    /* Full compare while still in ICP, before publishing DONE or releasing reset. */
    for(size_t pos=0;pos<s_header.size;pos+=sizeof(wanted)) {
        esp_err_t e=esp_partition_read(s_partition,offset+pos,wanted,sizeof(wanted));if(e!=ESP_OK)return e;
        e=io->read(pos,actual,sizeof(actual));if(e!=ESP_OK)return e;
        if(memcmp(wanted,actual,sizeof(wanted)))return ESP_ERR_INVALID_CRC;
        vTaskDelay(1);
    }
    return ESP_OK;
}
esp_err_t ms51_recovery_run(size_t size,const uint8_t config[MS51_CONFIG_SIZE],const ms51_recovery_io_t *io) {
    if(!ms51_recovery_pending() || !valid_header(&s_header))return ESP_ERR_INVALID_STATE;
    if(size!=s_header.size || memcmp(config,s_header.config,MS51_CONFIG_SIZE))return ESP_ERR_INVALID_STATE;
    if(s_header.armed!=ARMED) {
        esp_err_t armed_error=marker(offsetof(journal_t,armed),ARMED);
        if(armed_error!=ESP_OK)return armed_error;
        s_header.armed=ARMED;
    }
    uint32_t crc;
    bool rollback=s_header.rollback!=UINT32_MAX;
    if(rollback) {
        esp_err_t direction_error=restore_direction();
        if(direction_error!=ESP_OK)return direction_error;
    }
    size_t offset=rollback?OLD_OFFSET:NEW_OFFSET;
    esp_err_t e=read_crc(offset,size,&crc);
    if(e==ESP_OK && crc!=(rollback?s_header.old_crc:s_header.new_crc))e=ESP_ERR_INVALID_CRC;
    if(e==ESP_OK)e=program_snapshot(offset,io);
    if(e!=ESP_OK) {
        if(!rollback) { esp_err_t switch_error=restore_direction();if(switch_error!=ESP_OK)return switch_error; }
        return e;
    }
    e=marker(offsetof(journal_t,done),DONE);
    if(e==ESP_OK) {
        s_header.done=DONE;atomic_store(&s_state,rollback?RESTORED:VERIFIED);
        atomic_store(&s_pending,false);
    }
    return e;
}
