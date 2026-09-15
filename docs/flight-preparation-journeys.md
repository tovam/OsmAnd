# Flight preparation: user journeys and interaction contract

The local flight is the working document. Server publication, offline coverage and
automatic recording are independent operations, never prerequisites for opening
or editing it. A simulation is not a recording and must not fetch missing tiles.

| # | User goal | Actions in order | Visible outcome / failure |
|---|---|---|---|
| 1 | Find a planned flight | Flight tracking → Planned flights | Local rows first; server refresh must not block them. |
| 2 | Open a past flight | Past flights → Open | Read the chosen journal only; no upload or tile inventory. |
| 3 | Start preparing | Planned flights → New | A local draft, not a live recording. |
| 4 | Set the route | Pick departure → arrival on the map | Named endpoints, route and distance. |
| 5 | Change the route | Add / move / remove an intermediate stop | Geodesic legs between the chosen points. |
| 6 | Set the schedule | Departure date/time/UTC offset → arrival | Concrete local times and offsets; invalid fields identified. |
| 7 | Save the draft | Save on phone | Saved / saving / actual local error, independent of alarms and server. |
| 8 | Leave and return | Back to planned flights → reopen | Same route, times and quality settings. |
| 9 | Inspect the planned landscape | Test offline → Map / Window | Immediately enter the virtual route; no GPS recording or automatic departure. |
| 10 | Advance through the flight | Scrub / play / pause | Same replay controls in Map and Window. |
| 11 | Check real offline readiness | Test offline, including an imported recording | No asset downloads; absent terrain/imagery remains visibly absent. |
| 12 | Return from testing | Preparation | Preserve the draft; do not save simulated GPS points as a completed flight. |
| 13 | Choose the download coverage | Offline section → bands and detail | Tile counts, estimated bytes, already present / missing / failed. |
| 14 | Download / repair coverage | Download / pause / resume / verify | Pause is distinct from ready; failures remain retryable. |
| 15 | Enable automatic recording | Automatic departure → permission checks → Enable | Actual scheduled date/time (departure minus lead time), not merely a checkbox. |
| 16 | Change / cancel automatic recording | Edit schedule → Apply, or Disable | Show the currently armed time until a replacement succeeds; cancellation explicit. |
| 17 | Start a real flight early | Start recording → confirm | Switch to Live; clearly distinct from the offline test. |
| 18 | Publish or retrieve a flight | Server action, separately from Open | Local copy remains usable during timeout, conflict or expired edit lease. |

## Screen hierarchy

- Library: local rows with an **Open** action; independent server refresh status.
- Preparation header: route name, local save, **Test offline**, automatic departure
  state/time. These actions must not be buried below a long form.
- Preparation sections: **Route**, **Offline**, **Automatic departure**. Show only
  the selected section, with short labels and actionable validation messages.
- Test: **Offline test** indicator, Map / Window, replay controls, return to preparation.
  Tests of an existing recording retain its recorded samples.

## Engineering acceptance criteria

- Listing does not parse GPS arrays or enumerate terrain/satellite files on every visit.
- Save/load never implicitly scan the complete offline corridor.
- Local list and opening are not serialized behind server requests.
- Local save success is not turned into a save failure by an alarm permission/date error.
- A valid route can be inspected before saving or enabling automatic recording.
- Strict offline testing blocks new flight asset requests and interrupts active requests;
  changing views or missing files cannot silently re-enable networking.
- Simulated samples do not replace the stored trip, start sensors, or modify alarm state.
- Tests use synthetic journals and fake downloaders, never private application data.

## Verification and rollout

- Local check result: **84 JVM tests and 17 server tests passed**.
- Standalone JVM tests cover route readiness, replay, local-save/alarm separation,
  the journal index and concurrent list updates, shared offline gating and native
  raster cancellation. A synthetic 60,000-sample journal exercises index migration
  and invalidation; this is not a phone performance measurement.
- Server tests verify that metadata listing completes while another operation owns
  the upload lock, alongside the existing publication/version/permission tests.
- XML, Kotlin syntax and workflow YAML checks are separate from an Android build.
  No APK or server deployment is produced by these checks.
- Device acceptance remains: cold-open each library with an unreachable server;
  save an undated two-point plan without alarm permissions; open both test views,
  scrub and play with partially missing tiles; leave the test and resume downloads;
  program/change/cancel a departure and compare the displayed time.
- Deploy the Python server change separately. The Android local-first behavior does
  not depend on the server already running the new listing implementation.
