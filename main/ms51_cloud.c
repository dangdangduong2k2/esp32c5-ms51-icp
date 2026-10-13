#include "ms51_cloud.h"
#include "ms51_presence.h"
#include "ms51_image.h"
#include "ms51_operation.h"
#include "ms51_storage.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "cJSON.h"
#include "esp_crt_bundle.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_netif_sntp.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "mqtt_client.h"
#include "nvs.h"

#define CLOUD_HOST "142-91-99-63.sslip.io"
#define CLOUD_PORT 8884
#define COMMAND_MAX 6144
#define RESPONSE_MAX 24576
#define PREFIX "ms51connect/v1/"
static const char *TAG = "ms51_cloud";
typedef struct { char username[34], password[65], nonce[49]; } cloud_config_t;
static cloud_config_t s_config;
static SemaphoreHandle_t s_mutex;
static QueueHandle_t s_queue;
static esp_mqtt_client_handle_t s_client;
static volatile bool s_connected;
static volatile bool s_revoked;
static char s_topic[64], s_cmd_topic[100];
static char *s_receiving;
static int s_received, s_total;
/* QoS duplicates never repeat operations, even if the reply was lost. */
static char s_seen[32][33];
static unsigned s_seen_index;
static char s_mutations[8][33];
static unsigned s_mutation_index;

static const char *str(const cJSON *o, const char *key) {
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsString(v) ? v->valuestring : "";
}
static bool token_valid(const char *s, size_t low, size_t high) {
    size_t n = strlen(s);
    if (n < low || n > high) return false;
    for (size_t i=0; i<n; i++)
        if (!((s[i]>='a' && s[i]<='z') || (s[i]>='A' && s[i]<='Z') ||
              (s[i]>='0' && s[i]<='9') || s[i]=='-' || s[i]=='_')) return false;
    return true;
}
bool ms51_cloud_configured(void) {
    if (!s_mutex) return false;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    bool configured = s_config.username[0] != 0;
    xSemaphoreGive(s_mutex);
    return configured;
}
bool ms51_cloud_online(void) { return s_connected; }
bool ms51_cloud_clock_ready(void) { return time(NULL)>1767225600; }

esp_err_t ms51_cloud_pair(const char *json) {
    if (!s_mutex) return ESP_ERR_INVALID_STATE;
    cJSON *o = cJSON_Parse(json);
    cloud_config_t next = {0};
    const cJSON *port = cJSON_GetObjectItemCaseSensitive(o, "port");
    if (!o || strcmp(str(o,"host"), CLOUD_HOST) || !cJSON_IsNumber(port) || port->valueint != CLOUD_PORT ||
        strcmp(str(o,"device"), ms51_presence_id()) || !token_valid(str(o,"username"),33,33) ||
        !token_valid(str(o,"password"),32,64) || !token_valid(str(o,"nonce"),48,48)) {
        cJSON_Delete(o); return ESP_ERR_INVALID_ARG;
    }
    strlcpy(next.username,str(o,"username"),sizeof(next.username));
    strlcpy(next.password,str(o,"password"),sizeof(next.password));
    strlcpy(next.nonce,str(o,"nonce"),sizeof(next.nonce));
    cJSON_Delete(o);
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    esp_err_t result = ESP_OK;
    if (s_config.username[0]) {
        // An existing owner cannot be replaced over the open provisioning API.
        result = memcmp(&s_config, &next, sizeof(next)) == 0 ? ESP_OK : ESP_ERR_INVALID_STATE;
    } else {
        nvs_handle_t nvs;
        result = nvs_open("ms51_cloud", NVS_READWRITE, &nvs);
        if (result == ESP_OK) {
            result = nvs_set_blob(nvs,"config",&next,sizeof(next));
            if (result == ESP_OK) result = nvs_commit(nvs);
            nvs_close(nvs);
            if (result == ESP_OK) s_config = next;
        }
    }
    xSemaphoreGive(s_mutex);
    memset(&next,0,sizeof(next));
    return result;
}

