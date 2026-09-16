# Live flight audit — 2026-09-16

## Scope and confidence

Source review and synthetic JVM tests only. No personal trips, photos, caches, device logs,
phone settings or remote-server data were accessed. No Android APK/device test is implied by
the JVM results. In particular, the Pixel's EGL driver, Android permissions, exact-alarm delivery,
CameraX physical lenses and GPS reception inside the aircraft still require a device check.

This work retains the battery/lifecycle changes documented in `flight-background-work.md`.
No release, push or APK build was requested in this audit.

## Concrete defects addressed

1. **Unrecoverable graphics initialization error.** `retryTerrain()` previously reloaded tiles
   without recreating the broken GL surface. Renderer failures now have independent state, which
   tile-download success cannot erase. Retry replaces the surface/context and detaches old callbacks.
   Runtime rendering exceptions leave a visible error instead of killing the render thread silently.
   The default framebuffer is cleared explicitly; optional photo/shadow shader failures no longer
   disable the terrain program.
2. **Ambiguous dark/empty Hublot.** Waiting for a position, loading a scene, missing terrain and
   looking at the opaque cabin have distinct feedback. Cabin occlusion uses the same projection as
   the mask, with an explicit recenter action. This is not evidence that every reported black frame
   had the same cause; no reproduction on the user's phone was available.
3. **Camera below newly encountered terrain.** Ground clearance used the scene's original centre,
   which can be up to 8 km behind the moving aircraft. It now samples the existing rendered mesh
   at the eye's coordinates, using its triangle diagonal and highest available detail. Recorded GPS
   coordinates/altitudes and the native map track are unchanged. No extra download is involved.
4. **Stale GPS on reopening.** The predictor now consumes the recorder's monotonic fix timestamp,
   not the time at which the UI happened to read it. Restored fixes of unknown age are not extrapolated.
   Map/Hublot show waiting, stale or inaccurate GPS and missing measured altitude. Prediction remains
   display-only and capped at five seconds. The real GPS callback supplies the measurement's elapsed
   time rather than its callback-delivery time.
5. **Late first GPS fix.** If the first fix is acquired at cruise altitude, the relative-climb detector
   cannot infer takeoff. A confirmed “Already in flight” action is available from the live cockpit,
   requiring a fresh, accurate fix above the configured takeoff speed. It enables the existing landing
   detector; it does not invent an airport altitude or silently change the automatic criteria.
6. **Final point lost between recording intervals.** Manual stop now flushes the most recent measured
   fix once, if newer than the saved tail, before finalizing the journal. No predicted fix is persisted.
7. **Simulation blocking tomorrow's alarm.** A real start request can replace a paused/running
   simulation. Its journal is finalized first, simulation timers/sensors/network gating are released,
   then the same foreground service loads the real journal and subscribes to GPS. A real recording
   cannot be replaced by another start or by a simulation. Starts are latched while initial disk
   loading is still in progress, and repeated UI start taps share one start job.
8. **Old controls reaching a new flight.** Stop, microphone, simulation controls and manual takeoff
   confirmation carry the selected journey ID and are checked again on the recorder worker. A stale
   simulation button cannot stop the real flight after handoff. Controls during handoff are ignored.
9. **Cleanup skipped on permission revocation.** Failure to unregister one location callback no
   longer prevents microphone/sensor/timer/network cleanup. Terminal restored recordings clear the
   active preference instead of retaining a dead session. Start/recording errors identify the correct
   journey and remain visible at home and in the selected workspace, including Map/Hublot after stop.
10. **Photo ticks on the wrong time scale in live Hublot.** Photo markers now use the displayed live
    timeline, including its hypothetical future, rather than fractions of the recorded-only track.
11. **Restarting an empty stopped trial.** Even without a single GPS point, a stopped recorder has
    terminal state. Starting again now creates a new journal from its plan, preserving the old trial
    instead of silently stopping immediately on the old state.

## Flow-by-flow verification

“Logic test” means actual production Kotlin logic with synthetic inputs, not an Android UI test.

