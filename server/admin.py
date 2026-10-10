"""Same-origin administration; separate identities from mobile application users."""
import os
import re
import secrets
import sqlite3
import time
from pathlib import Path

from argon2.exceptions import VerificationError
from fastapi import Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, Field

SCHEMA = """
CREATE TABLE IF NOT EXISTS administrators(username TEXT PRIMARY KEY, hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS admin_sessions(hash TEXT PRIMARY KEY, username TEXT NOT NULL REFERENCES administrators(username),
 csrf TEXT NOT NULL, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS admin_audit(id INTEGER PRIMARY KEY, at REAL NOT NULL, actor TEXT NOT NULL,
 action TEXT NOT NULL, target TEXT NOT NULL);
"""


class Login(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=8, max_length=128)


class UserEdit(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str | None = Field(default=None, min_length=8, max_length=128)


class DeviceEdit(BaseModel):
    name: str = Field(min_length=1, max_length=40)


class PasswordEdit(BaseModel):
    current_password: str = Field(min_length=8, max_length=128)
    password: str = Field(min_length=12, max_length=128)


def install(app, core):
    db, lock = core.db, core.lock
    with lock:
        db.executescript(SCHEMA)
        db.commit()
    assets = Path(__file__).parent / "admin_web"
    cookie = "ms51_admin"
    cookie_path = core.API + "/admin/"
    origin = os.getenv("MS51_ADMIN_ORIGIN", "https://" + core.HOST).rstrip("/")

    def same_origin(request):
        if request.headers.get("origin") != origin:
            core.fail(403, "Yêu cầu không đến từ trang quản trị.")

    def identity(request, mutation=False):
        token = request.cookies.get(cookie, "")
        if not token or len(token) > 100:
            core.fail(401, "Vui lòng đăng nhập quản trị.")
        with lock:
            row = db.execute("SELECT * FROM admin_sessions WHERE hash=? AND expires>?",
                             (core.digest(token), time.time())).fetchone()
        if not row:
            core.fail(401, "Phiên quản trị đã hết hạn. Vui lòng đăng nhập lại.")
        if mutation:
            same_origin(request)
            if not secrets.compare_digest(request.headers.get("x-csrf-token", ""), row["csrf"]):
                core.fail(403, "Phiên thao tác không hợp lệ. Hãy tải lại trang.")
            core.limited(request, "admin-write", 30)
        return dict(row)

    def audit(actor, action, target):
        db.execute("INSERT INTO admin_audit(at,actor,action,target) VALUES(?,?,?,?)",
                   (time.time(), actor, action, target))

    @app.middleware("http")
    async def admin_headers(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith(("/admin", core.API + "/admin")):
            response.headers.update({
                "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
                "X-Frame-Options": "DENY", "Referrer-Policy": "no-referrer",
                "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
                "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
            })
        return response

    @app.get("/admin", include_in_schema=False)
    def redirect():
        return RedirectResponse(core.API + "/admin/", status_code=308)

    @app.get("/admin/", include_in_schema=False)
    def page():
        return FileResponse(assets / "index.html")

    @app.get("/admin/assets/{name}", include_in_schema=False)
    def asset(name: str):
        if name not in {"admin.css", "admin.js"}:
            core.fail(404, "Không tìm thấy.")
        return FileResponse(assets / name)

    @app.post("/admin/api/login")
    def login(data: Login, request: Request):
        same_origin(request)
        core.limited(request, "admin-login", 8, 300)
        name = data.username.strip().lower()
        with lock:
            row = db.execute("SELECT * FROM administrators WHERE username=?", (name,)).fetchone()
        try:
            core.ph.verify(row["hash"] if row else core.DUMMY, data.password)
        except VerificationError:
            core.fail(401, "Tên đăng nhập hoặc mật khẩu chưa đúng.")
        if not row:
            core.fail(401, "Tên đăng nhập hoặc mật khẩu chưa đúng.")
        token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        with lock:
            current = db.execute("SELECT hash FROM administrators WHERE username=?", (name,)).fetchone()
            if not current or current["hash"] != row["hash"]:
                core.fail(401, "Mật khẩu vừa thay đổi. Hãy đăng nhập lại.")
            db.execute("DELETE FROM admin_sessions WHERE expires<=? OR hash=?",
                       (time.time(), core.digest(request.cookies.get(cookie, ""))))
            db.execute("INSERT INTO admin_sessions VALUES(?,?,?,?)", (core.digest(token), name, csrf, time.time() + 28800))
            audit(name, "admin.login", name)
            db.commit()
        response = JSONResponse({"ok": True, "username": name, "csrf": csrf})
        response.set_cookie(cookie, token, max_age=28800, secure=True, httponly=True, samesite="strict", path=cookie_path)
        return response

    @app.get("/admin/api/session")
    def session(request: Request):
        who = identity(request)
        return {"ok": True, "username": who["username"], "csrf": who["csrf"]}

    @app.post("/admin/api/logout")
    def logout(request: Request):
        who = identity(request, True)
        with lock:
            db.execute("DELETE FROM admin_sessions WHERE hash=?", (who["hash"],))
            db.commit()
        response = JSONResponse({"ok": True})
        response.delete_cookie(cookie, path=cookie_path, secure=True, httponly=True, samesite="strict")
        return response

    @app.post("/admin/api/password")
    def password(data: PasswordEdit, request: Request):
        who = identity(request, True)
        core.limited(request, "admin-password", 5, 300)
        with lock:
            row = db.execute("SELECT hash FROM administrators WHERE username=?", (who["username"],)).fetchone()
        try:
            core.ph.verify(row["hash"], data.current_password)
        except VerificationError:
            core.fail(400, "Mật khẩu hiện tại chưa đúng.")
        hashed = core.ph.hash(data.password)
        with lock:
            # A concurrent password change must not be overwritten using the old password.
            changed = db.execute("UPDATE administrators SET hash=? WHERE username=? AND hash=?",
                                 (hashed, who["username"], row["hash"])).rowcount
            if not changed:
                core.fail(409, "Mật khẩu vừa thay đổi. Hãy đăng nhập lại.")
            db.execute("DELETE FROM admin_sessions WHERE username=?", (who["username"],))
            audit(who["username"], "admin.password", who["username"])
            db.commit()
        return {"ok": True}

    def device_view(row):
        return {"id": row["id"], "name": row["name"], "online": core.online(row), "last_seen": row["seen"]}

    @app.get("/admin/api/users")
    def users(request: Request, q: str = "", page: int = 1):
        identity(request)
        if page < 1 or page > 100000 or len(q) > 80:
            core.fail(400, "Bộ lọc chưa hợp lệ.")
        # instr treats %, _, and quotes as literal search characters.
        query = q.strip().lower()
        where = "WHERE instr(lower(u.username),?)>0 OR EXISTS (SELECT 1 FROM devices d WHERE d.owner=u.id AND (instr(lower(d.name),?)>0 OR instr(d.id,?)>0))"
        args = (query, query, query)
        live = int(core.bridge.connected.is_set())
        cutoff = time.time() - 45
        with lock:
            total = db.execute("SELECT count(*) FROM users u " + where, args).fetchone()[0]
            rows = db.execute("SELECT u.id,u.username,(SELECT count(*) FROM devices d WHERE d.owner=u.id) device_count,"
                              "(SELECT count(*) FROM devices d WHERE d.owner=u.id AND d.online=1 AND d.seen>? AND ?=1) online_count "
                              "FROM users u " + where + " ORDER BY u.username,u.id LIMIT 30 OFFSET ?",
                              (cutoff, live, *args, (page - 1) * 30)).fetchall()
            counts = db.execute("SELECT count(*) total,coalesce(sum(online=1 AND seen>? AND ?=1),0) online FROM devices", (cutoff, live)).fetchone()
            accounts = db.execute("SELECT count(*) FROM users").fetchone()[0]
        return {"ok": True, "users": [dict(row) for row in rows], "total": total, "page": page, "page_size": 30,
                "summary": {"accounts": accounts, "devices": counts["total"], "online": counts["online"],
                            "offline": counts["total"] - counts["online"], "mqtt": bool(live)}}

    @app.get("/admin/api/users/{uid}")
    def user_detail(uid: str, request: Request):
        identity(request)
        with lock:
            user = db.execute("SELECT id,username FROM users WHERE id=?", (uid,)).fetchone()
            if not user:
                core.fail(404, "Tài khoản không còn tồn tại.")
            devices = db.execute("SELECT id,name,online,seen FROM devices WHERE owner=? ORDER BY name,id", (uid,)).fetchall()
        return {"ok": True, "user": dict(user), "devices": [device_view(row) for row in devices],
                "mqtt": core.bridge.connected.is_set()}

    @app.post("/admin/api/users/{uid}")
    def user_edit(uid: str, data: UserEdit, request: Request):
        who = identity(request, True)
        name = data.username.strip().lower()
        if not re.fullmatch(r"[a-z0-9._-]{3,32}", name):
            core.fail(400, "Tên đăng nhập gồm 3–32 chữ không dấu, số, dấu chấm, gạch ngang hoặc gạch dưới.")
        hashed = core.ph.hash(data.password) if data.password is not None else None
        with lock:
            user = db.execute("SELECT username FROM users WHERE id=?", (uid,)).fetchone()
            if not user:
                core.fail(404, "Tài khoản không còn tồn tại.")
            changed = name != user["username"] or hashed is not None
            try:
                db.execute("UPDATE users SET username=?,hash=coalesce(?,hash) WHERE id=?", (name, hashed, uid))
                if changed:
                    db.execute("DELETE FROM sessions WHERE owner=?", (uid,))
                    audit(who["username"], "user.credentials" if hashed else "user.rename", uid)
                db.commit()
            except sqlite3.IntegrityError:
                db.rollback()
                core.fail(409, "Tên đăng nhập đã tồn tại.")
        return {"ok": True, "sessions_revoked": changed}

    @app.post("/admin/api/users/{uid}/devices/{device}")
    def device_edit(uid: str, device: str, data: DeviceEdit, request: Request):
        who = identity(request, True)
        name = data.name.strip()
        if not name or any(ord(c) < 32 for c in name):
            core.fail(400, "Nhập tên thiết bị hợp lệ.")
        with lock:
            changed = db.execute("UPDATE devices SET name=? WHERE id=? AND owner=?", (name, device, uid)).rowcount
            if not changed:
                core.fail(404, "Thiết bị không còn thuộc tài khoản này.")
            audit(who["username"], "device.rename", device)
            db.commit()
        return {"ok": True}
