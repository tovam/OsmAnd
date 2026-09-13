# Flight photo resection

## User workflow

Photo Plus uses a compact chronological library. Open a photo to access five editor tabs:

1. **Photo landmarks**: choose an index, then tap its pixel. Pinch and pan to aim. Coordinates are normalized against the EXIF-oriented preview, before the user's manual display rotation.
2. **Map landmarks**: select the same index and tap the corresponding ground feature on a north-up top-down map. Satellite and offline OsmAnd vector sources are selectable. These controls never take over the main map view.
3. **Compare**: show the recorded camera in orange, fitted camera in violet, landmarks in cyan and recorded trajectory in orange. Fit the map to about three times the horizontal camera separation (minimum 50 m); magnification beyond source zoom is explicitly only magnification.
4. **Hublot 3D**: switch between recorded eye, estimated eye and an overview aimed at their midpoint. Real sphere meshes identify the other camera. The photo is a fixed world-space rectangle, not a moving HUD. Pan, zoom and photo opacity are independent inspection controls.
5. **Details**: retain date provenance, track association and sensor metadata without expanding every library row.

Associate the photo with the track first. Five complete, well-spread point pairs are required; up to twenty are supported. Calculation can be cancelled. Every edit is passed to the existing journal autosave mechanism. Pending photos still require the normal import confirmation.

Photo and map clicks independently advance to the next unplaced landmark. Once all slots on that surface are filled, placement is disarmed: choose a numbered button explicitly to edit an existing point, or add a slot. P/C indicators show which half of each pair has been placed. Photo Plus uses undamped pinch scaling; the Hublot camera's existing half-strength pinch remains unchanged. Calculate is disabled until the image, recorded position, recorded altitude and at least five full pairs are available; the missing prerequisite is displayed. Terrain/network or solver failures discovered during calculation are still reported as errors.

Automatic association, association to the current track point and removal require confirmation. The current-point confirmation displays its fractional index and warns about resetting manual Hublot alignment. If playback moves the target while the dialog is open, the confirmation is cancelled. Landmark pairs are preserved by these association changes.

## Numerical model and limits

`PhotoPoseSolver` minimizes the squared pixel reprojection errors across all supplied pairs. It uses nine initializations and damped least squares (Levenberg–Marquardt). Unknowns are position, yaw, pitch, roll and optionally focal length. With a fixed focal length, the user controls the vertical field of view. Principal point is assumed centered, pixels square, and lens distortion is not fitted.

Ground altitude is sampled from a z14 Terrarium tile in the shared persistent terrain cache. Missing terrain is an error, never silently zero. Coordinates use the existing WGS84 curved-Earth conversion and its inverse, rather than flat latitude/longitude offsets. The search is bounded to 100 km horizontally from the recorded camera and local vertical coordinates -0.5 to 30 km. These are optimizer safety bounds, not confidence intervals. A bounded result near an edge is flagged.

The displayed RMS and individual errors use **decoded preview pixels**, not original-file pixels. The conditioning test detects weak geometry; it is not a positional confidence interval. Good reprojection agreement does not prove the camera location is correct. Prefer landmarks spread in both image directions and at different distances; mountains, shorelines and buildings must refer to the same actual point in both views. Map resolution, terrain errors, GPS/DEM vertical datum differences, cropped photos, lens distortion and uncertain point placement can all bias the result. This is a local estimate, not a guaranteed global optimum or an exact location.

`FlightPhotoCalibration` saves normalized points, fetched elevations, image dimensions, solver options, complete fitted pose, residuals and inspection settings in the photo's journal JSON. It travels through existing journal save/load and archive import/export. The recorded track association and existing manual Hublot alignment remain unchanged. Corrupt optional fit data is dropped without discarding valid landmark pairs.

## Continuous Hublot rotation

The previous view matrix formed `eye + direction` in Float, where direction was a unit vector. Hundreds of kilometres from the scene origin, rounding swallowed small angular changes. `DirectionalViewMatrix` constructs the view basis directly from the direction and computes translation separately. Both normal Hublot and the calibration editor also use a transform detector without a touch-slop dead zone; consumed button gestures retain ownership.

## Verification

Standalone tests under `OsmAnd/test/standalone` cover:

- `DirectionalViewMatrixTest`: 1000 successive small yaw steps and 1000 pitch steps at a distant origin.
- `PhotoCalibrationInputTest`: sequential photo/map placement, no overwrite after five clicks, explicit correction selection, readiness prerequisites and undamped photo zoom.
- `PhotoPoseSolverTest`: exact and noisy synthetic correspondences, fixed/free focal length, five-point unknown-focal recovery and rejection of collinear image points.
- `PhotoCalibrationPersistenceTest`: WGS84 position/direction round trips, including a dateline case; complete and partial JSON persistence; corrupt-fit recovery; agreement between solver projections and the real GL photo rectangle for five positive/negative roll angles.

Compile the Java tests with their helpers from `OsmAnd-java/src/main/java/net/osmand/util`, targeting Java 8. The Kotlin standalone test compiles `FlightTerrainCoordinates.kt`, `FlightPhotoCalibration.kt` and `fixtures/FlightCalibrationStubs.kt` with those Java classes, org.json and kotlinx-coroutines-core-jvm on its classpath. The fixture intentionally supplies no terrain network access. It is not an Android integration test.

These tests and Kotlin/XML syntax checks do not replace an APK compilation or on-device gesture/UI verification. No real user photo, journal or server data is part of these fixtures.
