"""MS51 Connect HTTPS -> authenticated MQTT bridge. Run one uvicorn worker."""
import asyncio
import hashlib
import json
import os
import re
import secrets
import sqlite3
import ssl
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from pathlib import Path

import paho.mqtt.client as mqtt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

HOST = os.getenv("MS51_HOST", "142-91-99-63.sslip.io")
PORT = int(os.getenv("MS51_MQTT_PORT", "8884"))
ROOT = Path(os.getenv("MS51_DATA", "/var/lib/ms51-connect"))
PREFIX = "ms51connect/v1/"
API = "/ms51/connect/v1"
ROOT.mkdir(parents=True, exist_ok=True)
db = sqlite3.connect(ROOT / "connect.sqlite3", check_same_thread=False)
db.row_factory = sqlite3.Row
db.execute("PRAGMA journal_mode=WAL")
db.execute("PRAGMA foreign_keys=ON")
lock = threading.RLock()
db.executescript("""
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
 mqtt TEXT UNIQUE NOT NULL, online INTEGER NOT NULL DEFAULT 0, seen REAL NOT NULL DEFAULT 0,
 state TEXT NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS claims(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), device TEXT UNIQUE NOT NULL,
 mqtt TEXT UNIQUE NOT NULL, secret TEXT NOT NULL, nonce TEXT NOT NULL, expires REAL NOT NULL, complete INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY, owner TEXT NOT NULL, device TEXT NOT NULL, fingerprint TEXT NOT NULL,
 result TEXT, created REAL NOT NULL);
""")
db.commit()
# Schema migration is additive; existing accounts and device ownership are retained.
if "ready" not in {row[1] for row in db.execute("PRAGMA table_info(claims)")}:
    db.execute("ALTER TABLE claims ADD COLUMN ready INTEGER NOT NULL DEFAULT 1")
    db.commit()
ph = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2)
DUMMY = ph.hash(secrets.token_urlsafe(32))
limits = defaultdict(deque)
waiters = {}
busy = set()
uploads = {}
admin_waiters = {}


def fail(code, message):
    raise HTTPException(code, message)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def limited(request, kind, count, period=60):
    # nginx overwrites X-Real-IP; uvicorn listens only on loopback.
    key = (request.headers.get("x-real-ip", request.client.host), kind)
    now = time.time()
    with lock:
        q = limits[key]
        while q and q[0] < now - period:
            q.popleft()
        if len(q) >= count:
            fail(429, "Thao tác quá nhanh. Vui lòng thử lại sau.")
        q.append(now)


