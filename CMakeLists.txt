cmake_minimum_required(VERSION 3.16)

# This project targets ESP32-C5. GPIO4/5/6 are used for the MS51 ICP interface;
# the USB Serial/JTAG console remains available for ESP diagnostics.
set(IDF_TARGET "esp32c5" CACHE STRING "ESP-IDF target" FORCE)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(esp32c5_ms51_icp)
