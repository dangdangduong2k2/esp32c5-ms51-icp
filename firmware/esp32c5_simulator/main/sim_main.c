#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "esp_console.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_http_client.h"
#include "nvs_flash.h"
#include "ms51_programmer.h"
#include "ms51_debug_uart.h"
#include "ms51_storage.h"
#include "ms51_web.h"
#include "ms51_presence.h"

/* A USB-to-local-HTTP diagnostic command lets the host test actual handlers
 * even without a Wi-Fi adapter. Requests never leave this ESP32. */
static int api_command(int argc,char **argv){
    if(argc<3 || (strcmp(argv[1],"GET") && strcmp(argv[1],"POST")) || strncmp(argv[2],"/api/",5)){
        printf("Usage: api GET /api/status | api POST /api/reset '{}'\n");return 1;
    }
    char url[160];snprintf(url,sizeof(url),"http://127.0.0.1%s",argv[2]);
    esp_http_client_config_t cfg={.url=url,.timeout_ms=25000,.buffer_size=1024};
    esp_http_client_handle_t client=esp_http_client_init(&cfg);
    if(!client)return 1;
    bool post=!strcmp(argv[1],"POST");const char *body=argc>3?argv[3]:"{}";
    esp_http_client_set_method(client,post?HTTP_METHOD_POST:HTTP_METHOD_GET);
    esp_http_client_set_header(client,"Content-Type","application/json");
    /* HEX upload fixture; uses the same HTTP upload handler as the APK. */
    if(!strcmp(argv[2],"/api/upload")){
        body=":0400000001020304F2\n:00000001FF\n";
        esp_http_client_set_header(client,"Content-Type","application/octet-stream");
        esp_http_client_set_header(client,"X-Filename","sim-test.hex");
    }
    int length=post?(int)strlen(body):0;
    esp_err_t error=esp_http_client_open(client,length);
    if(error==ESP_OK && length && esp_http_client_write(client,body,length)!=length)error=ESP_FAIL;
    if(error==ESP_OK){
        esp_http_client_fetch_headers(client);
        printf("\nAPI_BEGIN %d\n",esp_http_client_get_status_code(client));
        char buf[256];int count;
        while((count=esp_http_client_read(client,buf,sizeof(buf)))>0)fwrite(buf,1,count,stdout);
        printf("\nAPI_END\n");
    }else printf("API_ERROR %s\n",esp_err_to_name(error));
    esp_http_client_close(client);esp_http_client_cleanup(client);fflush(stdout);return error==ESP_OK?0:1;
}
void app_main(void){
    esp_err_t e=nvs_flash_init();
    if(e==ESP_ERR_NVS_NO_FREE_PAGES || e==ESP_ERR_NVS_NEW_VERSION_FOUND){ESP_ERROR_CHECK(nvs_flash_erase());e=nvs_flash_init();}
    ESP_ERROR_CHECK(e);
    ESP_ERROR_CHECK(ms51_programmer_init());ESP_ERROR_CHECK(ms51_storage_init());
    ESP_ERROR_CHECK(ms51_debug_uart_init());
    esp_console_repl_t *repl=NULL;
    esp_console_repl_config_t config=ESP_CONSOLE_REPL_CONFIG_DEFAULT();
    config.prompt="sim> ";config.max_cmdline_length=1200;config.task_stack_size=8192;
    esp_console_dev_usb_serial_jtag_config_t device=ESP_CONSOLE_DEV_USB_SERIAL_JTAG_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_serial_jtag(&device,&config,&repl));
    const esp_console_cmd_t cmd={.command="api",.help="Call the simulator HTTP API locally",.func=api_command};
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));ESP_ERROR_CHECK(esp_console_register_help_command());
    ESP_ERROR_CHECK(esp_console_start_repl(repl));
    ESP_ERROR_CHECK(ms51_web_start());
    ESP_LOGW("SIM","ESP32-C5 MS51 SIMULATOR READY. No GPIO/UART target access.");
    ESP_LOGW("SIM","AP: %s / password 12345678 / http://192.168.4.1",ms51_presence_ap_ssid());
}
