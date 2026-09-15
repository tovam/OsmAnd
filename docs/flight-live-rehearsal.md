# Live rehearsal

## User path

- Open a planned flight or a recorded journey and choose **Simuler le vol en cours**.
- A separate `[Simulation]` journal is saved. The source, its photos and its scheduled departure are untouched.
- Map, Window, Sensors and Photo+ are the real LIVE screens. The integrated camera is also accessible from the live toolbar.
- Pause or select 1×, 10×, 60× or 300×. The default is 60×.
- Planned routes include the configured pre-departure waiting period and a stationary arrival period. Recorded routes retain their geometry and altitude. Missing or unsuitable recorded data may prevent takeoff detection; that is not disguised as a successful landing.
- GPS fixes and time are simulated. Camera, microphone and battery are physical phone readings. Photo associations use virtual flight time; the original camera file is not rewritten to pretend it was taken then.

## Shared implementation

`FlightLiveSimulation` supplies fixes to `FlightRecordingService.acceptSample`, exactly like GPS. Adaptive recording, persistence, takeoff detection and the 30-minute low-speed landing rule are shared, not scripted screen changes. Every virtual second is fed to the detector even at accelerated speed.

The simulation owns a network block and uses already stored terrain/textures. It never registers for real GPS updates or arms a departure alarm. It is not a test of Android's scheduled wakeup or background-location permission delivery. It does test the foreground recording path and live UI. Android foreground-service limits still apply; a timed-out rehearsal is stopped and saved, not silently restarted as a real flight.

Camera controls include the existing lens selector, zoom and exposure compensation, plus manual ISO/exposure time, white balance and manual focus when advertised by the device. No unavailable hardware capability is promised. Camera sensor readings describe the real phone, not the virtual aircraft.

## Navigation and basemap

Library and preparation are parent pages, not bottom tabs of the visualization. The compact top bar returns to them. Bottom tabs describe views of the currently selected flight.

The native basemap patch evaluates overzoomed basemap-only objects with coarse-zoom styling while retaining requested geometry/projection. Regional map rendering is unchanged. This requires an installed world basemap and must still be checked on a device; it does not synthesize regional detail or fetch missing maps.

## Verification

Standalone tests cover the detector's complete simulated lifecycle, source-coordinate preservation and virtual-clock pause/rate behavior, alongside existing flight logic tests. Kotlin syntax checks are not an Android compilation or a device interaction test.
