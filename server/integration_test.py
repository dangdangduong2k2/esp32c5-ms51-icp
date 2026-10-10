"""Opt-in live integration test, run as root on the VPS. Creates/removes only its own fixtures.
Exercises HTTPS, real broker ACLs, two owners, proofs of pairing, RPC, uploads and revocation.
Never prints passwords, session tokens or MQTT credentials.
"""
import json
import secrets
import sqlite3
import ssl
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
import paho.mqtt.client as mqtt

BASE = 'https://142-91-99-63.sslip.io/ms51/connect/v1'
users, clients, mqtt_users = [], [], []
passed = []


def request(path, token='', body=None, expected=200, raw=None, headers=None):
    hdr = {'Content-Type': 'application/json'}
    if token: hdr['Authorization'] = 'Bearer ' + token
    if headers: hdr.update(headers)
    data = raw if raw is not None else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=hdr)
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            code, result = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        code, result = error.code, json.load(error)
    assert code == expected, (path, code, result)
    return result


def wait(predicate, seconds=8):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if predicate(): return
        time.sleep(.1)
    raise AssertionError('Timed out waiting for expected state')


class Device:
    def __init__(self, claim):
        self.config = claim['config']; self.device = self.config['device']
        self.topic = 'ms51connect/v1/' + self.config['username']
        self.connected = threading.Event(); self.commands = []; self.subacks = []; self.transferred = b''
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=self.config['username'])
        self.client.username_pw_set(self.config['username'], self.config['password'])
        self.client.tls_set(cert_reqs=ssl.CERT_REQUIRED)
        self.client.on_connect = self.on_connect
        self.client.on_subscribe = lambda c,u,m,codes,p: self.subacks.append(codes)
        self.client.on_message = self.on_message
        self.client.connect(self.config['host'], self.config['port'], 15)
        self.client.loop_start(); clients.append(self.client)
        assert self.connected.wait(8)
        wait(lambda: len(self.subacks) > 0)
        assert not self.subacks[0][0].is_failure, 'Own command subscription denied'

    def on_connect(self, c, u, flags, reason, properties):
        if not reason.is_failure:
            c.subscribe(self.topic + '/cmd', 1); self.connected.set()

    def publish(self, suffix, value):
        self.client.publish(self.topic + suffix, json.dumps(value), qos=1).wait_for_publish(5)

    def hello(self, nonce=None):
        self.publish('/hello', dict(device=self.device, nonce=self.config['nonce'] if nonce is None else nonce))

    def state(self, online=True, retained=False):
        self.client.publish(self.topic + '/state', json.dumps(dict(device=self.device, online=online,
            status=dict(ok=True, device=dict(id=self.device), wifi=dict(uplink=dict(ssid='Integration test', ip='192.168.1.51'))))),
            qos=1, retain=retained).wait_for_publish(5)

    def on_message(self, c, u, message):
        command = json.loads(message.payload)
        self.commands.append(command)
        if command.get('method') == 'ATTACK': return
        result = dict(ok=True, device=dict(id=self.device), busy=False, job_id=1)
        if command['path'] == '/internal/upload':
            body = command['body']
            req = urllib.request.Request(BASE + '/transfer/' + body['upload_id'], headers={'Authorization': 'Bearer ' + body['token']})
            with urllib.request.urlopen(req, timeout=5) as response: self.transferred = response.read()
            result['size'] = len(self.transferred)
            # Model the C5 reconnect around file transfer: offline state precedes reply.
            c.publish(self.topic + '/state', json.dumps(dict(device=self.device, online=False)), qos=1)
        c.publish(self.topic + '/reply/' + command['id'], json.dumps(dict(id=command['id'], result=result)), qos=1)


