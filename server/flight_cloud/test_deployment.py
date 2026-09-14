"""Deployment probes tested only against new synthetic storage inside this project."""
import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import deployment


def request(application, path):
    status = []
    body = application({"REQUEST_METHOD": "GET", "PATH_INFO": path, "wsgi.url_scheme": "http"},
                       lambda value, headers: status.append(value))
    return status[0], b"".join(body)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[2] / ".work"
        scratch.mkdir(exist_ok=True)
        directory = tempfile.TemporaryDirectory(prefix="cloud-deploy-test-", dir=scratch)
        self.addCleanup(directory.cleanup)
        self.home = Path(directory.name) / "library"
        self.environment = patch.dict(os.environ, {"FLIGHT_CLOUD_HOME": str(self.home),
                                                   "PROJECT_DEPLOYER_PREFLIGHT": "0"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_preflight_never_initializes_or_opens_storage(self):
        with patch.dict(os.environ, {"PROJECT_DEPLOYER_PREFLIGHT": "1"}), \
                patch.object(deployment, "create_app", side_effect=AssertionError("Storage opened")), \
                patch.object(deployment.subprocess, "run", side_effect=AssertionError("Identity created")):
            service = deployment.create_service()
            self.assertEqual(request(service, "/preflightz")[0], "200 OK")
            self.assertEqual(request(service, "/readyz")[0], "503 Service Unavailable")
            self.assertEqual(request(service, "/v1/journeys")[0], "503 Service Unavailable")
        self.assertFalse(self.home.exists())

    def test_first_boot_and_restart_preserve_identity(self):
        service = deployment.create_service()
        config = self.home / "config.json"
        # This file was generated above exclusively for this synthetic test.
        digest = hashlib.sha256(config.read_bytes()).digest()
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(request(service, "/readyz")[0], "200 OK")
        before = set((self.home / "data").iterdir())
        service = deployment.create_service()
        self.assertEqual(request(service, "/readyz")[0], "200 OK")
        self.assertEqual(hashlib.sha256(config.read_bytes()).digest(), digest)
        self.assertEqual(set((self.home / "data").iterdir()), before)

    def test_storage_failure_is_not_ready(self):
        service = deployment.create_service()
        with patch.object(deployment, "storage_ready", return_value=False):
            self.assertEqual(request(service, "/readyz")[0], "503 Service Unavailable")
            with self.assertRaises(RuntimeError):
                deployment.create_service()

    def test_nonempty_unconfigured_storage_is_not_reinitialized(self):
        self.home.mkdir()
        fixture = self.home / "synthetic-existing-data"
        fixture.write_bytes(b"keep")
        with self.assertRaises(deployment.subprocess.CalledProcessError):
            deployment.create_service()
        self.assertEqual(fixture.read_bytes(), b"keep")
        self.assertFalse((self.home / "config.json").exists())

    def test_token_display_uses_existing_token_without_modification(self):
        self.home.mkdir()
        token = "synthetic-test-token-000000000000000000"
        token_file = self.home / "access-token.txt"
        token_file.write_text(token)
        output = io.StringIO()
        with patch.object(deployment.sys, "stdout", output), patch.object(output, "isatty", return_value=True):
            deployment.show_token()
        self.assertIn(token, output.getvalue())
        self.assertEqual(token_file.read_text(), token)
        self.assertFalse((self.home / "config.json").exists())

    def test_token_not_read_during_preflight_or_noninteractive_execution(self):
        for preflight, terminal in (("1", True), ("0", False)):
            with patch.dict(os.environ, {"PROJECT_DEPLOYER_PREFLIGHT": preflight}), \
                    patch.object(deployment.sys.stdout, "isatty", return_value=terminal), \
                    patch.object(Path, "open", side_effect=AssertionError("Token file opened")):
                with self.assertRaises(RuntimeError):
                    deployment.show_token()

    def test_invalid_token_cannot_inject_terminal_controls(self):
        self.home.mkdir()
        (self.home / "access-token.txt").write_text("synthetic-token-00000000000\x1b[2J")
        output = io.StringIO()
        with patch.object(deployment.sys, "stdout", output), patch.object(output, "isatty", return_value=True):
            with self.assertRaises(RuntimeError):
                deployment.show_token()
        self.assertEqual(output.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
