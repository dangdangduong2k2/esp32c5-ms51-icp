# MS51 application image

Place the raw APROM image at `firmware/ms51_app.bin`, then run `idf.py build`.
The embedded image must start at MS51 APROM address `0x0000`; gaps must be
filled with `0xFF`. The web uploader separately accepts either BIN or Intel HEX.

If this file is absent, the ESP32 project still builds and exposes the console,
but the `program` and `verify` commands report that no image is embedded.

The normal `program` command preserves APROM bytes after the end of this file.
Use `program-full CONFIRM` only when those trailing bytes should be erased.

## Runtime debug telemetry

A runnable MS51FC0AE example is in [`ms51_debug_app/`](ms51_debug_app/). It
uses UART1 TX on P1.6 / ICE_DAT to send a heartbeat to ESP GPIO6.

`ms51_debug_telemetry.c` and `.h` are source files to add to the MS51
firmware project; they are not compiled into ESP32. Configure UART1 TX on
MS51 P1.6 / ICE_DAT at 115200 8N1, then give the helper a byte-writing
function from that project's UART BSP:

~~~c
ms51_debug_set_writer(board_uart1_write_byte);
ms51_debug_log("boot");

/* Call periodically from the main loop, not from a time-critical ISR. */
ms51_debug_var_u16("adc_raw", adc_raw);
ms51_debug_var_u8("state", app_state);
~~~

It produces ASCII lines such as `@var,adc_raw,812` and `@log,boot`. The
ESP32 web page receives them on its GPIO6/DAT wire and shows recent values and
logs. Names may use letters, digits, `_`, `-`, `.`, `[` and `]`.

This telemetry works only while MS51 is running normally. ESP32 detaches its
UART receiver before using the same DAT wire for ICP and reattaches it after
the operation. Upload a newly built MS51 BIN or HEX after adding the helper.

### Telemetry in the current MS51 application

`MS51_code/260502 add PIN/telemetry.c` sends this snapshot once per second:

- `mode`: `COOL`, `ICE_FLUSH`, `IDLE`, `SETTING`, `ADVANCE_SETTING`, or
  `PASSWORD_CONFIG`. All password screens are grouped as `PASSWORD_CONFIG`.
- `green_min` and `red_min`: the numeric minute values currently displayed on
  the green and red TM1650 displays. A non-time screen is reported as `N/A`.
- `relay1`, `relay2`, and `relay3`: the logical relay state reported as `ON`
  or `OFF`. Each value is set by its `RLx_ON()` / `RLx_OFF()` macro, so it
  represents the command issued by the application rather than an electrical
  pin readback. Their electrical polarity is set by `RL1_ACTIVE_LEVEL`,
  `RL2_ACTIVE_LEVEL`, and `RL3_ACTIVE_LEVEL` in `gpio_manager.c` (`1` means
  active-high; `0` means active-low).

The application reserves P1.6 for UART1 TX, so its former `SIG` input is mapped
to P0.1. P0.2 / ICE_CLK is now dedicated to ICP: the former `STT_PIN` output
was removed and P0.2 stays input-only while the MS51 application runs.
