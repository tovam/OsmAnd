# Flight 108 feedback — 2026-09-29

Scope: source code and synthetic fixtures only; no personal flight data or device logs. No APK build requested in this turn. Checked items indicate implementation/tests, not validation on the user's phone.

- [x] Investigate intermittent crash when opening a flight after using the normal map; distinguish reproducible defects from unconfirmed causes.
- [x] Explain downloaded-country orange/green colours from the actual renderer code.
- [x] Human-rounded time ruler with accurate independent width.
- [x] Consistent centred compact Map/Mixed controls.
- [x] Smaller altitude/speed/GPS/accuracy strip.
- [x] Substantially lower-opacity scale background.
- [x] Detect gaps using the median positive recorded-point interval; mark gaps red in both altitude profiles and the map route without moving points or changing planned segments.
- [x] Clip tile visualization inside its pane; prevent it drawing over navigation/actions.
- [x] Clear short tile controls, meaningful imagery/coverage distinction and concise optional help.
- [x] Persistent coarse z4 background for the Window minimap, refined by available detail.
- [x] Run targeted tests/source checks and document device-validation limitations.

## Findings

- Startup: corrected a resource-ownership defect in `FlightVisibilityEffect`: after recomposition, disposal could call the new renderer callback instead of releasing the old renderer/listener. A paired visibility lease now releases the actual acquired callback. Synthetic tests cover renderer replacement and pause/resume. Delayed native initialization cannot recreate the shared GL surface for an obsolete, paused or destroyed flight view; flight projection only activates on visible Map/Mixed pages. These are verified code defects/guards, **not proof of the reported device crash cause**. No device crash report was read or supplied for this request.
- Country fill: `DownloadedRegionsLayer` uses green for downloaded maps and orange for downloaded maps marked outdated by `checkIfObjectOutdated`. Orange is not missing data.
- Map: six 36 dp centred controls; 34 dp KPI strip instead of 66 dp; scale panel alpha 0x50 instead of 0xDD. Time ruler uses seconds, then 1/2/5/10/15/30 minutes and hours, with a separately computed physical width.
- Recorded gaps: strictly greater than 3 times the median positive timestamp interval, plus explicit recorded segment breaks. Red map strokes reuse the original coordinates/heights. Both altitude profiles use the selected graph time axis. Simulated/planned segments are excluded; route segmentation is cached outside drawing callbacks.
- Green tile corridor: the old default was a presence map (green = satellite and terrain files present), not satellite imagery. In addition, the old far-zoom rendering returned after drawing that presence bitmap even in image mode. Images are now the default, with separate Coverage mode, explicit pane clipping, a level picker and concise optional help. A separate cancellable worker builds a maximum-1024px mosaic from coarse-to-fine cached ancestors and all selected cached tiles; immutable snapshots appear progressively. Metadata is published first, detail decoding stays in its own queue, and inventory refreshes coalesce to one active plus one newest pending mosaic instead of repeatedly aborting work. No download is started by this viewer. Published bitmaps are not recycled while a hardware Canvas may reference them.
- Minimap: an independent z4 queue loads the visible background before waiting for the detailed disk inventory; its small process bitmap cache is independent of detailed imagery and is always drawn underneath. Missing z4 images may be fetched while visible and online. Offline rehearsal never bypasses the network gate. Imported-track preparation now counts and downloads z4 ancestors too. Coarse imagery must still exist locally for first-use offline display.

## Validation

- Standalone synthetic logic suite: 239 passing tests, including median-gap detection (ignoring missing timestamps), recorded versus theoretical profiles, renderer visibility ownership, date-line/polar z4 selection and imported-track coarse-backdrop accounting.
- Subsequent minute-scale adjustment: replaced 4/8 minutes with 5/10 minutes and updated boundary/exhaustive minute-step assertions. Source and whitespace checks only for this follow-up; the JVM suite was not rerun.
- Kotlin syntax parsing, XML well-formedness and `git diff --check` passed. No Android compilation or on-device rendering validation in this turn.
- Temporary synthetic test classes and downloaded test tooling were removed; no APK or Android build cache was created locally.
- Device acceptance still needed: normal map to first flight opening; Map/Mixed compass and scale; red missing intervals in replay and live; tile imagery/coverage toggling and clipping; minimap first display and offline backdrop.