def owner(request):
    token = request.headers.get("authorization", "")
    if not token.startswith("Bearer ") or len(token) > 200:
        fail(401, "Vui lòng đăng nhập lại.")
    with lock:
        row = db.execute("SELECT u.id,u.username FROM sessions s JOIN users u ON u.id=s.owner WHERE s.hash=? AND s.expires>?",
                         (digest(token[7:]), time.time())).fetchone()
    if not row:
        fail(401, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.")
    return dict(row)


def device_for(uid, device):
    with lock:
        row = db.execute("SELECT * FROM devices WHERE id=? AND owner=?", (device, uid)).fetchone()
    if not row:
        fail(404, "Thiết bị không thuộc tài khoản này.")
    return dict(row)


def online(row):
    return bridge.connected.is_set() and bool(row["online"]) and time.time() - row["seen"] < 45


class Bridge:
    def __init__(self):
        self.connected = threading.Event()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="ms51-connect-service", clean_session=True)
        self.client.username_pw_set(os.environ.get("MS51_MQTT_ADMIN", "connect-admin"), os.environ.get("MS51_MQTT_PASSWORD", ""))
        self.client.tls_set(cert_reqs=ssl.CERT_REQUIRED)
        self.client.reconnect_delay_set(1, 30)
        self.client.on_connect = self.on_connect
        self.client.on_disconnect = lambda *args: self.connected.clear()
        self.client.on_message = self.on_message

    def on_connect(self, client, userdata, flags, reason, properties):
        if reason.is_failure:
            return
        # Mark stale devices offline after service restart/reconnect, until a fresh heartbeat.
        with lock:
            db.execute("UPDATE devices SET online=0")
            db.commit()
        client.subscribe([(PREFIX + "+/hello", 1), (PREFIX + "+/state", 1),
                          (PREFIX + "+/reply/+", 1), ("$CONTROL/dynamic-security/v1/response", 1)])
        self.connected.set()

    def on_message(self, client, userdata, message):
        if len(message.payload) > 65536:
            return
        try:
            data = json.loads(message.payload)
            if not isinstance(data, dict):
                return
            if message.topic == "$CONTROL/dynamic-security/v1/response":
                for response in data.get("responses", []):
                    with lock:
                        pending = admin_waiters.get(response.get("correlationData"))
                        if pending:
                            pending[1].append(response)
                            pending[0].set()
                return
            parts = message.topic.split("/")
            user, kind = parts[2:4]
            with lock:
                if kind == "hello" and not message.retain:
                    claim = db.execute("SELECT * FROM claims WHERE mqtt=? AND complete=0 AND expires>?", (user, time.time())).fetchone()
                    if claim and data.get("device") == claim["device"] and secrets.compare_digest(str(data.get("nonce", "")), claim["nonce"]):
                        db.execute("INSERT OR IGNORE INTO devices(id,owner,name,mqtt) VALUES(?,?,?,?)",
                                   (claim["device"], claim["owner"], "MS51 " + claim["device"][-6:].upper(), user))
                        db.execute("UPDATE claims SET complete=1,secret='' WHERE id=?", (claim["id"],))
                        db.commit()
                elif kind == "state":
                    # A retained old online message is never accepted as liveness evidence.
                    if message.retain and data.get("online"):
                        return
                    device = db.execute("SELECT id FROM devices WHERE mqtt=?", (user,)).fetchone()
                    if device and data.get("device") == device["id"]:
                        state = data.get("status", {})
                        if not isinstance(state, dict) or not isinstance(state.get("wifi", {}), dict):
                            return
                        if not isinstance(state.get("wifi", {}).get("uplink", {}), dict):
                            return
                        db.execute("UPDATE devices SET online=?,seen=?,state=? WHERE mqtt=?",
                                   (int(data.get("online") is True), time.time(), json.dumps(state), user))
                        db.commit()
                elif kind == "reply" and len(parts) == 5 and not message.retain:
                    pending = waiters.get(parts[4])
                    if pending and pending[2] == user and data.get("id") == parts[4]:
                        # A correlated authenticated reply also proves current liveness,
                        # including immediately after the device's upload TLS reconnect.
                        db.execute("UPDATE devices SET online=1,seen=? WHERE mqtt=?", (time.time(), user))
                        db.commit()
                        pending[1].append(data)
                        pending[0].set()
        except (ValueError, KeyError, TypeError, IndexError):
            return

    def admin(self, command, allow_missing=False, **fields):
        if not self.connected.wait(5):
            fail(503, "Dịch vụ MQTT đang kết nối lại.")
        correlation = secrets.token_hex(16)
        pending = (threading.Event(), [])
        with lock:
            admin_waiters[correlation] = pending
        try:
            self.client.publish("$CONTROL/dynamic-security/v1", json.dumps({"commands": [dict(command=command, correlationData=correlation, **fields)]}), qos=1)
            if not pending[0].wait(8):
                fail(503, "Chưa nhận được phản hồi dịch vụ ghép nối.")
            response = pending[1][0]
            if response.get("error"):
                if allow_missing and any(word in response["error"].lower() for word in ("not found", "does not exist")):
                    return
                fail(503, "Không cập nhật được quyền MQTT.")
        finally:
            with lock:
                admin_waiters.pop(correlation, None)

    def call(self, row, uid, rid, method, path, body):
        fingerprint = digest(json.dumps([method, path, body], sort_keys=True))
        with lock:
            if not db.execute("SELECT 1 FROM devices WHERE id=? AND owner=? AND mqtt=?", (row["id"], uid, row["mqtt"])).fetchone():
                fail(404, "Thiết bị đã được gỡ khỏi tài khoản.")
            previous = db.execute("SELECT * FROM requests WHERE id=?", (rid,)).fetchone()
            if previous:
                if previous["owner"] != uid or previous["device"] != row["id"] or previous["fingerprint"] != fingerprint:
                    fail(409, "Mã yêu cầu đã được sử dụng.")
                if previous["result"]:
                    return json.loads(previous["result"])
                fail(409, "Yêu cầu đã gửi. Kiểm tra trạng thái thiết bị trước khi thao tác lại.")
            if not online(row):
                fail(409, "Thiết bị chưa kết nối Internet hoặc đang khởi động lại.")
            if row["id"] in busy:
                fail(409, "Thiết bị đang xử lý một yêu cầu. Vui lòng đợi.")
            busy.add(row["id"])
            pending = (threading.Event(), [], row["mqtt"])
            waiters[rid] = pending
            db.execute("INSERT INTO requests VALUES(?,?,?,?,NULL,?)", (rid, uid, row["id"], fingerprint, time.time()))
            db.commit()
        try:
            now = int(time.time())
            command = dict(id=rid, method=method, path=path, body=body, issued_at=now, expires_at=now + 45)
            info = self.client.publish(PREFIX + row["mqtt"] + "/cmd", json.dumps(command), qos=1, retain=False)
            if info.rc != mqtt.MQTT_ERR_SUCCESS or not pending[0].wait(38):
                result = {"ok": False, "message": "Chưa nhận được kết quả. Hãy kiểm tra trạng thái trước khi gửi lại lệnh."}
            else:
                result = pending[1][0].get("result")
                if not isinstance(result, dict) or not isinstance(result.get("ok"), bool):
                    result = {"ok": False, "message": "Thiết bị trả về dữ liệu không hợp lệ."}
            with lock:
                db.execute("UPDATE requests SET result=? WHERE id=?", (json.dumps(result), rid))
                db.commit()
            return result
        finally:
            with lock:
                busy.discard(row["id"])
                waiters.pop(rid, None)


