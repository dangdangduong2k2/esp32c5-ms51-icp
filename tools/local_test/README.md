# Local Windows LAN test server

Runs the existing account/admin API and Mosquitto dynamic-security broker without a VPS.
Keep the environment on D: outside this repository. It contains passwords, private keys,
SQLite accounts, broker credentials and private hardware backups: **do not commit it**.

## Isolation

- Production defaults remain unchanged. LAN Android uses application ID suffix `.localtest`
  and label **Hẹn Giờ Lạnh Test**, with its own encrypted session storage.
- Android trusts the generated CA only for the configured LAN IP. C5 verifies the same CA
  for MQTT and HTTPS downloads. TLS verification is never disabled.
- LAN C5 uses NVS namespace `ms51_lan`; production `ms51_cloud` pairing is retained.
  Wi-Fi and MS51 runtime settings are shared and are not overwritten during setup.
- Port 8443 serves HTTPS; 8884 serves authenticated MQTT. Only allow the local subnet
  through Windows Firewall. No router forwarding or public tunnel is configured.
- The API runs one worker, keeps its original secure cookies/CSRF/ownership checks and
  ignores client-supplied X-Real-IP. The external `/ms51/connect/v1` prefix is retained.

## Prepare

Use a stable/reserved private IPv4 address on the PC. Python needs `server/requirements.lock`
plus `cryptography` and `httpx`. Extract official Mosquitto Windows binaries to the private
work directory; do not install an unrelated global broker service.

```
python tools/local_test/prepare.py --root D:/path/to/test --host 192.168.x.y --broker D:/path/to/mosquitto
python tools/local_test/manage.py start D:/path/to/test
python tools/local_test/check.py D:/path/to/test
```

`prepare.py` preserves existing credentials/state. Its CA signing key is discarded after
generating the certificate. Server certificates last 180 days; recreate the test environment
and rebuild clients when renewing or changing IP. Import `outputs/local-test-ca.crt` into
the Windows current-user trusted roots to open admin without a certificate warning. The
Android app embeds this public CA and needs no phone-wide CA installation.

Run `enable-lan.ps1` from an elevated PowerShell. If script execution is disabled, run its
`New-NetFirewallRule` command directly instead of changing execution policy.

## Build clients

Android Gradle debug build properties:

```
-Pms51LocalEndpoint=https://192.168.x.y:8443/ms51/connect/v1
-Pms51LocalResources=D:/path/to/test/work/android-res
```

C5 ESP-IDF CMake property:

```
-D MS51_CLOUD_CONFIG_HEADER=D:/path/to/test/work/cloud-local.h
```

Use the current board's pin/sdkconfig profile in a separate build directory. Back up app/NVS,
compare partition tables, program only the application partition, verify flash and compare
NVS before/after. Never erase the chip or reprogram MS51 merely to change servers.

After boot, pair through the test app, or use the explicitly requested physical device:

```
python tools/local_test/pair.py --root D:/path/to/test --device-url http://DEVICE_LAN_IP
```

`check.py` creates test-only admin/mobile accounts and verifies TLS, secure cookies and CSRF.
`pair.py` tests proof of possession through the actual TLS MQTT broker. Android instrumentation
mode `lan-smoke` is restricted to `.localtest`; put `username`/`password` in private
`files/lan-smoke.json` via `adb run-as`. It logs in and reads paired status, runtime settings
and permissions; it never writes relay/runtime settings. The credential file is deleted.

## Everyday use / restore

Keep PC running and clients on the same LAN. Use `manage.py start|stop|status ROOT`.
An offline PC means this test cloud is offline; standalone MCU behavior is unchanged.
Use the private credential text file in `outputs`, or register a new mobile test account.
Production accounts are not automatically available because no VPS database was imported.

To return to VPS: restore the saved pre-test C5 application, keep NVS intact, and use the
normal Android app. Original VPS pairing remains in its separate namespace. Remove the
`MS51-LAN-Test-HTTPS-MQTT` firewall rule and the exact generated test CA certificate if no
longer needed. Back up the whole private test directory only while both local services are
stopped; it is not a backup of the old VPS.
