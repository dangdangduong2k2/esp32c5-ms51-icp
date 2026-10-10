"""Development-only localhost bridge for Android emulator -> C5 USB HTTP console.
Only provisioning requests use this adapter. Remote control goes through the real VPS/MQTT.
The emulator cannot join the physical ESP AP; report that limitation when using this test.
Requires pyserial. Never logs request bodies or serial echoes (may contain credentials).
"""
import json
import queue
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import serial

link = serial.Serial(port=None, baudrate=115200, timeout=.05, write_timeout=3)
link.dtr = False; link.rts = False; link.port = 'COM49'; link.open()
mutex = threading.Lock()
local_requests = 0
protocol_override = None
incoming = queue.Queue(maxsize=2048)


def serial_pump():
    pending = ''
    while True:
        part = link.read(max(1,min(link.in_waiting,4096)))
        if not part: continue
        if b'\x1b[6n' in part: link.write(b'\x1b[1;1R')
        pending += part.decode('utf-8','replace')
        lines = pending.split('\n'); pending = lines.pop()[-2048:]
        for line in lines:
            # Print only fixed diagnostic forms, never serial command echoes/credentials.
            for match in re.finditer(r'Upload rejected: ESP_[A-Z_]+|mbedtls_ssl_\w+ returned -0x[0-9a-fA-F]+|(-0x[0-9a-fA-F]+)|alloc(?:ate|ating|ation)[^\r\n]{0,80}',line):
                print(match.group(),flush=True)
        try: incoming.put_nowait(part)
        except queue.Full:
            incoming.get_nowait(); incoming.put_nowait(part)


def collect(seconds):
    data = bytearray(); end = time.monotonic() + seconds
    while time.monotonic() < end:
        try: part = incoming.get(timeout=min(.05,max(.001,end-time.monotonic())))
        except queue.Empty: continue
        data.extend(part)
    return data.decode('utf-8','replace')


def api(method, path, body=None):
    with mutex:
        collect(.1)
        command = f'api {method} {path}'
        if body is not None:
            raw = json.dumps(body,separators=(',',':'),ensure_ascii=True)
            command += ' "' + raw.replace('\\','\\\\').replace('"','\\"') + '"'
        text = ''; wire = (command+'\r').encode()
        for start in range(0,len(wire),32):
            link.write(wire[start:start+32]); text += collect(.04)
        end = time.monotonic()+30
        while 'API_END' not in text and time.monotonic()<end: text += collect(.1)
        match = re.search(r'API_BEGIN (\d+)\s*(.*?)\s*API_END',text,re.S)
        if not match: raise RuntimeError('C5 USB console did not complete the request')
        return int(match.group(1)), json.loads(match.group(2))


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self): self.handle_request('GET')
    def do_POST(self): self.handle_request('POST')
    def handle_request(self, method):
        global local_requests, protocol_override
        try:
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or b'{}') if method=='POST' else None
            if self.path == '/__test':
                if body and 'protocol' in body: protocol_override=body['protocol']
                code, result=200,dict(ok=True, requests=local_requests)
            elif self.path in ('/api/status','/api/cloud/pair','/api/uplink/scan'):
                local_requests += 1
                code,result=api(method,self.path,body)
                if self.path=='/api/status':
                    # Route the emulator's LAN verification back to this localhost adapter.
                    result['wifi']['uplink']['ip']='10.0.2.2'
                    if protocol_override is not None: result['cloud']['protocol']=protocol_override
            else: code,result=404,dict(ok=False,message='Unsupported test adapter route')
        except Exception as e:
            code,result=502,dict(ok=False,message=str(e))
        wire=json.dumps(result).encode()
        self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(wire)))
        self.end_headers();self.wfile.write(wire)


if __name__=='__main__':
    threading.Thread(target=serial_pump,daemon=True).start()
    collect(.2);link.write(b'\x03\r');collect(.2)
    print('C5 provisioning adapter listening on localhost:80; bodies are not logged.',flush=True)
    ThreadingHTTPServer(('127.0.0.1',80),Handler).serve_forever()
