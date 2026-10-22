# MS51 Connect server (protocol 1)

## Per-device permissions and managed firmware (3.1.0)

Select an account, then a device's **Cấp quyền** button (or its name). Account arrows have a larger teal background. The permission editor exposes 44 Android settings, including individual Min/Max fields, grouped by the existing settings sections. Each of DF, END, display mode, HCF and HDF has one checkbox: checked shows its setting in the app; unchecked hides it. The five separate “Hiện chế độ…” permissions are retired. Their MCU menu flags remain in the internal 51-field codec and are preserved during ordinary settings writes. This changes app visibility only; it does not write MCU menu flags. `showSt` and `defrostOpSeconds` are admin-only starting in 3.4.10; the app cannot edit them. All available fields remain visible by default; admin can hide any subset or all fields. Policy revisions prevent stale admin writes and stale mobile saves. Android 3.1 and later refresh policy on opening Settings and every 15 seconds while that tab is open, so this permission change needs no new APK.

Deploy `managed.py` and `settings_catalog.json` together with `app.py`, `admin.py`, and `admin_web/`; the catalog is derived from the Android codec offsets. `/devices/{id}/settings` accepts changed field values with a policy revision, checks visible keys and bounds, reads fresh MS51 settings, merges only those byte ranges, and checks range/PIN consistency before writing. Hidden fields and reserved bytes are preserved. Raw `/api/runtime-config` writes, user-uploaded files and direct cloud program/verify/erase calls are now denied, including from older APKs. Install APK 3.1.0 for these operations. Local C5 web/USB access retains the previously documented LAN/physical trust model.

Admin assigns one `.hex` per device. Upload validates Intel HEX records, checksum, EOF, duplicate addresses and 32 KiB APROM bounds before storing the file privately in SQLite (96 KiB text maximum). No public firmware download URL exists. The phone receives metadata only. Uploading/replacing a file does not program hardware; the user must press **Cập nhật** in the app. Admin can withdraw an idle assignment. Removing a paired device cascades its permissions and assigned firmware so a future owner does not inherit them.

An authenticated update request starts a persistent job with a random idempotency ID and the exact assigned release ID. A per-device lease covers the whole sequence: check idle, transfer using a short-lived bearer URL, compare C5 image CRC, program the exact generation, poll its job ID, explicitly verify, then mark that release installed. Duplicate requests do not program twice. Settings, unpairing, changing permission/file assignments and other cloud commands cannot overlap the lease. Unexpected replies, timeouts, changed image/job IDs or server restart are reported as unknown, never success or automatic retry. Admin must inspect the device and explicitly reassign firmware before retrying an unknown update. Ordinary confirmed device failures remain retryable. Jobs continue when the phone closes the app; reopening Firmware reads server status.

Schema migration adds `device_policies`, `device_releases` and `update_jobs`; back up SQLite using its backup API before restarting the service. Startup also removes the five retired permissions and the two admin-only options from existing policies, preserving each parent setting's visibility and incrementing changed revisions so open apps refresh. Devices without a stored policy receive the current defaults at revision 1. This migration is idempotent. A process interrupted mid-update is marked unknown on startup. No ESP firmware change is required for the existing native C5 build. Run `MS51_TEST_WORK=<isolated directory> python server/test_managed.py` with test-only httpx installed: the suite covers existing admin authentication plus policy enforcement, migration, offset preservation, legacy bypasses, HEX validation, ownership, idempotency, leases and simulated upload/program/verify replies. These tests never issue hardware commands. `integration_test.py` and the Android `live` runner document the older unrestricted 3.0 flow and must not be used as a 3.1 production smoke test.

## Admin-only MCU options (3.4.10)

In **Cấp quyền**, saved app permissions and the two MCU options load automatically when the dialog opens. Select **Hiện ST LED** and **Xả đá OP tính giây**, then press **Lưu xuống mạch**. Use **Đọc lại từ mạch** to refresh or retry. The device must be online for MCU options; permission loading uses the server and is still available if MCU reading fails. Unknown options are shown indeterminate and cannot be saved. Closing while loading invalidates late responses. The server reads fresh configuration under a device lease, rejects stale option values, and merges only these two fields (plus the existing SET protocol PIN-runtime normalization). MCU acknowledgement is required for success; failures are never retried automatically. An unchanged selection does not write or restart. Saving changed values uses the existing MCU SET/restart behavior, including resetting running counters. Admin authentication, ownership validation and CSRF protection apply. Mobile settings saves preserve both option values, and the server rejects attempts to modify them even from older clients. APK 3.4.10 removes their controls unconditionally and moves all three ST timing fields into Advanced Settings. No firmware update is required.

