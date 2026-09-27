# Flight live feedback — 2026-09-27

Status: implementation, local verification and push complete; GitHub build 106.1 launched. Checked items mean implemented and checked in code/tests, not validated on a physical phone. No real flight/user data is used.

## Requested changes

- [x] F01 Remove GPS-point monitoring UI from Map and Window (retain the physical track points requested in F11).
- [x] F02 Hide/show bottom controls in Map and Window.
- [x] F03 OSM/satellite 0–100% blending in Map and Mixed, using durable offline imagery; OSM remains visible where imagery is absent.
- [x] F04 Exclude bottom controls from the map viewport and aircraft centring.
- [x] F05 Audit/fix repeated inventory work delaying tile downloads.
- [x] F06 Update the Window minimap viewing cone immediately during gestures.
- [x] F07 Distance scale in Mixed.
- [x] F08 Compact compass in Mixed.
- [x] F09 Tile-screen close/back stays inside the flight workspace.
- [x] F10 Responsive tile map pan/zoom and live coverage feedback.
- [x] F11 Bypass inappropriate ground-visibility queries for elevated flight markers, preserving depth testing and recorded altitudes.
- [x] F12 Redesign tile status/actions: downloaded/total, used/remaining GB, progress, transfer rate, missing/error counts.
- [x] F13 Remove verbose explanatory UI text from these screens.
- [x] F14 Heading-up action in Map and Mixed.
- [x] F15 Show the upper view's viewing cone on the lower Mixed map.
- [x] F16 Preserve Mixed map camera when switching apps.
- [x] F17 Native orthographic projection at 90° in Map and Mixed, scoped to the flight workspace.
- [x] F18 Future route: thick black dashed native tubes matching the recorded route; existing recorded tube positions untouched.
- [x] F19 Travel-time scale at current speed in Map and Mixed; hidden when speed is missing/stationary.

## Delivery

- [x] Run targeted source/synthetic tests; record limitations.
- [x] Commit changes by coherent topic, including the previously prepared basemap fix/build names.
- [x] Push and launch GitHub APK build after implementation: [106.1](https://github.com/tovam/OsmAnd/actions/runs/36351078625), started from `bd9b5ebfa9`.

## Findings / verification

- Corridor starvation: every camera/GPS demand cancelled the background corridor task and restarted verification. Downloads now have bounded workers independent of scene updates; explicit pause and lifecycle cancellation remain effective.
- A slow tile no longer blocks an entire batch. Three workers publish completed transfers independently. Imported/live and prepared flights share the verified downloader; early satellite failures no longer disable the remaining corridor.
- Inventory is retained for the current manifest and updated by file-completion events. The tile view scans only the current manifest, reconciles on resume, retains its camera and decodes images off the UI thread.
- Zoomed-out tile coverage uses a bounded background-generated overview; zoomed-in drawing uses a row index and only visits visible cells. Counters distinguish present files from fully verified offline readiness.
- Window minimap gestures invalidate immediately instead of waiting for a disk scan; obsolete progress changes no longer discard every completed scan.
- Map/Mixed use the same bounded viewport, gesture surface, scales and controls. Normal-map camera state and flight-map camera state are saved separately across pause/resume.
- Native orthographic matrices cover rendering, projection, unprojection, pinch aiming and frustum planes. Altitudes remain unchanged. Elevated flight markers bypass only the asynchronous ground-visibility test.
- Verification: 222 synthetic JVM tests passed, including transfer concurrency/cancellation, camera changes during corridor downloads, viewport geometry, inventory accounting, scales and route dashes. Kotlin syntax parsed; resource XML and patch applicability checked. Native projection/basemap tests ran with address/undefined-behaviour sanitizers; the production basemap raster path also passed its synthetic pipeline test.
- Limitations: no Android SDK/NDK build or physical Pixel test locally (disk constraint). GitHub performs the full native/APK compilation. On-device validation still needs the real gestures, orthographic-to-perspective transition, marker visibility, mixed opacity and live download behaviour.
- Temporary downloaded test tools/native reference sources and the three generated synthetic JVM output directories were removed after verification (approximately 77 MB); all committed tests remain reproducible.

## Implementation commits

- `1d42e313da`: world basemap raster fallback (preceding request).
- `032a66c6f8`: live corridor transfers, cancellation and retained inventory.
- `c55f0a793e`: interactive offline coverage and compact live storage totals.
- `6f21c903ca`: immediate window minimap gaze updates.
- `d0bdaa1a99`: thick black volumetric future dashes.
- `164f8733fd`: orthographic projection and elevated marker visibility.
- `e385eee136`: shared map/mixed controls, viewport, scales, blending and resume camera.
- `707309fdc8`: native/JVM verification wiring and number-first GitHub build titles.