| Flow | Source path / check | Remaining device check |
|---|---|---|
| Prepare coordinates and UTC-offset times | `FlightPreparationLogicTest`, `FlightOfflineJourneyTest` | Date/time controls and saved plan |
| Download then verify offline corridor | `FlightOfflinePreparation`, `preloadPreparedFiles`; ancestor and missing-asset tests | Complete a small package; check counts |
| Explore the prepared flight with network blocked | `FlightNetworkAccess`; offline gate tests | Hublot/mini-map actually draw cached tiles |
| Start immersive simulation | `FlightLiveSimulationTest`: same detector and virtual fixes | Camera/cockpit layout and gestures |
| Simulation airport → climb → cruise → descent → landed | Whole synthetic flight through production detector | UI transitions |
| Pause simulation, lock phone, reopen | `FlightWorkPolicyTest`, clock and scene visibility tests | Android lifecycle delivery |
| Real scheduled start replaces background simulation | `FlightWorkPolicyTest`; serialized service handoff review | Exact alarm + foreground type transition |
| Repeated start taps or simultaneous start requests | Start-job latch, start disposition tests | Rapid taps on phone |
| Restart a test stopped before its first GPS point | Terminal-state/new-journal policy test | New journal starts and reports waiting GPS |
| Manual real GPS start | Service permission/foreground setup review | Allow precise location, observe fixes |
| Open Hublot before first GPS fix | Explicit waiting state; no synthetic live departure fallback | Waiting message is visible |
| GPS becomes stale / no fixes for minutes | `FlightDisplaySafetyTest`, landing-gap tests | Disable/re-enable location temporarily |
| GPS first acquired after takeoff | Explicit confirmation and subsequent landing tests | Action, confirmation, persisted phase |
| Scrub past/future then return live | `FlightWorkspaceTest`; prediction separate from recorder | Map and Window buttons/timeline |
| Switch Map ↔ Hublot repeatedly | Scene resident-cache/visibility tests; view lifecycle review | EGL resources and continuous gestures |
| Low-altitude Hublot over variable relief | Both mesh triangles, detailed/coarse/missing mesh tests | Real terrain at an airport |
| Turn away from the window | Shared aperture projection and recenter tests | Mask/message layering |
| Renderer initialization/draw failure | Recovery-state tests and catch/recreation wiring review | Driver failure/context recreation not injected here |
| Live camera, photos, shutter metadata | Capture serialization tests; CameraX lifecycle review | Each lens, denied permission, lock/unlock, saved photo |
| Change recording policy or optional microphone | Recorder command scoping and lifecycle review | Permission grant/revoke and microphone indicator |
| Stop between GPS recording intervals | Final-fix tests: no omission, duplication or time reversal | Reopen saved journal |
| Stop/return from simulation after real handoff | Control-ID tests | Old screen cannot stop new recorder |
| App background during a real flight | Real-mode work-policy tests; sticky service and append-only store review | GPS continues with screen locked |
| Process restart / interrupted file tail | Append-only recovery tests; fresh-fix age reset review | Android service restart, not force-stop |
| GPS gap near landing | Continuous 30-minute detector tests; gaps/unknown speed reset timer | Real reception may delay automatic stop |

## Preflight acceptance on the Pixel

Use a disposable test journey and generated/synthetic route; these steps do not require exporting
personal data or granting access to device logs.

1. Download/verify a **small** corridor; require zero missing files. Disable internet, open
   “Explore offline”, scrub the route, switch Map/Hublot and check that scenery is present. An
   uncached area must report missing terrain instead of pretending to be complete.
2. Run “Simulate live flight”. Scrub and return live, try the camera, switch tabs, lock for a minute
   and reopen. Simulation must pause while hidden and resume without fast-forwarding the hidden minute.
3. Start a short **real** test recording outdoors. Confirm a fresh GPS fix, lock for two minutes,
   return and stop manually. Reopen the journal and confirm measured points span the locked interval.
   Ground testing must not automatically claim takeoff or stop after waiting at an airport.
4. Arm a disposable planned flight so its start is two minutes ahead (departure minus the configured
   lead time). Grant all displayed requirements. Leave the app in the background, optionally with a
   paused simulation. Confirm the real recording notification appears and the correct journal is
   active. Stop manually; do not leave the test recording running overnight.
5. Tomorrow, open the app once after updating; verify the real planned departure time/offset and
   notification. At the airport, check a fresh GPS fix before takeoff. If automatic takeoff cannot
   be detected because GPS was unavailable, use the explicit “Already in flight” action after GPS
   returns. Manual stop remains available with confirmation.

Do not rely on automatic startup after using Android's **Force stop**, or with required permissions
revoked. Force-stop can cancel pending intents; reopening/rechecking the schedule is necessary.
The app cannot guarantee satellite reception inside a metal aircraft, or uninterrupted execution
under every system restriction.

## Platform references checked

- [Foreground/background start and location restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 stopped-state behavior](https://developer.android.com/about/versions/15/behavior-changes-all#enhanced-stop-states)
- [GLSurfaceView lifecycle and context preservation](https://developer.android.com/reference/android/opengl/GLSurfaceView)

## Repeatable checks

The final synthetic run passed **116 Kotlin/JUnit tests** (84 + 32), plus the standalone Java
resource-queue check. This is a logic-test count, not 115 successfully exercised Android UI flows.

`bash OsmAnd/test/standalone/check-flight-preparation.sh <explicit-test-dependency-directory>`
compiles the real pure logic against explicitly provided jars and no application data. The renderer
recovery, aperture, terrain-floor, stale-GPS and final-fix tests are in `FlightDisplaySafetyTest`.
`PreparedResourceQueueTest` is a separate standalone Java check. XML and Kotlin parsing are separate
syntax checks, not substitutes for compiling/running the Android application.
