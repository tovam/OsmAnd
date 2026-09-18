# Flight geometry integration

The Smart build applies `model3d-marker-elevation.patch` first, followed by
`flight-volumetric-geometry.patch`. Both were checked against OsmAnd-core
`c3cd29673dcb7118af8e5371aff33a4c421a246e`.

`basemap-overzoom-fallback.patch` is applied last. The complete stack was also
checked against `ee8cbbc6d952f6a625e89c7e0e0467fb6adbd015`. The workflow follows
upstream rather than pinning either revision, so it checks every patch before
applying it and publishes failures as check annotations.

The build copies `FlightTubeMesh.h` and `BasemapOverzoom.h` to `core/src/Map/` and
`FlightVectorLineBridge.cpp` to `core/wrappers/java/` before CMake runs. Changes
to any patch or supplied source file invalidate the native library cache.

## Volumetric flight route

`FlightVectorLineBridge.enableTube(line)` opts in only the flight route. Other
OsmAnd lines, including the aircraft tether, retain their existing rendering.
The small JNI bridge accesses SWIG's protected shared-pointer handle from the
same Java package; it does not use reflection or change the upstream Java AAR.
Its Java name is explicitly retained by ProGuard and CI checks that the exported
native function is present even when the library comes from cache.

The tube uses the native vector line's existing visible segments, sampled points,
absolute heights, map origin, zoom width and projection. A closed
12-sided cross-section replaces the flat ribbon. Neighbouring sections share
vertices; the two ends are capped. The radius is converted from map units to
metres separately at each sample's latitude, without converting or offsetting
the centreline height. Opaque depth writes provide correct self-occlusion.

`FlightTubeMesh.h` contains the standalone geometry algorithm. The tests in
`OsmAnd/test/native/FlightTubeMeshTest.cpp` cover closed edges, preserved centres,
equal top/side diameters, bends, climbs, vertical segments, duplicate samples,
reversals, invalid values and the bounded vertex count of a 4,000-point leg.
The workflow runs these tests with address and undefined-behaviour sanitizers.

Tubes are cut at tile boundaries, with coarse extra subdivisions at low globe
zooms, but never against the terrain's heixel grid. The latter could multiply a
4,000-point tube past the core's 4M-vertex safety limit, causing
`generatePrimitive()` to fail and hide the whole route. Disabling the separate
flat ribbon exposes that failure instead of masking it. The GPS centreline and its absolute
altitudes do not require DEM subdivision.

`run_flight_tube_pipeline_test.py` compiles the checked-out core's actual
`GeometryModifiers::cutMeshWithGrid()` against minimal POD type substitutes. It
reproduces that rejection with the old 64-cell grid and checks that the bounded
grid preserves the complete opaque elevated mesh, its per-tile draw counts and
its vertical diameter. CI checks that the patched `VectorLine_P.cpp` really uses
this policy before running the test. This is a native mesh-pipeline test, not a
GPU screenshot test.

## Recorded point altitude

`MapMarker_P::applyChanges()` already transfers the height and elevation scale
to billboard pin icons. Their creation path previously omitted both values.
The patch initializes them in `createSymbolsGroup()`, matching the update path.
Consequently stationary recorded points and photo pins start at their supplied
absolute altitude without requiring an artificial marker update.

## World basemap without a country download

When a tile lacks regional cartography, the native renderer keeps the world
basemap's last available level rather than applying street-level rules that
discard its coarse features. Projection and tile coordinates still use the
requested zoom: geography is enlarged in place, not moved to another tile.
Detailed country data takes precedence; routing-only or contour-only overlays
do not incorrectly suppress the background.

The patch clamps both ordinary map-reader paths for supplementary world-map
sections to their source zoom and parent bounding box, matching the existing
main-basemap read path. Primitive styles and captions use coarse rules; detailed
road captions in the same tile retain their requested zoom. Shared caches keep
their original zoom/identity keys. The small `BasemapOverzoom.h` policy is tested
locally and in CI; `CheckBasemapOverzoomFallbackSource.cmake` verifies its real
call sites in the patched core, including the two readers and label evaluation.

This requires a world basemap on the phone. It does not invent streets or fetch
country maps automatically, and cannot provide geography if no basemap exists.
These source/policy tests are not a device rendering test.

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

When checking patches against downloaded source files inside this repository,
run Git from this repository root with `--directory=<fixture-relative-path>`.
Running `git apply` from a nested, untracked fixture directory can silently skip
patch paths because Git finds the parent repository. Use `--verbose` and run the
source checks against the resulting files; a successful exit code alone is not
evidence that a patch was actually applied.
