# MS51 Connect server (protocol 1)

Android calls `https://142-91-99-63.sslip.io/ms51/connect/v1/`. The service bridges authorized requests to an isolated Mosquitto listener on TLS port **8884**. Existing MQTT on 8883 and unrelated applications are preserved. No domain purchase is needed for this deployment; sslip.io resolves the IP-based hostname, and the existing Let's Encrypt certificate authenticates it.

## Deployment on the existing Ubuntu VPS

Copy this directory to `/opt/ms51-connect`, install `python3-venv`, create the system user `ms51-connect`, and create `/var/lib/ms51-connect` owned by that user with mode 0700. Then:

```sh
python3 -m venv /opt/ms51-connect/.venv
/opt/ms51-connect/.venv/bin/pip install -r /opt/ms51-connect/requirements.lock
python3 /opt/ms51-connect/bootstrap.py
systemctl daemon-reload
systemctl enable --now ms51-connect-mqtt ms51-connect
systemctl reload nginx
```

`bootstrap.py` targets this VPS's existing `/etc/nginx/sites-available/ms51-updates-v2` and `/etc/mosquitto/certs/ms51-mqtt-{fullchain,privkey}.pem`. It adds only `/ms51/connect/v1/`, checks nginx configuration, and saves the previous virtual host as `.before-connect`. Do not run it on another host without adapting paths/hostname. Re-running retains the database and MQTT credentials.

The API listens on **127.0.0.1:8787** with one uvicorn worker. SQLite WAL and the in-process MQTT correlation map require one worker. Public clients need TCP 443 and devices TCP 8884. Firmware also needs DNS and outbound NTP (UDP 123) for certificate time validation. Never disable TLS validation to work around a clock/DNS problem.

The MS51 HTTPS virtual host uses `ssl_buffer_size 4k`. Keep this setting together with firmware `CONFIG_MBEDTLS_DYNAMIC_BUFFER=y`: full-size downloads otherwise require larger receive allocations on the memory-constrained C5. Other virtual hosts are unchanged.

## Accounts, pairing and control

- Server passwords use salted Argon2id. Session tokens are random, stored as hashes on the server, expire after 30 days and are revoked at logout. Android encrypts its session with Android Keystore; it stores no account password. There is no email recovery in this release.
- The device list is server-authoritative. Every list/rename/remove/RPC/upload checks ownership. A second phone sees the same registry after login. The home screen uses a fresh MQTT heartbeat (10-second interval, 45-second stale limit); server or phone connection failure is shown separately from a confirmed online device.
- First pairing requires the existing physical Wi-Fi setup flow. HTTPS issues a 10-minute pending claim and unique MQTT credentials. The local ESP receives these at `/api/cloud/pair` and proves possession through MQTT using its MAC and random nonce. Merely submitting a MAC does not grant ownership. A paired device rejects replacement credentials through the local API.
- Mosquitto 2.0 gets a separate exact-topic role for every device: publish its hello/state/replies, subscribe/receive only its command topic. No anonymous access or shared phone MQTT password. Phones use the HTTPS API; the broker service credentials never enter APK or firmware.
- Commands have IDs, deadlines, one in-flight request per device, and explicit path/method allowlists. The server caches results for seven days. ESP rejects retained, expired and duplicate commands; recent mutation IDs are also saved before execution to guard against replay across a reboot. A timeout is an unknown result; the app asks the user to check state before retrying.
- Uploads use an expiring authenticated HTTPS transfer, not large MQTT messages. C5 temporarily reconnects MQTT around the transfer to keep TLS memory bounded. It reserves the canonical image before opening download TLS, then parses directly from the HTTP stream. Dynamic TLS buffers reduce heap fragmentation. BIN/Intel HEX retains checksum, coverage, address and EOF checks and atomic A/B storage. An authenticated correlated reply refreshes server liveness immediately after reconnect. Existing `CONFIRM` and image generation checks still apply to erase/full programming.
- Removing a device revokes its broker client and role, including while offline. On its next connection, the ESP clears pairing only after an explicit authentication rejection from the TLS-verified broker. Allow several seconds before pairing it again. Ordinary Wi-Fi, DNS, TLS or Internet failures do not erase pairing.

Local web/USB controls retain their existing trust model: a person on the provisioning/LAN network can use the local API. MQTT ownership does not retrofit authentication to that local API. Use the device AP in a trusted environment. MS51 simulation and the absence of NTC support are unchanged.

## Operations and backups

```sh
systemctl status ms51-connect ms51-connect-mqtt
journalctl -u ms51-connect -u ms51-connect-mqtt --since '10 minutes ago'
curl --fail https://142-91-99-63.sslip.io/ms51/connect/v1/health
```

Keep backups private: the SQLite database contains account hashes and pending pairing credentials. Use SQLite's backup API (not a live copy of only the `.sqlite3` file while WAL is active). Back up `/var/lib/ms51-connect/connect.sqlite3`, `/var/lib/ms51-connect-mqtt/dynamic-security.json`, `/etc/ms51-connect.env`, the service/nginx configs, and the existing certificate/renewal setup. The environment file is root-only. Never commit these, full ESP flash/NVS dumps, session tokens or SSH keys to GitHub.

To restore, stop the two **ms51-connect** services, restore their matching database/broker state and permissions, then start them. Do not replace the unrelated `mosquitto.service` configuration. A server update replaces `app.py`/requirements and restarts only `ms51-connect`. The deployment adds a Certbot deploy hook to reload the new listener when the existing certificate renews.

## Explicit live tests

`integration_test.py` runs as root on this VPS using its virtualenv. It creates two temporary users and MQTT clients, tests TLS/authentication, proof of pairing, ownership, exact-topic ACLs, duplicate requests, file transfer, offline behavior and revocation, then removes only its fixtures. It must be invoked deliberately, not as an automatic public health check.

The Android live runner is documented in `../mobile/android/README.md`. Native parser checks can run with a host C compiler:

```sh
cc -Wall -Wextra -Werror -fsanitize=address,undefined -Itests/image -Imain main/ms51_image.c tests/image/stream_test.c -o /tmp/ms51-image-test
/tmp/ms51-image-test
```

Use a workspace on D: for Windows-generated test/build output.
