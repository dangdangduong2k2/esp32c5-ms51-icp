"""Provision an isolated MS51 stack on a fresh Ubuntu VPS. Run as root.
Install nginx, mosquitto (2.x), mosquitto-clients, certbot and python3-venv first.
Run --host HOST, obtain a webroot certificate, then rerun with --tls.
Secrets are generated on the VPS, never shipped in source.
"""
import argparse, json, os, pathlib, pwd, grp, re, secrets, shutil, subprocess
P=pathlib.Path
a=argparse.ArgumentParser()
a.add_argument('--host',required=True)
a.add_argument('--tls',action='store_true')
args=a.parse_args()
host=args.host
if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?',host):
    raise SystemExit('Invalid DNS hostname')
def run(*cmd): subprocess.run(cmd,check=True)
def write(path,text,mode=0o644):
    p=P(path);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);p.chmod(mode)
marker='# Managed by MS51 bootstrap_fresh.py\n'
site=P('/etc/nginx/sites-available/ms51-connect')
if site.exists() and not site.read_text().startswith(marker):
    raise SystemExit('Refusing to overwrite an unrelated nginx site')
existing_env=P('/etc/ms51-connect.env')
if existing_env.exists() and f'MS51_HOST={host}\n' not in existing_env.read_text():
    raise SystemExit('Existing service belongs to another hostname')
if site.exists() and 'listen 443 ssl;' in site.read_text() and not args.tls:
    print('TLS site already present; use --tls to refresh it')
    raise SystemExit(0)
P('/var/www/letsencrypt').mkdir(parents=True,exist_ok=True)
http=f"""server {{
    listen 80;
    server_name {host};
    location ^~ /.well-known/acme-challenge/ {{ root /var/www/letsencrypt; }}
    location / {{ return 301 https://{host}$request_uri; }}
}}
"""
tls=f"""server {{
    listen 443 ssl;
    server_name {host};
    ssl_certificate /etc/letsencrypt/live/{host}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/{host}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_buffer_size 4k;
    location = / {{ return 302 /ms51/connect/v1/admin/; }}
    location = /admin {{ return 302 /ms51/connect/v1/admin/; }}
    location = /admin/ {{ return 302 /ms51/connect/v1/admin/; }}
    location /downloads/ {{
        alias /var/www/ms51-downloads/;
        autoindex off;
        add_header Content-Disposition "attachment";
        add_header X-Content-Type-Options "nosniff";
    }}
    location /ms51/connect/v1/ {{
        proxy_pass http://127.0.0.1:8787/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 45s;
        proxy_connect_timeout 3s;
        client_max_body_size 128k;
        add_header Cache-Control "no-store" always;
    }}
    location / {{ return 404; }}
}}
"""
if args.tls:
    if not P(f'/etc/letsencrypt/live/{host}/fullchain.pem').exists():
        raise SystemExit('Obtain certificate before enabling TLS')
write(site,marker+http+(tls if args.tls else ''))
link=P('/etc/nginx/sites-enabled/ms51-connect')
if not link.exists():link.symlink_to(site)
run('nginx','-t');run('systemctl','reload','nginx')
if not args.tls:
    print('HTTP challenge site ready');raise SystemExit(0)
try:pwd.getpwnam('ms51-connect')
except KeyError:run('useradd','--system','--home','/var/lib/ms51-connect','--shell','/usr/sbin/nologin','ms51-connect')
uid=pwd.getpwnam('ms51-connect').pw_uid;gid=grp.getgrnam('ms51-connect').gr_gid
data=P('/var/lib/ms51-connect');data.mkdir(exist_ok=True);data.chmod(0o700);os.chown(data,uid,gid)
state=P('/var/lib/ms51-connect-mqtt');state.mkdir(exist_ok=True);state.chmod(0o750)
config=state/'dynamic-security.json';env=P('/etc/ms51-connect.env')
if config.exists() != env.exists(): raise SystemExit('Incomplete existing broker provisioning: inspect before continuing')
if not config.exists():
    password=secrets.token_urlsafe(40)
    subprocess.run(['mosquitto_ctrl','dynsec','init',str(config),'connect-admin',password],check=True,stdout=subprocess.DEVNULL)
    d=json.loads(config.read_text())
    d['defaultACLAccess']=dict(publishClientSend=False,publishClientReceive=False,subscribe=False,unsubscribe=True)
    d['roles'].append(dict(rolename='service',acls=[dict(acltype=t,topic='ms51connect/v1/#',allow=True) for t in ['publishClientSend','publishClientReceive','subscribePattern']]))
    d['clients'][0]['roles'].append(dict(rolename='service'))
    config.write_text(json.dumps(d,indent=2))
    write(env,f'MS51_HOST={host}\nMS51_MQTT_PORT=8884\nMS51_MQTT_ADMIN=connect-admin\nMS51_MQTT_PASSWORD={password}\n',0o600)
elif f'MS51_HOST={host}\n' not in env.read_text():
    raise SystemExit('Existing environment belongs to another hostname')
muid=pwd.getpwnam('mosquitto').pw_uid;mgid=grp.getgrnam('mosquitto').gr_gid
for p in (state,config):os.chown(p,muid,mgid)
config.chmod(0o600)
certs=P('/etc/mosquitto/ms51-certs');certs.mkdir(exist_ok=True);certs.chmod(0o750);os.chown(certs,0,mgid)
for name in ('fullchain.pem','privkey.pem'):
    dst=certs/name;shutil.copyfile(f'/etc/letsencrypt/live/{host}/{name}',dst);dst.chmod(0o640);os.chown(dst,0,mgid)
write('/etc/mosquitto/ms51-connect.conf',"""listener 8884 0.0.0.0
protocol mqtt
allow_anonymous false
plugin /usr/lib/x86_64-linux-gnu/mosquitto_dynamic_security.so
plugin_opt_config_file /var/lib/ms51-connect-mqtt/dynamic-security.json
certfile /etc/mosquitto/ms51-certs/fullchain.pem
keyfile /etc/mosquitto/ms51-certs/privkey.pem
tls_version tlsv1.2
max_connections 1000
message_size_limit 65536
max_queued_messages 20
max_inflight_messages 10
persistence false
log_dest stdout
log_type error
log_type warning
""")
write('/etc/systemd/system/ms51-connect-mqtt.service',"""[Unit]
Description=MS51 authenticated MQTT
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
""")
write('/etc/systemd/system/ms51-connect.service',"""[Unit]
Description=MS51 account and device API
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
""")
write('/etc/letsencrypt/renewal-hooks/deploy/52-ms51-connect',f"""#!/bin/sh
set -eu
if [ "${{RENEWED_LINEAGE:-}}" = "/etc/letsencrypt/live/{host}" ]; then
    install -o root -g mosquitto -m 640 "$RENEWED_LINEAGE/fullchain.pem" /etc/mosquitto/ms51-certs/fullchain.pem
    install -o root -g mosquitto -m 640 "$RENEWED_LINEAGE/privkey.pem" /etc/mosquitto/ms51-certs/privkey.pem
    systemctl reload ms51-connect-mqtt
    systemctl reload nginx
fi
""",0o755)
run('systemctl','daemon-reload')
run('systemctl','enable','--now','ms51-connect-mqtt','ms51-connect')
print('MS51 TLS services enabled. No account secrets printed.')
