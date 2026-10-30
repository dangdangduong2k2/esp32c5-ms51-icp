#include "ms51_ble.h"
#include "ms51_ble_policy.h"
#include "ms51_presence.h"
#include "esp_log.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

/* UUID 51b10001-8c22-4f42-9a51-3844be1fe4e0; RX=0002, TX=0003. */
#define UUID(n) BLE_UUID128_INIT(0xe0,0xe4,0x1f,0xbe,0x44,0x38,0x51,0x9a,0x42,0x4f,0x22,0x8c,n,0x00,0xb1,0x51)
static const ble_uuid128_t service_uuid=UUID(1), rx_uuid=UUID(2), tx_uuid=UUID(3);
#define MAX_REQUEST 8192
#define MAX_RESPONSE 32768
static uint16_t tx_handle;
static volatile uint16_t connection=BLE_HS_CONN_HANDLE_NONE;
static volatile unsigned generation;
static volatile bool subscribed;
static uint8_t address_type;
static char incoming[MAX_REQUEST];
static unsigned incoming_size;
static QueueHandle_t jobs;
static SemaphoreHandle_t tx_done;
static volatile bool tx_confirmed;
typedef struct { char *json; unsigned generation; uint16_t conn; } job_t;
void ble_store_config_init(void);

static int access_rx(uint16_t conn,uint16_t attr,struct ble_gatt_access_ctxt *ctx,void *arg) {
    if(ctx->op!=BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_UNLIKELY;
    if(!subscribed || conn!=connection) return BLE_ATT_ERR_UNLIKELY;
    uint16_t count=OS_MBUF_PKTLEN(ctx->om);
    if(!count || count+incoming_size>=MAX_REQUEST) { incoming_size=0; return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN; }
    if(os_mbuf_copydata(ctx->om,0,count,incoming+incoming_size)) return BLE_ATT_ERR_UNLIKELY;
    incoming_size+=count;
    if(incoming[incoming_size-1]!='\n') return 0;
    incoming[incoming_size-1]=0;
    job_t job={.json=strdup(incoming),.generation=generation,.conn=conn};
    memset(incoming,0,incoming_size); incoming_size=0;
    if(!job.json) return BLE_ATT_ERR_INSUFFICIENT_RES;
    if(xQueueSend(jobs,&job,0)!=pdTRUE) { free(job.json); return BLE_ATT_ERR_INSUFFICIENT_RES; }
    return 0;
}
static int access_tx(uint16_t conn,uint16_t attr,struct ble_gatt_access_ctxt *ctx,void *arg) {
    return BLE_ATT_ERR_READ_NOT_PERMITTED;
}
static const struct ble_gatt_svc_def services[]={
    {.type=BLE_GATT_SVC_TYPE_PRIMARY,.uuid=&service_uuid.u,
     .characteristics=(struct ble_gatt_chr_def[]){
         {.uuid=&rx_uuid.u,.access_cb=access_rx,.flags=BLE_GATT_CHR_F_WRITE|BLE_GATT_CHR_F_WRITE_ENC,.min_key_size=16},
         {.uuid=&tx_uuid.u,.access_cb=access_tx,.val_handle=&tx_handle,.flags=BLE_GATT_CHR_F_INDICATE}, {0}}}, {0}
};
static bool live(const job_t *job) { return job->generation==generation && job->conn==connection && subscribed; }
static void worker(void *unused) {
    job_t job;
    for(;;) {
        if(xQueueReceive(jobs,&job,portMAX_DELAY)!=pdTRUE) continue;
        cJSON *request=cJSON_Parse(job.json); memset(job.json,0,strlen(job.json)); free(job.json);
        if(!request || !live(&job)) { cJSON_Delete(request); continue; }
        cJSON *id=cJSON_GetObjectItemCaseSensitive(request,"id");
        cJSON *method=cJSON_GetObjectItemCaseSensitive(request,"method"), *path=cJSON_GetObjectItemCaseSensitive(request,"path");
        if(!cJSON_IsNumber(id) || !cJSON_IsString(method) || !cJSON_IsString(path)) { cJSON_Delete(request); continue; }
        cJSON *result=ms51_ble_request(method->valuestring,path->valuestring,cJSON_GetObjectItemCaseSensitive(request,"body"));
        cJSON *reply=cJSON_CreateObject(); cJSON_AddNumberToObject(reply,"id",id->valuedouble);
        cJSON_AddItemToObject(reply,"result",result); cJSON_Delete(request);
        char *json=cJSON_PrintUnformatted(reply); cJSON_Delete(reply);
        if(!json) continue;
        size_t length=strlen(json);
        if(length<MAX_RESPONSE) {
            /* Include a newline as the final fragment. Indications are acknowledged one by one. */
            for(size_t offset=0;offset<=length && live(&job);) {
                size_t chunk=ble_att_mtu(job.conn)-3;
                if(chunk>500) chunk=500;
                if(chunk>length+1-offset) chunk=length+1-offset;
                char data[500]; memcpy(data,json+offset,chunk);
                if(offset+chunk==length+1) data[chunk-1]='\n';
                xSemaphoreTake(tx_done,0);
                tx_confirmed=false;
                struct os_mbuf *mb=ble_hs_mbuf_from_flat(data,chunk);
                if(!mb || ble_gatts_indicate_custom(job.conn,tx_handle,mb)!=0) break;
                if(xSemaphoreTake(tx_done,pdMS_TO_TICKS(5000))!=pdTRUE || !tx_confirmed) break;
                offset+=chunk;
            }
        }
        memset(json,0,length); free(json);
    }
}
static void advertise(void);
static int gap_event(struct ble_gap_event *event,void *arg) {
    switch(event->type) {
    case BLE_GAP_EVENT_CONNECT:
        incoming_size=0; subscribed=false; generation++;
        if(event->connect.status==0) {
            connection=event->connect.conn_handle;
            ble_gap_security_initiate(connection);
        } else advertise();
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        connection=BLE_HS_CONN_HANDLE_NONE; generation++; subscribed=false; incoming_size=0;
        xSemaphoreGive(tx_done); advertise(); break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        if(event->subscribe.attr_handle==tx_handle) subscribed=event->subscribe.cur_indicate;
        break;
    case BLE_GAP_EVENT_NOTIFY_TX:
        if(event->notify_tx.conn_handle==connection && event->notify_tx.indication && event->notify_tx.status!=0) {
            tx_confirmed=event->notify_tx.status==BLE_HS_EDONE;
            xSemaphoreGive(tx_done);
        }
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE: advertise(); break;
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        struct ble_gap_conn_desc desc;
        if(ble_gap_conn_find(event->repeat_pairing.conn_handle,&desc)==0)
            ble_store_util_delete_peer(&desc.peer_id_addr);
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    default: break;
    }
    return 0;
}
static void advertise(void) {
    struct ble_hs_adv_fields fields={0};
    fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128=(ble_uuid128_t*)&service_uuid; fields.num_uuids128=1; fields.uuids128_is_complete=1;
    if(ble_gap_adv_set_fields(&fields)) return;
    struct ble_hs_adv_fields scan={0}; const char *name=ble_svc_gap_device_name();
    scan.name=(uint8_t*)name; scan.name_len=strlen(name); scan.name_is_complete=1;
    ble_gap_adv_rsp_set_fields(&scan);
    struct ble_gap_adv_params params={.conn_mode=BLE_GAP_CONN_MODE_UND,.disc_mode=BLE_GAP_DISC_MODE_GEN};
    ble_gap_adv_start(address_type,NULL,BLE_HS_FOREVER,&params,gap_event,NULL);
}
static void sync_host(void) {
    if(ble_hs_util_ensure_addr(0)==0 && ble_hs_id_infer_auto(0,&address_type)==0) advertise();
}
static void host(void *unused) { nimble_port_run(); nimble_port_freertos_deinit(); }
esp_err_t ms51_ble_start(void) {
    jobs=xQueueCreate(1,sizeof(job_t)); tx_done=xSemaphoreCreateBinary();
    if(!jobs || !tx_done) return ESP_ERR_NO_MEM;
    esp_err_t error=nimble_port_init(); if(error!=ESP_OK) return error;
    ble_hs_cfg.sync_cb=sync_host;
    ble_hs_cfg.store_status_cb=ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap=BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding=1; ble_hs_cfg.sm_sc=1;
    ble_hs_cfg.sm_our_key_dist=BLE_SM_PAIR_KEY_DIST_ENC|BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist=BLE_SM_PAIR_KEY_DIST_ENC|BLE_SM_PAIR_KEY_DIST_ID;
    ble_svc_gap_init(); ble_svc_gatt_init();
    if(ble_gatts_count_cfg(services) || ble_gatts_add_svcs(services)) return ESP_FAIL;
    char name[24]; const char *id=ms51_presence_id();
    snprintf(name,sizeof(name),"HenGio-%s",strlen(id)>=6?id+strlen(id)-6:id);
    ble_svc_gap_device_name_set(name); ble_att_set_preferred_mtu(247); ble_store_config_init();
    if(xTaskCreate(worker,"ble_commands",6144,NULL,4,NULL)!=pdPASS) return ESP_ERR_NO_MEM;
    nimble_port_freertos_init(host);
    ESP_LOGI("ms51_ble","Local Bluetooth ready: %s",name);
    return ESP_OK;
}
