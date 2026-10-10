"""Isolated admin/mobile regression tests. No broker connection or production data."""
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

# Test artifacts must stay under an explicitly selected working directory.
if not os.getenv("MS51_TEST_WORK"):
    raise RuntimeError("Set MS51_TEST_WORK to an isolated test directory (D: on Windows).")
Path(os.environ["MS51_TEST_WORK"]).mkdir(parents=True, exist_ok=True)
temporary = tempfile.TemporaryDirectory(dir=os.environ["MS51_TEST_WORK"])
os.environ["MS51_DATA"] = temporary.name
import app as core
from fastapi.testclient import TestClient

ORIGIN = "https://" + core.HOST
ADMIN_PASSWORD = "Admin-test-only-123!"
USER_PASSWORD = "User-test-only-123!"


class AdminTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.admin_hash = core.ph.hash(ADMIN_PASSWORD)
        cls.user_hash = core.ph.hash(USER_PASSWORD)

    def setUp(self):
        core.limits.clear()
        core.bridge.connected.set()
        with core.lock:
            for table in ("admin_sessions", "admin_audit", "administrators", "requests", "claims", "devices", "sessions", "users"):
                core.db.execute("DELETE FROM " + table)
            core.db.execute("INSERT INTO administrators VALUES(?,?)", ("admin", self.admin_hash))
            core.db.executemany("INSERT INTO users VALUES(?,?,?)", [("u1", "alice", self.user_hash), ("u2", "bob", self.user_hash)])
            core.db.executemany("INSERT INTO devices(id,owner,name,mqtt,online,seen,state) VALUES(?,?,?,?,?,?,?)", [
                ("ms51-111111111111", "u1", "Kho lạnh", "private-mqtt-1", 1, time.time(), '{"secret":"never disclose"}'),
                ("ms51-222222222222", "u1", "Tủ đông", "private-mqtt-2", 1, time.time() - 80, '{}'),
                ("ms51-333333333333", "u2", "Tủ mát", "private-mqtt-3", 0, 0, '{}')])
            core.db.commit()
        # No context manager: app lifespan would start the real MQTT connector.
        self.client = TestClient(core.app, base_url=ORIGIN + core.API + "/", root_path=core.API)
        self.headers = {"origin": ORIGIN}

    def tearDown(self):
        self.client.close()

    def auth(self):
        r = self.client.post("admin/api/login", json={"username": "admin", "password": ADMIN_PASSWORD}, headers=self.headers)
        self.assertEqual(r.status_code, 200, r.text)
        self.headers["x-csrf-token"] = r.json()["csrf"]
        return r

    def mobile(self, name="alice", password=USER_PASSWORD):
        r = self.client.post("auth/login", json={"username": name, "password": password})
        self.assertEqual(r.status_code, 200, r.text)
        return {"Authorization": "Bearer " + r.json()["token"]}

    def test_anonymous_and_mobile_users_cannot_access_admin(self):
        mobile = self.mobile()
        for path in ("admin/api/session", "admin/api/users", "admin/api/users/u1"):
            self.assertEqual(self.client.get(path, headers=mobile).status_code, 401)
        r = self.client.post("admin/api/users/u1", json={"username": "hacked"}, headers=mobile)
        self.assertEqual(r.status_code, 401)
        self.assertEqual(self.client.post("admin/api/login", json={"username": "alice", "password": USER_PASSWORD}, headers=self.headers).status_code, 401)

    def test_cookie_session_and_mobile_separation(self):
        r = self.auth()
        for attribute in ("HttpOnly", "Secure", "SameSite=strict", "Path=" + core.API + "/admin/"):
            self.assertIn(attribute, r.headers["set-cookie"])
        self.assertEqual(self.client.get("admin/api/session").json()["username"], "admin")
        self.assertEqual(self.client.get("devices").status_code, 401)

    def test_csrf_origin_and_validation(self):
        self.auth()
        data = {"username": "alice2"}
        for headers in ({}, {"origin": ORIGIN}, {"origin": "https://evil.example", "x-csrf-token": self.headers["x-csrf-token"]}):
            self.assertEqual(self.client.post("admin/api/users/u1", json=data, headers=headers).status_code, 403)
        r = self.client.post("admin/api/users/u1", json={"username": "a", "password": "secret"}, headers=self.headers)
        self.assertEqual(r.status_code, 422)
        self.assertNotIn("secret", r.text)

    def test_read_only_fields_and_presence(self):
        self.auth()
        r = self.client.get("admin/api/users").json()
        self.assertEqual(r["summary"], {"accounts": 2, "devices": 3, "online": 1, "offline": 2, "mqtt": True})
        detail = self.client.get("admin/api/users/u1").json()
        self.assertEqual(len(detail["devices"]), 2)
        for device in detail["devices"]:
            self.assertEqual(set(device), {"id", "name", "online", "last_seen"})
        self.assertEqual(set(detail["user"]), {"id", "username"})
        core.bridge.connected.clear()
        self.assertEqual(self.client.get("admin/api/users").json()["summary"]["online"], 0)
        self.assertTrue(all(not d["online"] for d in self.client.get("admin/api/users/u1").json()["devices"]))

    def test_search_pagination_and_literal_sql(self):
        self.auth()
        self.assertEqual(self.client.get("admin/api/users", params={"q": "Kho lạnh"}).json()["total"], 1)
        for q in ("%", "_", "' OR 1=1 --"):
            self.assertEqual(self.client.get("admin/api/users", params={"q": q}).json()["total"], 0)
        self.assertEqual(self.client.get("admin/api/users?page=0").status_code, 400)
        with core.lock:
            core.db.executemany("INSERT INTO users VALUES(?,?,?)", [("p" + str(i), "page" + str(i).zfill(2), self.user_hash) for i in range(35)])
            core.db.commit()
        self.assertEqual(len(self.client.get("admin/api/users?page=1").json()["users"]), 30)
        self.assertEqual(len(self.client.get("admin/api/users?page=2").json()["users"]), 7)

    def test_rename_and_reset_revoke_sessions_keep_devices(self):
        old1, old2 = self.mobile(), self.mobile()
        self.auth()
        r = self.client.post("admin/api/users/u1", json={"username": "alice.new", "password": "New-user-pass-123!"}, headers=self.headers)
        self.assertEqual(r.status_code, 200)
        for token in (old1, old2):
            self.assertEqual(self.client.get("devices", headers=token).status_code, 401)
        self.assertEqual(self.client.post("auth/login", json={"username": "alice.new", "password": USER_PASSWORD}).status_code, 401)
        new = self.mobile("alice.new", "New-user-pass-123!")
        self.assertEqual(len(self.client.get("devices", headers=new).json()["devices"]), 2)
        self.assertEqual(self.client.get("admin/api/session").status_code, 200)
        audit = str([tuple(r) for r in core.db.execute("SELECT * FROM admin_audit")])
        self.assertNotIn("New-user-pass", audit)

    def test_duplicate_rename_rolls_back_without_session_revocation(self):
        mobile = self.mobile()
        self.auth()
        r = self.client.post("admin/api/users/u1", json={"username": "bob", "password": "Other-password-123"}, headers=self.headers)
        self.assertEqual(r.status_code, 409)
        self.assertEqual(self.client.get("devices", headers=mobile).status_code, 200)
        self.mobile()

    def test_username_only_keeps_password_and_noop_keeps_session(self):
        mobile = self.mobile()
        self.auth()
        r = self.client.post("admin/api/users/u1", json={"username": "alice"}, headers=self.headers)
        self.assertFalse(r.json()["sessions_revoked"])
        self.assertEqual(self.client.get("devices", headers=mobile).status_code, 200)
        self.client.post("admin/api/users/u1", json={"username": "alice2"}, headers=self.headers).raise_for_status()
        self.assertEqual(self.client.get("devices", headers=mobile).status_code, 401)
        self.mobile("alice2")

    def test_device_rename_scoped_to_owner(self):
        self.auth()
        path = "admin/api/users/u2/devices/ms51-111111111111"
        self.assertEqual(self.client.post(path, json={"name": "Changed"}, headers=self.headers).status_code, 404)
        path = path.replace("u2", "u1")
        self.assertEqual(self.client.post(path, json={"name": " "}, headers=self.headers).status_code, 400)
        self.assertEqual(self.client.post(path, json={"name": "<img src=x onerror=alert(1)>"}, headers=self.headers).status_code, 200)
        device = core.db.execute("SELECT name,owner,mqtt FROM devices WHERE id='ms51-111111111111'").fetchone()
        self.assertEqual(device["owner"], "u1")
        self.assertEqual(device["mqtt"], "private-mqtt-1")

    def test_logout_expiry_and_login_limit(self):
        self.auth()
        self.assertEqual(self.client.post("admin/api/logout", json={}, headers=self.headers).status_code, 200)
        self.assertEqual(self.client.get("admin/api/users").status_code, 401)
        self.auth()
        core.db.execute("UPDATE admin_sessions SET expires=0"); core.db.commit()
        self.assertEqual(self.client.get("admin/api/users").status_code, 401)
        core.limits.clear()
        for _ in range(8):
            self.assertEqual(self.client.post("admin/api/login", json={"username": "admin", "password": "Wrong-password"}, headers=self.headers).status_code, 401)
        self.assertEqual(self.client.post("admin/api/login", json={"username": "admin", "password": ADMIN_PASSWORD}, headers=self.headers).status_code, 429)

    def test_admin_password_revokes_all_admin_sessions(self):
        self.auth()
        data = {"current_password": "wrong-password", "password": "New-admin-pass-123!"}
        self.assertEqual(self.client.post("admin/api/password", json=data, headers=self.headers).status_code, 400)
        data["current_password"] = ADMIN_PASSWORD
        self.assertEqual(self.client.post("admin/api/password", json=data, headers=self.headers).status_code, 200)
        self.assertEqual(self.client.get("admin/api/session").status_code, 401)
        self.assertEqual(self.client.post("admin/api/login", json={"username": "admin", "password": data["password"]}, headers=self.headers).status_code, 200)

    def test_mobile_login_race_with_password_reset(self):
        original = core.ph.verify
        def reset_during_verify(hashed, password):
            value = original(hashed, password)
            with core.lock:
                core.db.execute("UPDATE users SET hash=? WHERE id='u1'", (self.admin_hash,))
                core.db.commit()
            return value
        with patch.object(type(core.ph), "verify", lambda self, h, p: reset_during_verify(h, p)):
            response = self.client.post("auth/login", json={"username": "alice", "password": USER_PASSWORD})
        self.assertEqual(response.status_code, 401)
        self.assertEqual(core.db.execute("SELECT count(*) FROM sessions").fetchone()[0], 0)

    def test_register_login_health_regression(self):
        response = self.client.post("auth/register", json={"username": "new-user", "password": USER_PASSWORD})
        self.assertEqual(response.status_code, 200)
        self.assertIn("token", response.json())
        self.mobile("new-user")
        self.assertEqual(self.client.get("health").json(), {"ok": True, "mqtt": True, "protocol": 1})

    def test_static_headers_no_traversal(self):
        for path in ("admin/", "admin/assets/admin.css", "admin/assets/admin.js"):
            r = self.client.get(path)
            self.assertEqual(r.status_code, 200)
            self.assertEqual(r.headers["cache-control"], "no-store")
            self.assertIn("frame-ancestors 'none'", r.headers["content-security-policy"])
        self.assertEqual(self.client.get("admin/assets/app.py").status_code, 404)


if __name__ == "__main__":
    try:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(AdminTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        core.db.close()
        temporary.cleanup()
    sys.exit(not result.wasSuccessful())
