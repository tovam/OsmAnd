# Hublot rendering and streaming audit — 2026-09-13

## Scope and conclusion

Source-code audit only. No user journals, photographs, caches, device logs or remote
servers were inspected. No terrain streaming changes were made for this audit.
The separate requested photo-rendering corrections do change the photo draw pass.
The findings below establish code paths and risks, not measured phone timings or a
proven explanation for every previously reported blank tile.

The architecture already has a central coordinator (`FlightSceneStreamingEngine`),
retained CPU/GPU resources, progressive publication, a coarse-terrain-first pass,
parallel workers and independent texture/refinement lanes. It is not rebuilding
everything on every finger movement. The expensive work is nevertheless still
organized around scene-wide passes and whole-tile composites.

Current flow:

1. Aircraft position, settled gaze and settings become a desired scene.
2. `FlightTerrainRepository.loadScene` resolves cached assets, coarse DEM, then
   refined DEM and imagery. `FlightTerrainMeshBuilder` constructs geometry.
3. Partial scene snapshots enter `FlightTerrainView`, which uploads buffers and
   textures, renders terrain and optional cast shadows, then draws the photo with
   the same camera matrix.

## Five prioritized proposals

### 1. Make the render thread render, not prepare assets

**Observed:** `FlightTerrainView.createTexture` reads and decodes image files on the
GL thread. `estimatedTextureFileBytes` reads the image header again. Geometry
replacement synchronously copies vertex/index arrays and uploads every changed
buffer in `replaceRenderMeshes` / `cachedGeometry`. Texture uploads have count/byte
limits, but these are not elapsed-time limits; geometry replacement has no similar
per-frame bound. Photo decoding is also synchronous on that thread.

**Change:** decode and prepare bounded staging buffers on workers; submit ready
resources to GL with a per-frame time/byte budget. Keep the last good resource until
the new handle is ready. Do not enqueue unlimited decoded bitmaps.

**Expected benefit:** gestures remain responsive while quality improves, including
when all source files are already on disk.

**Verify:** synthetic cache-hit and cache-miss scenarios, frame-time p50/p95/p99,
decode/upload durations, maximum prepared-but-not-uploaded bytes. A frame budget
is a target to measure, not a claim that a single driver upload can be interrupted.

### 2. Schedule persistent tile jobs instead of restarting scene-wide passes

**Observed:** `FlightSceneStreamingEngine.startDesiredDemand` cancels the active
`loadScene` job and starts another one. Disk/RAM caches survive, but useful
unfinished work and prioritization belong to the cancelled pass. Coarse terrain
is globally resolved/retried before phase 2 starts. Geometry workers return their
results only after the whole `FlightTerrainCpuScheduler.map` batch completes.
Each progressive publication calls `buildBaseScene` and refinement assembly again.

**Change:** retain keyed resource jobs (tile, source revision, kind and LOD), update
their priorities as demands change, and cancel only jobs no longer wanted. Publish
completed meshes independently. Prioritize nearby coarse coverage, then nearby
refinement and Standard textures; let distant coarse coverage continue with a
reserved share so neither lane starves the other. Preserve the existing 700 ms
aircraft scrub and 1.5 s gaze debounce for expensive new detail requests.

**Expected benefit:** useful work survives a small move, one slow far tile does not
hold up nearby improvements, and the first useful terrain appears earlier.

**Verify:** jump between two synthetic flight positions during a slow download;
record time to coarse terrain beneath the new position, duplicate jobs, cancelled
useful jobs, queue wait by distance, and starvation of distant coarse coverage.

### 3. Stream high-resolution image tiles individually, without giant mosaics

**Observed:** `ensureSatelliteTexture` retrieves every child tile, builds a large
bitmap, compresses it into a JPEG, then the renderer decodes the JPEG again.
Ultra+++ has `zoomDelta = 5`, i.e. 32 × 32 = 1,024 child tiles for one composite.
For 256-pixel source tiles, the intermediate RGB565 bitmap alone is 128 MiB,
before decoded children, compression and GPU upload. Hardware texture limits can
also cause the result to be downsampled. Detail appears only once the composite
is complete.

**Change:** maintain a texture quadtree/page atlas independent of DEM geometry;
replace individual pages as children arrive, using an available parent texture
for missing children. Keep geometry unchanged when only image detail changes.

