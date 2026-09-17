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


def archive(name="Synthetic flight", photo=False, extra=None, planned=False, photo_id="photo1", calibration=None):
    memory = io.BytesIO()
    journal = {"schemaVersion": 9, "id": "synthetic-flight", "name": name,
               "trip": {"samples": [] if planned else [[0, 0, 0]]}, "offlineAssets": {}, "offlineRequest": {},
               "plan": {"stops": [{"name": "City A"}, {"name": "City B"}], "preparation": {"departureMillis": 1800000000000}},
               "photos": [{"id": photo_id, "storageName": "image-0.jpg", "calibration": calibration or {"synthetic": True}}] if photo else []}
    with zipfile.ZipFile(memory, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr("journey.json", json.dumps(journal))
        if not planned:
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

    def test_listing_does_not_wait_for_an_upload_lock(self):
        head = self.put(HTTP_X_EDIT_TOKEN=self.lease(), HTTP_IF_NONE_MATCH="*")["json"]
        with ThreadPoolExecutor(1) as pool:
            with self.app.locked("one"):
                # A photo merge can hold this lock for seconds. Metadata must stay readable.
                pending = pool.submit(self.call, "GET", "/v1/journeys")
                response = pending.result(timeout=2)
                self.assertEqual(response["status"], 200)
                self.assertEqual(response["json"]["journeys"], [head])

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
        current = self.call("GET", "/v1/journeys/trip1/archive")["body"]
        self.assertEqual(hashlib.sha256(current).hexdigest(), second["json"]["revision"])
        with zipfile.ZipFile(io.BytesIO(current)) as zip:
            self.assertEqual(json.loads(zip.read("journey.json"))["name"], "Changed")
        self.assertEqual(self.call("GET", "/v1/journeys/trip1/archive", HTTP_IF_MATCH='"' + first["revision"] + '"')["status"], 409)

    def test_two_recording_phones_keep_the_loser_local_copy_on_a_revision_conflict(self):
        lease = self.lease()
        base = self.put(HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["json"]
        phone_a_recording = archive("Recorded by phone A")
        phone_b_recording = archive("Recorded by phone B")

        published_by_a = self.put(
            phone_a_recording,
            HTTP_X_EDIT_TOKEN=lease,
            HTTP_IF_MATCH='"' + base["revision"] + '"',
        )
        self.assertEqual(published_by_a["status"], 200)
        rejected_on_b = self.put(
            phone_b_recording,
            HTTP_X_EDIT_TOKEN=lease,
            HTTP_IF_MATCH='"' + base["revision"] + '"',
        )

        self.assertEqual(rejected_on_b["status"], 409)
        with zipfile.ZipFile(io.BytesIO(phone_b_recording)) as zip:
            self.assertEqual(json.loads(zip.read("journey.json"))["name"], "Recorded by phone B")
        current = self.call("GET", "/v1/journeys/trip1/archive")["body"]
        with zipfile.ZipFile(io.BytesIO(current)) as zip:
            self.assertEqual(json.loads(zip.read("journey.json"))["name"], "Recorded by phone A")

    def test_concurrent_create_allows_only_one_winner(self):
        lease = self.lease()
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda n: self.put(archive(str(n)), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["status"], range(2)))
        self.assertEqual(sorted(results), [200, 409])

    def test_planned_flight_round_trip_without_gpx(self):
        data = archive(planned=True)
        result = self.put(data, HTTP_X_EDIT_TOKEN=self.lease(), HTTP_IF_NONE_MATCH="*")
        self.assertEqual(result["status"], 200)
        self.assertEqual(result["json"]["kind"], "planned")
        self.assertEqual(result["json"]["sampleCount"], 0)
        with zipfile.ZipFile(io.BytesIO(self.call("GET", "/v1/journeys/trip1/archive")["body"])) as zip:
            self.assertEqual(zip.namelist(), ["journey.json"])
            self.assertEqual(json.loads(zip.read("journey.json"))["plan"]["preparation"]["departureMillis"], 1800000000000)
        self.assertEqual(self.call("GET", "/v1/journeys")["json"]["protocolVersion"], 2)

    def test_recorded_flight_requires_gpx(self):
        with zipfile.ZipFile(io.BytesIO(archive())) as zip:
            manifest = zip.read("journey.json")
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, "w") as zip:
            zip.writestr("journey.json", manifest)
        self.assertEqual(self.put(payload.getvalue(), HTTP_X_EDIT_TOKEN=self.lease(), HTTP_IF_NONE_MATCH="*")["status"], 422)

    def test_omitted_photos_are_preserved_and_new_photos_append_despite_same_filename(self):
        lease = self.lease()
        first = self.put(archive(photo=True), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["json"]
        second = self.put(archive(photo=True, photo_id="photo2"), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"'+first["revision"]+'"')
        self.assertEqual(second["status"], 200)
        self.assertEqual(set(second["json"]["photoIds"]), {"photo1", "photo2"})
        third = self.put(archive("No photos selected"), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"'+second["json"]["revision"]+'"')
        self.assertEqual(set(third["json"]["photoIds"]), {"photo1", "photo2"})
        with zipfile.ZipFile(io.BytesIO(self.call("GET", "/v1/journeys/trip1/archive")["body"])) as zip:
            photos = json.loads(zip.read("journey.json"))["photos"]
            self.assertEqual(len({p["storageName"] for p in photos}), 2)
            for p in photos:
                self.assertEqual(zip.read("photos/"+p["storageName"]), b"synthetic-image-not-a-user-photo")

    def test_submitted_photo_updates_calibration_without_duplicates(self):
        lease = self.lease()
        first = self.put(archive(photo=True), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["json"]
        second = self.put(archive(photo=True, calibration={"yaw": 42}), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"'+first["revision"]+'"')
        self.assertEqual(second["status"], 200)
        with zipfile.ZipFile(io.BytesIO(self.call("GET", "/v1/journeys/trip1/archive")["body"])) as zip:
            photos = json.loads(zip.read("journey.json"))["photos"]
            self.assertEqual(len(photos), 1)
            self.assertEqual(photos[0]["calibration"], {"yaw": 42})

    def test_plan_can_become_recorded_but_never_erase_recording(self):
        lease = self.lease()
        first = self.put(archive(planned=True), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_NONE_MATCH="*")["json"]
        second = self.put(archive(), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"'+first["revision"]+'"')["json"]
        self.assertEqual(second["kind"], "past")
        self.assertEqual(self.put(archive(planned=True), HTTP_X_EDIT_TOKEN=lease, HTTP_IF_MATCH='"'+second["revision"]+'"')["status"], 409)

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
