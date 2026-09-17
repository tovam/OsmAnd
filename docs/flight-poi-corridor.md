# Corridor-scoped offline POI data (OSMAX-26 study)

## Finding

OsmAnd's standard offline-download catalog is region-oriented, not route- or
corridor-oriented. `DownloadActivityType.NORMAL_FILE` is the regular map type,
and `DownloadResources.findIndexItemsAt(...)` resolves the `WorldRegion` objects
containing a point before selecting an `IndexItem`. The implementation also
knows about maps joined from regional subregions, but has no flight-corridor
geometry or POI-only download type (`OsmAnd/src/net/osmand/plus/download/DownloadActivityType.java:47-72`,
`OsmAnd/src/net/osmand/plus/download/DownloadResources.java:424-437,655-724`).

Therefore, the current app cannot request “POIs within this buffered flight
path” as a normal offline download. A corridor would be covered only by the
selected standard regions, which can include substantially more data than the
path and still cannot guarantee a route-specific cut.

## What the file format permits

An OBF is OsmAnd's offline vector-data format. The official format description
identifies separate map, POI, routing and related index sections, and the
format definition exposes repeated `poiIndex` entries:

- [OBF format overview](https://download.osmand.net/docs/technical/osmand-file-formats/osmand-obf/?current-os=ios)
- [OBF protobuf definition](https://github.com/osmandapp/OsmAnd-resources/blob/master/protos/OBF.proto)

This makes a separately generated POI-only OBF technically viable. It can be
imported as a custom map file: `ImportHelper` recognizes `.obf`/`.obf.zip`,
`ObfImportTask` copies it to the app path, and the resource manager reloads
indexes (`OsmAnd/src/net/osmand/plus/importfiles/ImportHelper.java:226-237`,
`OsmAnd/src/net/osmand/plus/importfiles/tasks/ObfImportTask.java:28-43`).
The imported file is indexed as custom data; it is not added to the standard
regional download catalog.

## Practical pre-flight workflow (external preparation)

1. Build a corridor polygon or buffered track from the flight plan. Choose the
   buffer from the aircraft's expected lateral error and the POI classes of
   interest.
2. Prepare an OSM/PBF extract clipped to that polygon, keeping the tags and
   names needed by the POI search.
3. Run OsmAndMapCreator's `generate-poi` (or `generate-obf` when map/address/
   routing sections are also required), optionally with a custom
   `poi_types.xml`.
4. Validate the resulting OBF and import it before the flight.

The official custom-map guide documents the POI section, `generate-poi`, the
OSM/PBF inputs and custom POI-type configuration:
[Create offline maps yourself](https://osmand.net/docs/technical/map-creation/create-offline-maps-yourself/?current-os=ios).
No in-app network downloader or corridor generator is implied by this study.

## Limits and recommendation

- A POI-only file does not provide map rendering, address search or routing;
  those require the corresponding sections or the normal regional data.
- Coverage and freshness depend on the external extract and POI tag rules.
  Regeneration/import is a manual update path.
- OBF indexes use regional bounds and boxes, so a narrow corridor can still
  carry edge data; overlapping it with a regular region can expose duplicates.
- The official OBF guidance recommends keeping a file below 2 GB and provides
  `binary_inspector` for combining, splitting or removing sections. Large
  extracts therefore need deliberate partitioning.

This is a feasible future preparation workflow, not a current OSMAX feature.
The recommended product change, if needed later, is a dedicated external
corridor-extract/OBF preparation tool and explicit custom-file provenance—not
an attempt to reinterpret the existing region download selector.
