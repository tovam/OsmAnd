# Flight mode audit — 17 September 2026

## Scope and evidence

Requested scope: at least one hour, five independent reviewers, plus integration review and fixes by the primary agent. Audit started at **00:12:29 UTC** and completed final review at **01:12:40 UTC** (more than one hour).

The five workstreams were navigation/UX, recording/background lifecycle, terrain/rendering, journal/cloud storage, and photos/calibration. Reviewers reported findings; the primary agent checked them against source, rejected unsupported claims, made the changes, and ran tests. This was not five unattended copies of the same review.

Only source code and synthetic fixtures were used. No actual journeys, photos, server contents, application databases, credentials, or user logs were accessed. No SSH, device connection, APK build, push, or commit was performed. The already-present main-menu build-number footer was preserved, not counted as an audit fix.

This is a source audit, not a promise of a bug-free application. The Android screens, real camera, alarm delivery, GPU drivers, and on-device frame pacing have not been exercised by these tests.

## Corrected findings

### 1. A temporary flat tile could remain cached as real terrain

**Location:** `FlightTerrainMeshBuilder.build`.

The loading plane and the real elevation mesh shared a geometry cache key. If the loading plane won first, a later build with valid height data could retrieve that same zero-altitude plane. This explains one concrete mechanism for a persistent flat square; it does not prove that every previously observed terrain seam had this cause.

**Fix:** loading planes no longer enter the real-elevation geometry cache.

**Reproduction:** construct a placeholder, then supply a synthetic tile at 1,234 m using the same cache. Before the fix the test failed with expected `1234`, actual `0`. After the fix it gets real elevation and the expected mesh dimensions. A missing tile is also not returned as real terrain when placeholders are disabled.

### 2. Terrain refinement could stitch against a missing or unrelated parent

**Locations:** `FlightTerrainRepository`, `FlightTerrainMeshBuilder`, new `FlightTerrainResidency`.

Fine meshes now wait for their own available parent layer. A completed parent elsewhere must not authorize this child. The boundary layer advances even when empty, so a deeper child cannot silently use the wrong level. Exact east/south boundaries have a fallback to the tile's own parent instead of falling back to the incompatible fine height.

Tests cover absent parents, unrelated parents, valid parent selection, all four boundary edges, and preservation of detailed interior heights. This is deliberately not described as a complete solution to every LOD seam; see the residual coarse-triangle issue below.

### 3. Obsolete meshes could accumulate across scene retargets

**Locations:** `FlightTerrainRepository`, `FlightTerrainResidency`.

Progressive publication used to append previous coverage without consistently restricting it to the new scene plan. Retention is now restricted to planned tile/layer pairs. Useful resident objects are reused rather than rebuilt, including already-detailed geometry.

The synthetic test performs 500 retargets and verifies that coverage remains bounded. This does not globally lower satellite quality or change the existing nearby-texture retention rule.

### 4. Coarse terrain competed unnecessarily with expensive queued refinements

**Location:** `FlightTerrainRepository`.

After nine coarse jobs, extra work was interleaved with the remaining coarse work. All queued coarse work now precedes extra work. Spare bounded workers can still proceed once coarse jobs have started; one slow distant request does not impose an all-or-nothing barrier on nearby imagery. Progress reports the actual coarse tile count rather than announcing completion merely because no failure has yet been counted.

### 5. Late asynchronous completions could act on a different journal

**Locations:** `FlightModeViewModel`, new `FlightJournalOperations`.

An ID check alone is insufficient for leaving A, opening B, and returning to A. A first save also changes a previously null ID. An explicit selection generation now distinguishes these cases without invalidating the first save.

Guards cover simulation results, preparation saves, storage counts, photo imports and late metadata, export notifications, and several error paths. The real-recording start path rechecks ownership after suspensions so that leaving the selected flight does not start GPS for that old request. The intentional completed-flight-to-new-journal transition renews its own generation and still proceeds normally.

Canceled or stale photo imports discard only the new copies owned by that import, including cancellation just after IO completed. They never delete gallery originals. Failed navigation and duplicate-GPX warnings release abandoned busy flags without erasing the current journal, canceling its actual alarm, or stopping real recording.

Tests cover normal publication, A→B→A, cancellation during IO, failed loading, and preservation of journal/live/schedule data when navigation does not complete. These test the extracted ownership/state logic, not the entire Android ViewModel lifecycle.

### 6. Archive failures and media filenames were insufficiently isolated

**Locations:** `FlightJourneyStore`, new `FlightArchiveInventory`.

