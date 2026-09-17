# Calibrated photo depth and POI projection (OSMAX-28 study)

## Current geometry

The photo editor already has a calibrated world-space camera model. A valid
manual alignment is preferred; otherwise `FlightPhotoAttachment.dehazeProjection()`
accepts only a non-weak seven-parameter fit with RMS at most 8
(`OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightPhotoDepth.kt:190-238`).
`FlightPhotoProjection.vertices()` converts the pose to the shared WGS84 local
frame, constructs forward/right/up camera bases and creates the fixed photo
rectangle (`FlightPhotoDepth.kt:13-68`). The renderer applies the same pose with
`Matrix.perspectiveM` and `Matrix.multiplyMM` (`OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightTerrainView.kt:470-502`); these are the Android matrix operations documented in the
[Android OpenGL `Matrix` reference](https://developer.android.com/reference/android/opengl/Matrix).

`FlightPhotoDepth.calculate()` is a low-resolution terrain depth pass, not a
POI projection pass. It projects loaded mesh vertices onto the photo plane,
rasterises terrain triangles, uses perspective-correct inverse-depth
interpolation and keeps the nearest surface in a z-buffer
(`FlightPhotoDepth.kt:253-368`). Missing terrain, sky and out-of-range cells
remain `null`; the profile is only 8–64-ish pixels per axis and stores an
atmospheric-path estimate, not a full per-pixel 3D position. The terrain mesh
itself reports whether its elevation is available
(`OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightTerrainModels.kt:100-117`).

The existing landmark view is a 2D Mercator map overlay
(`OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightPhotoLandmarkView.kt:473-604`),
and the inspection renderer draws camera/trajectory spheres with GL depth
testing (`OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightInspectionRenderer.kt:60-130`).
There is currently no arbitrary-POI-to-photo projection or terrain-occlusion
decision in these paths.

## Future projection design

For a future altitude- and occlusion-aware POI overlay, keep the following
steps explicit:

1. Require a valid calibrated pose and the photo's optical signature. Convert
   the POI latitude/longitude/altitude into the same local frame with
   `FlightTerrainCoordinates.toLocal()` (`FlightTerrainCoordinates.kt:7-30`).
   If a POI has no reliable altitude, use its terrain elevation only as an
   explicitly uncertain ground point; do not invent building or tower height.
2. Form the calibrated camera basis and project the 3D POI onto the photo
   plane/perspective. Reject non-finite points behind the camera or outside the
   image. Keep the optical scale, crop, offset and rotation consistent with
   `FlightPhotoProjection.signature()`; the viewing eye must not silently alter
   the fixed photo geometry.
3. Cast the camera-to-POI segment against the loaded terrain mesh, or compare
   its range with the nearest-surface depth at the projected pixel. Render a
   POI only when terrain is known and the POI is in front of it, with a
   documented tolerance for DEM and pose error. Unknown terrain must produce
   “occlusion unknown”, not “visible”.
4. Use POI height and any known 3D footprint when available. A point at ground
   elevation cannot represent a building facade, mast or tree canopy.

This is a proposed pipeline, not an implementation. The existing depth pass
can provide the terrain-side raster and z-buffer strategy, but its coarse
profile is insufficient for precise per-pixel labels without refinement or a
ray/mesh query. Any implementation must preserve the physical camera/lens
calibration and the same curved-earth coordinate frame.

## Accuracy and visibility limits

- Sky and unloaded terrain have no intersection. A POI in those cells must be
  hidden or marked uncertain rather than projected against a flat fallback.
- DEM resolution, missing tiles, vertical datum differences and mesh
  approximation can move the terrain intersection. Terrain depth cannot hide
  an unmodelled building, vegetation or other object; POI metadata may also be
  only a 2D point.
- Calibration assumes a centred principal point, square pixels and no fitted
  lens distortion. RMS is calculated from decoded preview pixels and is not a
  confidence interval; cropped photos, distortion and poorly spread
  correspondences can bias the pose (`docs/flight-photo-calibration.md:19-27`).
- A good reprojection error does not establish global positional correctness.
  Labels should expose or retain the calibration/terrain uncertainty instead
  of implying survey-grade accuracy.

No new POI fetcher, network dependency or unsupported camera-frame assumption
is required by this study.
