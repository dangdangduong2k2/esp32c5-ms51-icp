from pathlib import Path
import serial,time,re,json,sys
base=Path(__file__).resolve().parent.parent
(base/'work').mkdir(exist_ok=True)
(base/'outputs/esp32c5-test').mkdir(parents=True,exist_ok=True)
logpath=base/'work/esp32c5-serial.log'
link=serial.Serial(port=None,baudrate=115200,timeout=.2,write_timeout=2)
link.dtr=False;link.rts=False;link.port='COM49';link.open()
def collect(seconds):
    end=time.monotonic()+seconds; data=bytearray()
    while time.monotonic()<end:
        available=link.in_waiting
        part=link.read(min(available,4096)) if available else b''
        data.extend(part)
        time.sleep(.02)
        if b'\x1b[6n' in part:link.write(b'\x1b[1;1R')
    text=data.decode('utf-8','replace')
    with logpath.open('a',encoding='utf-8') as f:f.write(text)
    return text
def api(method,path,body=None,expected=200):
    raw='' if body is None else json.dumps(body,separators=(',',':'),ensure_ascii=True)
    command=f'api {method} {path}'
    if body is not None: command+=' "'+raw.replace('\\','\\\\').replace('"','\\"')+'"'
    wire=(command+'\r').encode();text=''
    # Pace long config commands so the small USB console RX ring cannot overflow.
    for offset in range(0,len(wire),32):
        link.write(wire[offset:offset+32]);text+=collect(.04)
    end=time.monotonic()+30
    while 'API_END' not in text and time.monotonic()<end:text+=collect(.4)
    match=re.search(r'API_BEGIN (\d+)\s*(.*?)\s*API_END',text,re.S)
    if not match:raise RuntimeError('No response: '+text[-3000:])
    code=int(match.group(1));payload=json.loads(match.group(2))
    assert code==expected,(path,code,payload)
    print(method,path,code,flush=True)
    return payload
def job(path,generation=0,confirm=None,expected=202):
    body={'generation':generation}
    if confirm:body['confirm']=confirm
    result=api('POST',path,body,expected)
    if expected!=202:return result
    for _ in range(20):
        time.sleep(.2);status=api('GET','/api/status')
        if not status['busy']:return status
    raise AssertionError('Job did not finish')

boot=collect(2);link.write(b'\x03\r');collect(.5)
if len(sys.argv)>1 and sys.argv[1]=='boot':
    print(boot);print(json.dumps(api('GET','/api/status'),ensure_ascii=False,indent=2));link.close();sys.exit()
status=api('GET','/api/status');assert status['simulation'] and status['wifi']['ssid']=='MS51-PROGRAMMER-'+status['device']['id'][5:].upper()
debug=api('GET','/api/debug');assert debug['simulation'] and len(debug['variables'])>=9
first={v['name']:v['value'] for v in debug['variables']}
time.sleep(2)
second={v['name']:v['value'] for v in api('GET','/api/debug')['variables']}
assert int(second['uptime_s'])>int(first['uptime_s'])
config=api('GET','/api/runtime-config')['data'];assert len(config)==434
changed=bytearray.fromhex(config);changed[1:5]=(30).to_bytes(4,'big')
api('POST','/api/runtime-config',{'data':changed.hex()})
assert api('GET','/api/runtime-config')['data'].lower()==changed.hex()
api('POST','/api/runtime-config',{'data':config})
info=api('POST','/api/info',{});assert info['pdid']==0x0B005332 and info['aprom_size']==32768
api('POST','/api/reset',{})
api('POST','/api/erase',{},expected=400)
assert job('/api/erase',confirm='CONFIRM')['last_error']=='ESP_OK'
image=api('POST','/api/upload',expected=201)['image'];generation=image['generation']
assert image['covered_size']==4
result=job('/api/verify',generation);assert result['last_error']!='ESP_OK'
result=job('/api/program',generation);assert result['last_error']=='ESP_OK'
result=job('/api/verify',generation);assert result['last_error']=='ESP_OK'
result=job('/api/program-full',generation,confirm='CONFIRM');assert result['last_error']=='ESP_OK'
result=job('/api/erase',confirm='CONFIRM');assert result['last_error']=='ESP_OK'
result=job('/api/verify',generation);assert result['last_error']!='ESP_OK'
result=job('/api/program',generation);assert result['last_error']=='ESP_OK'
api('POST','/api/reset',{})
report={'result':'PASS','chip':'ESP32-C5','port':'COM49','ap_ip':'192.168.4.1',
        'checks':['status and simulation marker','live changing timers','217-byte config read/write',
                  'chip info','reset','HEX parsing/upload','verify mismatch','program+verify',
                  'program-full','erase confirmation','erase+verify mismatch','reprogram'],
        'method':'Real firmware HTTP handlers invoked over TCP on the ESP32, commands sent by USB serial.',
        'limitations':'Phone-to-Wi-Fi test not performed by host; host has Ethernet only.'}
(base/'outputs/esp32c5-test/test-results.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print('ALL ON-DEVICE HTTP TESTS PASSED',flush=True);link.close()
