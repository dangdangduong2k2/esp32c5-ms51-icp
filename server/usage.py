"""Administrator-only TRY renewal, using a targeted, idempotent MCU envelope."""
import hashlib
import json
import re
import secrets
import time

from fastapi import Request
from pydantic import BaseModel, ConfigDict, Field, StrictInt


class UsageGrant(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str = Field(pattern=r'^[a-f0-9]{32}$')
    amount: StrictInt = Field(ge=0, le=999)
    unit: str = Field(pattern=r'^(hours|days)$')
    revision: str = Field(pattern=r'^[a-f0-9]{64}$')


def trial_view(raw):
    if len(raw) != 217 or raw[0] != 2:
        raise ValueError('Cấu hình mạch chưa hợp lệ.')
    period = int.from_bytes(raw[0x20:0x22], 'big')
    unit, stopped = raw[0x2C], raw[0x2D]
    if period > 999 or unit > 1 or stopped > 1:
        raise ValueError('Thời gian sử dụng trên mạch chưa hợp lệ.')
    elapsed = int.from_bytes(raw[0x2E:0x32], 'big')
    total = period * (86400 if unit else 3600)
    used = min(total, elapsed * (1800 if unit else 600))
    state = 'expired' if stopped or (period and used >= total) else 'active' if period else 'unlimited'
    return {'state': state, 'period': period, 'unit': 'days' if unit else 'hours',
            'used_seconds': used, 'remaining_seconds': None if state == 'unlimited' else 0 if stopped else total-used}


def usage_receipt(debug):
    if (debug.get('enabled') is not True or debug.get('paused_for_icp') is True or
            type(debug.get('last_rx_age_ms')) is not int or not 0 <= debug['last_rx_age_ms'] <= 5000):
        return 0, ''
    fresh = {v.get('name'): v.get('value') for v in debug.get('variables', [])
             if isinstance(v, dict) and type(v.get('age_ms')) is int and 0 <= v['age_ms'] <= 5000}
    token = fresh.get('usage_id', '')
    version = {'1': 1, '2': 2}.get(fresh.get('usage_v'), 0)
    supported = version and isinstance(token, str) and bool(re.fullmatch('[0-9a-f]{32}', token))
    return version if supported else 0, token if supported else ''


def applied_message(amount):
    return ('Đã mở không giới hạn thời gian.' if amount == 0 else
            'Đã cấp thời gian mới. Hết thời gian mạch sẽ khóa lại.')


def renewal_envelope(operation, amount, unit):
    raw = bytearray(217)
    raw[:5] = b'\xE3TRY1'
    raw[5:7] = amount.to_bytes(2, 'big')
    raw[7] = int(unit == 'days')
    raw[8:24] = bytes.fromhex(operation)
    return raw.hex().upper()


def install_usage(app, core, admin_identity, audit, admin_device, acquire, release_lock, call):
    db, lock = core.db, core.lock
    with lock:
        db.execute('''CREATE TABLE IF NOT EXISTS usage_grants(
            id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            actor TEXT NOT NULL, amount INTEGER NOT NULL, unit TEXT NOT NULL, state TEXT NOT NULL,
            created REAL NOT NULL, completed REAL, message TEXT NOT NULL)''')
        db.commit()

    def grant_view(row):
        return {k: row[k] for k in ('id', 'amount', 'unit', 'state', 'created', 'completed', 'message')} if row else None

    def mark(operation, state, message):
        with lock:
            db.execute('UPDATE usage_grants SET state=?,message=?,completed=? WHERE id=?',
                       (state, message, time.time() if state == 'applied' else None, operation))
            db.commit()

    def read_usage(row, uid, lease):
        result = call(row, uid, lease, 'GET', '/api/runtime-config')
        encoded = result.get('data')
        if not isinstance(encoded, str) or not re.fullmatch('[0-9a-fA-F]{434}', encoded):
            core.fail(502, 'Chưa đọc được thời gian sử dụng từ mạch.')
        raw = bytes.fromhex(encoded)
        try:
            view = trial_view(raw)
        except ValueError as error:
            core.fail(502, str(error))
        debug = call(row, uid, lease, 'GET', '/api/debug')
        version, token = usage_receipt(debug)
        revision = hashlib.sha256(raw[0x20:0x22]+raw[0x2C:0x32]+token.encode()).hexdigest()
        return dict(view, supported=bool(version), unlimited_supported=version >= 2, revision=revision), token

    def latest(device):
        with lock:
            row = db.execute('SELECT * FROM usage_grants WHERE device=? ORDER BY created DESC LIMIT 1', (device,)).fetchone()
            return dict(row) if row else None

    def reconcile(device, token):
        with lock:
            row = db.execute("SELECT * FROM usage_grants WHERE device=? AND state IN ('pending','unknown') ORDER BY created DESC LIMIT 1", (device,)).fetchone()
        if row and token == row['id']:
            mark(row['id'], 'applied', applied_message(row['amount']))

    @app.get('/admin/api/users/{uid}/devices/{device}/usage')
    def get_usage(uid: str, device: str, request: Request):
        admin_device(uid, device, request)
        row, lease = core.device_for(uid, device), secrets.token_hex(16)
        acquire(row, uid, lease)
        try:
            view, token = read_usage(row, uid, lease)
            reconcile(device, token)
            return {'ok': True, **view, 'grant': grant_view(latest(device))}
        finally:
            release_lock(device, lease)

    @app.post('/admin/api/users/{uid}/devices/{device}/usage')
    def renew_usage(uid: str, device: str, data: UsageGrant, request: Request):
        who = admin_device(uid, device, request, True)
        core.limited(request, 'admin-usage', 15)
        if data.id in ('0'*32, 'f'*32):
            core.fail(400, 'Mã gia hạn chưa hợp lệ. Tải lại trang để thử lại.')
        with lock:
            previous = db.execute('SELECT * FROM usage_grants WHERE id=?', (data.id,)).fetchone()
            if previous and (previous['device'] != device or previous['amount'] != data.amount or previous['unit'] != data.unit):
                core.fail(409, 'Mã gia hạn đã được sử dụng cho yêu cầu khác.')
            if previous and previous['state'] == 'applied':
                return {'ok': True, 'grant': grant_view(previous)}
        row, lease = core.device_for(uid, device), secrets.token_hex(16)
        acquire(row, uid, lease)
        try:
            view, token = read_usage(row, uid, lease)
            reconcile(device, token)
            with lock:
                previous = db.execute('SELECT * FROM usage_grants WHERE id=?', (data.id,)).fetchone()
                if previous and previous['state'] == 'applied':
                    return {'ok': True, 'grant': grant_view(previous), **view}
                unresolved = db.execute("SELECT 1 FROM usage_grants WHERE device=? AND state IN ('pending','unknown') AND id<>?", (device, data.id)).fetchone()
                if unresolved:
                    core.fail(409, 'Lần gia hạn trước chưa được xác nhận. Đọc lại hoặc thử lại cùng lần đó.')
                if not view['supported']:
                    core.fail(409, 'Cần cập nhật chương trình MS51 để gia hạn mà giữ nguyên các cài đặt khác.')
                if data.amount == 0 and not view['unlimited_supported']:
                    core.fail(409, 'Cần cập nhật MS51 bản 3.7.6 trở lên để nhập 0 mở không giới hạn.')
                if not previous and data.revision != view['revision']:
                    core.fail(409, 'Thời gian trên mạch đã thay đổi. Đọc lại trước khi gia hạn.')
                if not previous:
                    db.execute("INSERT INTO usage_grants VALUES(?,?,?,?,?,'pending',?,NULL,?)",
                               (data.id, device, who['username'], data.amount, data.unit, time.time(), 'Đang gửi gia hạn đến mạch.'))
                    audit(who['username'], 'device.usage.renew', device)
                    db.commit()
            # Reusing this operation token after a lost ACK is safe: MS51 keeps
            # the receipt in EEPROM and never restarts the same period twice.
            try:
                call(row, uid, lease, 'POST', '/api/runtime-config',
                     {'data': renewal_envelope(data.id, data.amount, data.unit)})
            except Exception:
                mark(data.id, 'unknown', 'Chưa xác nhận gia hạn. Đọc lại hoặc thử lại cùng lệnh; thời gian sẽ không được cấp lặp.')
                raise
            for _ in range(5):
                time.sleep(1)
                try:
                    debug = call(row, uid, lease, 'GET', '/api/debug')
                    supported, token = usage_receipt(debug)
                except Exception:
                    break
                if supported and token == data.id:
                    mark(data.id, 'applied', applied_message(data.amount))
                    return {'ok': True, 'grant': grant_view(latest(device))}
            mark(data.id, 'unknown', 'Chưa đọc được xác nhận từ mạch. Đọc lại để kiểm tra trước khi gia hạn tiếp.')
            return {'ok': True, 'grant': grant_view(latest(device))}
        finally:
            release_lock(device, lease)