- Reject duplicate or unsafe ZIP entry names, traversal, unsupported file entries, excessive entry counts, and mismatches between photo entries and the photo manifest.
- Count ignored GPX/directory bytes toward the existing decompression limit too.
- Do not fall back to an unrelated existing local photo when an archive omits its claimed photo.
- Clean up only new UUID-named photo copies if import fails before saving.
- Validate stored photo names without truncating them when reopening a journal. Re-truncating a previously UUID-prefixed name could point at the wrong/nonexistent file.
- Check that all photos exist before opening the output destination for a full export, instead of silently omitting absent photos.
- Remove a newly created photo copy if that individual import fails.

The ZIP inventory rules are unit-tested. The full Android content-provider/archive integration is not exercised here. Shared terrain/cache files are not part of the photo rollback and must not be mistaken for a fully transactional import.

### 7. Cloud upload and local-removal checks could use stale state

**Locations:** `FlightCloudController`, `FlightCloudVersions`, `FlightJourneyStore`.

Publication reloads the journal before packing and rejects changed cloud content rather than using a stale photo-selection snapshot. Comparison excludes local-only offline tile inventory and timestamps; downloading tiles is not a cloud edit. Removing a supposedly uploaded local copy also checks the actual device schedule registry, not just the plan's automatic flag.

Tests cover content changes, harmless offline-only changes, and a device-scheduled flight that must not be removed. A remaining confirmation-dialog race with unsaved UI changes is listed below; the new comparison should not be oversold as a universal transaction.

### 8. Linked Hublot navigation was not persisted as an inspection camera

**Locations:** `FlightModeViewModel.transformLinkedWindowView`, `FlightModels.withInspectionView`.

Linked movement now persists the camera/view used to reopen the photo while retaining the already-calibrated spatial photo plane. A legacy photo without a spatial pose first derives its plane from the old alignment. This preserves the distinction between moving the viewer and moving the calibrated photo.

Tests check preservation of the plane, photo scale, other photos, and the legacy fallback. No automatic fit application or overwrite of manual calibration was added.

### 9. Mirrored EXIF orientations were not consistently handled

**Locations:** new `FlightPhotoOrientation`, `FlightPhotoPerspective`, `FlightModeScreen`, `FlightPreparedAssets`.

Preview and GL preparation share the eight EXIF orientation transforms. Mirroring and transposition are now handled as well as 90/180/270-degree rotation; FOV axis swapping follows the same orientation definition. Synthetic asymmetric image coordinates test all eight cases and undefined-orientation identity.

Original image files are not modified. Real Android bitmap/camera integration remains a device-test item.

### 10. A queued old alarm could revive a canceled or postponed departure

**Locations:** `FlightScheduleReceiver`, `flightScheduleIsDue` in `FlightPreparation`.

The START broadcast now verifies the current schedule still exists and is due before starting the service. A canceled or rescheduled alarm already queued for delivery must not revive the old departure. Explicit manual start is unchanged. Tests cover absent, invalid, future, due, slightly early and overdue schedules.

## User journeys checked in source

These are source-level expectations and regression targets, not a claim that all were clicked through on a phone.

| Intent | Expected sequence / boundary | Audit result |
|---|---|---|
| Open a saved local flight offline | Library → local open → map/hublot | Disk-only opening and save barrier retained; cloud upload is not required. |
| Reopen the currently selected flight | Library → same journal | Existing timeline and pending edits must survive; special resume path retained. |
| Leave with unconfirmed imported photos | Navigate → validate/cancel | Explicit pending-photo barrier retained; late imports now isolated. |
| Switch flights during background work | A → B, possibly back to A | Selection-generation guards added. |
| Retry an unreadable import | Error → retained current flight → retry | Busy flags now released; current journal retained. |
| Import an already-known GPX | Duplicate warning → open existing / deliberate duplicate / cancel | Warning path no longer leaves invalidated work busy. |
| Prepare a route without GPS | Planned flight → edit → explore | Preparation and real recording remain separate; no automatic GPS start added. |
| Explore a plan offline | Offline exploration → map/hublot/timeline | Existing network gate and preview model retained. |
| Simulate real flight mode | Explicit simulation → separate test journal | Same service sampling path retained; stale navigation cannot start the test. |
| Start a real flight now | Explicit start → save → service | Ownership rechecked after IO; completed records still use a new journal. |
| Arm/cancel automatic departure | Save + arm / cancel → device schedule status | Actual schedule registry checked at alarm delivery and local removal. |
| Adjust saving frequency live | Recording policy controls → persistence | Policy behavior retained; saved-point frequency is not GPS receiver frequency. |
| Continue a recording after leaving the UI | Foreground recording service + notification | Screen visibility does not define service lifetime. Requires device validation. |
| Edit photo landmarks | One explicit point-control bar + photo/map panes | Existing gesture tests rerun; documentation corrected to match actual controls. |
| Reopen a calibrated Hublot photo | Select photo → saved camera + fixed photo plane | Linked inspection camera persistence corrected. |
| Publish selected photos | Select → compare current content → upload | Stale persisted-content publication rejected. |
| Keep a local copy of a published flight | Publish success → local still present | Default retained; removal remains a separate guarded action. |
| Remove local data after cloud verification | Explicit remove → version/photo/recording/schedule checks | Device schedule guard strengthened. |