bridge = Bridge()


async def cleanup():
    while True:
        await asyncio.sleep(30)
        now = time.time()
        with lock:
            expired = db.execute("SELECT id,mqtt FROM claims WHERE complete=0 AND expires<?", (now,)).fetchall()
            db.execute("DELETE FROM sessions WHERE expires<?", (now,))
            db.execute("DELETE FROM requests WHERE created<?", (now - 86400 * 7,))
            db.execute("DELETE FROM claims WHERE complete=1 AND expires<?", (now - 86400,))
            db.commit()
            for key in list(uploads):
                if uploads[key][0] < now:
                    del uploads[key]
            for key in list(limits):
                if not limits[key] or limits[key][-1] < now - 3600:
                    del limits[key]
        for row in expired:
            try:
                await asyncio.to_thread(bridge.admin, "deleteClient", allow_missing=True, username=row["mqtt"])
                await asyncio.to_thread(bridge.admin, "deleteRole", allow_missing=True, rolename=row["mqtt"])
                with lock:
                    db.execute("DELETE FROM claims WHERE id=? AND complete=0", (row["id"],))
                    db.commit()
            except HTTPException:
                pass


@asynccontextmanager
async def lifespan(app):
    bridge.client.connect_async(HOST, PORT, 30)
    bridge.client.loop_start()
    task = asyncio.create_task(cleanup())
    yield
    task.cancel()
    bridge.client.disconnect()
    bridge.client.loop_stop()


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(HTTPException)
async def http_error(request, error):
    return JSONResponse({"ok": False, "message": error.detail}, status_code=error.status_code)


@app.exception_handler(RequestValidationError)
async def invalid_request(request, error):
    # Pydantic diagnostics can contain the submitted password; never echo them.
    return JSONResponse({"ok": False, "message": "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại."}, status_code=422)


@app.get("/health")
def health():
    return {"ok": True, "mqtt": bridge.connected.is_set(), "protocol": 1}


class Credentials(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=8, max_length=128)


def session(user):
    token = secrets.token_urlsafe(32)
    with lock:
        db.execute("INSERT INTO sessions VALUES(?,?,?)", (digest(token), user["id"], time.time() + 86400 * 30))
        db.commit()
    return {"ok": True, "user": dict(id=user["id"], username=user["username"]), "token": token}


@app.post("/auth/register")
def register(data: Credentials, request: Request):
    limited(request, "register", 5, 3600)
    name = data.username.strip().lower()
    if not re.fullmatch(r"[a-z0-9._-]{3,32}", name):
        fail(400, "Tên đăng nhập gồm 3–32 chữ không dấu, số, dấu chấm, gạch ngang hoặc gạch dưới.")
    user = dict(id=secrets.token_hex(16), username=name)
    password_hash = ph.hash(data.password)
    with lock:
        try:
            db.execute("INSERT INTO users VALUES(?,?,?)", (user["id"], name, password_hash))
            db.commit()
        except sqlite3.IntegrityError:
            db.rollback()
            fail(409, "Tên đăng nhập đã tồn tại.")
    return session(user)


@app.post("/auth/login")
def login(data: Credentials, request: Request):
    limited(request, "login", 12)
    with lock:
        user = db.execute("SELECT * FROM users WHERE username=?", (data.username.strip().lower(),)).fetchone()
    try:
        ph.verify(user["hash"] if user else DUMMY, data.password)
    except VerificationError:
        fail(401, "Tên đăng nhập hoặc mật khẩu chưa đúng.")
    if not user:
        fail(401, "Tên đăng nhập hoặc mật khẩu chưa đúng.")
    with lock:
        current = db.execute("SELECT username,hash FROM users WHERE id=?", (user["id"],)).fetchone()
        if not current or current["hash"] != user["hash"] or current["username"] != user["username"]:
            fail(401, "Thông tin đăng nhập vừa thay đổi. Vui lòng đăng nhập lại.")
        return session(user)


