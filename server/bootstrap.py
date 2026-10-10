"""Run as root after copying server/ to /opt/ms51-connect. No secrets are printed."""
import json
import os
from pathlib import Path
import pwd
import grp
import secrets
import subprocess

root = Path('/opt/ms51-connect')
state = Path('/var/lib/ms51-connect-mqtt')
state.mkdir(mode=0o750, exist_ok=True)
config = state / 'dynamic-security.json'
env = Path('/etc/ms51-connect.env')
if not config.exists():
    password = secrets.token_urlsafe(40)
    subprocess.run(['mosquitto_ctrl', 'dynsec', 'init', str(config), 'connect-admin', password],
                   check=True, stdout=subprocess.DEVNULL)
    data = json.loads(config.read_text())
    data['defaultACLAccess'] = dict(publishClientSend=False, publishClientReceive=False, subscribe=False, unsubscribe=True)
    data['roles'].extend([
        dict(rolename='service', acls=[dict(acltype=t, topic='ms51connect/v1/#', allow=True) for t in
             ['publishClientSend', 'publishClientReceive', 'subscribePattern']])])
    data['clients'][0]['roles'].append(dict(rolename='service'))
    config.write_text(json.dumps(data, indent=2))
    env.write_text('MS51_HOST=142-91-99-63.sslip.io\nMS51_MQTT_PORT=8884\n'
                   'MS51_MQTT_ADMIN=connect-admin\nMS51_MQTT_PASSWORD=' + password + '\n')
    env.chmod(0o600)
os.chown(state, pwd.getpwnam('mosquitto').pw_uid, grp.getgrnam('mosquitto').gr_gid)
os.chown(config, pwd.getpwnam('mosquitto').pw_uid, grp.getgrnam('mosquitto').gr_gid)
config.chmod(0o600)
Path('/etc/mosquitto/ms51-connect.conf').write_text('''
listener 8884 0.0.0.0
protocol mqtt
allow_anonymous false
plugin /usr/lib/x86_64-linux-gnu/mosquitto_dynamic_security.so
plugin_opt_config_file /var/lib/ms51-connect-mqtt/dynamic-security.json
certfile /etc/mosquitto/certs/ms51-mqtt-fullchain.pem
keyfile /etc/mosquitto/certs/ms51-mqtt-privkey.pem
tls_version tlsv1.2
max_connections 1000
message_size_limit 65536
max_queued_messages 20
max_inflight_messages 10
persistence false
log_dest stdout
log_type error
log_type warning
'''.lstrip())
Path('/etc/systemd/system/ms51-connect-mqtt.service').write_text('''
[Unit]
Description=MS51 Connect isolated MQTT broker
After=network-online.target
Wants=network-online.target
[Service]
User=mosquitto
Group=mosquitto
ExecStart=/usr/sbin/mosquitto -c /etc/mosquitto/ms51-connect.conf
ExecReload=/bin/kill -HUP $MAINPID
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/ms51-connect-mqtt
UMask=0077
[Install]
WantedBy=multi-user.target
'''.lstrip())
Path('/etc/systemd/system/ms51-connect.service').write_text('''
[Unit]
Description=MS51 Connect account and device API
After=network-online.target ms51-connect-mqtt.service
Wants=network-online.target ms51-connect-mqtt.service
[Service]
User=ms51-connect
Group=ms51-connect
WorkingDirectory=/opt/ms51-connect
EnvironmentFile=/etc/ms51-connect.env
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/opt/ms51-connect/.venv/bin/uvicorn app:app --host 127.0.0.1 --port 8787 --workers 1 --no-access-log
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/ms51-connect
UMask=0077
[Install]
WantedBy=multi-user.target
'''.lstrip())
hook = Path('/etc/letsencrypt/renewal-hooks/deploy/52-ms51-connect')
hook.write_text('#!/bin/sh\nset -eu\ncase "${RENEWED_LINEAGE:-}" in */ms51-mqtt) systemctl reload ms51-connect-mqtt ;; esac\n')
hook.chmod(0o755)
# Only augment the existing matching virtual host; keep unrelated routes verbatim.
site = Path('/etc/nginx/sites-available/ms51-updates-v2')
current = site.read_text()
if 'location /ms51/connect/v1/' not in current:
    backup = site.with_suffix('.before-connect')
    if not backup.exists():
        backup.write_text(current)
    assert 'server_name 142-91-99-63.sslip.io;' in current
    location = '''    location /ms51/connect/v1/ {
        proxy_pass http://127.0.0.1:8787/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 45s;
        proxy_connect_timeout 3s;
        client_max_body_size 128k;
        add_header Cache-Control "no-store" always;
    }
'''
    site.write_text(current.replace('    location / {', location + '    location / {'))
    assert location in site.read_text()
# Bound TLS records for C5's dynamic receive buffers during full-size transfers.
current = site.read_text()
if 'ssl_buffer_size 4k;' not in current:
    anchor = 'server_name 142-91-99-63.sslip.io;'
    assert anchor in current
    site.write_text(current.replace(anchor, anchor + '\n    ssl_buffer_size 4k;'))
subprocess.run(['nginx', '-t'], check=True)
print('Configuration ready; existing services and routes preserved.')
