# Flight tile inventory and inspection

## Completed changes

- [x] One source-file catalog shared by all flights and repository instances.
- [x] Initial header inventory off the UI thread; subsequent counts are metadata lookups and file-change events.
- [x] Per-source, per-zoom required/present/missing/unchecked counts and byte estimates.
- [x] Shared-store totals distinguish unrelated files from files required by the selected flight.
- [x] A common Files dialog from preparation, Tiles and Storage, in past/planned/live modes.
- [x] Confirmation before manual downloads; zero missing disables Download, with a distinct Verify / repair action.
- [x] Missing-file transfers precede verification of cached files, maintaining coarse-first order within each group.
- [x] Archive tile imports notify the catalog; corrupt/deleted file notifications remove their coverage.
- [x] Grid level no longer moves the camera or chooses image resolution.
- [x] Whole route fits the route, not the bounding box of its coarse tiles; imported tracks use GPS coordinates.
- [x] Satellite, terrain and availability are separate display layers.
- [x] Squares show all cached and required levels, including partial descendant coverage and files shared from other flights.
- [x] Pinch/pan transforms the current frame immediately; metadata and images load on workers.
- [x] Coarse ancestors are included for both sources in legacy/imported corridors too.

## Semantics

Source files are keyed by `(source, zoom, x, y)`. Rendering composites and GPU meshes do not count as source files. Storage paths are unchanged; no existing files are moved or duplicated.

`Present` means a readable 256 × 256 image header, not a guarantee that every pixel has been decoded. Download completion and **Verify / repair** perform the full check. Unknown counts are never labelled missing, and downloading cannot be confirmed while the inventory is incomplete. Estimated remaining bytes use observed sizes for that source and zoom (at least eight samples), falling back to explicit per-source defaults.

The shared catalog is in memory, rebuilt once per process from the two managed source roots. It is not a periodic filesystem scan. Cancellation preserves completed directory work. Managed downloads, removals and imports update it immediately. A removed or downloaded file wins over an older in-flight inventory read.

The camera is expressed in normalized Mercator coordinates. Selecting **Grid zN** affects inspection squares only. Images use available levels appropriate to the camera, with their actual `z` labelled. Cached coarse images remain behind detailed images. There is no download from inspecting or moving this view.

Each zoom increment divides one tile into four. `z10(3/4)` means partial coverage by three child tiles; `z10` without a fraction means complete coverage of the square at that source level. Coarse parents and fine children can coexist. When the chosen grid would exceed 512 visible/nearby cells, its aggregation level is explicitly displayed; zooming in reveals the finer grid. Tapping a cell displays untruncated level lists.

## Validation

`FlightTileFilesTest` uses synthetic coordinates, file sizes and events: level accounting, estimates, unknown/zero-download states, shared-file reuse, stale scan races, priority order, camera invariance, zoom anchoring, date-line handling, imported tracks, partial coverage and bounded aggregation.

Run through `OsmAnd/test/standalone/check-flight-preparation.sh <dependency-directory>`. A device check is still required for actual touch latency, font scaling and rendering. Standalone checks do not replace an APK/device test.
