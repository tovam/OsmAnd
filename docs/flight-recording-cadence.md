# Flight recording cadence

`FlightRecordingService` requests GPS updates at one-second/zero-distance cadence. This policy
does not slow GPS acquisition, tracking, stop detection, or the live display. It only decides
whether a received fix becomes an append-only recorded point.

In adaptive mode the normal saved-point interval is the lower of the configured straight-flight
maximum and configured distance divided by measured speed. A turn still shortens that interval.
The route-deviation multiplier is retained for the transition into its policy band, so the track
captures the departure from the planned corridor. It is not applied indefinitely to an otherwise
straight, stable flight that is already far from a stale plan; subsequent points use the normal
distance/maximum or turn cadence.

`FlightRecordingDecision` exposes both the active cadence factor and the save reason. A compact
live status can therefore distinguish fixed, distance/maximum, turn, and route-deviation-entry
cadence, plus first-fix, due, landing, or waiting decisions without exposing GPS or user data.

Synthetic tests cover stable off-route flight, corridor-band entry, turns, fixed mode, landing,
and the first fix. They do not make a claim about device GPS timing.
