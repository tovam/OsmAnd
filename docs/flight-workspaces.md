# Flight workspaces and regression checks

The entry page has three destinations. Navigation is defined once in
`FlightWorkspaceNavigation`. Library pages are outside the selected flight's bottom bar.

| Workspace | Available pages | Source of positions |
| --- | --- | --- |
| Past flight opened locally | Map, Window, Tiles, Sensors, Photo+, Journal | Imported or recorded samples |
| Planned flights | Preparation, Map, Window, Tiles | Display-only great-circle simulation |
| Current flight | Map, Window, Tiles, Sensors, Photo+, Recorder | Recorder service plus a separate predicted future |

The current-flight entry is disabled unless the recording service is running.
Opening another journal does not stop the service or cause its updates to replace
the selected journal. Starting a prepared flight requires confirmation. Creating
a new plan or previewing it does not start GPS recording.

## Local opening and library audit (September 2026)

The two library UIs previously disabled **Open** using the global `journeyDirty` flag.
Any unsaved edit, including an edit in a different journal, therefore disabled every local
flight. Saving locally cleared that flag, which made an intervening server-publication flow
look like a prerequisite for opening. It was not a requirement of the local file loader.

The local button now depends only on a local copy being present and no local open/removal
already targeting it. A server outage, read-only lease, revision conflict, unselected photos
or unsent edits cannot disable it. The same production policy is used in both libraries.

Additional findings addressed:

- Past flights mixed the collection, selected journal's editor and storage report in one list.
  Collection browsing is now separate from the selected flight's **Journal** tab.
- The versions screen repeated the entire library after selecting one flight. It now scopes
  itself to that flight. Global **Server connection** opens connection settings directly.
- Reopening the selected flight reloaded its file and reset the replay cursor. **Resume**
  now uses the in-memory flight and retains the cursor and photo data.
- Preparation's own Back handler closed the whole workspace. Its separate save/leave dialog
  saved without performing the requested navigation. Back now returns to Planned flights;
  the shared save-before-replacement/close path owns save protection instead of a second dialog.
- Local photo autosaves, renames, plan edits and explicit saves had inconsistent paths.
  Disk snapshots are now captured under one save mutex, with a local save barrier before
  opening another flight, importing a GPX/archive, creating a plan or closing the workspace.
- A failed save or load keeps the current journal; an error dialog explains what failed.
  Pending imported photos require **Keep and continue** or **Stay here**, not silent loss.
- Implicit local saves do not arm/cancel a recorder or require GPS/scheduling permissions.
  Programming automatic departure remains an explicit action in Preparation.
- Autosave completion and entering Photo+/Window triggered unnecessary server refreshes.
  Local save completion now only updates local summaries. Library refresh remains read-only.
- Generic **Update** and **Read only** labels confused local edits with server permissions.
  Transfer directions and server-only permissions are explicit. Successful publication is
  green; failures remain distinct. A refresh is not labelled as a cancellable file transfer.

### User journey / action contract

