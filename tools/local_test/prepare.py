"""Prepare isolated Windows LAN testing. No live VPS data or credentials are copied."""
import argparse
import datetime as dt
import ipaddress
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--host', required=True, help='Static/reserved LAN IPv4 address')
p.add_argument('--broker', type=Path, required=True)
a = p.parse_args()
ipaddress.IPv4Address(a.host)
repo = Path(__file__).resolve().parents[2]
root = a.root.resolve()
work, outputs = root/'work', root/'outputs'
state, certs = work/'state', work/'certs'
for d in (work, outputs, state, certs):
    d.mkdir(parents=True, exist_ok=True)
cfg_path = work/'local-config.json'
if cfg_path.exists():
    cfg = json.loads(cfg_path.read_text())
    if cfg['host'] != a.host:
        raise SystemExit('IP changed: use a new test root and rebuild clients; existing pairing preserved.')
else:
    cfg = dict(host=a.host, broker=str(a.broker.resolve()), python=sys.executable,
               mqtt_password=secrets.token_urlsafe(32), admin_password=secrets.token_urlsafe(18),
               user_password=secrets.token_urlsafe(18))
    cfg_path.write_text(json.dumps(cfg, indent=2))

if not (certs/'ca.pem').exists():
    now = dt.datetime.now(dt.timezone.utc)
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Hen Gio Lanh LAN Test '+a.host)])
    ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now-dt.timedelta(days=1))
          .not_valid_after(now+dt.timedelta(days=365)).add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), True)
          .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()),False)
          .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False)
          .sign(ca_key, hashes.SHA256()))
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, a.host)]))
            .issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-dt.timedelta(days=1)).not_valid_after(now+dt.timedelta(days=180))
            .add_extension(x509.BasicConstraints(ca=False,path_length=None), True)
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(a.host)),
                x509.DNSName(a.host), x509.IPAddress(ipaddress.ip_address('127.0.0.1')), x509.DNSName('localhost')]),False)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False)
            .add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False)
            .sign(ca_key,hashes.SHA256()))
    for filename, value in [('ca.pem',ca.public_bytes(serialization.Encoding.PEM)),
                            ('server.pem',leaf.public_bytes(serialization.Encoding.PEM)),
                            ('server-key.pem',key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))]:
        (certs/filename).write_bytes(value)
    # CA signing key is deliberately not persisted. Recreate the test environment to renew.

dynsec = state/'dynamic-security.json'
if not dynsec.exists():
    subprocess.run([str(a.broker/'mosquitto_ctrl.exe'), 'dynsec','init',str(dynsec),'connect-admin',cfg['mqtt_password']],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    data = json.loads(dynsec.read_text())
    data['defaultACLAccess'] = dict(publishClientSend=False,publishClientReceive=False,subscribe=False,unsubscribe=True)
    data['roles'].append(dict(rolename='service',acls=[dict(acltype=t,topic='ms51connect/v1/#',allow=True)
        for t in ['publishClientSend','publishClientReceive','subscribePattern']]))
    data['clients'][0]['roles'].append(dict(rolename='service'))
    dynsec.write_text(json.dumps(data,indent=2))

(work/'mosquitto.conf').write_text(f'''listener 8884 {a.host}
allow_anonymous false
plugin {(a.broker/'mosquitto_dynamic_security.dll').as_posix()}
plugin_opt_config_file {dynsec.as_posix()}
certfile {(certs/'server.pem').as_posix()}
keyfile {(certs/'server-key.pem').as_posix()}
tls_version tlsv1.2
max_connections 100
message_size_limit 65536
max_queued_messages 20
persistence false
log_dest stdout
log_type error
log_type warning
''')
shutil.copytree(repo/'server',work/'server',dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
ca_text = (certs/'ca.pem').read_text()
(work/'cloud-local.h').write_text('\n'.join([
    '#define CLOUD_HOST '+json.dumps(a.host),
    '#define CLOUD_HTTPS_ORIGIN '+json.dumps(f'https://{a.host}:8443'),
    '#define CLOUD_NVS_NAMESPACE "ms51_lan"',
    '#define CLOUD_CA_PEM '+json.dumps(ca_text), '']))
res = work/'android-res'
(res/'raw').mkdir(parents=True,exist_ok=True)
(res/'xml').mkdir(exist_ok=True)
shutil.copy2(certs/'ca.pem',res/'raw/local_test_ca.pem')
(res/'xml/network_security_config.xml').write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <base-config cleartextTrafficPermitted="true" />
  <domain-config cleartextTrafficPermitted="false">
    <domain>{a.host}</domain>
    <trust-anchors><certificates src="@raw/local_test_ca" /></trust-anchors>
  </domain-config>
</network-security-config>
''')
credentials = (f'TEST ONLY — not your VPS accounts\nAdmin: adminlocal\nPassword: {cfg["admin_password"]}\n'
               f'App: testlocal\nPassword: {cfg["user_password"]}\n'
               f'Admin URL: https://{a.host}:8443/ms51/connect/v1/admin/\n')
(outputs/'TAI-KHOAN-TEST-RIENG.txt').write_text(credentials,encoding='utf-8')
shutil.copy2(certs/'ca.pem',outputs/'local-test-ca.crt')
print(json.dumps(dict(host=a.host,admin=f'https://{a.host}:8443/ms51/connect/v1/admin/',prepared=True)))
