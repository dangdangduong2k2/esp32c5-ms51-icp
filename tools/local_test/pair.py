"""Pair a physical C5 running the separate LAN firmware with the local test account.
Only pairing is written; runtime configuration and relays are not changed.
"""
import argparse,json,time
from pathlib import Path
import httpx
p=argparse.ArgumentParser()
p.add_argument('--root',type=Path,required=True)
p.add_argument('--device-url',required=True)
a=p.parse_args()
cfg=json.loads((a.root/'work/local-config.json').read_text())
base=f'https://{cfg["host"]}:8443/ms51/connect/v1'
with httpx.Client(verify=str(a.root/'work/certs/ca.pem'),timeout=45,trust_env=False) as cloud, httpx.Client(timeout=8,trust_env=False) as local:
    status=local.get(a.device_url+'/api/status').json()
    assert status['ok']
    device=status['device']['id']
    r=cloud.post(base+'/auth/login',json=dict(username='testlocal',password=cfg['user_password']))
    r.raise_for_status();token=r.json()['token']
    cloud.headers['Authorization']='Bearer '+token
    r=cloud.post(base+'/pair/start',json=dict(device=device));r.raise_for_status();claim=r.json()
    if not claim['paired']:
        # A running production build rejects this host. Never reset or replace its owner.
        r=local.post(a.device_url+'/api/cloud/pair',json=claim['config']);r.raise_for_status()
        assert r.json()['ok'],r.text
        for _ in range(30):
            r=cloud.get(base+'/pair/'+claim['claim_id']);r.raise_for_status()
            if r.json()['paired']:break
            time.sleep(1)
        else:raise RuntimeError('C5 did not prove pairing through TLS MQTT; inspect private logs')
    devices=cloud.get(base+'/devices').json()['devices']
    row=next(x for x in devices if x['id']==device)
    assert row['online'],row
    report=dict(device=device,paired=True,online=True,https_mqtt_hardware_path=True)
    (a.root/'outputs/pair-check.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report))
