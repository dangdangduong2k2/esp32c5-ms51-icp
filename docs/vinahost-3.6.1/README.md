# VinaHost production deployment — 3.6.1

Production API/admin: https://103-9-78-139.sslip.io/ms51/connect/v1/
Public IPv4: 103.9.78.139. Host provisioned with 1 vCPU, approximately 1 GiB RAM,
25 GB disk. Ubuntu: 24.04 LTS (upgraded from the supplied 20.04 image); 2 GiB swap added during provisioning.
This is a NEW registry, as requested. No old VPS or LAN-test database is imported.
Passwords, SSH credentials, SQLite state, broker secrets and private backups remain
outside this repository on D:.

## Clients

- Android: versionName 3.6.1 / versionCode 52; original production application ID
  and signing key. The LAN test app remains a separate application.
- The encrypted phone session now records its API endpoint. A legacy session or
  one saved for another endpoint is discarded locally and requires login again.
- C5: production host changed to 103-9-78-139.sslip.io. A separate NVS namespace
  `ms51_vina` preserves old VPS/LAN credentials without reusing them on the new registry.
- C5 build uses the existing board profile: DAT1 / CLK0 / RS3; NTC 10k IO5,
  20k IO6, 50k IO4; 4 MiB flash header. Partition table matches the LAN build.
- Existing bo: program ONLY the app at 0x10000, without erase-flash. Wi-Fi and
  MCU settings remain intact; pair to the new server after boot. No MS51 update.
- New compatible bo: bootloader 0x2000, partition table 0x8000, app 0x10000.

Client files are exported for later reuse. After the user connected C5, its application
was backed up, programmed and verified through JTAG. NVS was byte-for-byte unchanged;
MS51 was not programmed. The board returned to Wi-Fi after a physical EN reset.

## Fresh server provisioning

Do not use the historical `server/bootstrap.py`, which augments the old VPS.
On a fresh Ubuntu 24.04 server install nginx, Mosquitto 2.x, mosquitto-clients,
certbot, python3-venv and ca-certificates. Copy server/ to /opt/ms51-connect.
Create /opt/ms51-connect/.venv and install requirements.lock. Code and virtualenv
must be readable/executable by the ms51-connect service user (normally umask 022).

1. Run `python3 /opt/ms51-connect/bootstrap_fresh.py --host HOST`.
2. Allow TCP 22 (SSH), 80 (ACME), 443 (HTTPS), 8884 (MQTT TLS) in the firewall.
3. Obtain a public certificate using Certbot webroot /var/www/letsencrypt for HOST.
4. Run the bootstrap again with `--tls`; it generates broker credentials, sets
   exact-topic default-deny ACLs, creates users/systemd units and enables services.
5. Provision the administrator with admin_cli.py --password-stdin. Create the
   requested mobile account using HTTPS registration; never reuse the SSH password.
6. Enable certbot.timer. The deploy hook copies renewed certificates into the
   broker-readable directory, then reloads MQTT and nginx.

The API binds only to 127.0.0.1:8787, with one worker. nginx terminates TLS and
overwrites X-Real-IP. MQTT TLS listens on 8884. No TLS verification bypass is used.
The website root and /admin redirect to the console. Public client installers can
be placed in /var/www/ms51-downloads; directory listing is disabled. Never place
private account files, firmware assignments or backups there.

## Verification

- Android assembleDebug + lintDebug.
- Android emulator session-server regression: current endpoint restores;
  other-endpoint and unscoped legacy sessions require re-login.
- 14 administrator tests and 36 managed-device tests.
- Admin UI regression: grouping, read/save, CSRF/error-related UI behavior,
  and no duplicate device writes.
- C5 build, partition equality, app size and binary endpoint checks.
- Physical C5: app verification passed, NVS unchanged, Wi-Fi returned after EN reset.
  MS51 was not programmed or sent runtime-setting/relay test commands.
- Live public HTTPS/admin authentication, CSRF rejection, simulated MQTT pairing,
  physical C5 pairing/online state and public download hashes passed.
- Results and hashes: [test-results.json](test-results.json).

## Private backups

Use SQLite's backup API for the database while WAL is enabled. Preserve the matching
dynamic-security.json and /etc/ms51-connect.env, service/nginx config, certificate
renewal setup and certificate keys. Do not commit these. A fresh deployment does not
recover accounts from the unpaid old VPS; users register or use new credentials.


The migrated VPS uses systemd-resolved, enabled at boot. Its DNS override is
/etc/systemd/resolved.conf.d/ms51-dns.conf (1.1.1.1 and 8.8.8.8). The original
image used legacy network configuration; the upgrade left resolved disabled,
which was corrected before deployment. SSH socket/service are both active.
