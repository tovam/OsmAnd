# Flight recording outputs

This note describes the current source implementation. It does not claim that recording has been
verified on a physical device.

## Flight Mode recording

`FlightRecordingService` receives flight GPS fixes and writes them through
`FlightRecordingStore`. The recorder's durable data is a private append-only flight log, keyed by
the local flight journal ID. Flight Mode does not call `SavingTrackHelper`, does not enable the Trip
Recording plugin, and does not create an ordinary OsmAnd recorded track.

When a flight archive is exported, `FlightJourneyStore` derives `track.gpx` from the flight journal
and includes it with the journal metadata, photos, and selected offline assets. Cloud publication
uses the same derived GPX only when the flight has recorded samples.

## Ordinary OsmAnd tracks

Ordinary track recording is independently owned by the Monitoring/Trip Recording feature and its
`SavingTrackHelper`. It can be enabled separately by the user, but Flight Mode neither starts nor
stops it. If both are active, they are separate recordings with separate lifecycle and storage.

To obtain a GPX representation of a flight, use the Flight Mode export action. To keep a parallel
ordinary OsmAnd track, explicitly enable ordinary trip recording; Flight Mode will not enable it on
the user's behalf.

## Source references

- `OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightRecordingService.kt`
- `OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightRecordingStore.kt`
- `OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightJourneyStore.kt`
- `OsmAnd/src/net/osmand/plus/plugins/monitoring/SavingTrackHelper.java`
