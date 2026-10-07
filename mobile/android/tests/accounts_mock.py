from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from threading import Lock
import json, time
from pathlib import Path

lock=Lock()
state={}
def reset():
    state.clear()
    state.update(delay=0, scan_error=False, status_error=False, connect_result='failed',
                 wifi_state='not_configured', ssid='', scans=0, writes=0, last_body=None, local_id='ms51-3844be1fe4e0', force_id='', status_delay=0, config_delay=0, requests=[], internet='online')
reset()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def reply(self,code,obj):
        data=json.dumps(obj,ensure_ascii=False).encode()
        try:
            self.send_response(code); self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError): pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))) or b'{}')
        if self.path=='/__test':
            with lock:
                if body.pop('reset',False): reset()
                state.update(body); result=state.copy()
            return self.reply(200,result)
        expected=self.headers.get('X-MS51-Device','')
        with lock: state['requests'].append({'method':'POST','path':self.path,'device':expected})
        if state['force_id'] and expected and expected!=state['force_id']:
            return self.reply(409,{'ok':False,'message':'Wrong device'})
        if self.path=='/api/runtime-config':
            return self.reply(200,{'ok':True,'message':'Saved'})
        if self.path=='/api/uplink':
            with lock:
                state['writes']+=1; state['last_body']=body
                state['ssid']=body.get('ssid',''); state['wifi_state']=state['connect_result']; delay=state['delay']
            time.sleep(delay)
            return self.reply(202,{'ok':True,'accepted':True})
        self.reply(404,{'ok':False})
    def do_GET(self):
        if self.path=='/__test':
            with lock: result=state.copy()
            return self.reply(200,result)
        expected=self.headers.get('X-MS51-Device','')
        with lock: state['requests'].append({'method':'GET','path':self.path,'device':expected})
        if state['force_id'] and expected and expected!=state['force_id']:
            # Deliberately emulate an old server which ignores the identity header.
            if self.path!='/api/status': return self.reply(409,{'ok':False,'message':'Wrong device'})
        if self.path=='/api/runtime-config':
            time.sleep(state['config_delay'])
            return self.reply(200,{'ok':True,'data':Path(__file__).with_name('config.hex').read_text().strip()})
        if self.path=='/api/uplink/scan':
            with lock: state['scans']+=1; delay=state['delay']; error=state['scan_error']
            time.sleep(delay)
            return self.reply(503 if error else 200,{'ok':not error,'message':'Scan unavailable' if error else '',
                'networks':[{'ssid':'Home 5G','channel':36,'auth':'WPA2','rssi':-42},
                            {'ssid':'Home 2G','channel':6,'auth':'WPA2','rssi':-55},
                            {'ssid':'Guest','channel':149,'auth':'Mo','rssi':-61}]})
        if self.path=='/api/status':
            with lock: data=state.copy()
            time.sleep(data['status_delay'])
            identity=data['force_id'] or expected or data['local_id']
            if data['status_error']: return self.reply(503,{'ok':False,'message':'Device unavailable'})
            return self.reply(200,{'ok':True,'busy':False,'image':{'valid':False},
                'device':{'id':identity,'hostname':identity+'.local'},
                'internet':{'state':data['internet'] if identity.endswith('e4e0') else 'unverified','checked_age_ms':1000},
                'wifi':{'ssid':'MS51-PROGRAMMER-'+identity[5:].upper(),'ip':'192.168.4.1','uplink':{
                    'state':data['wifi_state'],'ssid':data['ssid'],'ip':'10.0.2.2','rssi':-42}}})
        if self.path=='/api/debug':
            return self.reply(200,{'ok':True,'enabled':True,'variables':[{'name':'mode','value':'COOL [SIM]'}],'logs':[]})
        self.reply(404,{'ok':False})

print('Provisioning test server listening on localhost:80',flush=True)
ThreadingHTTPServer(('127.0.0.1',80),Handler).serve_forever()