## Remaining risks and improvement opportunities

These were deliberately not turned into a large pre-flight redesign.

### High-value follow-up work

1. **Hard global resident/in-flight memory accounting and incremental uploads.** Ultra+++ can prepare an 8192×8192 RGB565 composite: about 128 MiB for that image alone, before other copies, geometry and driver overhead. Active GPU resources are not all constrained by the inactive-cache eviction budget. In `FlightTerrainView.processTextureUploads`, the 8 MiB/frame guard deliberately lets the first oversized image through, and the 3 ms deadline cannot interrupt the ensuing single `GLUtils.texImage2D` call. This is a concrete potential source of long frames, not proof of a measured duration on the phone. Introduce a single measured budget spanning prepared bitmaps, uploads and resident GL textures, with incremental subimage uploads or smaller independently replaceable patches. Pause extra refinement visibly under pressure; do not silently destroy nearby useful quality. First measure on the target phone.
2. **Exact coarse/fine mesh boundary tests and stitching.** The fixed missing-parent case is real, but boundary morphing still samples the parent height field, not necessarily the exact surface of its rendered coarse triangles. On uneven synthetic terrain these representations need explicit comparison. Add corner, slope, adjacent-LOD and dateline cases before changing the geometry algorithm.
3. **Finish asynchronous ownership at the remaining boundaries.** Capture metadata parsing is still synchronous in `finishPhotoCapture`; move it off Main with ownership of the captured file. Publication confirmation should recheck unsaved changes and recording status, not only persisted content. An abandoned immersive simulation can leave a harmless empty test journal after its disk write; clean up only that newly owned journal if a safe transaction is added.
4. **Separate clock domains and characterize short turns.** Real GPS input is age-checked using elapsed time, but phase detection additionally compares GNSS timestamps with device wall time. A badly set device clock can prevent phase transitions. Turn rate currently averages from the last saved point, so a brief turn after a long straight section can be underestimated. Add synthetic trajectories and clock-offset tests before changing these recording decisions.
5. **Instrumented end-to-end regression suite for the actual phone flows.** Cover canceled alarms, background/foreground transitions, camera lens choice, photo transforms, real file providers, EGL context recreation, offline missing tiles, and rapid journal changes. The current pure tests are valuable but cannot prove Android lifecycle behavior or frame pacing.

### Storage and protocol edge cases

- Duplicate photo IDs in a malformed imported manifest can collide with ID-keyed export metadata. Validate at import/pack boundaries without making existing user journals unopenable.
- Offline tile files imported before a later archive error can remain in the shared cache. Existing file-size checks are not a full image/content verification. Keep shared-cache cleanup distinct from journal deletion.
- A remote empty simulation can be classified as a planned flight because the remote summary lacks an explicit simulation discriminator. A backward-compatible protocol field would make library classification unambiguous.
- A persisted-content comparison does not cover every edit made in UI while an upload-selection dialog is open. Recheck the save barrier and active-recording state at final confirmation.
- Android photo-picker/camera results should carry the originating journal identity from launch through completion, not just capture the selection at import time. The new IO ownership guard does not replace that external-activity boundary.

### Rendering failure paths to exercise on device

- `createTexture` checks the upload error before generating mipmaps, but does not separately check the mipmap result. Add a controlled GPU-memory-failure test and keep a usable prior texture when preparation/upload fails; do not infer success merely from receiving a nonzero texture handle.
- The native map's elevated route already includes a centerline fallback if the optional tube bridge is unavailable. Its GPS coordinates/heights were not changed in this audit. Validate the packaged native library before diagnosing a missing tube as another coordinate bug.
- Explicit preparation downloads currently pause when the flight UI becomes hidden, whereas a real recording keeps running. The UI should make that distinction visible; do not silently turn every tile download into an always-on background service.

### UX improvements worth doing after the flight

