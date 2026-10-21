"""Runs only isolated SQLite fixtures and mock device replies; never flashes hardware."""
import secrets
import time
import unittest
from unittest.mock import patch

from test_admin import AdminTests, core, temporary
from managed import CATALOG, ACCESS_CATALOG, MODE_DISPLAY_FIELDS, migrate_mode_permissions, parse_hex

DEVICE = 'ms51-111111111111'
ADMIN = 'admin/api/users/u1/devices/' + DEVICE
MOBILE = 'devices/' + DEVICE
HEX = b':0400000001020304F2\n:00000001FF\n'


class ManagedTests(AdminTests):
    def test_thermal_threshold_validation_and_ownership(self):
        body={'id':'c'*32,'method':'POST','path':'/api/thermal',
              'body':{'source':'10K','threshold_centi':3000,'defrost_centi':500}}
        with patch.object(core.bridge,'call',return_value={'ok':True}) as call:
            for source in ('10K','20K'):
                body['body']['source']=source
                for boundary in (-5500,12500):
                    body['body']['defrost_centi']=boundary
                    self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,200)
            call.reset_mock()
            valid={'source':'10K','threshold_centi':3000,'defrost_centi':500}
            for enabled in (True,False):
                body['body']=dict(valid,safety_enabled=enabled)
                self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,200)
                self.assertIs(call.call_args.args[-1]['safety_enabled'],enabled)
            call.reset_mock()
            for safety in (True,False):
                for enabled in (True,False):
                    body['body']=dict(valid,safety_enabled=safety,defrost_enabled=enabled)
                    self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,200)
                    self.assertIs(call.call_args.args[-1]['defrost_enabled'],enabled)
                    self.assertIs(call.call_args.args[-1]['safety_enabled'],safety)
            body['body']=dict(valid,defrost_enabled=False)
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,200)
            call.reset_mock()
            invalid=[{},dict(valid,source='SIG'),dict(valid,source='50K'),dict(valid,extra=1)]
            invalid += [dict(valid,**{key:v}) for key in ('safety_enabled','defrost_enabled') for v in (0,1,None,'true',[],{})]
            for key in ('threshold_centi','defrost_centi'):
                for value in (-5501,12501,True,False,None,3.5,'20'):
                    invalid.append(dict(valid,**{key:value}))
            for value in invalid:
                body['body']=value
                self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,400)
            body['body']=valid
            self.assertEqual(self.client.post(MOBILE+'/request',json=body).status_code,401)
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile('bob')).status_code,404)
            with core.lock:
                core.db.execute("INSERT INTO update_jobs VALUES(?,?,?,?,?,?,?)",('job-thermal',DEVICE,'u1','test','running','',time.time()))
                core.db.commit()
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,409)
            call.assert_not_called()
            with core.lock:
                core.db.execute("DELETE FROM update_jobs WHERE id='job-thermal'");core.db.commit()

    def setUp(self):
        super().setUp()
        core.managed_locks.clear()
        self.auth()
        self.mobile_headers = self.mobile()

    def test_mode_owner_validation_and_update_overlap(self):
        body = {'id':'a'*32, 'method':'POST', 'path':'/api/mode', 'body':{'mode':'COOL'}}
        with patch.object(core.bridge, 'call', return_value={'ok':True}) as call:
            for mode in ('COOL','ICE_FLUSH'):
                body['body']={'mode':mode}
                self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,200)
            self.assertEqual(call.call_count,2)
            call.reset_mock()
            for invalid in ({'mode':'IDLE'},{'mode':0},{'mode':'COOL','extra':1},{}):
                body['body']=invalid
                self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,400)
            body['body']={'mode':'COOL'}
            self.assertEqual(self.client.post(MOBILE+'/request',json=body).status_code,401)
            other=self.mobile('bob')
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=other).status_code,404)
            call.assert_not_called()
            with core.lock:
                core.db.execute("INSERT INTO update_jobs VALUES(?,?,?,?,?,?,?)", ('job-mode',DEVICE,'u1','test-release','running','',time.time()))
                core.db.commit()
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,409)
            call.assert_not_called()
            with core.lock:
                core.db.execute("DELETE FROM update_jobs WHERE id='job-mode'");core.db.commit()

    def test_trigger_owner_validation_and_update_overlap(self):
        body={'id':'b'*32,'method':'POST','path':'/api/trigger','body':{}}
        with patch.object(core.bridge,'call',return_value={'ok':True}) as call:
            for source in ('SIG','10K','20K','50K'):
                body['body']={'source':source,'threshold_centi':2500}
                self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,200)
            self.assertEqual(call.call_count,4);call.reset_mock()
            for value in ({}, {'source':'BAD','threshold_centi':0}, {'source':'20K','threshold_centi':True},
                          {'source':'20K','threshold_centi':12501}, {'source':'20K','threshold_centi':-5501},
                          {'source':'20K','threshold_centi':2.5}, {'source':'20K','threshold_centi':0,'extra':1}):
                body['body']=value
                self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,400)
            body['body']={'source':'20K','threshold_centi':2500}
            self.assertEqual(self.client.post(MOBILE+'/request',json=body).status_code,401)
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile('bob')).status_code,404)
            with core.lock:
                core.db.execute("INSERT INTO update_jobs VALUES(?,?,?,?,?,?,?)",('job-trigger',DEVICE,'u1','test','running','',time.time()));core.db.commit()
            self.assertEqual(self.client.post(MOBILE+'/request',json=body,headers=self.mobile_headers).status_code,409)
            call.assert_not_called()
            with core.lock:
                core.db.execute("DELETE FROM update_jobs WHERE id='job-trigger'");core.db.commit()

    def set_policy(self, visible):
        rev = self.client.get(ADMIN + '/access').json()['revision']
        r = self.client.post(ADMIN + '/access', json={'visible': visible, 'revision': rev}, headers=self.headers)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()['revision']

    def assign(self):
        r = self.client.post(ADMIN + '/firmware', content=HEX, headers={**self.headers, 'X-Filename': 'controller.hex'})
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()['firmware']

    def test_policy_validation_and_stale_admin(self):
        data = self.client.get(ADMIN + '/access').json()
        self.assertEqual(len(data['catalog']), 46)
        self.assertEqual(len(data['visible']), 46)
        rev = self.set_policy(['coolCl', 'rangeCoolClMin'])
        mobile = self.client.get(MOBILE+'/access', headers=self.mobile_headers).json()
        self.assertEqual(mobile['visible'], ['coolCl','rangeCoolClMin'])
        self.assertEqual(mobile['revision'], rev)
        for fields in (['unknown'], ['coolCl','coolCl']):
            self.assertEqual(self.client.post(ADMIN+'/access', json={'visible':fields,'revision':rev}, headers=self.headers).status_code, 400)
        self.assertEqual(self.client.post(ADMIN+'/access', json={'visible':[],'revision':0}, headers=self.headers).status_code, 409)

    def test_modes_have_one_permission_and_no_separate_display_controls(self):
        initial = self.client.get(ADMIN+'/access').json()
        for old, parent in MODE_DISPLAY_FIELDS.items():
            self.assertNotIn(old, initial['catalog'])
            self.assertIn(parent, initial['catalog'])
            # A cached admin may still submit the removed checkbox: it cannot
            # expose that checkbox or implicitly grant the associated mode.
            rev = self.set_policy([old])
            mobile = self.client.get(MOBILE+'/access', headers=self.mobile_headers).json()
            self.assertEqual(mobile['visible'], [])
            self.assertEqual(mobile['revision'], rev)
            rev = self.set_policy([parent, old])
            mobile = self.client.get(MOBILE+'/access', headers=self.mobile_headers).json()
            self.assertEqual(mobile['visible'], [parent])
            with patch.object(core.bridge, 'call') as call:
                r = self.client.post(MOBILE+'/settings', json={'revision':rev, 'values':{old:1}},
                                     headers=self.mobile_headers)
                self.assertEqual(r.status_code, 403)
                call.assert_not_called()
        self.assertIn('showSt', initial['catalog'])
        self.assertIn('defrostOpSeconds', initial['catalog'])

    def test_mode_permission_migration_preserves_parent_choices_and_is_idempotent(self):
        with core.lock:
            core.db.execute('INSERT INTO device_policies VALUES(?,?,7)',
                            (DEVICE, '["df","showDf","showEnd","displayMode","pinEnable"]'))
            migrate_mode_permissions(core.db)
            core.db.commit()
        data = self.client.get(ADMIN+'/access').json()
        self.assertEqual(data['visible'], ['df','displayMode','pinEnable'])
        self.assertEqual(data['revision'], 8)
        with core.lock:
            migrate_mode_permissions(core.db)
            core.db.commit()
        self.assertEqual(self.client.get(ADMIN+'/access').json()['revision'], 8)
        with core.lock:
            core.db.execute('DELETE FROM device_policies WHERE device=?', (DEVICE,))
            migrate_mode_permissions(core.db)
            core.db.commit()
        data = self.client.get(ADMIN+'/access').json()
        self.assertEqual(data['revision'], 1)
        self.assertEqual(data['visible'], list(ACCESS_CATALOG))

    def test_history_uint32_offsets_and_hidden_settings(self):
        self.set_policy([])
        raw = bytearray(217); raw[0] = 2
        raw[0x32] = 0  # sig_index wraps: must not truncate the 30 saved slots
        values = [0, 1, 599, 600, 601, 0xffffffff] + list(range(24))
        for i, value in enumerate(values):
            raw[0x33+i*4:0x37+i*4] = value.to_bytes(4, 'big')
        with patch.object(core.bridge, 'call', return_value={'ok':True,'data':raw.hex()}) as call:
            r = self.client.get(MOBILE+'/history', headers=self.mobile_headers)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json(), {'ok':True,'capacity':30,'tick_ms':100,'ticks':values})
        self.assertEqual(call.call_args.args[3:6], ('GET','/api/runtime-config',{}))

    def test_history_malformed_and_wrong_owner(self):
        other = self.mobile('bob')
        with patch.object(core.bridge, 'call') as call:
            self.assertEqual(self.client.get(MOBILE+'/history').status_code, 401)
            self.assertEqual(self.client.get(MOBILE+'/history', headers=other).status_code, 404)
            call.assert_not_called()
        for data in ('', None, '00'*217, '02'+'00'*215, '02'+'gg'*216, '02'+' 0'*216):
            with patch.object(core.bridge, 'call', return_value={'ok':True,'data':data}):
                self.assertEqual(self.client.get(MOBILE+'/history', headers=self.mobile_headers).status_code, 502)

    def test_history_does_not_bypass_update_lock(self):
        core.managed_locks[DEVICE] = 'active-update'
        with patch.object(core.bridge.client, 'publish') as publish:
            self.assertEqual(self.client.get(MOBILE+'/history', headers=self.mobile_headers).status_code, 409)
            publish.assert_not_called()

    def test_hidden_settings_and_legacy_bypasses_denied(self):
        revision = self.set_policy(['coolCl'])
        with patch.object(core.bridge, 'call') as call:
            for values in ({'pinPassword':123},{'coolCl':True},{'coolCl':1000}):
                response=self.client.post(MOBILE+'/settings',json={'revision':revision,'values':values},headers=self.mobile_headers)
                self.assertIn(response.status_code,(400,403))
            for path in ['/api/runtime-config','/api/program','/api/program-full','/api/verify','/api/erase']:
                response=self.client.post(MOBILE+'/request',json={'id':secrets.token_hex(16),'method':'POST','path':path,'body':{}},headers=self.mobile_headers)
                self.assertEqual(response.status_code,403)
            self.assertEqual(self.client.post(MOBILE+'/upload',content=HEX,headers=self.mobile_headers).status_code,403)
            call.assert_not_called()

    def test_settings_merge_preserves_hidden_and_reserved_bytes(self):
        revision=self.set_policy(['coolCl'])
        raw=bytearray(b'\x39'*217);raw[0]=2;raw[0xD4]=0
        for stem,current in [('rangeCoolCl','coolCl'),('rangeCoolOp','coolOp'),('rangeDefrostCl','defrostCl'),('rangeDefrostOp','defrostOp'),('rangeStLcd','stLcd'),('rangeStLed','stLed')]:
            for key,value in [(stem+'Min',0),(stem+'Max',90),(current,5)]:
                f=CATALOG[key];raw[f['offset']:f['offset']+f['size']]=value.to_bytes(f['size'],'big')
        calls=[]
        def fake(row,uid,rid,method,path,body,lease=None):
            calls.append((method,path,body))
            return {'ok':True,'data':raw.hex()} if method=='GET' else {'ok':True,'message':'saved'}
        with patch.object(core.bridge,'call',side_effect=fake):
            r=self.client.post(MOBILE+'/settings',json={'revision':revision,'values':{'coolCl':9}},headers=self.mobile_headers)
        self.assertEqual(r.status_code,200,r.text)
        actual=bytes.fromhex(calls[-1][2]['data']);expected=raw[:];expected[1:5]=(9).to_bytes(4,'big')
        expected[0xC5:0xD9]=bytes(20)  # disabled PIN has a canonical zero block
        self.assertEqual(actual,bytes(expected))
        self.assertNotIn(DEVICE,core.managed_locks)

    def test_settings_pin_runtime_is_reset_but_hidden_pin_configuration_survives(self):
        revision=self.set_policy(['coolCl'])
        raw=bytearray(217);raw[0]=2
        for key,spec in CATALOG.items():
            raw[spec['offset']:spec['offset']+spec['size']]=spec['min'].to_bytes(spec['size'],'big')
        for key,value in {'pinEnable':1,'pinPassword':123,'pinPeriod':30,
                          'pinBackupPassword':690,'pinBackupPeriod':15,'pinWrongLimit':26,
                          'pinPeriodUnit':1,'pinBackupPeriodUnit':1,'rangeCoolClMax':999}.items():
            spec=CATALOG[key];raw[spec['offset']:spec['offset']+spec['size']]=value.to_bytes(spec['size'],'big')
        raw[0xC9:0xCC]=b'\x00\x3b\x01'  # observed elapsed counter 59 + request
        raw[0xCE:0xD0]=b'\x01\x01'
        raw[0xD7:0xD9]=b'\xa7\x58'
        raw[0x2D:0xAB]=bytes(range(126))  # unrelated state and saved history
        sent=[]
        def fake(row,uid,rid,method,path,body,lease=None):
            if method=='GET':return {'ok':True,'data':raw.hex()}
            sent.append(bytes.fromhex(body['data']))
            return {'ok':True}
        with patch.object(core.bridge,'call',side_effect=fake):
            r=self.client.post(MOBILE+'/settings',json={'revision':revision,'values':{'coolCl':9}},headers=self.mobile_headers)
        self.assertEqual(r.status_code,200,r.text)
        expected=raw[:];expected[1:5]=(9).to_bytes(4,'big')
        for a,b in [(0xC9,0xCC),(0xCE,0xD0),(0xD7,0xD9)]:expected[a:b]=bytes(b-a)
        self.assertEqual(sent,[bytes(expected)])
        self.assertNotIn(DEVICE,core.managed_locks)

    def test_settings_disabling_pin_clears_previous_pin_block(self):
        revision=self.set_policy(['pinEnable'])
        raw=bytearray(217);raw[0]=2
        for spec in CATALOG.values():
            raw[spec['offset']:spec['offset']+spec['size']]=spec['min'].to_bytes(spec['size'],'big')
        raw[0xC5:0xD9]=b'\x39'*20;raw[0xD4]=1
        sent=[]
        def fake(row,uid,rid,method,path,body,lease=None):
            if method=='GET':return {'ok':True,'data':raw.hex()}
            sent.append(bytes.fromhex(body['data']));return {'ok':True}
        with patch.object(core.bridge,'call',side_effect=fake):
            r=self.client.post(MOBILE+'/settings',json={'revision':revision,'values':{'pinEnable':0}},headers=self.mobile_headers)
        self.assertEqual(r.status_code,200,r.text)
        self.assertEqual(sent,[bytes(raw[:0xC5])+bytes(20)])

    def test_settings_empty_stale_and_all_hidden(self):
        rev=self.set_policy([])
        with patch.object(core.bridge,'call') as call:
            self.assertEqual(self.client.post(MOBILE+'/settings',json={'revision':rev,'values':{}},headers=self.mobile_headers).status_code,400)
            self.assertEqual(self.client.post(MOBILE+'/settings',json={'revision':0,'values':{'coolCl':4}},headers=self.mobile_headers).status_code,403)
            self.assertEqual(self.client.post(MOBILE+'/request',json={'id':secrets.token_hex(16),'method':'GET','path':'/api/runtime-config'},headers=self.mobile_headers).status_code,403)
            call.assert_not_called()

    def test_hex_rejections_and_owner_security(self):
        for content in (b'', HEX.replace(b'F2',b'F3'),HEX.splitlines()[0],HEX+HEX,b':0100000400FB\n:00000001FF\n',b'bad'):
            with self.assertRaises(ValueError):parse_hex(content)
        self.assertIsInstance(parse_hex(HEX),int)
        self.assertEqual(self.client.post(ADMIN+'/firmware',content=HEX,headers={'X-Filename':'a.hex','Origin':self.headers['origin']}).status_code,403)
        r=self.client.post(ADMIN+'/firmware',content=b'x'*98305,headers={**self.headers,'X-Filename':'a.hex'})
        self.assertEqual(r.status_code,413)
        other=self.mobile('bob')
        self.assertEqual(self.client.get(MOBILE+'/access',headers=other).status_code,404)
        self.assertEqual(self.client.get(ADMIN.replace('/u1/','/u2/')+'/access').status_code,404)

    def test_assignment_revoke_and_no_content_in_mobile_metadata(self):
        firmware=self.assign()
        data=self.client.get(MOBILE+'/access',headers=self.mobile_headers).json()
        self.assertTrue(data['update_available'])
        self.assertNotIn('content',data['firmware'])
        self.assertEqual(data['firmware']['id'],firmware['id'])
        self.assertEqual(self.client.post(ADMIN+'/firmware/revoke',json={},headers=self.headers).status_code,200)
        self.assertFalse(self.client.get(MOBILE+'/access',headers=self.mobile_headers).json()['update_available'])

    def start_job(self):
        firmware=self.assign();job=secrets.token_hex(16)
        with patch.object(core,'managed_spawn') as spawn:
            r=self.client.post(MOBILE+'/update',json={'id':job,'release':firmware['id']},headers=self.mobile_headers)
            self.assertEqual(r.status_code,200,r.text)
            args=spawn.call_args.args
        return firmware,job,args

    def test_repeated_update_locked_and_idempotent(self):
        firmware,job,args=self.start_job()
        with patch.object(core,'managed_spawn') as spawn:
            data={'id':job,'release':firmware['id']}
            self.assertEqual(self.client.post(MOBILE+'/update',json=data,headers=self.mobile_headers).status_code,200)
            data['id']=secrets.token_hex(16)
            self.assertEqual(self.client.post(MOBILE+'/update',json=data,headers=self.mobile_headers).status_code,409)
            self.assertEqual(self.client.post(MOBILE.replace('/devices/','/')+'/remove',json={},headers=self.mobile_headers).status_code,409)
            self.assertEqual(self.client.post(ADMIN+'/firmware/revoke',json={},headers=self.headers).status_code,409)
            self.assertEqual(self.client.post(ADMIN+'/access',json={'visible':[],'revision':0},headers=self.headers).status_code,409)
            spawn.assert_not_called()

    def test_worker_success_only_after_program_and_verify(self):
        firmware,job,args=self.start_job();calls=[];device_job=0
        def fake(row,uid,rid,method,path,body,lease=None):
            nonlocal device_job
            self.assertEqual(lease,job);calls.append(path)
            image={'generation':7,'crc32':parse_hex(HEX)}
            if path=='/internal/upload':return {'ok':True,'image':image}
            if path in ('/api/program','/api/verify'):
                device_job+=1;return {'ok':True,'accepted':True,'job_id':device_job}
            return {'ok':True,'busy':False,'job_id':device_job,'image':image,'last_error':'ESP_OK'}
        with patch.object(core.bridge,'call',side_effect=fake),patch('managed.time.sleep'):
            core.managed_worker(*args)
        result=self.client.get(MOBILE+'/access',headers=self.mobile_headers).json()
        self.assertEqual(result['job']['state'],'success');self.assertFalse(result['update_available'])
        self.assertEqual(calls.count('/api/program'),1);self.assertEqual(calls.count('/api/verify'),1)
        self.assertNotIn(DEVICE,core.managed_locks);self.assertFalse(core.uploads)

    def test_worker_mismatch_never_programs_or_claims_success(self):
        firmware,job,args=self.start_job();paths=[]
        def fake(row,uid,rid,method,path,body,lease=None):
            paths.append(path)
            return {'ok':True,'busy':False,'image':{'generation':1,'crc32':0}}
        with patch.object(core.bridge,'call',side_effect=fake):core.managed_worker(*args)
        data=self.client.get(MOBILE+'/access',headers=self.mobile_headers).json()
        self.assertEqual(data['job']['state'],'unknown');self.assertTrue(data['update_available'])
        self.assertNotIn('/api/program',paths)
        self.assertEqual(self.client.post(MOBILE+'/update',json={'id':secrets.token_hex(16),'release':firmware['id']},headers=self.mobile_headers).status_code,409)
        self.assertNotIn(DEVICE,core.managed_locks)


if __name__=='__main__':
    try:
        suite=unittest.defaultTestLoader.loadTestsFromTestCase(AdminTests)
        suite.addTests(ManagedTests(name) for name in ManagedTests.__dict__ if name.startswith('test_'))
        result=unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        core.db.close();temporary.cleanup()
    raise SystemExit(not result.wasSuccessful())
