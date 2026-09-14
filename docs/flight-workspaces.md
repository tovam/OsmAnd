# Flight workspaces and regression checks

The entry page has three destinations. Navigation is defined once in
`FlightWorkspaceNavigation`, then rendered in the same bottom bar on every page.

| Workspace | Available pages | Source of positions |
| --- | --- | --- |
| Past flights | Map, Window, Tiles, Sensors, Photo+, Journals | Imported or recorded samples |
| Planned flights | Preparation, Map, Window, Tiles | Display-only great-circle simulation |
| Current flight | Map, Window, Tiles, Sensors, Photo+, Recorder | Recorder service plus a separate predicted future |

The current-flight entry is disabled unless the recording service is running.
Opening another journal does not stop the service or cause its updates to replace
the selected journal. Starting a prepared flight requires confirmation. Creating
a new plan or previewing it does not start GPS recording.

## Data boundaries

- A simulation is never saved as recorded samples. Saving a prepared journal
  saves its plan, shared tile references and its original (empty) recorded trip.
- `liveTimeline` is display-only. The recorder, exports and photo associations
  continue to use the measured `trip`.
- Scrubbing a live flight retains an absolute cursor time when a new GPS fix
  rebuilds the future. Map and Window both provide Return to live.
- Predicted samples have no fabricated satellite count, accuracy, sound or
  vibration readings, including interpolation immediately after the last fix.
- Photo associations convert between recorded point positions and display time,
  never between the two timelines' incompatible progress fractions.
- CameraX's capture-start callback stores the last measured GPS fix, magnetic
  field and rotation vector alongside the photo. GPS wall time and sensor
  monotonic timestamps are retained separately. These are not guaranteed to be
  simultaneous with the physical exposure; missing readings remain missing.

## Photo editor visibility

The native Canvas view previously had no explicit clip and could draw outside
its measured rectangle over the Compose toolbar. It is now clipped in Canvas,
with a bounds outline, and at the Compose viewport. The toolbar is outside that
viewport. Selection is through numbered buttons, not hit-testing photo markers.
The photo and map keep their separate transforms when switching tabs.

## Map geometry

The route keeps its original coordinates and absolute heights. A black native
centreline is created independently of the optional tube mesh, before enabling
the JNI tube extension. The red vertical tether is 2.7 dp wide (previously 1.7).
The centre lock changes only the geographic target; it does not call the API
that cancels native zoom animations. The GPS-point toggle is on Map, not Sensors.

## Verification

Standalone synthetic checks cover navigation, prediction, photo timestamp
mapping and capture JSON round trips:

```sh
bash OsmAnd/test/standalone/check-flight-preparation.sh /path/to/test-libraries
```

The directory contains `ktfmt.jar` (with the Kotlin compiler), `json.jar`,
`coroutines.jar`, `junit.jar` and `hamcrest.jar`. Native geometry checks are in
`OsmAnd/test/native/FlightTubeMeshTest.cpp`. Photo action/transform/solver checks
are in `OsmAnd/test/standalone/Photo*Test.java`.

These checks do not replace an Android build or device rendering validation.
Device acceptance must verify:

1. With an eight-point synthetic photo, the complete toolbar stays visible while
   panning, rotating and zooming; numbered selection, move, delete and clear work.
2. Switching Photo/Satellite and returning preserves map location and zoom.
3. A synthetic elevated track shows the black connecting path with GPS points
   both enabled and disabled, from above and with a tilted camera.
4. Locked map centre does not move during pan/pinch/rotate/tilt; zoom and angles
   still change. Unlock restores free panning.
5. A saved plan reopens under Planned flights with no recorded samples. Preview
   has no Live, Sensors or Photo+ tabs. A completed journal has no Live tab.
6. While a recording continues, scrub into the past and future in Map/Window;
   new fixes must not steal the cursor. Return to live resumes following.
7. A live photo's persisted capture object survives journal save/export/import,
   including absent sensors and independently timestamped measurements.
