"""Create isolated test accounts and verify HTTPS/admin authentication. No MCU writes."""
import json
import os
from pathlib import Path
import subprocess
import sys
import httpx

root=Path(sys.argv[1]).resolve()
work=root/'work'
cfg=json.loads((work/'local-config.json').read_text())
env=dict(os.environ,MS51_DATA=str(work/'state'),PYTHONDONTWRITEBYTECODE='1')
subprocess.run([sys.executable,str(work/'server/admin_cli.py'),'adminlocal','--password-stdin'],
               input=cfg['admin_password']+'\n',text=True,env=env,check=True,stdout=subprocess.DEVNULL)
origin=f'https://{cfg["host"]}:8443'
base=origin+'/ms51/connect/v1'
with httpx.Client(verify=str(work/'certs/ca.pem'),timeout=15,trust_env=False) as client:
    health=client.get(base+'/health').json()
    assert health['ok'] and health['mqtt'],health
    page=client.get(base+'/admin/')
    assert page.status_code==200 and '<html' in page.text
    r=client.post(base+'/admin/api/login',headers={'Origin':origin},json={'username':'adminlocal','password':cfg['admin_password']})
    assert r.status_code==200,r.text
    assert 'Secure' in r.headers['set-cookie'] and 'HttpOnly' in r.headers['set-cookie']
    assert client.get(base+'/admin/api/session').status_code==200
    assert client.post(base+'/admin/api/logout',headers={'Origin':'https://example.invalid'},json={}).status_code==403
    csrf=r.json()['csrf']
    assert client.post(base+'/admin/api/logout',headers={'Origin':origin,'X-CSRF-Token':csrf},json={}).status_code==200
    credentials={'username':'testlocal','password':cfg['user_password']}
    r=client.post(base+'/auth/login',json=credentials)
    if r.status_code!=200:
        r=client.post(base+'/auth/register',json=credentials)
    assert r.status_code==200,r.text
    token=r.json()['token']
    r=client.get(base+'/devices',headers={'Authorization':'Bearer '+token})
    assert r.status_code==200,r.text
report=dict(https=True,mqtt=True,admin_page=True,admin_login=True,secure_cookie=True,csrf_rejection=True,
            mobile_login=True,device_list=True)
(root/'outputs/server-check.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
