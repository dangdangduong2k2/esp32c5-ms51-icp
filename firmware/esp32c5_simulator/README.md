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

This 2.2 build has compiled successfully but has NOT been flashed or tested on
physical hardware in this revision: the user deferred flashing. Earlier serial
API reports refer to 2.1. RF 5 GHz, real multi-board pairing, and the new Internet
probe still need hardware validation. USB API diagnostic timeout is now 25s.