- Keep **past / planned / current** as entry choices and use stable content tabs inside each workspace. Do not put previous navigation steps among content tabs. Source already has a centralized workspace/navigation policy; extend it rather than creating another competing router.
- A flight's compact status should distinguish **local copy**, **server version**, **offline coverage**, **automatic start time**, and **GPS actually running**. These are independent facts, not one generic “ready” state.
- Live mode has more tabs than preparation/replay. Consider a persistent live-status action and secondary management entry rather than another row of equal-priority destinations; do not remove existing content before a validated screen review.
- Make system Back and explicit “return to library” predictable and preserve the current journal. Do not add confirmation dialogs to every harmless navigation action.
- Distinguish a missing/corrupt tile, an unavailable provider tile, and work merely queued. Show actual counts, bytes and progress, not an unqualified completion label.
- Explain **saved GPS point interval** separately from **GPS receiver active**. Keeping 1 Hz fixes can still be necessary for turn and flight-phase detection even when fewer points are saved.
- Split the large screen/ViewModel by responsibility only after adding characterization tests. Avoid a wholesale rewrite while capture, persistence and recording behavior are flight-critical.

## Rejected or narrowed reviewer suspicions

- Preserving an EGL context on pause does not by itself prove a leak: AOSP tears down the GL thread/context when the view detaches. A dead-looking explicit cleanup method alone was not enough evidence to change this lifecycle.
- A device clock change is not automatically a lost GPS recording. GPS time and wall time have different sources, and the landing detector already resets continuity on long gaps. The remaining concern is phase freshness, not an invented guaranteed loss of measurements.
- A calculated photo fit must not automatically overwrite a user's working manual Hublot alignment. Separate inspection/fit/manual states were preserved intentionally.
- The claim that a 281-character portable photo name inevitably creates an overlong destination was rejected: the generated destination sanitizes/truncates its suffix before adding the UUID. The real reopening problem was truncating an already stored identifier.
- Existing local opening already avoids a mandatory cloud round trip. No new server dependency was added to “open local.”

## Validation performed

- Baseline standalone flight logic: **134 tests passed**.
- Final standalone flight logic: **159 tests passed** (103 + 56), using production pure logic and synthetic substitutes only for Android/platform boundaries. The actual terrain mesh builder is now included, not stubbed out.
- Python cloud server: **17 tests passed**, synthetic temporary fixtures only.
- **9 Java test programs passed:** photo plane geometry, pose solver, pose diagnostics, calibration input, directional view matrix, landmark geometry, dehaze, prepared-resource queue, render-resource ownership. Their individual assertions are not counted as additional JUnit tests above.
- All **96 flight Kotlin source files** parsed with the Kotlin formatter/parser; output was discarded, source was not mass-formatted. This is a syntax check, not an Android type-check or APK build.
- XML validation and `git diff --check` run separately.
- Test dependencies and generated classes were temporary project-local files. The seven exact temporary directories created for this audit were validated and removed after checks (about 80 MiB); source tests and this report remain. No Android SDK/NDK or model download was needed.

The Kotlin harness is `OsmAnd/test/standalone/check-flight-preparation.sh`; it takes a directory containing `ktfmt.jar` 0.54, `json.jar` 20240303, `coroutines.jar` 1.8.1, `junit.jar` 4.13.2, `hamcrest.jar` 1.3, and `gson.jar` 2.8.9. It does not read app data or credentials. A future Android build remains necessary before installing these source changes.

### Device acceptance checks still required

1. Open an already-local journal with networking disabled; reach map, Hublot and its calibrated photos without a cloud request being a prerequisite.
2. Exercise the separate simulated-live session through waiting, airborne, landing and stop. Confirm the session remains explicitly a simulation and cannot overwrite the real journal.
3. Background/foreground Hublot repeatedly, including a photo overlay and pending high-quality terrain. Verify input responsiveness, unchanged alignment, and no permanent black surface after context recreation.
4. On a disposable planned flight, arm, postpone and cancel an imminent alarm; check both the visible status and whether a service actually starts. This is not an instruction to modify the user's real scheduled flight.
5. During a synthetic recording, leave the flight screen and return. Verify persisted points continue while rendering work stops; then stop the test explicitly and verify GPS stops.
6. Exercise archive/provider failures and canceled photo picking against disposable fixtures, including navigation away during slow IO. Confirm no unrelated journal or gallery media is changed.

No results for these six device checks are claimed in this audit.

## External references used to check assumptions

- Android documents the distinct GPS/system clock semantics and recommends elapsed time for interval/age calculations: [Location.getTime](https://developer.android.com/reference/android/location/Location#getTime()).
- EGL teardown and pause behavior were checked against the implementation, not inferred from one application method: [AOSP GLSurfaceView](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/GLSurfaceView.java).
- Platform restrictions still apply to background recording/start flows: [Android foreground service launch](https://developer.android.com/develop/background-work/services/fgs/launch).
- UX recommendations use stable destinations, visible state, and progressive disclosure rather than extra prose: [Material navigation](https://m3.material.io/components/navigation-bar/overview), [visibility of system status](https://www.nngroup.com/articles/visibility-system-status/), [progressive disclosure](https://www.nngroup.com/articles/progressive-disclosure/).