| User action | Expected result | Server dependency |
| --- | --- | --- |
| Enter flight tracking | Home: past, planned, current; no implicit GPS start | None |
| Browse past or planned flights | Separate collection; All / Phone / Server filters; storage badges | Server listing is optional |
| Open a phone copy | Save pending local edits, then open from disk | None |
| Open a phone copy with server conflicts | Same local open; show differing revisions separately | None |
| Resume the selected flight | Keep timeline, current data and photo edits; no file reload | None |
| Switch from an edited journal to another flight | Finish the local write before replacing the selected journal | None |
| Switch with unvalidated photo imports | Keep photos in the old journal and continue, or stay | None |
| Fail to save while switching | Stay on the old flight with an explicit local-save error | None |
| Fail to load the requested flight | Keep the old flight and its saved edits; report the load error | None |
| Import a GPX / OsmAnd track / flight archive | Save old journal first; retain duplicate-GPX warning; autosave newly imported GPX | None |
| Create a planned flight | Save old journal, create an independent plan, autosave locally | None |
| Clone a route | New plan identity, cleared schedule and no enabled automatic departure | None |
| Simulate a planned flight | Display-only future; original recorded samples remain empty | Cached coverage needed for offline scenery |
| Start / revisit the current flight | Existing confirmation/service; other journals cannot steal its selection or stop recording | None |
| Rename, retouch or calibrate a photo, mark flight spans | Local autosave with visible saving/error state | None |
| Use Back from Map | Return to the appropriate library (or Home for live) | None |
| Use Back from a library | Home, not a different selected-flight tab | None |
| Open Journal | Only this flight: rename, local save, export, expandable storage details | None |
| Open Versions and send | Only the selected flight's phone/server versions and explicit transfers | Listing/update requires connection |
| Download a server-only flight | Download and open a new phone copy; no edit lease | Read token + network |
| Fetch a differing server version | Confirmation; separate local copy, no overwrite of local edits | Read token + network |
| Publish local changes | Explicit photo selection and confirmation; preserve other server photos | Temporary server-edit lease |
| Retire a phone copy | Separate confirmed action; verify published revision and all photos first | Verified downloadable server copy |
| Close flight tracking | Finish local save first; leave recorder service running if active | None |

The lightweight suite includes `FlightLocalNavigationTest`, which exercises the production
open policy, suspendable save-before-load sequence, failure preservation, version states,
filters and navigation hierarchy with synthetic data. It does not exercise Android touch
delivery or real device file-system failures. Device acceptance should repeat the local-open
cases with network disabled, an unexpired/expired edit lease and two independently edited copies.

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

## Photo editor and paired landmarks

The native Canvas view previously had no explicit clip and could draw outside
its measured rectangle over the Compose toolbar. It is now clipped in Canvas,
with a bounds outline, and at the Compose viewport. The toolbar is outside that
viewport. Selection is through numbered buttons, not hit-testing photo markers.
The single **Repères** tab shows two independent native viewports simultaneously: photo above,
satellite map below, with equal weights in the remaining space. Both stay mounted behind Retouch,
Compare and Details, retaining their cameras. Comparison has its own camera. Decoded satellite
tiles are shared through `FlightPhotoTileCache`; ordinary edits never clear that cache.

Four compact, single-line toolbar rows replace the old wrapping controls and instructional footer.
Actions have a 28 dp minimum height (growing if font scaling needs it). The numbered pair list is lazy
and horizontally scrollable, so adding many pairs does not create a large vertical panel. P/C marks
show whether the photo pixel and map position are set. Calculation stays disabled until four complete
pairs, the image, a track association and a known reference altitude are available. Missing prerequisites
and load errors remain accessible through **Infos**, not a permanent paragraph over the canvases.

- **+ Repère** creates/selects one empty pair. Taps can fill either half in either order.
- Selecting a numbered button selects that pair for placement/movement on both canvases.
- A tap in placement mode updates only the selected pair; canvas taps never select or add markers.
- One finger pans, two fingers pan/pinch/rotate, including while placing points. A drag cannot place
  a point accidentally. **Explorer** disables placement; **Placer/déplacer** re-enables it.
- **Supprimer** removes the selected pair. **Tout effacer** requires confirmation.
- Photo rotation is display-only: canonical pixel coordinates and existing fits are unchanged.
- Satellite rotation transforms tiles, the track and control points consistently. Inverse picking
  and gesture anchoring use the same rotation; the tile planner covers all rotated viewport corners.
  Labels stay upright. **Nord** resets bearing only; **Recadrer** resets that pane's framing.
- Back from Retouch, Compare, Hublot or Details returns to the unified landmark workspace, then
  Back closes the editor. No removed map-only editing page remains in the back stack.

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
2. Photo above and satellite below remain simultaneously visible and equally sized. Rotating and
   zooming either pane does not affect the other. Switching Retouch/Compare and returning preserves
   both views. Place a pair in either order; after rotating the map, picking a visible landmark still
   returns that landmark's ground coordinates. Check at normal and enlarged Android font sizes.
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
