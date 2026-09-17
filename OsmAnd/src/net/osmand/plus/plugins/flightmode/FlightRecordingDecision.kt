package net.osmand.plus.plugins.flightmode

/** The factor currently setting the stored-point cadence; suitable for a compact live status. */
enum class FlightRecordingCadence {
    FIXED_INTERVAL,
    DISTANCE_OR_MAXIMUM_INTERVAL,
    TURN,
    ROUTE_DEVIATION_ENTRY,
}

/** Why this candidate is, or is not, appended to the append-only recording. */
enum class FlightRecordingSaveReason {
    FIRST_FIX,
    CADENCE_DUE,
    LANDING,
    WAITING_FOR_CADENCE,
}

data class FlightRecordingDecision(
    val shouldRecord: Boolean,
    val intervalSeconds: Float,
    val elapsedSeconds: Float,
    val cadence: FlightRecordingCadence,
    val reason: FlightRecordingSaveReason,
)

/**
 * Pure persistence decision. GPS acquisition remains outside this helper and stays at the
 * service's requested cadence. A route-deviation boost is applied when crossing into the
 * policy's boosted band, not indefinitely while flying straight and parallel to a remote plan.
 */
internal object FlightRecordingDecisions {
    private fun absAngle(a: Float, b: Float): Float {
        val difference = kotlin.math.abs(a - b) % 360f
        return kotlin.math.min(difference, 360f - difference)
    }

    fun decide(
        policy: FlightRecordingPolicy,
        previousRecorded: FlightSample?,
        candidate: FlightSample,
        landed: Boolean,
        previousRouteDeviationMeters: Float?,
        routeDeviationMeters: Float?,
    ): FlightRecordingDecision {
        val turnRate = turnRate(previousRecorded, candidate)
        val baseline = policy.intervalSeconds(candidate.speedMetersPerSecond ?: 0f)
        val turnInterval = policy.intervalSeconds(candidate.speedMetersPerSecond ?: 0f, turnRate)
        val routeBoostEntered = previousRecorded != null &&
            routeBoostApplies(policy, candidate.speedMetersPerSecond ?: 0f, routeDeviationMeters) &&
            !routeBoostApplies(policy, candidate.speedMetersPerSecond ?: 0f, previousRouteDeviationMeters)
        val interval = policy.intervalSeconds(
            speedMetersPerSecond = candidate.speedMetersPerSecond ?: 0f,
            turnRateDegreesPerSecond = turnRate,
            distanceFromExpectedRouteMeters = if (routeBoostEntered) routeDeviationMeters ?: 0f else 0f,
        )
        val cadence = when {
            policy.clamped().mode == FlightRecordingMode.FIXED -> FlightRecordingCadence.FIXED_INTERVAL
            routeBoostEntered && interval < turnInterval -> FlightRecordingCadence.ROUTE_DEVIATION_ENTRY
            turnInterval < baseline -> FlightRecordingCadence.TURN
            else -> FlightRecordingCadence.DISTANCE_OR_MAXIMUM_INTERVAL
        }
        val elapsed = previousRecorded?.let {
            (candidate.timestampMillis - it.timestampMillis) / 1_000f
        } ?: Float.POSITIVE_INFINITY
        val reason = when {
            previousRecorded == null -> FlightRecordingSaveReason.FIRST_FIX
            landed -> FlightRecordingSaveReason.LANDING
            elapsed >= interval -> FlightRecordingSaveReason.CADENCE_DUE
            else -> FlightRecordingSaveReason.WAITING_FOR_CADENCE
        }
        return FlightRecordingDecision(
            shouldRecord = reason != FlightRecordingSaveReason.WAITING_FOR_CADENCE,
            intervalSeconds = interval,
            elapsedSeconds = elapsed,
            cadence = cadence,
            reason = reason,
        )
    }

    private fun turnRate(previous: FlightSample?, candidate: FlightSample): Float {
        val elapsed = previous?.let { (candidate.timestampMillis - it.timestampMillis) / 1_000f } ?: return 0f
        return if (previous.bearingDegrees != null && candidate.bearingDegrees != null && elapsed > 0f)
            absAngle(previous.bearingDegrees, candidate.bearingDegrees) / elapsed
        else 0f
    }

    /** Reuses the policy's own threshold and clamp behaviour rather than duplicating 5 km. */
    private fun routeBoostApplies(
        policy: FlightRecordingPolicy,
        speedMetersPerSecond: Float,
        deviationMeters: Float?,
    ): Boolean {
        val baseline = policy.intervalSeconds(speedMetersPerSecond)
        val withDeviation = policy.intervalSeconds(
            speedMetersPerSecond = speedMetersPerSecond,
            distanceFromExpectedRouteMeters = deviationMeters ?: 0f,
        )
        return withDeviation < baseline
    }
}
