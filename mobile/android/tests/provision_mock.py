from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from threading import Lock
import json, time

lock=Lock()
state={}
def reset():
    state.clear()
    state.update(delay=0, scan_error=False, status_error=False, connect_result='failed',
                 wifi_state='not_configured', ssid='', scans=0, writes=0, last_body=None)
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
        if self.path=='/api/uplink/scan':
            with lock: state['scans']+=1; delay=state['delay']; error=state['scan_error']
            time.sleep(delay)
            return self.reply(503 if error else 200,{'ok':not error,'message':'Scan unavailable' if error else '',
                'networks':[{'ssid':'Home 5G','channel':36,'auth':'WPA2','rssi':-42},
                            {'ssid':'Home 2G','channel':6,'auth':'WPA2','rssi':-55},
                            {'ssid':'Guest','channel':149,'auth':'Mo','rssi':-61}]})
        if self.path=='/api/status':
            with lock: data=state.copy()
            if data['status_error']: return self.reply(503,{'ok':False,'message':'Device unavailable'})
            return self.reply(200,{'ok':True,'busy':False,'image':{'valid':False},
                'wifi':{'ssid':'MS51-PROGRAMMER','ip':'192.168.4.1','uplink':{
                    'state':data['wifi_state'],'ssid':data['ssid'],'ip':'10.0.2.2','rssi':-42}}})
        if self.path=='/api/debug':
            return self.reply(200,{'ok':True,'enabled':True,'variables':[{'name':'mode','value':'COOL [SIM]'}],'logs':[]})
        self.reply(404,{'ok':False})

print('Provisioning test server listening on localhost:80',flush=True)
ThreadingHTTPServer(('127.0.0.1',80),Handler).serve_forever()