def test():
    assert request('/health')['mqtt']
    request('/devices', expected=401)
    credentials = []
    for _ in range(2):
        credentials.append(dict(username='test_' + secrets.token_hex(6), password=secrets.token_urlsafe(20)))
        user = request('/auth/register', body=credentials[-1]); users.append(user)
    one, two = (u['token'] for u in users)
    request('/auth/login', body=dict(username=credentials[0]['username'], password='IncorrectPassword'), expected=401)
    login = request('/auth/login', body=credentials[0])
    assert login['user']['id'] == users[0]['user']['id']
    passed.append('HTTPS registration/login, wrong password rejection, same account on another phone')
    claims = []
    for token in (one, two):
        claim = request('/pair/start', token, dict(device='ms51-' + secrets.token_hex(6)))
        claims.append(claim); mqtt_users.append(claim['config']['username'])
        assert request('/devices', token)['devices'] == []
    d1, d2 = Device(claims[0]), Device(claims[1])
    d1.hello('0' * 48); time.sleep(.25)
    assert not request('/pair/' + claims[0]['claim_id'], one)['paired']
    for d, claim, token in zip((d1,d2), claims, (one,two)):
        d.hello(); wait(lambda: request('/pair/' + claim['claim_id'], token)['paired']); d.state()
    wait(lambda: request('/devices', one)['devices'][0]['online'])
    request('/pair/start', two, dict(device=d1.device), expected=409)
    assert request('/pair/start', one, dict(device=d1.device))['paired']
    passed.append('Pairing requires authenticated MQTT proof and correct nonce; ownership cannot be stolen')
    # An authenticated client with malformed telemetry must not kill the shared MQTT loop.
    d1.publish('/state', [])
    for state in ([], {'wifi': []}, {'wifi': {'uplink': []}}):
        d1.publish('/state', dict(device=d1.device, online=True, status=state))
    d1.state(False); wait(lambda: not request('/devices', one)['devices'][0]['online'])
    d1.state(True); wait(lambda: request('/devices', one)['devices'][0]['online'])
    passed.append('Malformed MQTT telemetry cannot interrupt the bridge or corrupt device lists')
    command = dict(id=secrets.token_hex(16), method='GET', path='/api/status', body={})
    request('/devices/' + d1.device + '/request', two, command, expected=404)
    request('/devices/' + d1.device + '/name', two, dict(name='attack'), expected=404)
    request('/devices/' + d1.device + '/remove', two, {}, expected=404)
    result = request('/devices/' + d1.device + '/request', one, command)
    assert result['device']['id'] == d1.device
    before = len(d1.commands)
    assert request('/devices/' + d1.device + '/request', one, command) == result
    time.sleep(.2); assert len(d1.commands) == before
    request('/devices/' + d1.device + '/request', one, dict(command, body={'changed': True}), expected=409)
    request('/devices/' + d1.device + '/request', one, dict(command, id=secrets.token_hex(16), path='/api/uplink'), expected=400)
    passed.append('Cross-account access denied; correlated MQTT RPC; duplicate request not executed twice; allowlist enforced')
    old = len(d1.subacks)
    d1.client.subscribe(d2.topic + '/cmd', 1)
    wait(lambda: len(d1.subacks) > old)
    assert d1.subacks[-1][0].is_failure, 'Cross-device subscription was accepted'
    before = len(d2.commands)
    d1.client.publish(d2.topic + '/cmd', json.dumps(dict(method='ATTACK')), qos=1).wait_for_publish(5)
    time.sleep(.4); assert len(d2.commands) == before, 'Cross-device publish reached target'
    passed.append('Broker rejects cross-device subscribe and publish')
    payload = b':0400000001020304F2\n:00000001FF\n'
    result = request('/devices/' + d1.device + '/upload', one, raw=payload,
                     headers={'Content-Type':'application/octet-stream','X-Filename':'test.hex','X-Request-ID':secrets.token_hex(16)})
    assert result['size'] == len(payload) and d1.transferred == payload
    assert request('/devices', one)['devices'][0]['online'], 'Correlated reply must refresh liveness after upload'
    request('/transfer/' + d1.commands[-1]['body']['upload_id'], expected=404)
    passed.append('HTTPS upload delivered through scoped transfer; expired download inaccessible')
    d1.state(False); wait(lambda: not request('/devices', one)['devices'][0]['online'])
    request('/devices/' + d1.device + '/request', one, dict(command, id=secrets.token_hex(16)), expected=409)
    d1.state(True); wait(lambda: request('/devices', one)['devices'][0]['online'])
    request('/devices/' + d1.device + '/name', one, dict(name='C5 test'))
    assert request('/devices', login['token'])['devices'][0]['name'] == 'C5 test'
    request('/auth/logout', login['token'], {})
    request('/devices', login['token'], expected=401)
    request('/devices/' + d1.device + '/remove', one, {})
    assert request('/devices', one)['devices'] == []
    passed.append('Offline requests rejected; registry shared across sessions; logout and unpair revoke access')
    print(json.dumps(dict(passed=passed, count=len(passed)), ensure_ascii=False, indent=2))


def cleanup():
    for c in clients:
        c.disconnect(); c.loop_stop()
    # Privileged teardown is deliberately outside the public application API.
    env = dict(line.split('=',1) for line in Path('/etc/ms51-connect.env').read_text().splitlines() if '=' in line)
    admin = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='ms51-test-cleanup-' + secrets.token_hex(4))
    admin.username_pw_set(env['MS51_MQTT_ADMIN'], env['MS51_MQTT_PASSWORD']); admin.tls_set()
    admin.connect('142-91-99-63.sslip.io',8884,15); admin.loop_start()
    for user in mqtt_users:
        admin.publish('$CONTROL/dynamic-security/v1', json.dumps({'commands':[
            {'command':'deleteClient','username':user}, {'command':'deleteRole','rolename':user}]}), qos=1).wait_for_publish(5)
    time.sleep(.3); admin.disconnect(); admin.loop_stop()
    db = sqlite3.connect('/var/lib/ms51-connect/connect.sqlite3')
    for user in users:
        uid = user['user']['id']
        for table in ('devices','sessions','claims','requests'): db.execute('DELETE FROM '+table+' WHERE owner=?',(uid,))
        db.execute('DELETE FROM users WHERE id=? AND username LIKE ?', (uid,'test_%'))
    db.commit(); db.close()


if __name__ == '__main__':
    try: test()
    finally: cleanup()
