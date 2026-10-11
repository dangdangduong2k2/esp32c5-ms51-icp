# ESP32-C5: DAT 2 / CLK 3 / RS 4

Additional pin variant; the default DAT 1 / CLK 0 / RS 3 build is unchanged.

| Signal to MS51 | ESP32-C5 pin |
|---|---|
| DAT / ICE_DAT | GPIO2 |
| CLK / ICE_CLK | GPIO3 |
| RS / nRESET | GPIO4 |
| Ground | GND |

ICP and runtime data both use this mapping. This is the native ESP32-C5
programmer with the existing Internet connection and application support,
not the simulated MS51 controller. Automatic programming at boot is disabled.

## Flash with the Espressif website

Open <https://espressif.github.io/esptool-js/> in Chrome or Edge on the computer
connected to the board. Click Connect and select the board's USB port. If it
does not enter download mode automatically, hold BOOT, press/release RESET,
then release BOOT and reconnect.

Add these three files at these exact addresses:

| Flash address | File |
|---|---|
| `0x2000` | `bootloader.bin` |
| `0x8000` | `partition-table.bin` |
| `0x10000` | `esp32c5-dat2-clk3-rs4.bin` |

Use DIO, 80 MHz, 4 MB. ESP-IDF 6.0 images target ESP32-C5 revision 1.x. The application and data partitions fit below 2 MiB;
the 4 MB image header supports the C5-WROOM-1-N4 as well as C5 boards with
larger flash, including the earlier 16 MB board. It does not use capacity
above 4 MB. The ESP32-C5 bootloader starts at **0x2000**, not 0x0000.

Click Program, wait for completion and then press RESET once. These are ESP32
files for the Espressif flasher; they are not MS51 HEX files for the admin page.
Leave Erase Flash unused when updating an already paired device: the three
files retain the existing partition layout and do not contain account, Wi-Fi,
or device-specific NVS data. On a new device, pair it normally using the app.

## Rebuild

On Windows with ESP-IDF 6.0 and the Espressif Python environment installed:

```powershell
.\tools\build-c5-dat2-clk3-rs4.ps1 -WorkRoot 'D:\Codex\Documents\work\c5-dat2-clk3-rs4'
```

The script creates a separate configuration and build directory. Export files
according to that build's `flasher_args.json`; verify chip ID, checksums,
partition bounds and generated GPIO definitions before sharing them.

This variant is build-validated; it has not been flashed or electrically
tested on a board wired to GPIO2/3/4 in this task.
