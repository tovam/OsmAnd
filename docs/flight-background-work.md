# Flight work lifetime audit

## Findings and changes

This is a source-code audit, not a measurement of a user's phone or battery history.

1. Live simulation used a foreground service with a 100 ms timer. It did not request real GPS,
   but did start the real accelerometer. Leaving the flight UI did not stop either. Simulation
   now requires a resumed UI showing that particular journey. Losing visibility stops its
   timers, microphone (if enabled) and accelerometer. Returning resumes at the saved virtual
   instant, without catching up on elapsed wall time. A manual pause remains paused.
2. Real flight recording is deliberately independent of UI visibility. Its GPS, adaptive
   recording, selected sensors, battery samples and landing detection continue in background.
   Merely opening a plan or an old journal still does not start that service.
3. Compose effects remain alive until disposal, which can be much later than activity pause.
   Replay, predictor and UI clocks now stop on pause. Invisible live timeline reconstruction
   stops too. Camera capture sensors now follow lifecycle, like the CameraX preview already did.
4. Terrain streaming now suspends its current request, delayed retargeting and automatic
   corridor work. Download connections are disconnected and queued work cancelled. Desired
   demand and resident scene are retained: a completed scene is not rebuilt merely on resume.
   Explicit preparation downloads are paused with the UI and remain manually resumable;
   completed files are retained. Saves and explicit cloud transfers are not discarded.
5. Hublot calls GLSurfaceView.onPause/onResume, closes CPU staging on pause and preserves its
   resident EGL resources where Android permits. While visible, pending *unready* tiles no
   longer drive a continuous redraw loop. Worker completions wake rendering; ready uploads
   still progress in bounded batches. GPU failure retries use a timer, not a frame-rate loop.
6. Photo landmark maps, the window mini-map and cached-tile viewer stop their workers when
   hidden by Android. Their cached images/camera state survive backgrounding. Photo solving
   and image/depth preview work also stop when their editor is no longer resumed.
7. Empty resource queues now wait for an event, not a 250 ms poll. Coroutine asset waiters
   also use completion signals instead of 16 ms polling. Failed work retains its backoff;
   cancelled work cannot replace a fresh request with the same key on resume.
8. AudioRecord read failures exit the audio loop instead of spinning on errors. Policy-only
   service commands do not leave an idle service alive; simulation commands do not make the
   service restartable as a real GPS recording after process death.

## Expected transitions

| Activity | Flight visible | Another app / screen off | Return |
| --- | --- | --- | --- |
| Plan or past flight | Requested preview work only | Rendering/streaming paused | Cached view reused |
| Live simulation | Synthetic fixes + selected real sensors | Clock and sensors paused | Same virtual instant; manual pause retained |
| Actual live recording | GPS + recording + visible UI | Recording continues; UI work paused | Latest actual position |
| Preparation download | Progressively stored files | Paused | Use resume; completed files remain |
| Explicit save/server transfer | Finite operation | Allowed to finish | Result retained |

The simulation may retain an idle foreground notification so it can resume without destroying
its recording session. It does not keep its simulation/battery timers or capture sensors running
while paused. A single already-running non-interruptible native bitmap decode can finish before
its cancellation is observed; it must not start the next queued decode.

## Verification

Run `OsmAnd/test/standalone/check-flight-preparation.sh <test-dependency-directory>` for the
synthetic Kotlin logic tests, including visibility policy, virtual clock, multi-owner visibility,
asset completion/cancellation and the real streaming reconciler with a no-I/O fixture repository.
`OsmAnd/test/standalone/PreparedResourceQueueTest.java` also checks idle workers wait indefinitely,
failed work retries, budget/reordering and same-key cancellation/resume.

Device validation still needed after an APK build:

- Start an immersive simulation, then Home/lock the screen: notification becomes paused, capture
  indicators stop, virtual time remains unchanged; resume and check there is no jump.
- Manually pause first, background and return: playback must remain paused.
- Background Hublot while terrain loads, then return: retained terrain appears and refinement
  resumes, with no stale-job publication or permanently stalled uploads.
- Background the camera modal and both photo maps, then return and check interaction/capture.
- Start an actual flight recording: GPS recording and stop detection must remain active with
  the screen off. Do not replace this check with a simulation.
- Confirm a preparation download changes to paused and resumes without losing completed tiles.

No battery percentage improvement can be claimed from these source and synthetic checks alone.