static cJSON *error_result(const char *message) {
    cJSON *result=cJSON_CreateObject();
    cJSON_AddBoolToObject(result,"ok",false);
    cJSON_AddStringToObject(result,"message",message);
    return result;
}
static cJSON *read_response(esp_http_client_handle_t http) {
    size_t capacity=1024;
    char *text=malloc(capacity+1);
    if (!text) return error_result("ESP khong du bo nho.");
    int used=0, count=0;
    while(used<RESPONSE_MAX) {
        if((size_t)used==capacity) {
            if(esp_http_client_is_complete_data_received(http)) break;
            size_t next=capacity*2;
            if(next>RESPONSE_MAX) next=RESPONSE_MAX;
            char *grown=realloc(text,next+1);
            if(!grown) { free(text); return error_result("ESP khong du bo nho nhan phan hoi."); }
            text=grown; capacity=next;
        }
        count=esp_http_client_read(http,text+used,capacity-used);
        if(count<=0) break;
        used+=count;
    }
    text[used]=0;
    cJSON *result=(count>=0 && used<RESPONSE_MAX && esp_http_client_is_complete_data_received(http)) ? cJSON_Parse(text) : NULL;
    free(text);
    return result ? result : error_result("Chua nhan du ket qua. Kiem tra trang thai truoc khi gui lai.");
}
static esp_http_client_handle_t local_client(const char *path) {
    char url[160]; snprintf(url,sizeof(url),"http://127.0.0.1%s",path);
    esp_http_client_config_t config={.url=url,.timeout_ms=20000,.buffer_size=1024,.disable_auto_redirect=true};
    esp_http_client_handle_t http=esp_http_client_init(&config);
    if(http) esp_http_client_set_header(http,"X-MS51-Device",ms51_presence_id());
    return http;
}
static bool write_all(esp_http_client_handle_t http, const char *bytes, int length) {
    int sent=0;
    while(sent<length) {
        int n=esp_http_client_write(http,bytes+sent,length-sent);
        if(n<=0) return false;
        sent+=n;
    }
    return true;
}
static cJSON *local_request(const char *method,const char *path,const cJSON *body) {
    esp_http_client_handle_t http=local_client(path);
    if(!http) return error_result("Khong tao duoc ket noi noi bo.");
    bool post=!strcmp(method,"POST");
    char *payload=post ? cJSON_PrintUnformatted(body) : NULL;
    int length=payload ? strlen(payload) : 0;
    esp_http_client_set_method(http,post ? HTTP_METHOD_POST : HTTP_METHOD_GET);
    esp_http_client_set_header(http,"Content-Type","application/json");
    cJSON *result=NULL;
    if(esp_http_client_open(http,length)==ESP_OK && (!length || write_all(http,payload,length)) &&
       esp_http_client_fetch_headers(http)>=0) result=read_response(http);
    free(payload); esp_http_client_cleanup(http);
    return result ? result : error_result("Khong nhan duoc ket qua tu thiet bi.");
}
typedef struct { esp_http_client_handle_t http; int size; bool opened; } transfer_reader_t;
static int transfer_read(void *context, uint8_t *buffer, size_t maximum) {
    transfer_reader_t *reader=context;
    // The parser reserves its canonical image before the TLS handshake can fragment RAM.
    if(!reader->opened) {
        if(esp_http_client_open(reader->http,0)!=ESP_OK ||
           esp_http_client_fetch_headers(reader->http)!=reader->size ||
           esp_http_client_get_status_code(reader->http)!=200) return -1;
        reader->opened=true;
    }
    return esp_http_client_read(reader->http,(char*)buffer,maximum);
}
static cJSON *remote_upload(const cJSON *body) {
    const char *id=str(body,"upload_id"), *token=str(body,"token"), *name=str(body,"filename");
    const cJSON *n=cJSON_GetObjectItemCaseSensitive(body,"size");
    if(!token_valid(id,32,32) || !token_valid(token,32,64) || !cJSON_IsNumber(n) ||
        n->valueint<1 || n->valueint>98304 || strlen(name)<5 || strlen(name)>104 || strchr(name,'\r') || strchr(name,'\n'))
        return error_result("Tep khong hop le.");
    char url[200], auth[80];
    snprintf(url,sizeof(url),"https://" CLOUD_HOST "/ms51/connect/v1/transfer/%s",id);
    snprintf(auth,sizeof(auth),"Bearer %s",token);
    esp_http_client_config_t config={.url=url,.timeout_ms=15000,.buffer_size=1024,.crt_bundle_attach=esp_crt_bundle_attach,.disable_auto_redirect=true};
    esp_http_client_handle_t download=esp_http_client_init(&config);
    if(!download) return error_result("Khong du bo nho tai tep.");
    esp_http_client_set_header(download,"Authorization",auth);
    esp_err_t error=ms51_operation_lock(0);
    if(error!=ESP_OK) { esp_http_client_cleanup(download); return error_result("Thiet bi dang ban. Vui long doi."); }
    transfer_reader_t reader={.http=download,.size=n->valueint};
    ms51_image_t image={0}; ms51_storage_info_t info;
    error=ms51_image_parse_stream(transfer_read,&reader,n->valueint,name,&image);
    // Close TLS before flash verification and response allocation.
    esp_http_client_cleanup(download);
    if(error==ESP_OK) error=ms51_storage_commit_image(name,&image,&info);
    ms51_image_free(&image); ms51_operation_unlock();
    if(error!=ESP_OK) {
        ESP_LOGW(TAG,"Upload rejected: %s",esp_err_to_name(error));
        return error_result(error==ESP_ERR_NO_MEM ? "ESP khong du bo nho nhan tep." :
            "Tep chua duoc luu. Kiem tra ket noi, checksum, EOF va dung luong MS51.");
    }
    cJSON *result=cJSON_CreateObject(), *stored=cJSON_CreateObject();
    cJSON_AddBoolToObject(result,"ok",true);
    cJSON_AddStringToObject(result,"message","Da luu tep tren thiet bi.");
    cJSON_AddItemToObject(result,"image",stored);
    cJSON_AddBoolToObject(stored,"valid",true); cJSON_AddStringToObject(stored,"name",info.filename);
    cJSON_AddNumberToObject(stored,"size",info.size); cJSON_AddNumberToObject(stored,"covered_size",info.covered_size);
    cJSON_AddStringToObject(stored,"format",ms51_image_format_name(info.format));
    cJSON_AddNumberToObject(stored,"crc32",info.crc32); cJSON_AddNumberToObject(stored,"generation",info.generation);
    return result;
}
static bool allowed(const char *method,const char *path) {
    const char *get[]={"/api/status","/api/debug","/api/runtime-config"};
    const char *post[]={"/api/mode","/api/info","/api/reset","/api/runtime-config","/api/program","/api/verify","/api/program-full","/api/erase","/internal/upload","/internal/unpair"};
    if(!strcmp(method,"GET")) { for(size_t i=0;i<sizeof(get)/sizeof(*get);i++) if(!strcmp(path,get[i])) return true; }
    if(!strcmp(method,"POST")) { for(size_t i=0;i<sizeof(post)/sizeof(*post);i++) if(!strcmp(path,post[i])) return true; }
    return false;
}
static bool remember_mutation(const char *id) {
    nvs_handle_t nvs;
    if(nvs_open("ms51_cloud",NVS_READWRITE,&nvs)!=ESP_OK) return false;
    strlcpy(s_mutations[s_mutation_index++%8],id,33);
    esp_err_t e=nvs_set_blob(nvs,"requests",s_mutations,sizeof(s_mutations));
    if(e==ESP_OK) e=nvs_commit(nvs);
    nvs_close(nvs);
    return e==ESP_OK;
}
static void publish_json(const char *topic,cJSON *data) {
    char *text=cJSON_PrintUnformatted(data);
    if(text && s_client && s_connected) esp_mqtt_client_enqueue(s_client,topic,text,0,1,0,true);
    free(text);
}
static void mqtt_event(void *arg,esp_event_base_t base,int32_t event_id,void *event_data) {
    esp_mqtt_event_handle_t event=event_data;
    if(event_id==MQTT_EVENT_CONNECTED) {
        s_connected=true;
        esp_mqtt_client_subscribe(s_client,s_cmd_topic,1);
    } else if(event_id==MQTT_EVENT_DISCONNECTED) s_connected=false;
    else if(event_id==MQTT_EVENT_ERROR && event->error_handle &&
            event->error_handle->error_type==MQTT_ERROR_TYPE_CONNECTION_REFUSED &&
            (event->error_handle->connect_return_code==MQTT_CONNECTION_REFUSE_NOT_AUTHORIZED ||
             event->error_handle->connect_return_code==MQTT_CONNECTION_REFUSE_BAD_USERNAME)) s_revoked=true;
    else if(event_id==MQTT_EVENT_DATA) {
        if(event->current_data_offset==0) {
            free(s_receiving); s_receiving=NULL; s_received=0;
            if(event->retain || event->topic_len!=(int)strlen(s_cmd_topic) ||
               memcmp(event->topic,s_cmd_topic,event->topic_len) || event->total_data_len<1 || event->total_data_len>COMMAND_MAX) return;
            s_total=event->total_data_len; s_receiving=malloc(s_total+1);
        }
        if(!s_receiving) return;
        if(event->current_data_offset!=s_received || event->data_len>s_total-s_received) {
            free(s_receiving); s_receiving=NULL; return;
        }
        memcpy(s_receiving+s_received,event->data,event->data_len); s_received+=event->data_len;
        if(s_received==s_total) {
            s_receiving[s_total]=0;
            if(xQueueSend(s_queue,&s_receiving,0)!=pdTRUE) free(s_receiving);
            s_receiving=NULL;
        }
    }
}
static void process(char *text) {
    cJSON *command=cJSON_Parse(text); free(text);
    if(!command) return;
    const char *id=str(command,"id"), *method=str(command,"method"), *path=str(command,"path");
    const cJSON *expires=cJSON_GetObjectItemCaseSensitive(command,"expires_at");
    const cJSON *issued=cJSON_GetObjectItemCaseSensitive(command,"issued_at");
    const cJSON *body=cJSON_GetObjectItemCaseSensitive(command,"body");
    if(!token_valid(id,32,32)) { cJSON_Delete(command); return; }
    bool duplicate=false, unpair=false;
    for(size_t i=0;i<32;i++) if(!strcmp(id,s_seen[i])) duplicate=true;
    for(size_t i=0;i<8;i++) if(!strcmp(id,s_mutations[i])) duplicate=true;
    cJSON *result=NULL;
    time_t now=time(NULL);
    if(duplicate) result=error_result("Yeu cau da duoc xu ly. Kiem tra trang thai thiet bi.");
    else if(!cJSON_IsNumber(expires) || !cJSON_IsNumber(issued) || expires->valuedouble<now ||
            issued->valuedouble>now+5 || expires->valuedouble-issued->valuedouble>60 ||
            !cJSON_IsObject(body) || !allowed(method,path)) result=error_result("Lenh khong hop le hoac da het han.");
    else if(!strcmp(method,"POST") && strcmp(path,"/api/info") && !remember_mutation(id))
        result=error_result("Khong luu duoc ma lenh; chua thuc hien thao tac.");
    else {
        strlcpy(s_seen[s_seen_index++%32],id,33);
        if(!strcmp(path,"/internal/upload")) {
            // C5 shares RAM between Wi-Fi, MS51 simulation and TLS. Only one TLS
            // connection is needed during the transfer. The server holds this RPC
            // slot while we reconnect, then receives the correlated reply.
            esp_mqtt_client_stop(s_client); s_connected=false;
            result=remote_upload(body);
            esp_mqtt_client_start(s_client);
            for(int i=0;i<150 && !s_connected;i++) vTaskDelay(pdMS_TO_TICKS(100));
        }
        else if(!strcmp(path,"/internal/unpair")) {
            nvs_handle_t nvs;
            esp_err_t e=nvs_open("ms51_cloud",NVS_READWRITE,&nvs);
            if(e==ESP_OK) { e=nvs_erase_key(nvs,"config"); if(e==ESP_OK) e=nvs_commit(nvs); nvs_close(nvs); }
            if(e==ESP_OK) { unpair=true; result=cJSON_CreateObject(); cJSON_AddBoolToObject(result,"ok",true); }
            else result=error_result("Chua xoa duoc ghep noi. Vui long thu lai.");
        } else result=local_request(method,path,body);
    }
    cJSON *reply=cJSON_CreateObject(); cJSON_AddStringToObject(reply,"id",id); cJSON_AddItemToObject(reply,"result",result);
    char topic[120]; snprintf(topic,sizeof(topic),"%s/reply/%s",s_topic,id);
    publish_json(topic,reply); cJSON_Delete(reply); cJSON_Delete(command);
    if(unpair) {
        vTaskDelay(pdMS_TO_TICKS(1200));
        esp_mqtt_client_stop(s_client); esp_mqtt_client_destroy(s_client); s_client=NULL; s_connected=false;
        xSemaphoreTake(s_mutex,portMAX_DELAY); memset(&s_config,0,sizeof(s_config)); xSemaphoreGive(s_mutex);
    }
}
static void heartbeat(void) {
    cJSON *hello=cJSON_CreateObject();
    cJSON_AddStringToObject(hello,"device",ms51_presence_id()); cJSON_AddStringToObject(hello,"nonce",s_config.nonce);
    char topic[120]; snprintf(topic,sizeof(topic),"%s/hello",s_topic); publish_json(topic,hello); cJSON_Delete(hello);
    cJSON *state=cJSON_CreateObject();
    cJSON_AddStringToObject(state,"device",ms51_presence_id()); cJSON_AddBoolToObject(state,"online",true);
    cJSON_AddItemToObject(state,"status",local_request("GET","/api/status",NULL));
    snprintf(topic,sizeof(topic),"%s/state",s_topic); publish_json(topic,state); cJSON_Delete(state);
}
static void worker(void *arg) {
    esp_sntp_config_t ntp=ESP_NETIF_SNTP_DEFAULT_CONFIG_MULTIPLE(3,
        ESP_SNTP_SERVER_LIST("time.google.com","time.cloudflare.com","pool.ntp.org"));
    ntp.start=false;
    esp_netif_sntp_init(&ntp);
    int64_t last_time_attempt=-60000000;
    int64_t last_heartbeat=0;
    for(;;) {
        if(!ms51_cloud_clock_ready() && esp_timer_get_time()-last_time_attempt>=60000000) {
            esp_netif_t *station=esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
            esp_netif_ip_info_t ip;
            if(station && esp_netif_get_ip_info(station,&ip)==ESP_OK && ip.ip.addr!=0) {
                // DNS must be available before starting SNTP, especially after a cold boot.
                last_time_attempt=esp_timer_get_time(); esp_netif_sntp_start();
                ESP_LOGI(TAG,"Synchronizing clock for TLS");
            }
        }
        if(s_revoked && s_client) {
            esp_mqtt_client_stop(s_client); esp_mqtt_client_destroy(s_client); s_client=NULL; s_connected=false;
            xSemaphoreTake(s_mutex,portMAX_DELAY);
            nvs_handle_t nvs;
            if(nvs_open("ms51_cloud",NVS_READWRITE,&nvs)==ESP_OK) {
                nvs_erase_key(nvs,"config"); nvs_commit(nvs); nvs_close(nvs);
            }
            memset(&s_config,0,sizeof(s_config)); xSemaphoreGive(s_mutex); s_revoked=false;
            char *discard=NULL;
            while(xQueueReceive(s_queue,&discard,0)==pdTRUE) free(discard);
        }
        if(!s_client && ms51_cloud_configured() && ms51_cloud_clock_ready()) {
            // Start only after time sync; TLS certificate date checks stay enabled.
            char will[100], will_topic[120];
            snprintf(s_topic,sizeof(s_topic),PREFIX "%s",s_config.username);
            snprintf(s_cmd_topic,sizeof(s_cmd_topic),"%s/cmd",s_topic);
            snprintf(will_topic,sizeof(will_topic),"%s/state",s_topic);
            snprintf(will,sizeof(will),"{\"device\":\"%s\",\"online\":false}",ms51_presence_id());
            esp_mqtt_client_config_t config={
                .broker.address.uri="mqtts://" CLOUD_HOST ":8884",
                .broker.verification.crt_bundle_attach=esp_crt_bundle_attach,
                .credentials.username=s_config.username,.credentials.client_id=s_config.username,
                .credentials.authentication.password=s_config.password,
                .session.keepalive=30,.session.disable_clean_session=false,
                .session.last_will.topic=will_topic,.session.last_will.msg=will,.session.last_will.qos=1,
                .network.timeout_ms=10000,.network.reconnect_timeout_ms=5000,
                .buffer.size=2048,.buffer.out_size=1024,.outbox.limit=65536,
                .task.stack_size=6144,
            };
            s_client=esp_mqtt_client_init(&config);
            if(s_client) {
                esp_mqtt_client_register_event(s_client,ESP_EVENT_ANY_ID,mqtt_event,NULL);
                esp_mqtt_client_start(s_client);
            }
        }
        char *command=NULL;
        if(xQueueReceive(s_queue,&command,pdMS_TO_TICKS(500))==pdTRUE) process(command);
        int64_t now=esp_timer_get_time();
        if(s_connected && now-last_heartbeat>=10000000) { last_heartbeat=now; heartbeat(); }
    }
}
esp_err_t ms51_cloud_start(void) {
    if(s_mutex) return ESP_OK;
    s_mutex=xSemaphoreCreateMutex(); s_queue=xQueueCreate(1,sizeof(char*));
    if(!s_mutex || !s_queue) return ESP_ERR_NO_MEM;
    nvs_handle_t nvs;
    if(nvs_open("ms51_cloud",NVS_READONLY,&nvs)==ESP_OK) {
        size_t length=sizeof(s_config);
        if(nvs_get_blob(nvs,"config",&s_config,&length)!=ESP_OK || length!=sizeof(s_config)) memset(&s_config,0,sizeof(s_config));
        length=sizeof(s_mutations);
        if(nvs_get_blob(nvs,"requests",s_mutations,&length)!=ESP_OK || length!=sizeof(s_mutations)) memset(s_mutations,0,sizeof(s_mutations));
        nvs_close(nvs);
    }
    if(xTaskCreate(worker,"ms51-cloud",10240,NULL,4,NULL)!=pdPASS) return ESP_ERR_NO_MEM;
    ESP_LOGI(TAG,"Cloud protocol 1 ready; paired=%d",ms51_cloud_configured());
    return ESP_OK;
}
