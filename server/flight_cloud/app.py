"""Small WSGI flight library: token accounts, expiring edit sessions, revision-checked ZIPs.

Only journey.json, track.gpx and explicitly selected photos are accepted. Run behind HTTPS.
No application data or credentials are bundled with the source tree.
"""
import argparse
import base64
import contextlib
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import stat
import tempfile
import time
import zipfile
from wsgiref.util import FileWrapper

ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
HASH = re.compile(r"[a-f0-9]{64}\Z")
MAX_UPLOAD = 512 * 1024 * 1024
MAX_JSON = 32 * 1024 * 1024
EDIT_SECONDS = 15 * 60


class ApiError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def encode_json(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()


def reject_constant(value):
    raise ValueError("Non-finite JSON")


def load_json(value):
    return json.loads(value, parse_constant=reject_constant)


def atomic_json(path, value):
    fd, temporary = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(encode_json(value))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def inspect_archive(file):
    """Validate a strict allowlist without extracting any archive paths."""
    try:
        with zipfile.ZipFile(file) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(entries) > 1002 or len(names) != len(set(names)):
                raise ValueError("Duplicate or excessive entries")
            if "journey.json" not in names or "track.gpx" not in names:
                raise ValueError("Missing journal or GPX")
            total = 0
            for entry in entries:
                name = entry.filename
                photo = name.startswith("photos/") and len(name) < 240 and len(name.split("/")) == 2
                if (name not in ("journey.json", "track.gpx") and not photo) or "\\" in name or "\x00" in name:
                    raise ValueError("Unsupported entry")
                if photo and (name[7:] in ("", ".", "..") or name[7:].startswith(".")):
                    raise ValueError("Invalid photo name")
                if entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1:
                    raise ValueError("Unsupported entry type")
                total += entry.file_size
                if total > MAX_UPLOAD or (name == "journey.json" and entry.file_size > MAX_JSON):
                    raise ApiError(413, "archive_too_large")
                # Consume entries to validate actual decompressed size and CRC, not just ZIP headers.
                count = 0
                with archive.open(entry) as stream:
                    while chunk := stream.read(65536):
                        count += len(chunk)
                        if count > entry.file_size:
                            raise ValueError("Incorrect entry size")
            journal = load_json(archive.read("journey.json"))
            if (not isinstance(journal, dict) or type(journal.get("schemaVersion")) is not int
                    or journal["schemaVersion"] not in range(1, 10)):
                raise ValueError("Unsupported journal schema")
            photos = journal.get("photos", [])
            samples = journal.get("trip", {}).get("samples", [])
            if not isinstance(photos, list) or not isinstance(samples, list) or len(photos) > 1000:
                raise ValueError("Invalid journal")
            expected = {"photos/" + p["storageName"] for p in photos}
            if len(expected) != len(photos) or expected != {n for n in names if n.startswith("photos/")}:
                raise ValueError("Photo manifest does not match files")
            if (any(not isinstance(p.get("id"), str) or not ID.fullmatch(p["id"]) for p in photos)
                    or len({p["id"] for p in photos}) != len(photos)):
                raise ValueError("Duplicate photo IDs")
            for key in ("offlineAssets", "offlineRequest"):
                if any(journal.get(key, {}).values()):
                    raise ValueError("Offline tile manifests must be empty")
            name = journal.get("name", "")
            if not isinstance(name, str) or not name.strip() or len(name) > 300:
                raise ValueError("Invalid journal name")
            return {"name": name, "photoCount": len(photos), "sampleCount": len(samples),
                    "photoIds": [p["id"] for p in photos]}
    except ApiError:
        raise
    except (ValueError, KeyError, TypeError, AttributeError, zipfile.BadZipFile, EOFError, RuntimeError):
        raise ApiError(422, "invalid_archive") from None


class FlightCloud:
    def __init__(self, root, accounts, signing_key, *, clock=time.time, require_https=True):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.accounts = accounts  # SHA-256(access token) -> opaque account ID
        if not all(HASH.fullmatch(key) and ID.fullmatch(value) for key, value in accounts.items()):
            raise ValueError("Invalid account configuration")
        if len(signing_key) < 32:
            raise ValueError("Signing key is too short")
        self.key, self.clock, self.require_https = signing_key, clock, require_https

    @contextlib.contextmanager
    def locked(self, account):
        directory = self.root / account
        directory.mkdir(exist_ok=True, mode=0o700)
        with (directory / ".lock").open("a+b") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield directory

    def authenticate(self, env):
        if self.require_https and env.get("wsgi.url_scheme") != "https":
            raise ApiError(400, "https_required")
        auth = env.get("HTTP_AUTHORIZATION", "")
        if not auth.startswith("Bearer ") or not 24 <= len(auth[7:]) <= 256:
            raise ApiError(401, "invalid_token")
        account = self.accounts.get(hashlib.sha256(auth[7:].encode()).hexdigest())
        if not account:
            raise ApiError(401, "invalid_token")
        return account

    def lease(self, account):
        expires = int(self.clock()) + EDIT_SECONDS
        payload = base64.urlsafe_b64encode(encode_json([account, expires, secrets.token_hex(16)])).decode()
        signature = hmac.new(self.key, payload.encode(), hashlib.sha256).hexdigest()
        return {"editToken": payload + "." + signature, "expiresAt": expires * 1000,
                "expiresInSeconds": EDIT_SECONDS}

    def require_edit(self, env, account):
        try:
            token = env.get("HTTP_X_EDIT_TOKEN", "")
            if len(token) > 512:
                raise ValueError()
            payload, signature = token.split(".")
            expected = hmac.new(self.key, payload.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature, expected):
                raise ValueError()
            owner, expires, nonce = load_json(base64.urlsafe_b64decode(payload))
            if owner != account or not self.clock() < expires <= self.clock() + EDIT_SECONDS + 2:
                raise ValueError()
        except (ValueError, TypeError, UnicodeError):
            raise ApiError(403, "edit_session_expired") from None

    def __call__(self, env, start_response):
        headers = [("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff")]
        try:
            account = self.authenticate(env)
            method, path = env["REQUEST_METHOD"], env.get("PATH_INFO", "")
            if method == "GET" and path == "/v1/journeys":
                with self.locked(account) as directory:
                    rows = [load_json(p.read_bytes()) for p in directory.glob("*/head.json")]
                body = {"journeys": sorted(rows, key=lambda r: r["updatedAt"], reverse=True),
                        "maxUploadBytes": MAX_UPLOAD}
            elif method == "POST" and path == "/v1/edit-session":
                body = self.lease(account)
            else:
                match = re.fullmatch(r"/v1/journeys/([A-Za-z0-9_-]{1,80})/archive", path)
                if not match:
                    raise ApiError(404, "not_found")
                identifier = match[1]
                if method == "GET":
                    with self.locked(account) as directory:
                        folder = directory / identifier
                        if not (folder / "head.json").is_file():
                            raise ApiError(404, "not_found")
                        head = load_json((folder / "head.json").read_bytes())
                        if env.get("HTTP_IF_MATCH") not in (None, '"' + head["revision"] + '"'):
                            raise ApiError(409, "revision_conflict")
                        stream = (folder / (head["revision"] + ".zip")).open("rb")
                    start_response("200 OK", headers + [("Content-Type", "application/zip"),
                        ("Content-Length", str(head["bytes"])), ("ETag", '"' + head["revision"] + '"'),
                        ("Content-Disposition", 'attachment; filename="' + identifier + '.flightlog"')])
                    return FileWrapper(stream, 65536)
                if method != "PUT":
                    raise ApiError(405, "method_not_allowed")
                self.require_edit(env, account)
                try:
                    length = int(env.get("CONTENT_LENGTH", ""))
                except ValueError:
                    raise ApiError(411, "length_required") from None
                if not 0 < length <= MAX_UPLOAD:
                    raise ApiError(413, "archive_too_large")
                # One temporary file; network reception and ZIP validation do not hold the account lock.
                with tempfile.TemporaryFile(dir=self.root) as incoming:
                    digest, remaining = hashlib.sha256(), length
                    while remaining:
                        chunk = env["wsgi.input"].read(min(65536, remaining))
                        if not chunk:
                            raise ApiError(400, "incomplete_upload")
                        incoming.write(chunk)
                        digest.update(chunk)
                        remaining -= len(chunk)
                    incoming.seek(0)
                    summary = inspect_archive(incoming)
                    revision = digest.hexdigest()
                    self.require_edit(env, account)  # An expired upload cannot commit.
                    with self.locked(account) as directory:
                        folder = directory / identifier
                        head_path = folder / "head.json"
                        previous = load_json(head_path.read_bytes()) if head_path.exists() else None
                        if previous and env.get("HTTP_IF_MATCH") != '"' + previous["revision"] + '"':
                            raise ApiError(409, "revision_conflict")
                        if not previous and env.get("HTTP_IF_NONE_MATCH") != "*":
                            raise ApiError(409, "revision_conflict")
                        folder.mkdir(exist_ok=True, mode=0o700)
                        archive_path = folder / (revision + ".zip")
                        if not archive_path.exists():
                            incoming.seek(0)
                            fd, temporary = tempfile.mkstemp(prefix=".upload-", dir=folder)
                            try:
                                with os.fdopen(fd, "wb") as output:
                                    while chunk := incoming.read(65536):
                                        output.write(chunk)
                                    output.flush()
                                    os.fsync(output.fileno())
                                os.replace(temporary, archive_path)
                            finally:
                                if os.path.exists(temporary):
                                    os.unlink(temporary)
                        body = dict(summary, id=identifier, revision=revision, bytes=length,
                                    updatedAt=int(self.clock() * 1000))
                        atomic_json(head_path, body)
                        # Retain the preceding revision for manual recovery; never delete a head archive.
                        keep = {revision, previous["revision"] if previous else revision}
                        for old in folder.glob("*.zip"):
                            if HASH.fullmatch(old.stem) and old.stem not in keep and not old.is_symlink():
                                old.unlink()
            encoded = encode_json(body)
            start_response("200 OK", headers + [("Content-Type", "application/json"), ("Content-Length", str(len(encoded)))])
            return [encoded]
        except ApiError as error:
            code, payload = error.status, {"error": error.code}
        except Exception:
            # No paths, account identifiers, tokens, or journal contents in HTTP errors/logs.
            code, payload = 500, {"error": "server_error"}
        encoded = encode_json(payload)
        from http import HTTPStatus
        start_response(str(code) + " " + HTTPStatus(code).phrase,
                       headers + [("Content-Type", "application/json"), ("Content-Length", str(len(encoded)))])
        return [encoded]


def create_app():
    config_path = Path(os.environ["FLIGHT_CLOUD_CONFIG"])
    config = load_json(config_path.read_bytes())
    return FlightCloud(config["dataDirectory"], config["accounts"], bytes.fromhex(config["signingKey"]))


def main():
    parser = argparse.ArgumentParser(description="Initialize a private flight library (never overwrites configuration).")
    parser.add_argument("init", choices=["init"])
    parser.add_argument("--directory", required=True)
    args = parser.parse_args()
    directory = Path(args.directory).resolve()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if any(directory.iterdir()):
        parser.error("Choose an empty directory")
    token = secrets.token_urlsafe(32)
    config = {"accounts": {hashlib.sha256(token.encode()).hexdigest(): "account1"},
              "signingKey": secrets.token_hex(32), "dataDirectory": str(directory / "data")}
    for filename, content in (("config.json", encode_json(config)), ("access-token.txt", token.encode())):
        fd = os.open(directory / filename, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(content)
    print("Created private config.json and access-token.txt in the chosen directory. Keep both private.")


if __name__ == "__main__":
    main()
