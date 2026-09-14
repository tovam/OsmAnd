"""Container bootstrap and minimal operational probes; preflight never opens account storage."""
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile

from app import create_app


def response(start_response, status, body):
    start_response(status, [("Content-Type", "application/json"), ("Cache-Control", "no-store"),
                            ("Content-Length", str(len(body)))])
    return [body]


def storage_ready(directory):
    """Probe only a new disposable file, never an existing journal or photo."""
    try:
        with tempfile.TemporaryFile(dir=directory) as probe:
            probe.write(b"flight-storage-probe")
            probe.flush()
            os.fsync(probe.fileno())
            probe.seek(0)
            return probe.read() == b"flight-storage-probe"
    except OSError:
        return False


def create_service():
    if os.environ.get("PROJECT_DEPLOYER_PREFLIGHT") == "1":
        def preflight(env, start_response):
            if env.get("REQUEST_METHOD") == "GET" and env.get("PATH_INFO") == "/preflightz":
                return response(start_response, "200 OK", b'{"preflight":true}')
            return response(start_response, "503 Service Unavailable", b'{"ready":false}')
        return preflight

    home = Path(os.environ.get("FLIGHT_CLOUD_HOME", "/app/data/library"))
    config = home / "config.json"
    if not config.is_file():
        # Existing nonempty storage without its configuration must fail, never generate a new identity.
        subprocess.run([sys.executable, str(Path(__file__).with_name("app.py")), "init",
                        "--directory", str(home)], check=True)
    os.environ["FLIGHT_CLOUD_CONFIG"] = str(config)
    application = create_app()
    if not storage_ready(application.root):
        raise RuntimeError("Flight storage is not writable")

    def service(env, start_response):
        if env.get("REQUEST_METHOD") == "GET" and env.get("PATH_INFO") == "/readyz":
            if storage_ready(application.root):
                return response(start_response, "200 OK", b'{"ready":true}')
            return response(start_response, "503 Service Unavailable", b'{"ready":false}')
        return application(env, start_response)
    return service


def show_token():
    """Explicit operator command; docker exec output does not enter the service's stdout log."""
    if os.environ.get("PROJECT_DEPLOYER_PREFLIGHT") == "1" or not sys.stdout.isatty():
        raise RuntimeError("Token display requires an interactive production terminal")
    path = Path(os.environ.get("FLIGHT_CLOUD_HOME", "/app/data/library")) / "access-token.txt"
    with path.open("r", encoding="ascii") as source:
        token = source.read(258).strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{24,256}", token):
        raise RuntimeError("Account token file is invalid")
    print("\nToken du compte à copier dans OsmAnd :\n" + token + "\n")


if __name__ == "__main__":
    if sys.argv[1:] != ["show-token"]:
        raise SystemExit("Usage: deployment.py show-token")
    try:
        show_token()
    except (OSError, UnicodeError, RuntimeError):
        raise SystemExit("Cannot display the account token; use an interactive production terminal and check provisioning.") from None