@app.post("/auth/logout")
def logout(request: Request):
    owner(request)
    with lock:
        db.execute("DELETE FROM sessions WHERE hash=?", (digest(request.headers["authorization"][7:]),))
        db.commit()
    return {"ok": True}


@app.get("/devices")
def devices(request: Request):
    user = owner(request)
    with lock:
        rows = db.execute("SELECT * FROM devices WHERE owner=? ORDER BY name,id", (user["id"],)).fetchall()
    result = []
    for row in rows:
        state = json.loads(row["state"])
        wifi = state.get("wifi", {}).get("uplink", {})
        result.append(dict(id=row["id"], name=row["name"], online=online(row), last_seen=row["seen"],
                           ip=wifi.get("ip", ""), ssid=wifi.get("ssid", ""), hostname=row["id"] + ".local"))
    return {"ok": True, "devices": result}


class PairStart(BaseModel):
    device: str = Field(pattern=r"^ms51-[0-9a-f]{12}$")


@app.post("/pair/start")
def pair_start(data: PairStart, request: Request):
    user = owner(request)
    limited(request, "pair", 10, 600)
    with lock:
        existing = db.execute("SELECT owner FROM devices WHERE id=?", (data.device,)).fetchone()
        if existing:
            if existing["owner"] != user["id"]:
                fail(409, "Thiết bị đã được ghép với tài khoản khác.")
            return {"ok": True, "paired": True}
        claim = db.execute("SELECT * FROM claims WHERE device=?", (data.device,)).fetchone()
        if claim and (claim["owner"] != user["id"] or claim["expires"] <= time.time()):
            fail(409, "Thiết bị đang có phiên ghép nối. Hãy thử lại sau 10 phút.")
        if claim:
            if not claim["ready"]:
                fail(409, "Đang chuẩn bị phiên ghép nối. Hãy thử lại sau vài giây.")
            return claim_response(claim)
        claim = dict(id=secrets.token_hex(16), owner=user["id"], device=data.device, mqtt="c" + secrets.token_hex(16),
                     secret=secrets.token_urlsafe(32), nonce=secrets.token_hex(24), expires=time.time() + 600)
        db.execute("INSERT INTO claims(id,owner,device,mqtt,secret,nonce,expires,ready) VALUES(:id,:owner,:device,:mqtt,:secret,:nonce,:expires,0)", claim)
        db.commit()
    try:
        # Mosquitto 2.0 does not interpolate %u in dynamic-security ACLs.
        # Use an exact per-device role, also excluding wildcard subscriptions.
        role = claim["mqtt"]
        topic = PREFIX + role
        bridge.admin("createRole", rolename=role)
        for acltype, suffix in (("publishClientSend", "/hello"), ("publishClientSend", "/state"),
                                ("publishClientSend", "/reply/+"), ("publishClientReceive", "/cmd"),
                                ("subscribeLiteral", "/cmd")):
            bridge.admin("addRoleACL", rolename=role, acltype=acltype, topic=topic + suffix, allow=True)
        bridge.admin("createClient", username=role, password=claim["secret"], clientid=role, roles=[{"rolename": role}])
        with lock:
            db.execute("UPDATE claims SET ready=1 WHERE id=?", (claim["id"],))
            db.commit()
    except HTTPException:
        with lock:
            db.execute("UPDATE claims SET expires=? WHERE id=?", (time.time() - 1, claim["id"]))
            db.commit()
        fail(503, "Dịch vụ ghép nối chưa sẵn sàng. Thử lại sau ít phút.")
    return claim_response(claim)


def claim_response(claim):
    return {"ok": True, "paired": False, "claim_id": claim["id"], "config": dict(device=claim["device"],
            host=HOST, port=PORT, username=claim["mqtt"], password=claim["secret"], nonce=claim["nonce"])}


@app.get("/pair/{claim_id}")
def pair_status(claim_id: str, request: Request):
    user = owner(request)
    with lock:
        claim = db.execute("SELECT * FROM claims WHERE id=? AND owner=?", (claim_id, user["id"])).fetchone()
    if not claim:
        fail(404, "Phiên ghép nối không còn tồn tại.")
    return {"ok": True, "paired": bool(claim["complete"]), "expired": claim["expires"] < time.time()}


