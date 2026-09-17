package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightRecordingCadence
import net.osmand.plus.plugins.flightmode.FlightRecordingDecisions
import net.osmand.plus.plugins.flightmode.FlightRecordingMode
import net.osmand.plus.plugins.flightmode.FlightRecordingPolicy
import net.osmand.plus.plugins.flightmode.FlightRecordingSaveReason
import net.osmand.plus.plugins.flightmode.FlightSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stored-point decisions; no GPS provider, service, files or device state. */
class FlightRecordingDecisionTest {

    @Test
    fun stableStraightFlightFarFromPlanUsesTheNormalDistanceCadence() {
        val decision = FlightRecordingDecisions.decide(
            policy = FlightRecordingPolicy(),
            previousRecorded = sample(time = 0),
            candidate = sample(time = 2_000),
            landed = false,
            previousRouteDeviationMeters = 10_000f,
            routeDeviationMeters = 10_000f,
        )

        assertEquals(4f, decision.intervalSeconds, 0.001f)
        assertEquals(FlightRecordingCadence.DISTANCE_OR_MAXIMUM_INTERVAL, decision.cadence)
        assertEquals(FlightRecordingSaveReason.WAITING_FOR_CADENCE, decision.reason)
        assertFalse(decision.shouldRecord)
    }

    @Test
    fun enteringTheOffRouteBandKeepsTheExistingDeviationBoost() {
        val decision = FlightRecordingDecisions.decide(
            policy = FlightRecordingPolicy(),
            previousRecorded = sample(time = 0),
            candidate = sample(time = 2_000),
            landed = false,
            previousRouteDeviationMeters = 0f,
            routeDeviationMeters = 6_000f,
        )

        assertEquals(2f, decision.intervalSeconds, 0.001f)
        assertEquals(FlightRecordingCadence.ROUTE_DEVIATION_ENTRY, decision.cadence)
        assertEquals(FlightRecordingSaveReason.CADENCE_DUE, decision.reason)
        assertTrue(decision.shouldRecord)
    }

    @Test
    fun turnBoostStillAppliesEvenWhenTheRouteDeviationIsStable() {
        val decision = FlightRecordingDecisions.decide(
            policy = FlightRecordingPolicy(),
            previousRecorded = sample(time = 0, bearing = 0f),
            candidate = sample(time = 2_000, bearing = 4f),
            landed = false,
            previousRouteDeviationMeters = 10_000f,
            routeDeviationMeters = 10_000f,
        )

        assertEquals(2f, decision.intervalSeconds, 0.001f)
        assertEquals(FlightRecordingCadence.TURN, decision.cadence)
        assertEquals(FlightRecordingSaveReason.CADENCE_DUE, decision.reason)
    }

    @Test
    fun fixedPolicyAndLandingKeepTheirExistingPriority() {
        val fixed = FlightRecordingDecisions.decide(
            policy = FlightRecordingPolicy(mode = FlightRecordingMode.FIXED, fixedIntervalSeconds = 60f),
            previousRecorded = sample(time = 0, bearing = 0f),
            candidate = sample(time = 2_000, bearing = 180f),
            landed = false,
            previousRouteDeviationMeters = 0f,
            routeDeviationMeters = 20_000f,
        )
        val landing = FlightRecordingDecisions.decide(
            policy = FlightRecordingPolicy(),
            previousRecorded = sample(time = 0),
            candidate = sample(time = 1_000),
            landed = true,
            previousRouteDeviationMeters = 0f,
            routeDeviationMeters = 0f,
        )

        assertEquals(60f, fixed.intervalSeconds, 0.001f)
        assertEquals(FlightRecordingCadence.FIXED_INTERVAL, fixed.cadence)
        assertFalse(fixed.shouldRecord)
        assertEquals(FlightRecordingSaveReason.LANDING, landing.reason)
        assertTrue(landing.shouldRecord)
    }

    @Test
    fun firstFixIsSavedWithoutInventingARouteBoost() {
        val decision = FlightRecordingDecisions.decide(
            policy = FlightRecordingPolicy(),
            previousRecorded = null,
            candidate = sample(time = 1_000),
            landed = false,
            previousRouteDeviationMeters = null,
            routeDeviationMeters = 20_000f,
        )

        assertEquals(4f, decision.intervalSeconds, 0.001f)
        assertEquals(FlightRecordingCadence.DISTANCE_OR_MAXIMUM_INTERVAL, decision.cadence)
        assertEquals(FlightRecordingSaveReason.FIRST_FIX, decision.reason)
        assertTrue(decision.shouldRecord)
    }

    private fun sample(time: Long, bearing: Float? = null) = FlightSample(
        index = 0,
        legIndex = 0,
        timestampMillis = time,
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = 10_000.0,
        speedMetersPerSecond = 250f,
        bearingDegrees = bearing,
        horizontalAccuracyMeters = 5f,
    )
}
