# ESP32-C5 MS51 simulator

Temporary test firmware for the MS51 Connect Android APK. Based on
https://github.com/dangdangduong2k2/esp32c5-ms51-icp at
`c870c99b838b7636ded712e6f64e5167b380a32a`.

Build with ESP-IDF 6.0 for ESP32-C5; tested hardware has 16 MiB flash.
Run `idf.py build` from this directory after exporting the IDF environment.
Dependencies are pinned in dependencies.lock. Source license is in LICENSE.

AP: `MS51-PROGRAMMER-<12 uppercase factory MAC digits>`, password `12345678`, HTTP address `192.168.4.1`.
The existing web/API and Wi-Fi provisioning are retained. Target GPIO and
UART implementations are replaced with sim_target.c; no target pins are driven.
Responses include simulation=true and runtime mode includes [SIM].

One real second represents one simulated timer minute. Fresh defaults are
25 minutes COOL and 8 minutes DEFROST. Runtime settings persist in ESP NVS.
Uploaded images persist in ESP flash. Emulated 32 KiB APROM is RAM and clears
when ESP power cycles. A target reset only restarts the emulated timer.
The simulator does not execute uploaded MS51 machine code or measure NTC.

The USB console has an `api` command to exercise the real HTTP server over
127.0.0.1. Examples: `api GET /api/status` and `api POST /api/reset "{}"`.
For `/api/upload` the diagnostic command sends the bundled four-byte HEX
fixture; the ordinary HTTP upload endpoint accepts actual user files.
Host-side serial tests cover read/write settings, info, reset, upload, program,
verify mismatch/success, and confirmed erase. See delivered test-results.json.
Loopback tests do not establish phone Wi-Fi or APK connectivity.

For this board, manually enter download mode with BOOT + RESET before flashing.
Use esptool `--before no-reset --after watchdog-reset`; a USB RTS reset alone
can leave a chip that entered download mode manually in the ROM loader.
Never write the original full-chip backup into an unrelated ESP board.

Version 2.2 adds a factory-MAC device identity, a unique mDNS hostname
ms51-<mac>.local, and per-device Internet reachability telemetry. An HTTP 204
probe runs every 20 seconds while STA has an IP. It does not send account or
Wi-Fi credentials. Failure is reported as unverified, not proof of an outage.
X-MS51-Device, when supplied by the Android app, must match before an API
handler runs. This routing guard is not password authentication.

On 2026-10-08, simulator 2.2 was flashed successfully to the ESP32-C5 rev1.0
with 16 MiB flash. Flash hash verification passed. On-device HTTP checks over
USB verified MAC-based AP/mDNS identity, status, changing simulated timers,
217-byte runtime config, simulated chip info, and Wi-Fi scan (7 networks on
2.4 GHz, 8 on 5 GHz). Internet state was offline because STA was not connected.
The public report is docs/ms51-connect-2.2/hardware-test-2026-10-08.json in the
repository root. Physical phone pairing, multiple physical boards and a
positive Internet probe remain unverified. USB API diagnostic timeout is 25s.
The native programmer in the repository root now also targets ESP32-C5, but
only this simulator was flashed while the MS51 target is absent.
