"""Start/stop the Windows LAN server without changing PowerShell script execution policy."""
import argparse,json,os,subprocess,sys,time
from pathlib import Path
import httpx
p=argparse.ArgumentParser()
p.add_argument('action',choices=['start','stop','status'])
p.add_argument('root',type=Path)
a=p.parse_args();root=a.root.resolve();work=root/'work'
cfg=json.loads((work/'local-config.json').read_text())
url=f'https://{cfg["host"]}:8443/ms51/connect/v1/health'
def health():
    try:
        return httpx.get(url,verify=str(work/'certs/ca.pem'),timeout=3,trust_env=False).json()
    except Exception:return None
if a.action=='status':
    print(json.dumps(health() or {'ok':False}));sys.exit(0)
if a.action=='stop':
    pidfile=work/'pids.json'
    if pidfile.exists():
        for pid in json.loads(pidfile.read_text(encoding='utf-8-sig')).values():
            # Verify identity as well as PID, so stale PID files cannot stop another app.
            command=f'Get-CimInstance Win32_Process -Filter "ProcessId={int(pid)}" | Select-Object CommandLine | ConvertTo-Json -Compress'
            r=subprocess.run(['powershell.exe','-NoProfile','-Command',command],capture_output=True,text=True)
            process=json.loads(r.stdout.strip() or '{}')
            if process and str(root).lower() in (process.get('CommandLine') or '').lower():
                subprocess.run(['taskkill.exe','/PID',str(int(pid)),'/F'],stdout=subprocess.DEVNULL,check=True)
    print('Local test server stopped. Data preserved.');sys.exit(0)
if health():
    print('Local test server is already running.');sys.exit(0)
# Do not create orphan processes if another service owns one of the ports.
import socket
for port in (8443,8884):
    with socket.socket() as s:
        if s.connect_ex((cfg['host'],port))==0:raise SystemExit(f'Port {port} is already occupied; inspect processes first.')
env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',TEMP=str(work/'tmp'),TMP=str(work/'tmp'))
(work/'tmp').mkdir(exist_ok=True)
flags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
commands={'broker':[str(Path(cfg['broker'])/'mosquitto.exe'),'-c',str(work/'mosquitto.conf')],
          'api':[cfg['python'],str(Path(__file__).with_name('run.py')),str(root)]}
pids={}
for name,cmd in commands.items():
    with (work/(name+'.log')).open('w') as log,(work/(name+'-error.log')).open('w') as err:
        child=subprocess.Popen(cmd,stdout=log,stderr=err,stdin=subprocess.DEVNULL,env=env,creationflags=flags)
        pids[name]=child.pid
(work/'pids.json').write_text(json.dumps(pids))
for _ in range(15):
    state=health()
    if state and state.get('mqtt'):
        print(f'Ready: https://{cfg["host"]}:8443/ms51/connect/v1/admin/');break
    time.sleep(1)
else:raise SystemExit('Startup not confirmed. Inspect work/api-error.log and work/broker-error.log.')
