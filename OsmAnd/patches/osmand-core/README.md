# Flight geometry integration

The Smart build applies `model3d-marker-elevation.patch` first, followed by
`flight-volumetric-geometry.patch`. Both were checked against OsmAnd-core
`c3cd29673dcb7118af8e5371aff33a4c421a246e`.

The build copies `FlightTubeMesh.h` to `core/src/Map/` and
`FlightVectorLineBridge.cpp` to `core/wrappers/java/` before CMake runs. Changes
to either patch or either source file invalidate the native library cache.

## Volumetric flight route

`FlightVectorLineBridge.enableTube(line)` opts in only the flight route. Other
OsmAnd lines, including the aircraft tether, retain their existing rendering.
The small JNI bridge accesses SWIG's protected shared-pointer handle from the
same Java package; it does not use reflection or change the upstream Java AAR.
Its Java name is explicitly retained by ProGuard and CI checks that the exported
native function is present even when the library comes from cache.

The tube uses the native vector line's existing visible segments, sampled points,
absolute heights, map origin, zoom width, tessellation and projection. A closed
12-sided cross-section replaces the flat ribbon. Neighbouring sections share
vertices; the two ends are capped. The radius is converted from map units to
metres separately at each sample's latitude, without converting or offsetting
the centreline height. Opaque depth writes provide correct self-occlusion.

`FlightTubeMesh.h` contains the standalone geometry algorithm. The tests in
`OsmAnd/test/native/FlightTubeMeshTest.cpp` cover closed edges, preserved centres,
equal top/side diameters, bends, climbs, vertical segments, duplicate samples,
reversals, invalid values and the bounded vertex count of a 4,000-point leg.
The workflow runs these tests with address and undefined-behaviour sanitizers.

## Recorded point altitude

`MapMarker_P::applyChanges()` already transfers the height and elevation scale
to billboard pin icons. Their creation path previously omitted both values.
The patch initializes them in `createSymbolsGroup()`, matching the update path.
Consequently stationary recorded points and photo pins start at their supplied
absolute altitude without requiring an artificial marker update.

## Local check without compiling an APK

From the Android repository root, with a temporary output directory inside it:

```sh
mkdir -p .work-flight-check
clang++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  OsmAnd/test/native/FlightTubeMeshTest.cpp -o .work-flight-check/tube-test
.work-flight-check/tube-test
```

The standalone tests do not validate phone rendering or Android integration;
those still require compiling the patched native library and testing an APK.
