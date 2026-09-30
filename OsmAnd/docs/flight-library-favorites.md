# Flight library favorites and compact rows

Flight rows have a star, a one-line name and a short date/photo-count/storage caption. Secondary actions are in the overflow menu; opening a local row still opens the flight directly. Ordinary rows target 54 dp. GPS-off captions, logical IDs and tile totals no longer occupy list rows. Recording, schedule, GPS-error and cloud-version warnings remain visible; larger fonts and warnings can increase row height.

Favorites are personal preferences on this device. They are persisted separately from journals in `flight-library-favorites` SharedPreferences, so starring a flight or photo does not dirty its journal or change its cloud revision. The star is filled and gold when selected, with checked semantics and localized add/remove descriptions.

A flight uses its stable logical cloud ID (its local ID before the first upload). Downloaded copies therefore share the same favorite, including when only their saved cloud binding is available. Photos use their attachment IDs, independently of flight favorites and filenames.

The star in the library toolbar filters the current All/Planned/Past list together with its existing location filter. The active-recording strip remains visible. Photo+ has stars on each photo and a favorites filter for accepted photos. Pending photo imports remain visible until confirmed or discarded, so batch confirmation never silently includes photos hidden by that filter. Sorting stays chronological.

Preference loading runs off the UI thread. Star controls are disabled until loading finishes, preventing an initial load from overwriting a rapid toggle. SharedPreferences applies writes asynchronously and updates the in-memory UI immediately.

`FlightFavoritesTest` uses only synthetic identities to verify first uploads, downloaded copies, server-only un-starring, renamed flights and independent photo/flight namespaces. Run it through `OsmAnd/test/standalone/check-flight-preparation.sh <dependency-directory>`. Standalone checks do not validate APK rendering or Android preference storage on a device.
