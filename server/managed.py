"""Per-device settings policy and administrator-assigned MS51 updates."""
import asyncio
import hashlib
import json
import re
import secrets
import threading
import time
import zlib
from pathlib import Path

from fastapi import HTTPException, Request
from pydantic import BaseModel, Field

CATALOG = json.loads(Path(__file__).with_name('settings_catalog.json').read_text(encoding='utf-8'))
# These are MCU menu flags, not additional app visibility permissions.
# The parent field alone controls whether its setting appears in the app.
MODE_DISPLAY_FIELDS = {
    'showDf': 'df', 'showEnd': 'end', 'showSl': 'displayMode',
    'showHcf': 'hcfMode', 'showHdf': 'hdf',
}
ACCESS_CATALOG = {key: spec for key, spec in CATALOG.items() if key not in MODE_DISPLAY_FIELDS}


def visible_settings(keys):
    return [key for key in keys if key in ACCESS_CATALOG]


def migrate_mode_permissions(db):
    """Refresh existing app views without granting any previously hidden mode."""
    rows = db.execute('SELECT d.id,p.visible,p.revision FROM devices d '
                      'LEFT JOIN device_policies p ON p.device=d.id').fetchall()
    for row in rows:
        if row['visible'] is None:
            db.execute('INSERT INTO device_policies VALUES(?,?,1)',
                       (row['id'], json.dumps(list(ACCESS_CATALOG))))
        else:
            old = json.loads(row['visible'])
            new = visible_settings(old)
            if new != old:
                db.execute('UPDATE device_policies SET visible=?,revision=revision+1 WHERE device=?',
                           (json.dumps(new), row['id']))


SCHEMA = """
CREATE TABLE IF NOT EXISTS device_policies(device TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
 visible TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS device_releases(device TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
 id TEXT NOT NULL, filename TEXT NOT NULL, content BLOB NOT NULL, sha256 TEXT NOT NULL, crc32 INTEGER NOT NULL,
 created REAL NOT NULL, installed TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS update_jobs(id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
 owner TEXT NOT NULL, release TEXT NOT NULL, state TEXT NOT NULL, message TEXT NOT NULL, created REAL NOT NULL);
"""


def prepare_pin_for_write(raw):
    """Match MS51 SET semantics; GET also contains live PIN counters/flags."""
    if raw[0xD4] == 0:
        # The disabled-PIN representation is twenty zero bytes in this protocol.
        raw[0xC5:0xD9] = bytes(20)
    else:
        # Preserve passwords, periods, units and options; reset only runtime state.
        raw[0xC9:0xCC] = bytes(3)  # elapsed period and password request
        raw[0xCE:0xD0] = bytes(2)  # locked and backup-used flags
        raw[0xD7:0xD9] = bytes(2)  # runtime request marker and its inverse


