#include "ms51_presence.h"
#include <stdio.h>
#include <string.h>
#include "esp_mac.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_http_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"

static char s_id[24], s_fqdn[32], s_ap_ssid[33];
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static TaskHandle_t s_task;
static bool s_checked, s_online;
static int64_t s_checked_us;
static uint8_t s_checked_bssid[6];
static uint32_t s_checked_ip;

esp_err_t ms51_presence_init(void) {
    uint8_t mac[6];
    esp_err_t e=esp_read_mac(mac,ESP_MAC_WIFI_STA); if(e!=ESP_OK)return e;
    snprintf(s_id,sizeof(s_id),"ms51-%02x%02x%02x%02x%02x%02x",mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
    snprintf(s_fqdn,sizeof(s_fqdn),"%s.local",s_id);
    snprintf(s_ap_ssid,sizeof(s_ap_ssid),"%.19s-%02X%02X%02X%02X%02X%02X",CONFIG_MS51_WIFI_SSID,mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
    return ESP_OK;
}
const char *ms51_presence_id(void){return s_id;}
const char *ms51_presence_hostname(void){return s_id;}
const char *ms51_presence_fqdn(void){return s_fqdn;}
const char *ms51_presence_ap_ssid(void){return s_ap_ssid;}

static bool station(wifi_ap_record_t *ap, uint32_t *ip) {
    esp_netif_t *netif=esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    esp_netif_ip_info_t info;
    if(!netif || esp_wifi_sta_get_ap_info(ap)!=ESP_OK ||
       esp_netif_get_ip_info(netif,&info)!=ESP_OK || info.ip.addr==0)return false;
    *ip=info.ip.addr;return true;
}
static void probe_task(void *unused) {
    (void)unused;
    for(;;) {
        wifi_ap_record_t ap,after;uint32_t ip=0,after_ip=0;
        if(station(&ap,&ip)) {
            // HTTP 204 confirms an external endpoint is reachable. DNS/NAT alone do not.
            // No account, device ID, Wi-Fi credentials or telemetry is sent.
            esp_http_client_config_t config={
                .url="http://connectivitycheck.gstatic.com/generate_204",
                .timeout_ms=6000,.disable_auto_redirect=true,
                .buffer_size=512,.buffer_size_tx=512,
                .user_agent="MS51-Connectivity-Check",
            };
            esp_http_client_handle_t client=esp_http_client_init(&config);
            bool online=false;
            if(client) {
                online=esp_http_client_perform(client)==ESP_OK &&
                       esp_http_client_get_status_code(client)==204;
                esp_http_client_cleanup(client);
            }
            if(station(&after,&after_ip) && ip==after_ip && memcmp(ap.bssid,after.bssid,6)==0) {
                portENTER_CRITICAL(&s_lock);
                s_checked=true;s_online=online;s_checked_us=esp_timer_get_time();
                memcpy(s_checked_bssid,ap.bssid,6);s_checked_ip=ip;
                portEXIT_CRITICAL(&s_lock);
            }
        } else {
            portENTER_CRITICAL(&s_lock);s_checked=false;s_online=false;portEXIT_CRITICAL(&s_lock);
        }
        vTaskDelay(pdMS_TO_TICKS(20000));
    }
}
esp_err_t ms51_presence_start(void) {
    if(s_task)return ESP_OK;
    return xTaskCreate(probe_task,"internet-check",6144,NULL,2,&s_task)==pdPASS?ESP_OK:ESP_ERR_NO_MEM;
}
void ms51_presence_stop(void) {
    if(s_task){vTaskDelete(s_task);s_task=NULL;}
}
void ms51_presence_json(char *out,size_t size) {
    wifi_ap_record_t ap;uint32_t ip=0;
    bool connected=station(&ap,&ip),checked,online;int64_t stamp;bool same=false;
    portENTER_CRITICAL(&s_lock);
    checked=s_checked;online=s_online;stamp=s_checked_us;
    if(connected)same=ip==s_checked_ip && memcmp(ap.bssid,s_checked_bssid,6)==0;
    portEXIT_CRITICAL(&s_lock);
    long long age=checked?(esp_timer_get_time()-stamp)/1000:-1;
    const char *state=!connected?"offline":(!checked||!same||age>90000)?"checking":online?"online":"unverified";
    if(!connected)age=0;
    snprintf(out,size,",\"device\":{\"id\":\"%s\",\"name\":\"MS51 %s\",\"hostname\":\"%s\"},"
             "\"internet\":{\"state\":\"%s\",\"checked_age_ms\":%lld,\"method\":\"http_204\"}",
             s_id,s_id+11,s_fqdn,state,age);
}
