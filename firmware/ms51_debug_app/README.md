# MS51FC0AE telemetry demo

This is a minimal standalone firmware for checking the ESP32-S3 web debug
screen. It does not depend on an LED, sensor, or any unknown board wiring.

After reset, it sends one log line and then sends three variables once each
second:

```text
@log,ms51_debug_demo_boot
@var,heartbeat,0
@var,uptime_s,0
@var,state,1
```

## Wiring and UART settings

| MS51FC0AE | ESP32-S3 programmer | Purpose |
| --- | --- | --- |
| P1.6 / ICE_DAT | GPIO6 | UART1 TX telemetry at 115200 baud |
| GND | GND | Shared reference |

The source deliberately enables **only UART1 TX**. It leaves the ICE clock
pin P0.2 untouched, so it does not introduce a UART RX conflict with the
programmer wiring.

P1.6 is also the MS51 ICE_DAT pin. Program the final HEX first, then reset
the MS51 and view telemetry in the ESP web page. Do not attempt live ICP/OCD
debugging while this application is actively transmitting on P1.6.

## Built HEX

The ready-to-upload file is:

```text
firmware/ms51_debug_app/out/ms51_debug_demo.hex
```

It is an ASCII, line-delimited Intel HEX image for MS51FC0AE APROM. The build
checks every record checksum, the EOF record, the 32 KiB APROM range, and the
reset/startup instructions before reporting success.

## Rebuild with SDCC

The project includes [`build.ps1`](build.ps1), tested with SDCC 4.6.0. It
expects the official Nuvoton BSP in `third_party/MS51_BSP`, which is purposely
ignored by Git:

```powershell
git clone --depth 1 https://github.com/OpenNuvoton/MS51_BSP third_party/MS51_BSP
.\firmware\ms51_debug_app\build.ps1
```

On this machine the SDCC package was installed with `scoop install sdcc`. If
SDCC or the BSP is elsewhere, pass the paths explicitly:

```powershell
.\firmware\ms51_debug_app\build.ps1 `
  -SdccBin C:\path\to\sdcc\bin `
  -BspRoot C:\path\to\MS51FC0AE_MS51XC0BE_MS51EB0AE_MS51EC0AE_MS51TC0AE_MS51PC0AE
```

The build uses these application/BSP sources:

```text
firmware/ms51_debug_app/src/main.c
firmware/ms51_debug_app/src/board_uart1.c
firmware/ms51_debug_app/src/ms51_bsp_bit_tmp.c
firmware/ms51_debug_app/src/crtstart_fixed_sp.asm
firmware/ms51_debug_telemetry.c
<MS51_BSP>/Library/StdDriver/src/sys.c
```

`sys.c` requires the BSP's `BIT_TMP` symbol; this demo supplies only that
symbol in `ms51_bsp_bit_tmp.c` instead of linking the larger `common.c`.
Nuvoton's current BSP checks `__SDCC__`, while SDCC 4.6 defines `__SDCC`; the
script therefore supplies `-D__SDCC__`.

The custom startup assembly sets `SP` to `0x70`, making the first stack push
land at `0x71`, above the demo's direct-data range. This avoids SDCC's default
startup placing the stack too low for this BSP.

Retain the default 115200-baud setting in the ESP firmware. Upload the
resulting `.hex` file to the ESP web interface and select **Program**.

`board_clock_init_24mhz()` uses Nuvoton's `FsysSelect()` and `MODIFY_HIRC()`
from `sys.c`; include that file rather than replacing it with a guessed HIRC
trim value.
