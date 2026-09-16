package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightRecordingMode
import net.osmand.plus.plugins.flightmode.FlightRecordingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightRecordingPolicyTest {

    @Test
    fun adaptiveDefaultKeepsTheExistingKilometreFormula() {
        val policy = FlightRecordingPolicy()

        assertEquals(FlightRecordingMode.ADAPTIVE, policy.mode)
        assertEquals(4f, policy.intervalSeconds(250f), 0.001f)
        assertEquals(2f, policy.intervalSeconds(250f, turnRateDegreesPerSecond = 1f), 0.001f)
        assertEquals(2f, policy.intervalSeconds(250f, distanceFromExpectedRouteMeters = 5_000f), 0.001f)
    }

    @Test
    fun fixedCadenceIgnoresSpeedTurnAndRouteGeometry() {
        val policy = FlightRecordingPolicy(
            mode = FlightRecordingMode.FIXED,
            fixedIntervalSeconds = 600f,
            cruisePointDistanceMeters = 100f,
            maximumStraightIntervalSeconds = 1f,
            turnAcceleration = 10f,
            routeDeviationAcceleration = 10f
        )

        assertEquals(600f, policy.intervalSeconds(500f, 180f, 50_000f), 0.001f)
    }

    @Test
    fun clampedPolicyUsesSafeFiniteRangesAndSupportsOneHourCadence() {
        val safe = FlightRecordingPolicy(
            cruisePointDistanceMeters = Float.NaN,
            maximumStraightIntervalSeconds = Float.POSITIVE_INFINITY,
            turnAcceleration = -2f,
            routeDeviationAcceleration = 99f,
            mode = FlightRecordingMode.FIXED,
            fixedIntervalSeconds = 10_000f
        ).clamped()

        assertEquals(FlightRecordingPolicy.DEFAULT_CRUISE_DISTANCE_METERS, safe.cruisePointDistanceMeters, 0f)
        assertEquals(FlightRecordingPolicy.DEFAULT_MAXIMUM_INTERVAL_SECONDS, safe.maximumStraightIntervalSeconds, 0f)
        assertEquals(FlightRecordingPolicy.MIN_ACCELERATION, safe.turnAcceleration, 0f)
        assertEquals(FlightRecordingPolicy.MAX_ACCELERATION, safe.routeDeviationAcceleration, 0f)
        assertEquals(FlightRecordingPolicy.MAX_INTERVAL_SECONDS, safe.fixedIntervalSeconds, 0f)
        assertTrue(safe.intervalSeconds(250f).isFinite())
    }

    @Test
    fun fixedIntervalUsesDefaultForNonFiniteAndOneSecondForLowFiniteValue() {
        val safe = FlightRecordingPolicy(
            mode = FlightRecordingMode.FIXED,
            fixedIntervalSeconds = -5f
        ).clamped()

        assertEquals(FlightRecordingPolicy.MIN_INTERVAL_SECONDS, safe.fixedIntervalSeconds, 0f)
        assertEquals(FlightRecordingPolicy.MIN_INTERVAL_SECONDS, safe.intervalSeconds(0f), 0.001f)

        val nonFinite = FlightRecordingPolicy(
            mode = FlightRecordingMode.FIXED,
            fixedIntervalSeconds = Float.NEGATIVE_INFINITY
        ).clamped()
        assertEquals(FlightRecordingPolicy.DEFAULT_FIXED_INTERVAL_SECONDS, nonFinite.fixedIntervalSeconds, 0f)
    }
}
