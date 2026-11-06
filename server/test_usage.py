"""No hardware writes: isolated fixtures emulate the targeted MCU renewal."""
import secrets
import time
import unittest
from unittest.mock import patch
from fastapi import HTTPException
from test_admin import AdminTests, core, temporary
from usage import trial_view

DEVICE='ms51-111111111111'
URL='admin/api/users/u1/devices/'+DEVICE+'/usage'

class UsageTests(AdminTests):
    def setUp(self):
        super().setUp();self.authed=False
        self.raw=bytearray(217);self.raw[0]=2;self.raw[0x20:0x22]=(10).to_bytes(2,'big')
        self.raw[0x2D]=1;self.raw[0xC5:]=bytes([7])*20
        self.token='0'*32;self.supported=True;self.version='2';self.stale=False;self.writes=0;self.lost=None
    def fake(self,row,uid,rid,method,path,body,lease=None):
        if path=='/api/debug':
            return {'ok':True,'enabled':True,'last_rx_age_ms':6000 if self.stale else 5,'paused_for_icp':False,
                    'variables':[{'name':'usage_v','value':self.version if self.supported else '0','age_ms':5},
                                 {'name':'usage_id','value':self.token,'age_ms':5}]}
        self.assertEqual(path,'/api/runtime-config')
        if method=='GET':return {'ok':True,'data':self.raw.hex()}
        self.writes+=1
        if self.lost=='before':self.lost=None;raise HTTPException(504,'Lost connection')
        blob=bytes.fromhex(body['data']);self.assertEqual(blob[:5],b'\xe3TRY1');self.assertEqual(blob[24:],bytes(193))
        new_token=blob[8:24].hex()
        if new_token!=self.token:
            self.raw[0x20:0x22]=blob[5:7];self.raw[0x2C]=blob[7];self.raw[0x2D:0x32]=bytes(5);self.token=new_token
        if self.lost=='after':self.lost=None;raise HTTPException(504,'ACK lost')
        return {'ok':True}
    def read(self):
        if not self.authed:self.auth();self.authed=True
        r=self.client.get(URL);self.assertEqual(r.status_code,200,r.text);return r.json()
    def body(self,view,**kw):
        return dict({'id':secrets.token_hex(16),'amount':12,'unit':'hours','revision':view['revision']},**kw)
    def test_expired_grant_and_duplicate_preserve_all_other_bytes(self):
        before=bytes(self.raw)
        with patch.object(core.bridge,'call',side_effect=self.fake),patch('usage.time.sleep'):
            v=self.read();self.assertEqual(v['state'],'expired')
            body=self.body(v);r=self.client.post(URL,json=body,headers=self.headers)
            self.assertEqual(r.status_code,200,r.text);self.assertEqual(r.json()['grant']['state'],'applied')
            self.assertEqual(self.writes,1);self.raw[0x31]=3
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,200)
            self.assertEqual(self.writes,1);self.assertEqual(self.raw[0x31],3)
            for i in range(217):
                if i not in [0x20,0x21,*range(0x2C,0x32)]:self.assertEqual(self.raw[i],before[i],hex(i))
            changed=dict(body,amount=13)
            self.assertEqual(self.client.post(URL,json=changed,headers=self.headers).status_code,409)
    def test_lost_ack_reconciles_receipt_without_writing_again(self):
        with patch.object(core.bridge,'call',side_effect=self.fake),patch('usage.time.sleep'):
            body=self.body(self.read());self.lost='after'
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,504)
            self.assertEqual(self.read()['grant']['state'],'applied')
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,200)
            self.assertEqual(self.writes,1)
    def test_not_applied_retry_reuses_token_and_blocks_new_grant(self):
        with patch.object(core.bridge,'call',side_effect=self.fake),patch('usage.time.sleep'):
            v=self.read();body=self.body(v);self.lost='before'
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,504)
            self.assertEqual(self.client.post(URL,json=self.body(v),headers=self.headers).status_code,409)
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,200)
            self.assertEqual(self.writes,2);self.assertEqual(self.token,body['id'])
    def test_permission_csrf_and_input_validation(self):
        with patch.object(core.bridge,'call',side_effect=self.fake):
            v=self.read();b=self.body(v)
            for k,val in [('amount',-1),('amount',1000),('amount',True),('amount','12'),('unit','months'),('id','bad')]:
                invalid=dict(b);invalid[k]=val
                self.assertEqual(self.client.post(URL,json=invalid,headers=self.headers).status_code,422)
            self.assertEqual(self.client.post(URL,json=b,headers={'origin':self.headers['origin']}).status_code,403)
            self.assertEqual(self.client.get(URL.replace('/u1/','/u2/')).status_code,404)
            self.assertEqual(self.writes,0)
            mobile=self.client.post('auth/login',json={'username':'alice','password':'User-test-only-123!'}).json()
            self.client.cookies.clear()
            self.assertEqual(self.client.post(URL,json=b,headers={'Authorization':'Bearer '+mobile['token']}).status_code,401)
    def test_unsupported_stale_revision_and_busy_never_write(self):
        with patch.object(core.bridge,'call',side_effect=self.fake):
            v=self.read();b=self.body(v);self.supported=False
            self.assertEqual(self.client.post(URL,json=b,headers=self.headers).status_code,409)
            self.supported=True;self.stale=True
            self.assertEqual(self.client.post(URL,json=b,headers=self.headers).status_code,409)
            self.stale=False;self.raw[0x31]=1
            self.assertEqual(self.client.post(URL,json=b,headers=self.headers).status_code,409)
            core.managed_locks[DEVICE]='firmware-job'
            try:self.assertEqual(self.client.post(URL,json=b,headers=self.headers).status_code,409)
            finally:core.managed_locks.pop(DEVICE,None)
            self.assertEqual(self.writes,0)
    def test_trial_time_units_and_unlimited(self):
        raw=bytearray(217);raw[0]=2
        self.assertEqual(trial_view(raw)['state'],'unlimited')
        raw[0x21]=2;raw[0x31]=3
        self.assertEqual(trial_view(raw)['remaining_seconds'],5400)
        raw[0x2C]=1;raw[0x31]=1
        self.assertEqual(trial_view(raw)['remaining_seconds'],171000)
        raw[0x2D]=1
        self.assertEqual(trial_view(raw)['remaining_seconds'],0)
    def test_zero_opens_expired_try_and_preserves_other_settings(self):
        before=bytes(self.raw)
        with patch.object(core.bridge,'call',side_effect=self.fake),patch('usage.time.sleep'):
            v=self.read();self.assertTrue(v['unlimited_supported'])
            body=self.body(v,amount=0)
            r=self.client.post(URL,json=body,headers=self.headers)
            self.assertEqual(r.status_code,200,r.text)
            self.assertEqual(r.json()['grant']['state'],'applied')
            self.assertIn('không giới hạn',r.json()['grant']['message'])
            view=self.read();self.assertEqual(view['state'],'unlimited');self.assertIsNone(view['remaining_seconds'])
            self.assertEqual(self.raw[0x20:0x22],bytes(2));self.assertEqual(self.raw[0x2D:0x32],bytes(5))
            for i in range(217):
                if i not in [0x20,0x21,*range(0x2C,0x32)]:self.assertEqual(self.raw[i],before[i],hex(i))
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,200)
            self.assertEqual(self.writes,1)
            finite=self.body(view,amount=1)
            self.assertEqual(self.client.post(URL,json=finite,headers=self.headers).status_code,200)
            self.assertEqual(self.read()['state'],'active')
    def test_zero_lost_ack_reconciles_without_granting_again(self):
        with patch.object(core.bridge,'call',side_effect=self.fake),patch('usage.time.sleep'):
            body=self.body(self.read(),amount=0);self.lost='after'
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,504)
            view=self.read();self.assertEqual(view['state'],'unlimited')
            self.assertEqual(view['grant']['state'],'applied');self.assertIn('không giới hạn',view['grant']['message'])
            self.assertEqual(self.client.post(URL,json=body,headers=self.headers).status_code,200)
            self.assertEqual(self.writes,1)
    def test_v1_firmware_allows_finite_grants_but_never_sends_zero(self):
        self.version='1'
        with patch.object(core.bridge,'call',side_effect=self.fake),patch('usage.time.sleep'):
            view=self.read();self.assertTrue(view['supported']);self.assertFalse(view['unlimited_supported'])
            r=self.client.post(URL,json=self.body(view,amount=0),headers=self.headers)
            self.assertEqual(r.status_code,409);self.assertIn('3.7.6',r.text);self.assertEqual(self.writes,0)
            self.assertEqual(self.client.post(URL,json=self.body(view),headers=self.headers).status_code,200)
            self.assertEqual(self.writes,1)
    def test_mobile_cannot_change_try_even_if_old_policy_granted_it(self):
        with core.lock:
            core.db.execute('INSERT OR REPLACE INTO device_policies VALUES(?,?,1)',(DEVICE,'["tryTime","tryUnit","coolCl"]'));core.db.commit()
        headers=self.mobile()
        with patch.object(core.bridge,'call') as call:
            access=self.client.get('devices/'+DEVICE+'/access',headers=headers).json()
            self.assertNotIn('tryTime',access['visible']);self.assertNotIn('tryUnit',access['visible'])
            response=self.client.post('devices/'+DEVICE+'/settings',json={'values':{'tryTime':0},'revision':access['revision']},headers=headers)
            self.assertEqual(response.status_code,403);call.assert_not_called()

if __name__=='__main__':
    try:
        result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(UsageTests))
        raise SystemExit(not result.wasSuccessful())
    finally:core.db.close();temporary.cleanup()
