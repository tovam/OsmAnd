# Flight cloud across phones

This note records source-level behavior only. It is not a device or network test plan and does not
claim that any particular phone configuration has been exercised.

## Identity

One downloaded server archive always becomes a new local journal. The new local identifier prevents
a download from overwriting edits or an active recording already on the receiving phone. The client
stores a binding from that local identifier to the stable server identifier and the exact server
revision it downloaded. A later upload uses the bound server identifier, not the downloaded local
identifier.

Consequently, the same flight has two identifiers on a receiving phone:

- Local journal ID: private to that phone and deliberately new after each download.
- Cloud flight ID: stable server identity used for publication and revision checks.

The cloud ID is the identity to compare between phones. It is not safe to change the import rule to
reuse it as the local journal filename.

## Recording and scheduling

Flight GPS measurements are appended to a per-local-journal recording log. Starting a real flight
selects the local journal currently open on that phone; it does not redirect writes to another
phone's local storage.

Cloud archives clear the automatic-departure flag on both writing and importing. Downloading a
flight therefore does not arm or start GPS recording on the receiving phone. Each phone must start
or explicitly arm its own recording.

## Concurrent publications

Publication carries the cloud revision previously downloaded or published by the local copy. The
server accepts an update only when that revision still matches its current head. If phone A
publishes first, a recorded change from phone B based on the older revision receives a conflict;
the server archive and B's local recording remain intact.

The server preserves omitted photos in a successful update, but it does not merge independent GPS
tracks. There is intentionally no unconditional overwrite operation. To inspect a newer server
version, download it as another local copy and reconcile records deliberately outside of automatic
publication.

## Source references

- `OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightCloudArchive.kt`
- `OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightCloudController.kt`
- `OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightRecordingService.kt`
- `server/flight_cloud/app.py`