## Admin console

The short entry URL **https://142-91-99-63.sslip.io/** redirects to the console; `/admin` and `/admin/` do the same. Install these exact-match HTTPS redirects on the existing VPS with `sudo python3 /opt/ms51-connect/configure_admin_entry.py`. The helper backs up the virtual host, validates nginx, then reloads it. Unknown routes still return 404; existing API and firmware-download routes are retained.

Open **https://142-91-99-63.sslip.io/ms51/connect/v1/admin/**. The Vietnamese navy/teal console works on desktop and phones. It lists existing mobile accounts, searches account/device names and IDs, shows each account's paired devices and fresh online/offline status, and edits usernames, resets passwords or renames devices. Status refreshes every 15 seconds; a heartbeat older than 45 seconds is offline. Broker/API failures show an unknown status instead of claiming a device is online.

Existing passwords are salted Argon2id hashes and cannot be displayed. Changing a user's name or password invalidates all their mobile sessions while preserving paired devices, firmware, and MQTT credentials. A no-op edit does not log them out. There are no account deletion or unpair actions in this console. The dedicated device options form can change the two MCU options described below.

Admin identities are separate from mobile accounts. Deploy `admin.py`, `admin_cli.py` and the entire `admin_web/` directory alongside `app.py`; restart only `ms51-connect`. No nginx change or new service is needed. Schema changes only add `administrators`, `admin_sessions` and `admin_audit` tables, retaining all existing data. Provision/reset an administrator from the trusted server shell:

```sh
sudo -u ms51-connect /opt/ms51-connect/.venv/bin/python /opt/ms51-connect/admin_cli.py admin
```

Enter a unique password of 12–128 characters at the hidden prompt. There is no default password and no public administrator registration endpoint. `--password-stdin` is available for secure deployment automation; never put passwords in command arguments or source control. Provisioning resets existing admin sessions. The administrator can subsequently change their own password in the console.

Admin sessions expire after eight hours and use a hashed random token with a Secure, HttpOnly, SameSite=Strict cookie scoped to `/ms51/connect/v1/admin/`. Writes require both the configured origin and a session CSRF token. Login is rate-limited. Responses use no-store and a same-origin CSP. Audit entries contain action/target identifiers, never passwords. All account/device JSON fields are explicitly selected; password hashes, mobile sessions and MQTT secrets are never returned. Host defaults to `https://` plus `MS51_HOST`; if changing the hostname, set `MS51_ADMIN_ORIGIN` to its exact HTTPS origin. Production must remain behind TLS and loopback-only uvicorn.

Isolated tests (install `httpx` in the test environment):

```powershell
$env:MS51_TEST_WORK='D:\Codex\Documents\ms51-admin-tests\work'
python server/test_admin.py
```

The suite creates its own SQLite database and never starts MQTT. It covers administrator/mobile separation, CSRF, cookie attributes, session revocation, duplicate-name rollback, device ownership, presence/search/pagination, password-reset/login races and existing mobile login/register behavior. Back up the live database using SQLite's backup API before deployment; do not copy only the main file while WAL is active.

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

### Admin grouping (2026-10-23)

The permission editor follows the Android settings layout: Thời gian hoạt động, Cài đặt nâng cao (Cài đặt khởi động, Chu trình, HCF, Hiển thị và thao tác, Thử lại), Giới hạn thời gian (Min / Max), and Mật khẩu bảo vệ. Hiện ST LED is integrated into Cài đặt khởi động; Xả đá OP tính giây is integrated into Chu trình. Both carry a Trên mạch badge and remain actual device settings, not app visibility permissions. Bulk visibility actions and policy saves only include the 44 permission fields. Lưu xuống mạch still submits the two device options separately, using the existing revision/acknowledgement behavior. No APK, firmware or database changes are required.

`node server/test_admin_access_ui.cjs` checks grouping, field coverage, separate save payloads, bulk actions, automatic reads, offline/error handling and late responses with mocked APIs only. Set MS51_UI_PREVIEW to a directory to capture desktop/mobile previews and check mobile overflow.
