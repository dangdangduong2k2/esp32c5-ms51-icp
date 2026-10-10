"""Provision/reset an administrator from a trusted server shell; password via stdin/getpass."""
import argparse
import getpass
import os
import re
import sqlite3
import sys
from pathlib import Path

from argon2 import PasswordHasher
from admin import SCHEMA


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("username")
    parser.add_argument("--password-stdin", action="store_true")
    args = parser.parse_args()
    username = args.username.strip().lower()
    if not re.fullmatch(r"[a-z0-9._-]{3,32}", username):
        parser.error("Username must be 3-32 ASCII letters, digits, dots, underscores or hyphens.")
    password = sys.stdin.readline().rstrip("\r\n") if args.password_stdin else getpass.getpass("New admin password: ")
    if not 12 <= len(password) <= 128:
        parser.error("Admin password must contain 12-128 characters.")
    path = Path(os.getenv("MS51_DATA", "/var/lib/ms51-connect")) / "connect.sqlite3"
    if not path.is_file():
        parser.error("Existing MS51 database not found; start the service first.")
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA foreign_keys=ON")
        db.executescript(SCHEMA)
        hashed = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2).hash(password)
        db.execute("INSERT INTO administrators VALUES(?,?) ON CONFLICT(username) DO UPDATE SET hash=excluded.hash", (username, hashed))
        db.execute("DELETE FROM admin_sessions WHERE username=?", (username,))
    print("Administrator saved; previous admin sessions revoked.")


if __name__ == "__main__":
    main()
