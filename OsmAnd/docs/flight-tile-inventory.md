# Flight tile inventory and inspection

## Completed changes

- [x] One source-file catalog shared by all flights and repository instances.
- [x] Initial header inventory off the UI thread; subsequent counts are metadata lookups and file-change events.
- [x] Per-source, per-zoom required/present/missing/unchecked counts and byte estimates.
- [x] Shared-store totals distinguish unrelated files from files required by the selected flight.
- [x] A common Files dialog from preparation, Tiles and Storage, in past/planned/live modes.
- [x] Downloads require an explicit action; zero missing offers Verify / repair instead of Download.
- [x] Missing-file transfers precede verification of cached files, maintaining coarse-first order within each group.
- [x] Archive tile imports notify the catalog; corrupt/deleted file notifications remove their coverage.
- [x] Grid level no longer moves the camera or chooses image resolution.
- [x] Whole route fits the route, not the bounding box of its coarse tiles; imported tracks use GPS coordinates.
- [x] Satellite, terrain and availability are separate display layers.
- [x] Squares show all cached and required levels, including partial descendant coverage and files shared from other flights.
- [x] Pinch/pan transforms the current frame immediately; metadata and images load on workers.
- [x] Coarse ancestors are included for both sources in legacy/imported corridors too.

## Tiles tab usability audit and redesign

The previous screen had ten related problems:

1. A title, dashboard and several command rows competed with the map for space and attention.
2. Text at 8–12 sp and small compact actions made reading and tapping difficult.
3. Source selection used loosely arranged links, without a clear selected state.
4. Permanent grid lines, raster zoom labels and four technical lines per cell obscured imagery.
5. Camera zoom, inspection grid level and source-file resolution were easy to confuse.
6. Coverage relied on colours, with a cramped legend and no selected-cell highlight.
7. A missing inventory could appear as zero bytes/files rather than an unknown state.
8. Downloading required finding the Files dialog; pause/resume and readiness were poorly surfaced.
9. Cell details used compressed technical level notation instead of readable coverage descriptions.
10. Files used a six-column table on phone widths, fixed gigabyte formatting and weak empty states.

The new screen keeps imagery central. Satellite, Terrain and Coverage have separate 48 dp minimum selection targets. Route framing, grid level and optional grid visibility share a scrollable map toolbar. Zoom buttons supplement pinch gestures. A distance scale follows camera zoom and Mercator latitude; the current flight position is marked separately from its route.

Imagery starts without grid clutter. Coverage uses distinct colours plus symbols, a scrollable legend and a selection outline. Inspecting a cell opens readable source cards with complete, partial, absent and pending states. Local imagery loading, unavailable images and decode failures have explanatory messages. Terrain colours interpolate between altitude stops.

Readiness and an explicit Download / Pause / Resume / Verify action remain below the map. Unknown inventories, inventory errors, empty routes and offline simulation disable new transfers. Downloads can still be paused during an inventory refresh. Header presence remains distinct from full pixel verification.

Files now uses source/scope selectors and scrolling level cards. Measured storage, estimated remaining bytes, confirmed missing and unchecked counts have separate labels. Sizes use B/kB/MB/GB. Shared totals follow the selected source and remain unknown or lower bounds while scanning. Help text and all new labels are available in English and French.

## Semantics

Source files are keyed by `(source, zoom, x, y)`. Rendering composites and GPU meshes do not count as source files. Storage paths are unchanged; no existing files are moved or duplicated.

`Present` means a readable 256 × 256 image header, not a guarantee that every pixel has been decoded. Download completion and **Verify / repair** perform the full check. Unknown counts are never labelled missing, and a new download cannot start while the inventory is incomplete. Estimated remaining bytes use observed sizes for that source and zoom (at least eight samples), falling back to explicit per-source defaults.

The shared catalog is in memory, rebuilt once per process from the two managed source roots. It is not a periodic filesystem scan. Cancellation preserves completed directory work. Managed downloads, removals and imports update it immediately. A removed or downloaded file wins over an older in-flight inventory read.

The camera is expressed in normalized Mercator coordinates. Selecting **Grid zN** affects inspection squares only. Images use available levels appropriate to the camera, without labels over each raster. Source levels live in cell details and Files. Cached coarse images remain behind detailed images. There is no download from inspecting or moving this view.

Each zoom increment divides one tile into four. Details describe partial coverage as a count of subtiles at a source level. Coarse parents and fine children can coexist. Inspection squares target at least 56 dp and remain bounded to 512 visible/nearby cells. The actual aggregation level is explicitly displayed; zooming in reveals the requested finer grid. Tapping a cell displays untruncated level lists, updated when inventory changes.

## Validation

`FlightTileFilesTest` uses synthetic coordinates, file sizes and events: level accounting, estimates, unknown/zero-download states, shared-file reuse, stale scan races, priority order, camera invariance, zoom anchoring, date-line handling, imported tracks, partial coverage and bounded aggregation.

`FlightTilePresentationTest` adds synthetic checks for truthful readiness, transfer eligibility, pause/resume, inventory errors, empty routes, header-versus-pixel verification, adaptive units, readable bounded grid cells, zoom/latitude scale changes and altitude interpolation.

Run through `OsmAnd/test/standalone/check-flight-preparation.sh <dependency-directory>`. A device check is still required for actual touch latency, font scaling and rendering. Standalone checks do not replace an APK/device test.
