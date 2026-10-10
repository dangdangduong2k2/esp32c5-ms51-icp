idf_component_register(
    SRCS "../../../main/ms51_cloud.c" "ms51_presence.c" "sim_main.c" "sim_target.c" "ms51_web.c" "ms51_storage.c" "ms51_image.c" "ms51_operation.c"
    INCLUDE_DIRS "." "../../../main"
    EMBED_TXTFILES "web/index.html"
    PRIV_REQUIRES mqtt cjson mbedtls console esp_event esp_http_client esp_http_server esp_netif esp_timer esp_wifi esp_partition esp_rom freertos mdns nvs_flash
)
