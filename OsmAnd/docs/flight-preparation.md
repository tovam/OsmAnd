# Prepared flights and independent recording

The preparation page defines endpoints and optional **via points, not stopovers**. Each scheduled endpoint has an explicit UTC offset for that date. The trip is a great-circle hypothesis; GPS records never get snapped to it. The blue-grey native map line recomputes the remaining hypothetical route from the last measured position. The black elevated tube and orange markers keep their original measured coordinates.

## Recording lifecycle

`FlightRecordingService` is a location foreground service with its own serial worker, GNSS status, adaptive recording policy and append-only measurement store. Closing the fragment, switching tabs and turning the screen off do not stop it. The screen observes a StateFlow. Its bounded five-second visual extrapolation/correction is never written as GPS data.

Default state transitions:

* An exact alarm starts the recorder 15 minutes before scheduled departure.
* WAITING establishes the departure baseline from the first reliable altitude; low ground speed cannot stop this phase.
* A gain of at least 1,000 m **and** a speed above 200 km/h establishes AIRBORNE.
* Only AIRBORNE can reach LANDED: speed below 50 km/h continuously for 30 minutes, with fresh fixes, at most 100 m horizontal uncertainty and no gaps over 30 seconds. Missing speed or unreliable fixes reset the low-speed timer.
* Explicit stop requires confirmation. Stopped/completed journals are preserved; use Repeat to create another flight.

The state survives process recreation; the low-speed timer is reset at service restart rather than treating downtime as a measured landing. An incomplete final JSONL append is backed up before repair. Metadata saves use AtomicFile and cannot overwrite the raw recorder stream or a concurrent photo edit/download completion.

Automatic start requires precise + background location, exact alarms and notification access. Boot/package replacement re-arm unfinished schedules. Android force-stop, a powered-off device, revoked permissions or vendor restrictions cannot be bypassed. Microphone recording is opt-in while the interface is visible, not secretly started by a flight alarm. Sensor processing is assigned to the service worker.

## Offline preparation

Satellite and DEM zooms are independent in each cumulative corridor band. The preview is geographically positioned and colour-coded, with approximate pixels/metres, tile counts, storage estimate and already shared files. The manifest includes every coarser parent down to z3, with coarse DEM/imagery fetched first. Requested zooms are never silently lowered. Huge requests above 250,000 files are rejected explicitly, as are polar corridors outside Web Mercator coverage.

All journals physically reuse the same immutable tile files. The requested manifest is saved **before** downloading, so reopening after interruption rediscovers completed high-zoom files too. Re-running checks existing images and retries absent/broken ones. READY means every requested image decoded; partial failures, paused jobs and insufficient space remain incomplete. A 256 MiB reserve protects recording capacity.

Simulation is explicitly marked, never saved as measurements, and returns to the untouched prepared journal. The live recorder retains the plan and its offline asset references. Battery readings are persisted once per minute; the graph and discharge slope are available in Direct and Sensors. The slope uses the last discharge segment (up to one hour), not charging samples.

## Camera

The live camera modal uses CameraX preview/still capture, available camera selection, native zoom range, focus by tapping and exposure compensation where supported. Pinch is unattenuated here. Only this modal's camera use cases are unbound on close; GPS recording continues. Capture metadata and timestamps remain attached to each photo; a later recorded GPS bracket can complete its time association.

## Verification

`OsmAnd/test/standalone/check-flight-preparation.sh <dependency-directory>` runs 16 synthetic JVM checks using the production recorder state machine, corridor planner, great-circle math, predictor and append recovery. Its two explicit non-subject fixtures substitute only the photo editor type and a renderer constant. These are **not** on-device Android lifecycle, camera or touch tests.

The CI workflow fetches the pinned small test toolchain and runs this check alongside APK compilation. Device verification still needs: background permission/alarm grant, screen-off recording, reboot before departure, permission denial/revocation, actual lens controls, interrupted download/reopen and map/photo touch gestures.

Deferred by request: identifying cities/lakes/POIs in the view, annotating photos with those names, and announcing what will appear on either side. No speculative POI feature was implemented.
