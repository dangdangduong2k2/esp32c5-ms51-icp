"""Run the isolated HTTPS API in the foreground (launch hidden from start.ps1)."""
import json
import os
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
work = root/'work'
cfg = json.loads((work/'local-config.json').read_text())
os.environ.update(MS51_HOST=cfg['host'],MS51_MQTT_PORT='8884',MS51_DATA=str(work/'state'),
                  MS51_MQTT_ADMIN='connect-admin',MS51_MQTT_PASSWORD=cfg['mqtt_password'],
                  MS51_MQTT_CA=str(work/'certs/ca.pem'),MS51_ADMIN_ORIGIN=f'https://{cfg["host"]}:8443')
sys.path.insert(0,str(work/'server'))
from fastapi import FastAPI
from fastapi.responses import RedirectResponse
import app as core
import uvicorn

# Preserve nginx's external URL prefix and the original secure-cookie/CSRF rules.
web = FastAPI(lifespan=core.lifespan,docs_url=None,redoc_url=None,openapi_url=None)
@web.middleware('http')
async def client_address(request,call_next):
    # Unlike nginx, this listener is directly reachable on the LAN. Ignore spoofed proxy headers.
    request.scope['headers']=[(k,v) for k,v in request.scope['headers'] if k.lower()!=b'x-real-ip']
    return await call_next(request)
web.mount(core.API,core.app)
@web.get('/')
def index():
    return RedirectResponse(core.API+'/admin/')

uvicorn.run(web,host=cfg['host'],port=8443,workers=1,access_log=False,proxy_headers=False,
            ssl_certfile=str(work/'certs/server.pem'),ssl_keyfile=str(work/'certs/server-key.pem'))
