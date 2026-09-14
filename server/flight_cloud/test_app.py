"""Exercise the real WSGI application with synthetic tokens/journals, never a running account."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from concurrent.futures import ThreadPoolExecutor
from app import FlightCloud, MAX_UPLOAD


TOKEN = "synthetic-test-account-one-token-00001"
OTHER = "synthetic-test-account-two-token-00002"


def archive(name="Synthetic flight", photo=False, extra=None):
    memory = io.BytesIO()
    journal = {"schemaVersion": 9, "id": "synthetic-flight", "name": name,
               "trip": {"samples": [[0, 0, 0]]}, "offlineAssets": {}, "offlineRequest": {},
               "photos": [{"id": "photo1", "storageName": "image-0.jpg", "calibration": {"synthetic": True}}] if photo else []}
    with zipfile.ZipFile(memory, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr("journey.json", json.dumps(journal))
        output.writestr("track.gpx", "<gpx/>")
        if photo:
            output.writestr("photos/image-0.jpg", b"synthetic-image-not-a-user-photo")
        if extra:
            output.writestr(*extra)
    return memory.getvalue()


class CloudTest(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[2] / ".work"
        scratch.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="cloud-test-", dir=scratch)
        self.now = 10000
        self.app = FlightCloud(self.directory.name,
            {hashlib.sha256(TOKEN.encode()).hexdigest(): "one", hashlib.sha256(OTHER.encode()).hexdigest(): "two"},
            b"synthetic-test-signing-key-only-000000", clock=lambda: self.now)
        self.addCleanup(self.directory.cleanup)

    def call(self, method, path, body=b"", token=TOKEN, **headers):
        env = {"REQUEST_METHOD": method, "PATH_INFO": path, "wsgi.url_scheme": "https",
               "wsgi.input": io.BytesIO(body), "CONTENT_LENGTH": str(len(body)),
               "HTTP_AUTHORIZATION": "Bearer " + token}
        env.update(headers)
        response = {}
        def start(status, pairs):
            response["status"] = int(status.split()[0]); response["headers"] = dict(pairs)
        result = self.app(env, start)
        try:
            response["body"] = b"".join(result)
        finally:
            if hasattr(result, "close"):
                result.close()
        if response["headers"].get("Content-Type") == "application/json":
            response["json"] = json.loads(response["body"])
        return response

    def lease(self, token=TOKEN):
        response = self.call("POST", "/v1/edit-session", token=token)
        self.assertEqual(response["status"], 200)
        return response["json"]["editToken"]

    def put(self, body=None, **headers):
        return self.call("PUT", "/v1/journeys/trip1/archive", body if body is not None else archive(), **headers)

    def test_readonly_by_default_and_token_required(self):
        self.assertEqual(self.call("GET", "/v1/journeys")["json"]["journeys"], [])
        self.assertEqual(self.put(HTTP_IF_NONE_MATCH="*")["status"], 403)
        self.assertEqual(self.call("GET", "/v1/journeys", token="invalid")["status"], 401)

    def test_round_trip_with_optional_photos_and_isolated_accounts(self):
        data = archive(photo=True)
        result = self.put(data, HTTP_X_EDIT_TOKEN=self.lease(), HTTP_IF_NONE_MATCH="*")
        self.assertEqual(result["status"], 200)
        entry = result["json"]
        self.assertEqual(entry["photoIds"], ["photo1"])
        self.assertEqual(entry["revision"], hashlib.sha256(data).hexdigest())
        self.assertEqual(self.call("GET", "/v1/journeys/trip1/archive")["body"], data)
        self.assertEqual(self.call("GET", "/v1/journeys", token=OTHER)["json"]["journeys"], [])
        self.assertEqual(self.call("GET", "/v1/journeys/trip1/archive", token=OTHER)["status"], 404)

    def test_edit_token_expires_and_is_account_bound(self):
        lease = self.lease()
        self.assertEqual(self.put(HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*", token=OTHER)["status"], 403)
        self.now += 900
        self.assertEqual(self.put(HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["status"], 403)
        self.assertEqual(self.call("GET", "/v1/journeys")["status"], 200)

    def test_forged_lease_rejected(self):
        lease = self.lease()
        self.assertEqual(self.put(HTTP_X_EDIT_TOKEN=lease[:-1] + ("0" if lease[-1] != "0" else "1"), HTTP_IF_NONE_MATCH="*")["status"], 403)

    def test_revision_conflict_never_overwrites(self):
        lease = self.lease()
        first = self.put(HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["json"]
        second_data = archive("Changed", photo=True)
        second = self.put(second_data, HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"' + first["revision"] + '"')
        self.assertEqual(second["status"], 200)
        self.assertEqual(self.put(archive("Stale"), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"' + first["revision"] + '"')["status"], 409)
        self.assertEqual(self.call("GET", "/v1/journeys/trip1/archive")["body"], second_data)
        self.assertEqual(self.call("GET", "/v1/journeys/trip1/archive", HTTP_IF_MATCH='"' + first["revision"] + '"')["status"], 409)

    def test_concurrent_create_allows_only_one_winner(self):
        lease = self.lease()
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda n: self.put(archive(str(n)), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["status"], range(2)))
        self.assertEqual(sorted(results), [200, 409])

    def test_only_current_and_previous_archive_retained(self):
        lease, revision = self.lease(), None
        for index in range(4):
            headers = {"HTTP_IF_MATCH": '"' + revision + '"'} if revision else {"HTTP_IF_NONE_MATCH": "*"}
            response = self.put(archive(str(index)), HTTP_X_EDIT_TOKEN=lease, **headers)
            self.assertEqual(response["status"], 200)
            revision = response["json"]["revision"]
        self.assertEqual(len(list(Path(self.directory.name).glob("one/trip1/*.zip"))), 2)

    def test_no_textures_relief_or_traversal(self):
        for name in ("offline/terrain/1.png", "satellite/a.jpg", "../escape", "photos/../escape", "photos/../../bad", "photos/\\escape", "photos/.hidden"):
            response = self.put(archive(extra=(name, b"bad")), HTTP_X_EDIT_TOKEN=self.lease(), HTTP_IF_NONE_MATCH="*")
            self.assertEqual(response["status"], 422, name)
        self.assertEqual(self.call("GET", "/v1/journeys")["json"]["journeys"], [])

    def test_unlisted_photo_rejected(self):
        self.assertEqual(self.put(archive(extra=("photos/unlisted.jpg", b"photo")), HTTP_X_EDIT_TOKEN=self.lease(), HTTP_IF_NONE_MATCH="*")["status"], 422)

    def test_partial_upload_and_missing_precondition_do_not_publish(self):
        lease = self.lease()
        self.assertEqual(self.put(HTTP_X_EDIT_TOKEN=lease)["status"], 409)
        self.assertEqual(self.put(b"not-a-zip", HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["status"], 422)
        self.assertEqual(self.put(b"short", HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*", CONTENT_LENGTH="100")["status"], 400)
        self.assertEqual(self.put(HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*", CONTENT_LENGTH=str(MAX_UPLOAD + 1))["status"], 413)

    def test_https_required_and_no_reflected_credentials(self):
        response = self.call("GET", "/v1/journeys", **{"wsgi.url_scheme": "http"})
        self.assertEqual(response["status"], 400)
        self.assertNotIn(TOKEN.encode(), response["body"])
        self.assertEqual(response["headers"]["Cache-Control"], "no-store")


if __name__ == "__main__":
    unittest.main()
