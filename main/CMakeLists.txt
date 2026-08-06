set(ms51_firmware_bin "${PROJECT_DIR}/firmware/ms51_app.bin")
set(ms51_generator "${PROJECT_DIR}/tools/embed_ms51_firmware.py")
set(ms51_generated_dir "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(ms51_generated_c "${ms51_generated_dir}/ms51_firmware_blob.c")

# ESP-IDF evaluates component CMake files once in an early script-mode pass to
# discover requirements. File generation must only run in the real configure pass.
if(NOT CMAKE_BUILD_EARLY_EXPANSION)
    # Re-run CMake when the optional firmware file appears, disappears, or changes.
    file(GLOB ms51_firmware_input CONFIGURE_DEPENDS "${ms51_firmware_bin}")
    if(ms51_firmware_input)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${ms51_firmware_bin}")
    endif()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${ms51_generator}")

    file(MAKE_DIRECTORY "${ms51_generated_dir}")
    idf_build_get_property(python PYTHON)
    execute_process(
        COMMAND "${python}" "${ms51_generator}"
                --input "${ms51_firmware_bin}"
                --output "${ms51_generated_c}"
        RESULT_VARIABLE ms51_generator_result
        OUTPUT_VARIABLE ms51_generator_stdout
        ERROR_VARIABLE ms51_generator_stderr
    )
    if(NOT ms51_generator_result EQUAL 0)
        message(FATAL_ERROR
            "Failed to generate the MS51 firmware blob:\n${ms51_generator_stdout}${ms51_generator_stderr}")
    endif()
endif()

idf_component_register(
    SRCS
        "app_main.c"
        "ms51_debug_uart.c"
        "ms51_image.c"
        "ms51_icp.c"
        "ms51_operation.c"
        "ms51_programmer.c"
        "ms51_storage.c"
        "ms51_web.c"
        "${ms51_generated_c}"
    INCLUDE_DIRS "."
    EMBED_TXTFILES "web/index.html"
    PRIV_REQUIRES
        console
        esp_driver_gpio
        esp_driver_uart
        esp_event
        esp_http_server
        esp_netif
        esp_partition
        esp_rom
        esp_timer
        esp_wifi
        freertos
        nvs_flash
)
