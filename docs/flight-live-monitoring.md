# Live recording and offline coverage feedback

## GPS status

The library used a one-second UI timer as the current monotonic time. A fix received
between ticks therefore had `lastFixElapsed > elapsed`, which the safety check correctly
classifies as invalid/stale. This made a fresh fix briefly display as lost until the next
tick. The UI now uses the actual monotonic clock at composition time; the recorder's
15-second freshness threshold is unchanged.

Recording, flight phase and signal age are separate labels. `WAITING` means takeoff has
not been detected, not proof that the aircraft is on the ground. Starting the GPS after
the climb can leave that phase unchanged; the existing explicit airborne confirmation
remains available under its existing safety conditions. No phase thresholds were changed.

The live workspace displays a persistent recorded-point count, last-fix age and accuracy.
Tapping it opens Direct, including the latest 12 recorded points (local time, coordinates,
altitude and accuracy). Map point markers are enabled when entering the live workspace,
and can still be hidden. Counters read the recorder's persisted sample list, never the
predicted aircraft position, simulation preview or future part of the live timeline.

## Offline coverage

One background inventory covers the requested corridor and levels, including coarse
ancestors. The same figures are shown in Preparation, Tiles and the live workspace:

- source files stored / required, separated into satellite and terrain;
- remaining files, exact stored file bytes and estimated remaining GB;
- transfer/verification progress, pause and failures when a download is running.

Units are decimal GB (1,000,000,000 bytes). Before enough files exist, estimates use
45,000 bytes per satellite file and 110,000 per terrain file; thereafter they use observed
mean sizes per source. Compression and zoom make the remaining volume approximate.

An initial background file/header inventory is followed by constant-time notifications
from source-tile writes/removals. UI snapshots are published at most twice a second;
there is no whole-cache scan per GPS fix, frame or tab switch. Going to the library or
background cancels the observer. Resuming rechecks storage while preserving the last
complete count with an explicit recheck label. No network request is made by the inventory.

Only complete source filenames with readable image headers are counted as present;
temporary downloads and render composites do not count. Presence is not advertised as
fully verified readiness: that requires a successful full pixel verification of every
expected file. Failures remain visible and never increase the coverage numerator.

## Verification

`OsmAnd/test/standalone/check-flight-preparation.sh` includes synthetic monitoring tests
for the between-tick clock race, real stale fixes, actual versus predicted point counts,
source isolation, partial coverage, file sizes, retries, deletion, concurrent inventory
updates, readiness and observer removal. These tests do not access any recorded flight,
phone, user photo, cache or credentials. They do not replace a build/device test.
