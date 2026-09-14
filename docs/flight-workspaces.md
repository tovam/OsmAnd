# Flight workspaces and regression checks

The entry page has three destinations. Navigation is defined once in
`FlightWorkspaceNavigation`, then rendered in the same bottom bar on every page.

| Workspace | Available pages | Source of positions |
| --- | --- | --- |
| Past flights | Map, Window, Tiles, Sensors, Photo+, Journals | Imported or recorded samples |
| Planned flights | Preparation, Map, Window, Tiles | Display-only great-circle simulation |
| Current flight | Map, Window, Tiles, Sensors, Photo+, Recorder | Recorder service plus a separate predicted future |

The current-flight entry is disabled unless the recording service is running.
Opening another journal does not stop the service or cause its updates to replace
the selected journal. Starting a prepared flight requires confirmation. Creating
a new plan or previewing it does not start GPS recording.

## Data boundaries

- A simulation is never saved as recorded samples. Saving a prepared journal
  saves its plan, shared tile references and its original (empty) recorded trip.
- `liveTimeline` is display-only. The recorder, exports and photo associations
  continue to use the measured `trip`.
- Scrubbing a live flight retains an absolute cursor time when a new GPS fix
  rebuilds the future. Map and Window both provide Return to live.
- Predicted samples have no fabricated satellite count, accuracy, sound or
  vibration readings, including interpolation immediately after the last fix.
- Photo associations convert between recorded point positions and display time,
  never between the two timelines' incompatible progress fractions.
- CameraX's capture-start callback stores the last measured GPS fix, magnetic
  field and rotation vector alongside the photo. GPS wall time and sensor
  monotonic timestamps are retained separately. These are not guaranteed to be
  simultaneous with the physical exposure; missing readings remain missing.

## Photo editor visibility

The native Canvas view previously had no explicit clip and could draw outside
its measured rectangle over the Compose toolbar. It is now clipped in Canvas,
with a bounds outline, and at the Compose viewport. The toolbar is outside that
viewport. Selection is through numbered buttons, not hit-testing photo markers.
The photo and map keep their separate transforms when switching tabs.

## Map geometry

The route keeps its original coordinates and absolute heights. A black native
centreline is created independently of the optional tube mesh, before enabling
the JNI tube extension. The red vertical tether is 4.1 dp wide; the black centreline is 3.2 dp.
The centre lock changes only the geographic target; it does not call the API
that cancels native zoom animations. The GPS-point toggle is on Map, not Sensors.

## Verification

Standalone synthetic checks cover navigation, prediction, photo timestamp
mapping and capture JSON round trips:

```sh
bash OsmAnd/test/standalone/check-flight-preparation.sh /path/to/test-libraries
```

The directory contains `ktfmt.jar` (with the Kotlin compiler), `json.jar`,
`coroutines.jar`, `junit.jar` and `hamcrest.jar`. Native geometry checks are in
`OsmAnd/test/native/FlightTubeMeshTest.cpp`. Photo action/transform/solver checks
are in `OsmAnd/test/standalone/Photo*Test.java`.

These checks do not replace an Android build or device rendering validation.

## Preparation responsiveness and telephoto compatibility

### Photo calibration point diagnostics

Each explicit calibration computes the ordinary full fit, then one fit per complete pair with
that pair excluded (at least five complete pairs are required for exclusions). Fits run off the
UI thread, sequentially, with progress and cancellation. The complete fit remains the authoritative
pose; diagnostics never move the camera, delete observations, or recalculate existing saved fits.
All subsets keep the same original camera bounds and focal setting. They also try the full-fit
parameters as an extra seed so a worse local optimum is not mistaken for point influence.

The scrolling **Impact des points** report opens after completion and is saved in the photo's
calibration metadata. It ranks either by RMS reduction or by current point residual. For each
exclusion, it compares the full model with the refitted model **on the same retained pairs**, using
both RMS and the sum of Euclidean pixel residuals. The excluded pair is separately projected through
the new model. Pixel units refer to the decoded calibration image, not metres or location accuracy.
Weak/degenerate subsets are flagged; a failed subset does not discard the other diagnostics.
Multiple wrong correspondences may mask each other, so no automatic rejection is performed.

Synthetic checks: `PhotoPoseDiagnosticsTest.java` and `FlightPhotoFitDiagnosticsTest.kt` cover known
bad/clean pairs, fixed/free focal length, minimum point counts, cancellation, original numbering,
unchanged full fits, JSON round trips, and malformed/legacy optional reports.

### Planning and telephoto changes

- The offline quote has identity equality and precomputed counts/asset lists. Compose must not
  traverse the download manifest to hash effect keys or compute statistics on each update.
- Overview coverage is prepared off-thread and bounded to 8,192 cells, normally at zoom 8.
  The embedded preview does not intercept vertical scrolling; editing happens in the full-screen map.
- Editing dates never starts hidden 3D work; opening Map/Window resumes the shared scene.
  Native date/time dialogs commit complete values. Incomplete UTC-offset text preserves the schedule.
- Download progress is throttled to four updates per second. Pause cancels the queue, interrupts workers
  and disconnects only that preparation's HTTP connections. Resume waits for cancellation, verifies
  cached files and retries missing/corrupt sources. Only a fully verified manifest is complete.
- Saving has a persistent action/status at the top of Preparation. A stale asynchronous save cannot
  mark later edits clean. Existing automatic schedules are not silently disabled by ordinary Save.
- Simulation reopening retains its cursor. Changing image quality does not invalidate an in-flight
  simulation calculation; its input key contains only route coordinates and departure/arrival times.
- Display quality is editable in Preparation using the same plan fields as Window. Download source
  zooms and display quality are labelled separately; source coverage is not a camera zoom setting.
- Photo focal fitting/persistence and the 3D projection now support vertical fields down to 1 degree.
  Valid existing zooms retain the exact previous projection formula; no stored fit is recalculated or
  photograph migrated. Slider spacing is logarithmic so the extended zoom range remains adjustable.
- Synthetic tests include 2/5-degree focal fitting, ordinary projections, calibration JSON round trips,
  a 50,000-source manifest and a single continuous flight through an intermediate waypoint.

Device acceptance must verify:

1. With an eight-point synthetic photo, the complete toolbar stays visible while
   panning, rotating and zooming; numbered selection, move, delete and clear work.
2. Switching Photo/Satellite and returning preserves map location and zoom.
3. A synthetic elevated track shows the black connecting path with GPS points
   both enabled and disabled, from above and with a tilted camera.
4. Locked map centre does not move during pan/pinch/rotate/tilt; zoom and angles
   still change. Unlock restores free panning.
5. A saved plan reopens under Planned flights with no recorded samples. Preview
   has no Live, Sensors or Photo+ tabs. A completed journal has no Live tab.
6. While a recording continues, scrub into the past and future in Map/Window;
   new fixes must not steal the cursor. Return to live resumes following.
7. A live photo's persisted capture object survives journal save/export/import,
   including absent sensors and independently timestamped measurements.