class Rename(BaseModel):
    name: str = Field(min_length=1, max_length=40)


@app.post("/devices/{device}/name")
def rename(device: str, data: Rename, request: Request):
    user = owner(request)
    device_for(user["id"], device)
    if not data.name.strip():
        fail(400, "Nhập tên thiết bị.")
    with lock:
        db.execute("UPDATE devices SET name=? WHERE id=? AND owner=?", (data.name.strip(), device, user["id"]))
        db.commit()
    return {"ok": True}


@app.post("/devices/{device}/remove")
def remove(device: str, request: Request):
    user = owner(request)
    row = device_for(user["id"], device)
    with lock:
        if device in busy:
            fail(409, "Thiết bị đang thực hiện một yêu cầu. Vui lòng đợi.")
        busy.add(device)
    # Revocation also works offline. The ESP clears credentials only on a TLS-authenticated
    # broker's explicit not-authorized response, never on ordinary network failures.
    try:
        bridge.admin("deleteClient", allow_missing=True, username=row["mqtt"])
        bridge.admin("deleteRole", allow_missing=True, rolename=row["mqtt"])
        with lock:
            db.execute("DELETE FROM devices WHERE id=? AND owner=?", (device, user["id"]))
            db.execute("DELETE FROM claims WHERE device=?", (device,))
            db.commit()
    finally:
        with lock:
            busy.discard(device)
    return {"ok": True}


class Rpc(BaseModel):
    id: str = Field(pattern=r"^[a-f0-9]{32}$")
    method: str
    path: str
    body: dict = Field(default_factory=dict)


GET_PATHS = {"/api/status", "/api/debug", "/api/runtime-config"}
POST_PATHS = {"/api/info", "/api/reset", "/api/runtime-config", "/api/program", "/api/verify", "/api/program-full", "/api/erase"}


@app.post("/devices/{device}/request")
def rpc(device: str, data: Rpc, request: Request):
    user = owner(request)
    limited(request, "rpc", 180)
    row = device_for(user["id"], device)
    if not (data.method == "GET" and data.path in GET_PATHS or data.method == "POST" and data.path in POST_PATHS):
        fail(400, "Lệnh này chỉ dùng khi kết nối trực tiếp với thiết bị.")
    if len(json.dumps(data.body).encode()) > 4096:
        fail(413, "Nội dung lệnh quá lớn.")
    return bridge.call(row, user["id"], data.id, data.method, data.path, data.body)


@app.post("/devices/{device}/upload")
async def upload(device: str, request: Request):
    user = owner(request)
    limited(request, "upload", 6)
    row = device_for(user["id"], device)
    name = request.headers.get("x-filename", "")
    if not re.fullmatch(r"[A-Za-z0-9._ -]{1,100}\.(hex|ihex|ihx|bin)", name, re.I):
        fail(400, "Tên tệp phải kết thúc bằng .hex, .ihex, .ihx hoặc .bin.")
    rid = request.headers.get("x-request-id", "")
    if not re.fullmatch(r"[a-f0-9]{32}", rid):
        fail(400, "Thiếu mã yêu cầu.")
    maximum = 32768 if name.lower().endswith(".bin") else 98304
    content = bytearray()
    async for chunk in request.stream():
        content.extend(chunk)
        if len(content) > maximum:
            fail(413, "Tệp vượt dung lượng MS51.")
    if not content:
        fail(400, "Tệp trống.")
    token = secrets.token_urlsafe(32)
    upload_id = secrets.token_hex(16)
    with lock:
        uploads[upload_id] = (time.time() + 60, digest(token), bytes(content))
    try:
        return await asyncio.to_thread(bridge.call, row, user["id"], rid, "POST", "/internal/upload",
                                      dict(upload_id=upload_id, token=token, filename=name, size=len(content)))
    finally:
        with lock:
            uploads.pop(upload_id, None)


@app.get("/transfer/{upload_id}")
def transfer(upload_id: str, request: Request):
    bearer = request.headers.get("authorization", "")
    with lock:
        item = uploads.get(upload_id)
    if not item or item[0] < time.time() or not bearer.startswith("Bearer ") or not secrets.compare_digest(item[1], digest(bearer[7:])):
        fail(404, "Tệp đã hết hạn.")
    return Response(item[2], media_type="application/octet-stream", headers={"Cache-Control": "no-store"})


# Install after the mobile routes; admin identities and cookies are independent.
import sys
from admin import install as install_admin
install_admin(app, sys.modules[__name__])
