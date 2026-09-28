# Flight live feedback — 2026-09-27

Status: implementation and local verification complete. Build 106.1's native patch failure was fixed by `54cc07f5c7`; build 107.1 passed native compilation but failed upstream Java tests because its floating resources were incompatible with this fork. Resources are now pinned to the exact version from successful build 105.1; the next APK build will verify the complete fix. Checked items mean implemented and checked in code/tests, not validated on a physical phone. No real flight/user data is used.

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

## Build 106.1 follow-up — 2026-09-28

- [x] Identify failure: upstream `VectorLine_P` replaced the separate map/surface zoom fields with `ZoomState`; the flight volumetric patch still matched the removed expressions. The failure occurred before native or Kotlin compilation.
- [x] Adapt tube width and grid selection to `geometryZoom`, `surfaceGeometryZoom` and the current local `mapZoomLevel`, preserving upstream snapped zoom and our altitude/width policy.
- [x] Update the production-formula test fixture and source assertion to the same upstream API.
- [x] Apply all five patches, in CI order, to upstream core `3a5be42e57c3245d31078b9217bfb5569f469d75` using only their small source-file subset (no repository clone or Android build).
- [x] Pass the actual native grid-cutting pipeline, 36 width/DEM combinations, basemap raster pipeline and patched JNI CMake validation. An independent read-only Kotlin review found no concrete compile issue; full compilation remains GitHub's check.
- [x] Push the compatibility correction and launch [build 107.1](https://github.com/tovam/OsmAnd/actions/runs/36444624147). Native compilation passed; Java tests then failed as documented below.

## Build 107.1 follow-up — 2026-09-28

- [x] Retrieve the exact failed-test output: 1,261 tests completed, 52 failed, 14 skipped. Failures: 34 lane-preparation, 13 turn-preparation compatibility, four bicycle-routing and one search test.
- [x] Identify cross-repository version skew: resource commit `fb5e1559264194e6ed012bb9362f802cdbe20f9d` introduced lane expectations and per-case maps requiring newer routing/test-loader code. This fork still loads a single `Turn_lanes_test.obf`. Routing source/test code and Gradle configuration have not changed since successful build 105.1.
- [x] Recover the exact resource checkout from build 105.1's CI log: `13bb530595a2671f4baef431a1e0c7aa5f0b12fa`. Pin the complete resource repository to this coherent baseline rather than disabling tests or mixing new runtime resources with old test fixtures. Refresh this pin deliberately alongside upstream routing/test updates; it also versions routing XML, rendering and POI assets.
- [x] Add automatic bounded CI failure annotations and successful-build resource provenance, allowing diagnostics without a local GitHub token or downloading build artifacts. Synthetic tests cover named and unnamed log steps, missing revisions, escaping and chunking.
- [ ] Launch the replacement APK build and record its URL. Full Gradle/APK verification runs on GitHub, not locally.
