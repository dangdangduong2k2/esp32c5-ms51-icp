cmake_minimum_required(VERSION 3.16)

# This project is intentionally tied to ESP32-C5 because GPIO2/3/4 and the
# default USB Serial/JTAG console configuration are part of the design.
set(IDF_TARGET "esp32c5" CACHE STRING "ESP-IDF target" FORCE)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(esp32c5_ms51_icp)