def parse_hex(content):
    """Same APROM bounds/coverage/record rules as main/ms51_image.c."""
    if not 0 < len(content) <= 98304:
        raise ValueError('Tệp HEX phải có dữ liệu và không vượt 96 KiB.')
    image, coverage = bytearray(b'\xff' * 32768), bytearray(4096)
    base, eof, covered = 0, False, 0
    try:
        for line in content.decode('ascii').splitlines():
            if not line:
                continue
            if eof or not re.fullmatch(r':[0-9a-fA-F]+', line) or len(line) % 2 != 1:
                raise ValueError()
            record = bytes.fromhex(line[1:])
            if len(record) < 5 or len(record) != record[0] + 5 or sum(record) % 256:
                raise ValueError()
            count, address, kind, data = record[0], int.from_bytes(record[1:3], 'big'), record[3], record[4:-1]
            if kind == 0:
                if not count:
                    continue
                start = base + address
                if start + count > 32768:
                    raise ValueError()
                for i, value in enumerate(data, start):
                    bit = 1 << (i % 8)
                    if coverage[i // 8] & bit:
                        raise ValueError()
                    coverage[i // 8] |= bit
                    image[i] = value
                covered += count
            elif kind == 1 and count == 0 and address == 0:
                eof = True
            elif kind in (2, 4) and count == 2 and address == 0:
                base = int.from_bytes(data, 'big') << (4 if kind == 2 else 16)
            elif kind in (3, 5) and count == 4 and address == 0:
                pass
            else:
                raise ValueError()
        if not eof or not covered:
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ValueError('HEX không hợp lệ: kiểm tra checksum, EOF, địa chỉ APROM 32 KiB và vùng dữ liệu trùng.') from None
    return zlib.crc32(coverage + image)


class PolicyEdit(BaseModel):
    visible: list[str] = Field(max_length=51)
    revision: int = Field(ge=0)


class SettingsEdit(BaseModel):
    values: dict = Field(max_length=51)
    revision: int = Field(ge=0)


class UpdateStart(BaseModel):
    id: str = Field(pattern=r'^[a-f0-9]{32}$')
    release: str = Field(pattern=r'^[a-f0-9]{32}$')


def install(app, core, admin_identity, audit):
    db, lock = core.db, core.lock
    with lock:
        db.executescript(SCHEMA)
        migrate_mode_permissions(db)
        # An interrupted process must never claim success or automatically replay programming.
        db.execute("UPDATE update_jobs SET state='unknown',message='Máy chủ đã khởi động lại. Cần admin kiểm tra rồi cấp lại bản cập nhật.' WHERE state='running'")
        db.commit()

    def policy(device):
        row = db.execute('SELECT * FROM device_policies WHERE device=?', (device,)).fetchone()
        return {'visible': visible_settings(json.loads(row['visible'])) if row else list(ACCESS_CATALOG),
                'revision': row['revision'] if row else 0}

    def release_view(device):
        row = db.execute('SELECT id,filename,sha256,created,installed FROM device_releases WHERE device=?', (device,)).fetchone()
        return dict(row) if row else None

    def job_view(device):
        row = db.execute('SELECT id,release,state,message FROM update_jobs WHERE device=? ORDER BY created DESC LIMIT 1', (device,)).fetchone()
        return dict(row) if row else None

    def acquire(row, uid, lease):
        with lock:
            core.device_for(uid, row['id'])
            if row['id'] in core.managed_locks or row['id'] in core.busy:
                core.fail(409, 'Thiết bị đang thực hiện thao tác khác. Vui lòng đợi.')
            core.managed_locks[row['id']] = lease

    def release_lock(device, lease):
        with lock:
            if core.managed_locks.get(device) == lease:
                del core.managed_locks[device]

    def call(row, uid, lease, method, path, body=None):
        # Refresh liveness rather than using the row captured before a long upload/program operation.
        fresh = core.device_for(uid, row['id'])
        if fresh['mqtt'] != row['mqtt']:
            core.fail(409, 'Thiết bị đã được ghép lại. Hãy mở lại thiết bị.')
        result = core.bridge.call(fresh, uid, secrets.token_hex(16), method, path, body or {}, lease=lease)
        if result.get('ok') is not True:
            core.fail(502, result.get('message', 'Chưa xác nhận được kết quả từ thiết bị.'))
        return result

    def admin_device(uid, device, request, mutation=False):
        who = admin_identity(request, mutation)
        core.device_for(uid, device)
        return who

    @app.get('/admin/api/users/{uid}/devices/{device}/access')
    def admin_access(uid: str, device: str, request: Request):
        admin_device(uid, device, request)
        with lock:
            return {'ok': True, 'catalog': ACCESS_CATALOG, **policy(device), 'firmware': release_view(device), 'job': job_view(device)}

    @app.post('/admin/api/users/{uid}/devices/{device}/access')
    def admin_policy(uid: str, device: str, data: PolicyEdit, request: Request):
        who = admin_device(uid, device, request, True)
        if len(set(data.visible)) != len(data.visible) or set(data.visible) - CATALOG.keys():
            core.fail(400, 'Danh sách quyền không hợp lệ.')
        with lock:
            core.device_for(uid, device)
            if device in core.managed_locks or device in core.busy:
                core.fail(409, 'Đợi thiết bị hoàn tất thao tác trước khi đổi quyền.')
            if policy(device)['revision'] != data.revision:
                core.fail(409, 'Quyền vừa được thay đổi. Đóng rồi mở lại mục Cấp quyền.')
            db.execute('INSERT INTO device_policies VALUES(?,?,?) ON CONFLICT(device) DO UPDATE SET visible=excluded.visible,revision=excluded.revision',
                       (device, json.dumps(visible_settings(data.visible)), data.revision + 1))
            audit(who['username'], 'device.policy', device)
            db.commit()
        return {'ok': True, 'revision': data.revision + 1}

    @app.post('/admin/api/users/{uid}/devices/{device}/firmware')
    async def admin_firmware(uid: str, device: str, request: Request):
        who = admin_device(uid, device, request, True)
        filename = request.headers.get('x-filename', '')
        if not re.fullmatch(r'[A-Za-z0-9._ -]{1,95}\.hex', filename, re.I):
            core.fail(400, 'Chọn tệp .hex, tên gồm chữ không dấu, số, dấu chấm, gạch ngang hoặc gạch dưới.')
        content = bytearray()
        async for chunk in request.stream():
            content.extend(chunk)
            if len(content) > 98304:
                core.fail(413, 'Tệp HEX vượt 96 KiB.')
        try:
            crc = parse_hex(content)
        except ValueError as error:
            core.fail(400, str(error))
        with lock:
            core.device_for(uid, device)
            if device in core.managed_locks or device in core.busy:
                core.fail(409, 'Thiết bị đang thực hiện thao tác. Chờ hoàn tất rồi cấp firmware.')
            db.execute("INSERT INTO device_releases VALUES(?,?,?,?,?,?,?,'') ON CONFLICT(device) DO UPDATE SET id=excluded.id,filename=excluded.filename,content=excluded.content,sha256=excluded.sha256,crc32=excluded.crc32,created=excluded.created,installed=''",
                       (device, secrets.token_hex(16), filename, bytes(content), hashlib.sha256(content).hexdigest(), crc, time.time()))
            audit(who['username'], 'device.firmware.assign', device)
            db.commit()
            return {'ok': True, 'firmware': release_view(device)}

    @app.post('/admin/api/users/{uid}/devices/{device}/firmware/revoke')
    def revoke_firmware(uid: str, device: str, request: Request):
        who = admin_device(uid, device, request, True)
        with lock:
            core.device_for(uid, device)
            if device in core.managed_locks:
                core.fail(409, 'Bản cập nhật đang được thực hiện. Vui lòng đợi.')
            db.execute('DELETE FROM device_releases WHERE device=?', (device,))
            audit(who['username'], 'device.firmware.revoke', device)
            db.commit()
        return {'ok': True}

    @app.get('/devices/{device}/history')
    def history(device: str, request: Request):
        user = core.owner(request)
        core.limited(request, 'history', 30)
        row = core.device_for(user['id'], device)
        # Read the existing UART G response; never write EEPROM or reset the MCU.
        # History remains readable even when every setting is hidden. Return only
        # sig_count, never the configuration bytes (which include PINs).
        fresh = call(row, user['id'], None, 'GET', '/api/runtime-config')
        encoded = fresh.get('data')
        if not isinstance(encoded, str) or not re.fullmatch(r'[0-9a-fA-F]{434}', encoded):
            core.fail(502, 'Nhật ký MS51 trả về không hợp lệ.')
        raw = bytes.fromhex(encoded)
        if raw[0] != 2:
            core.fail(502, 'Phiên bản nhật ký MS51 chưa được hỗ trợ.')
        return {'ok': True, 'capacity': 30, 'tick_ms': 100,
                'ticks': [int.from_bytes(raw[i:i+4], 'big') for i in range(0x33, 0xAB, 4)]}

    @app.get('/devices/{device}/access')
    def access(device: str, request: Request):
        user = core.owner(request)
        core.device_for(user['id'], device)
        with lock:
            firmware = release_view(device)
            return {'ok': True, **policy(device), 'firmware': firmware, 'job': job_view(device),
                    'update_available': bool(firmware and firmware['id'] != firmware['installed'])}

    @app.post('/devices/{device}/settings')
    def settings(device: str, data: SettingsEdit, request: Request):
        user = core.owner(request)
        core.limited(request, 'managed-settings', 15)
        row = core.device_for(user['id'], device)
        lease = secrets.token_hex(16)
        acquire(row, user['id'], lease)
        try:
            with lock:
                rules = policy(device)
            if data.revision != rules['revision'] or set(data.values) - set(rules['visible']):
                core.fail(403, 'Quyền cài đặt đã thay đổi hoặc có mục bị ẩn. Hãy mở lại Cài đặt.')
            if not data.values:
                core.fail(400, 'Không có cài đặt được cấp quyền để lưu.')
            for key, value in data.values.items():
                spec = CATALOG[key]
                if type(value) is not int or not spec['min'] <= value <= spec['max']:
                    core.fail(400, 'Giá trị không hợp lệ: ' + spec['label'])
            fresh = call(row, user['id'], lease, 'GET', '/api/runtime-config')
            try:
                raw = bytearray.fromhex(fresh.get('data', ''))
            except (ValueError, TypeError):
                core.fail(502, 'Cấu hình MS51 chưa hợp lệ.')
            if len(raw) != 217 or raw[0] != 2:
                core.fail(502, 'Cấu hình MS51 chưa hợp lệ.')
            for key, value in data.values.items():
                spec = CATALOG[key]
                raw[spec['offset']:spec['offset'] + spec['size']] = value.to_bytes(spec['size'], 'big')
            values = {k: int.from_bytes(raw[v['offset']:v['offset']+v['size']], 'big') for k, v in CATALOG.items()}
            if values['pinEnable']:
                for key, spec in CATALOG.items():
                    if spec['group'] == 'pin' and not spec['min'] <= values[key] <= spec['max']:
                        core.fail(400, 'Thiết lập mật khẩu chưa hợp lệ: ' + spec['label'])
            for stem, current in [('rangeCoolCl','coolCl'),('rangeCoolOp','coolOp'),('rangeDefrostCl','defrostCl'),('rangeDefrostOp','defrostOp'),('rangeStLcd','stLcd'),('rangeStLed','stLed')]:
                if not values[stem+'Min'] <= values[current] <= values[stem+'Max']:
                    core.fail(400, CATALOG[current]['label'] + ' phải nằm trong giới hạn Min / Max.')
            # Preserve unedited settings/history, except PIN runtime state which
            # MS51 rejects on SET. This follows the existing Android/web codec.
            prepare_pin_for_write(raw)
            return call(row, user['id'], lease, 'POST', '/api/runtime-config', {'data': raw.hex().upper()})
        finally:
            release_lock(device, lease)

    def finish(job, state, message):
        with lock:
            db.execute('UPDATE update_jobs SET state=?,message=? WHERE id=?', (state, message, job))
            db.commit()

    def worker(row, uid, job, firmware):
        upload_id = None
        try:
            status = call(row, uid, job, 'GET', '/api/status')
            if status.get('busy') is not False:
                finish(job, 'failed', 'Thiết bị đang bận. Chờ hoàn tất trước khi cập nhật.'); return
            token, upload_id = secrets.token_urlsafe(32), secrets.token_hex(16)
            with lock:
                core.uploads[upload_id] = (time.time() + 90, core.digest(token), firmware['content'])
            result = call(row, uid, job, 'POST', '/internal/upload', {'upload_id': upload_id, 'token': token, 'filename': firmware['filename'], 'size': len(firmware['content'])})
            image = result.get('image', {})
            generation = image.get('generation', 0)
            if generation <= 0 or image.get('crc32') != firmware['crc32']:
                core.fail(502, 'Ảnh firmware trên thiết bị không khớp bản admin đã cấp.')
            # Program only covered APROM pages, then explicitly verify against the same generation.
            for action, message in [('program', 'Đang nạp chương trình MS51…'), ('verify', 'Đang đối chiếu chương trình…')]:
                finish(job, 'running', message)
                response = call(row, uid, job, 'POST', '/api/' + action, {'generation': generation})
                if response.get('accepted') is not True or type(response.get('job_id')) is not int:
                    core.fail(502, 'Chưa xác nhận được lệnh cập nhật.')
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    time.sleep(1)
                    status = call(row, uid, job, 'GET', '/api/status')
                    if status.get('job_id') != response['job_id'] or status.get('image', {}).get('generation') != generation or status.get('image', {}).get('crc32') != firmware['crc32']:
                        core.fail(502, 'Tác vụ hoặc ảnh firmware đã thay đổi. Cần kiểm tra thiết bị.')
                    if status.get('busy') is False:
                        if status.get('last_error') != 'ESP_OK':
                            finish(job, 'failed', status.get('last_message', 'Thiết bị báo cập nhật thất bại.')); return
                        break
                else:
                    core.fail(504, 'Chưa xác nhận được kết quả cập nhật. Cần admin kiểm tra thiết bị.')
            with lock:
                db.execute('UPDATE device_releases SET installed=? WHERE device=? AND id=?', (firmware['id'], row['id'], firmware['id']))
                finish(job, 'success', 'Đã cập nhật và đối chiếu chương trình thành công.')
        except Exception as error:
            message = str(error.detail) if isinstance(error, HTTPException) else 'Kết nối bị gián đoạn. Cần admin kiểm tra rồi cấp lại bản cập nhật.'
            finish(job, 'unknown', message)
        finally:
            with lock:
                if upload_id:
                    core.uploads.pop(upload_id, None)
            release_lock(row['id'], job)

    @app.post('/devices/{device}/update')
    def update(device: str, data: UpdateStart, request: Request):
        user = core.owner(request)
        core.limited(request, 'managed-update', 10)
        row = core.device_for(user['id'], device)
        with lock:
            previous = db.execute('SELECT * FROM update_jobs WHERE id=?', (data.id,)).fetchone()
            if previous:
                if previous['device'] != device or previous['owner'] != user['id'] or previous['release'] != data.release:
                    core.fail(409, 'Mã yêu cầu đã được sử dụng.')
                return {'ok': True, 'job': {k: previous[k] for k in ('id','release','state','message')}}
            firmware = db.execute('SELECT * FROM device_releases WHERE device=?', (device,)).fetchone()
            if not firmware or firmware['id'] != data.release or firmware['installed'] == data.release:
                core.fail(409, 'Không có phiên bản cập nhật mới. Hãy mở lại mục Firmware.')
            uncertain = db.execute("SELECT 1 FROM update_jobs WHERE device=? AND release=? AND state IN ('unknown','running')", (device, data.release)).fetchone()
            if uncertain:
                core.fail(409, 'Bản cập nhật đang xử lý hoặc chưa xác nhận kết quả. Cần admin kiểm tra trước khi cấp lại.')
            if not core.online(row):
                core.fail(409, 'Thiết bị đang ngoại tuyến. Chờ thiết bị kết nối để cập nhật.')
            acquire(row, user['id'], data.id)
            db.execute("INSERT INTO update_jobs VALUES(?,?,?,?,'running',?,?)", (data.id, device, user['id'], data.release, 'Đang gửi firmware đến thiết bị…', time.time()))
            db.commit()
            core.managed_spawn(row, user['id'], data.id, dict(firmware))
            return {'ok': True, 'job': job_view(device)}

    core.managed_policy = policy
    core.managed_worker = worker
    core.managed_spawn = lambda *args: threading.Thread(target=worker, args=args, daemon=True).start()