**Expected benefit:** detail can appear piece by piece; lower peak memory and less
decode/compress/decode work. Near retained high-quality pages do not need to be
replaced by a lower quality after a quality-setting change.

**Verify:** latency to the first detailed page, peak RAM/GPU memory, downloaded
bytes versus displayed detail, and no downgrade within the retained 50 km region.

### 4. Publish replacements transactionally and recover per tile

**Observed:** `cachedGeometry` releases an old GPU mesh before the new upload is
known to have succeeded. If replacement throws, `onDrawFrame` releases the whole
scene and marks that generation as uploaded, so it is not retried until a new
generation arrives. Scene, sample and view state are also separately published
volatile fields. They can be observed from different updates. CPU-side retention
already avoids replacing known terrain with a missing-data plane, but that
protection does not cover every GPU replacement failure.

**Change:** prepare/validate a candidate, atomically swap the successful tile, then
retire its predecessor. Keep failed tiles on the last good parent/mesh and expose
an explicit retry state. Publish a coherent frame snapshot for scene origin,
aircraft and camera. Version geometry dependencies, including neighboring and
parent DEM edges, and test shared borders before replacing a refinement.

**Expected benefit:** an upload failure does not blank the world; asynchronous
updates cannot mix coordinate frames; missing DEM is visibly distinguishable
from genuinely flat zero-altitude terrain.

**Verify:** inject upload failure, missing DEM, delayed neighbor DEM, cancellation
and coordinate-origin reset. Assert retained visible coverage and consistent
shared-edge heights. This is a proposed validation strategy, not a diagnosis of
the user's actual tile data.

### 5. Enforce a global memory/residency budget and render only useful geometry

**Observed:** GPU geometry and texture budgets are each nominally 128 MiB, but
eviction skips all active/protected resources and can stop above budget. "Active"
means part of the scene, not necessarily on screen. Terrain and shadow passes
iterate the mesh collection without view/light-frustum culling. CPU terrain,
geometry, composites, photos and GPU copies have separate lifetimes/budgets.

**Change:** account for all resident and staging allocations under one manager.
Distinguish downloaded, CPU-ready, GPU-resident and visible resources. Cull by
camera/light bounds and give near visible surfaces the GPU budget first. Preserve
downloaded detail and the no-downgrade-within-50-km policy; retiring an invisible
GPU handle must not erase its high-quality disk asset or lower its requested LOD.
If the visible working set cannot fit, expose memory pressure rather than silently
breaking the quality-retention promise.

**Expected benefit:** fewer memory-pressure stalls/crashes and less work on terrain
that contributes no visible pixels, without deleting the journal's offline assets.

**Verify:** full 360-degree sweeps at Ultra+++ with a bounded staging budget;
record total resident bytes, visible versus submitted meshes, shadow draw calls,
and quality retention when returning to a previous direction.

## Separate photo correction implemented alongside this audit

The photo was already an OpenGL quad, but LINKED mode rebuilt its reference pose
from the live camera and also applied screen-space compensation. A 2D Compose
fallback existed for photos without initialized calibration.

The correction retains the saved world pose, removes compensation and the HUD
fallback, persists the calibration viewport aspect, and uses the terrain's same
MVP/depth buffer. Continuous rotation is around the photo centre. A photograph is
a planar object, not a reconstructed 3D mountain surface: translating the eye can
therefore reveal physically expected parallax relative to real terrain.

Standalone synthetic geometry checks (no Android SDK needed):

```sh
mkdir -p .work-photo-geometry-check
javac -d .work-photo-geometry-check \
  OsmAnd-java/src/main/java/net/osmand/util/PhotoPlaneGeometry.java \
  OsmAnd/test/standalone/PhotoPlaneGeometryTest.java
java -cp .work-photo-geometry-check PhotoPlaneGeometryTest
```

Six checks passed locally: rectangle placement, continuous rotation, clockwise
direction, origin translation, centre-preserving zoom, and ray registration across
camera yaw/FOV changes. Kotlin regression tests were updated for immutable LINKED
calibration, fractional photo timeline positions, and missing camera metadata.
The Kotlin/Android suite and device rendering were not executed in this audit;
no APK build was requested or launched.
