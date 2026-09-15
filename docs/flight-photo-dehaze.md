# Non-destructive aerial-photo dehazing

Photo Plus → a photo → **Retouche** contains **Dévoiler**, a continuous strength slider,
**Profondeur**, and an **Original** comparison toggle. Dehazing is off for existing
photos. Enabling it starts at 55%; nothing changes the original JPEG or calibration.
Existing brightness, contrast, saturation and white-balance adjustments run after dehazing.
The corrected image is used by the landmarks pane, gallery, fullscreen preview and
the fixed photo plane in Hublot.

## Image and geometry

`PhotoDehaze` estimates atmospheric light and transmission using the dark-channel
prior on a bounded preview, refines transmission with a guided filter, then recovers
colour in linear light: `J = (I - A) / t + A`. Strength scales optical depth rather than
adding arbitrary contrast. Minimum transmission is 0.22 to limit amplification of noise.
The implementation is self-contained Java, without a model or native library dependency.

When a photo has a saved manual alignment or a reliable landmark fit,
`FlightPhotoDepth` projects currently loaded terrain onto a small image-space depth
grid. It uses the same `FlightPhotoProjection` and `PhotoPlaneGeometry` as the GL
photo rectangle, including WGS84 origin, eye altitude, FOV, aspect, roll, scale and
offsets. A perspective-correct z-buffer chooses the nearest visible surface. Unknown
terrain, unavailable flat placeholders and sky are not assigned invented distances.

The chord from terrain to eye is integrated with a simple exponential density
profile (8 km scale height), including an Earth-curvature correction. This gives an
equivalent sea-level optical path, not a measurement of weather or aerosols. Image
evidence estimates an extinction coefficient; known-depth pixels blend 60% geometry
with 40% image evidence. Unknown-depth pixels retain image-only processing. The
coverage percentage and approximate metric range are shown beside the controls.

## Lifecycle, persistence and performance

- `FlightPhotoTreatmentJson` stores the recipe and small depth grid in the photo's
  journal metadata. The existing archive/cloud transport carries these fields; no
  satellite/terrain cache or replacement image is added to an upload.
- A signature of photo optics invalidates the grid after recalibration. Current
  viewing direction and replay time do not affect it. Retouche recomputes geometry
  after 700 ms of stability when terrain is available; **Recalculer** retries.
- Loaded coverage accumulates for the same photo signature; a partial scene cannot
  wipe earlier depth samples. **Charger dans Hublot** opens the existing terrain loader
  at the photo when coverage is missing. Return to Retouche to recompute.
- Compose and GL use the same CPU processor. Sliders debounce by 180 ms and cancelled
  work cannot publish over a newer result. Processing never runs on the UI or GL
  thread. The GL queue includes the recipe in the texture key and retains the previous
  texture until replacement. Corrected GL images are capped at 2048 pixels per side
  with explicit working-memory reservation; regular previews are smaller.
- GPU colour matrices do not repeat dehazing. Strength zero preserves the original
  pixels. Contrast adjustments cannot move the plane or the user's landmark pairs.

This is a useful prior, not exact atmospheric inversion. Reflections, tinted windows,
clipped channels, nonuniform haze, bright snow/clouds and JPEG processing can defeat
the assumptions. Reduce strength if halos/noise or unrealistic colours appear.

## Verification and references

`PhotoDehazeTest` checks synthetic blue-haze recovery, analytic transmission inversion,
depth-dependent strength, alpha, identity, unknown-depth fallback, tiny/uniform images
and cancellation. `FlightPhotoDepthTest` checks metric distance, occlusion, sky masks,
roll/offset/scale, density integration, origin changes, stale-profile invalidation,
coverage accumulation, backwards compatibility and JSON round trips. These run in CI;
they are not a claim of visual validation on the user's phone or photographs.

- [He, Sun, Tang: dark-channel prior, CVPR 2009](https://people.csail.mit.edu/kaiming/publications/cvpr09.pdf)
- [He, Sun, Tang: guided image filtering](https://people.csail.mit.edu/kaiming/eccv10/index.html)
- [NASA atmospheric-model discussion](https://psg.gsfc.nasa.gov/helpatm.php)
