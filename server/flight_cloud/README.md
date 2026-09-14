# Flight library

Small Python WSGI application. Files on disk, no database, no login/password, no tile uploads.
The Android library is under **Flights → Phone and server** (also available in Journals).

## Deploy with Project Deployer

From this directory, after supplying `SERVER` yourself in your shell. The Korz public name is fixed
to `osmand-flight` in the YAML; neither `KORZ_NAME` nor `GATEWAY_PUBLIC_FQDN` is required:

```sh
bash deploy.sh --dry-run
bash deploy.sh
```

`project-deployer.yml` uses Docker and the existing Korz client on the target machine. It uploads only
this service directory, builds remotely, checks a temporary container, starts production, checks
storage, then restarts its Korz exposure. No server address or account token is stored in the YAML.
The launcher then runs `pd op show-token` in the same interactive terminal, only after a successful
deployment. Use the launcher to include this last step: plain `pd deploy` does not display the token.
The token appears on every successful deployment, including updates, without being regenerated.
Do not record or publicly share that terminal output. To display it again separately: `pd op show-token`.
The target must already have Docker, sudo access and the Korz prerequisites documented by `pd help`.

- Container and project: `osmand-flight`; UID/GID **10001:10001**, read-only root filesystem.
- Host listener: **127.0.0.1:58743**, reachable publicly only through the HTTPS proxy. Preserve
  `X-Forwarded-Proto: https` and allow 512 MiB uploads. Never publish the Docker port directly.
- Persistent data: **/opt/osmand-flight/data**, mounted at **/app/data**. Deployments do not package
  or replace this directory. The first production start creates the account in `data/library`.
- The launcher displays the token stored in **/opt/osmand-flight/data/library/access-token.txt**;
  paste it into Android along with the public HTTPS URL. Bootstrap and probes never print it,
  and its explicit `docker exec` output does not enter the application's Docker logs.
- Configuration: **/opt/osmand-flight/data/library/config.json**. Preserve it: it contains account
  hashes and the edit-session signing key. An existing nonempty directory without configuration
  causes startup to fail instead of silently changing identity.
- `/preflightz` exists only in explicit preflight mode: no storage access, initialization or writes.
  Production `/readyz` verifies a new temporary file can be written, synced and read on the data mount.
- Back up the whole persistent directory privately, including configuration and photos. Application
  release rollback is **not** a data backup; this deployment does not create a backup sidecar.

No remote deployment or Docker build has been executed while preparing these files. The configuration
was checked using `pd` dry-runs with the synthetic target `deployment-target.invalid`.

## Start on your web server

Python 3.10+ on Linux; deploy behind an HTTPS reverse proxy. Nothing is installed or started remotely
by this change. Choose a private empty directory **outside the web root and outside Git**:

```sh
python3 app.py init --directory /absolute/private/flight-library
python3 -m venv .venv
.venv/bin/pip install 'gunicorn>=23,<24'
FLIGHT_CLOUD_CONFIG=/absolute/private/flight-library/config.json \
  .venv/bin/gunicorn 'app:create_app()' --bind 127.0.0.1:58743 --workers 1 --threads 4 --timeout 300
```

`init` creates `config.json` and `access-token.txt` with mode 0600 and never prints the token.
The operator copies the token into the Android connection form. Do not commit either file.
For another account, add its SHA-256 token digest and a different opaque account ID to `accounts`.
All devices using the same token see the same library.

Point a domain such as `flights.example.org` to an HTTPS reverse proxy. Forward the URL path unchanged
to the bound port, preserve `Authorization`, `X-Edit-Token`, `If-Match` and `If-None-Match`, and set
`X-Forwarded-Proto: https`. Allow up to 512 MiB request bodies and a suitable upload timeout.
Keep Gunicorn bound to loopback: do not trust forwarded HTTPS headers from arbitrary clients.
See [Gunicorn deployment](https://gunicorn.org/deploy/) and
[forwarded-header settings](https://gunicorn.org/reference/settings/).
For local proxy testing, use `https://osmand-flight.localhost` with a trusted development certificate.
The Android client requires HTTPS and does not follow redirects with its token.

## Behaviour

- Reading requires the account bearer token. No public listing or unauthenticated files.
- **Enable editing for 15 minutes** exchanges that same token for an expiring signed edit token.
  This is an accidental-write guard, **not** a separate read-only user role: the account token can
  obtain edit sessions. The UI discards the edit token when closed or locked.
- Transfers are explicit snapshots, not continuous synchronization. Each update requires the revision
  previously downloaded/published by this phone. Concurrent edits return HTTP 409; no automatic merge.
- Uploads include `journey.json`, `track.gpx`, and only the selected photos. Photo associations,
  spatial placement, adjustments, control points and calibration diagnostics travel in the manifest.
- Terrain, satellite, render caches and models are rejected. Local originals are not deleted when a
  photo is omitted. Downloaded preparations are not automatically armed.
- Downloading a different server revision creates a **new local copy**, preserving current local edits.
- Each journal retains its current and preceding archive. Older revisions are removed after a
  successful update. The previous ZIP is available for manual operator recovery, not exposed in the UI.
- Limits: 512 MiB compressed **and decompressed** per journal, 32 MiB JSON, 1000 photos.
  ZIP content and CRCs are checked before publication. Revisions are SHA-256 checksums.
- Configuration and edit tokens never appear in application logs. Keep the private directory backed
  up and monitor its available disk space. Interrupted uncommitted files may require operator cleanup
  after a process/machine crash. No remote deletion endpoint is provided in this first version.

## Tests (synthetic data only)

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s server/flight_cloud -p 'test_*.py'
```

Tests call the actual WSGI application and create disposable fixtures inside the repository's `.work`
directory. They never load `config.json`, access a deployed server, or read a real journal.

The existing `OsmAnd/test/standalone/check-flight-preparation.sh <test-libraries>` suite also checks the
Android cloud archive and connection validation, with production flight models and an explicitly fake
Android persistence boundary. Existing photo-calibration serialization tests remain in that suite.
The GitHub build runs both suites. These checks do not replace testing the screens on a phone.
