package net.osmand.test.junit

import kotlin.math.*
import kotlinx.coroutines.runBlocking
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic routes only; no files, devices, accounts or network are used by this suite. */
class FlightPreparationLogicTest {
    @Test
    fun routeNameTracksCitiesButPreservesCustomTitles() {
        val before = FlightPlan(listOf(FlightStop("Départ"), FlightStop("Arrivée")))
        val after =
            before.copy(
                stops = listOf(FlightStop("Paris"), FlightStop("Vienne"), FlightStop("Podgorica"))
            )
        assertEquals(
            "Paris → Vienne → Podgorica",
            FlightJourneyNaming.updated("Départ -> arrivée", before, after),
        )
        assertEquals(
            "Paris → Vienne → Podgorica",
            FlightJourneyNaming.updated("Départ → Arrivée", before, after),
        )
        val next = after.copy(stops = after.stops.dropLast(1))
        assertEquals(
            "Paris → Vienne",
            FlightJourneyNaming.updated(FlightJourneyNaming.route(after), after, next),
        )
        assertEquals("Vacances", FlightJourneyNaming.updated("Vacances", before, after))
    }

    @Test
    fun compactUtcOffsetsAcceptBothSignsAndLegacyFormat() {
        assertEquals(120, FlightPreparation.parseOffset("+0200"))
        assertEquals(-330, FlightPreparation.parseOffset("-0530"))
        assertEquals(120, FlightPreparation.parseOffset("+02:00"))
        assertEquals(840, FlightPreparation.parseOffset("+1400"))
        assertNull(FlightPreparation.parseOffset("+1460"))
        assertNull(FlightPreparation.parseOffset("+1401"))
        assertNull(FlightPreparation.parseOffset("-1201"))
        assertNull(FlightPreparation.parseOffset("+02"))
    }

    private val base = 1_800_000_000_000L

    @Test fun cancelledOrPostponedAlarmsDoNotStartFromAnOldBroadcast() {
        assertFalse(flightScheduleIsDue(null, base))
        assertFalse(flightScheduleIsDue(0L, base))
        assertFalse(flightScheduleIsDue(base + 60_000L, base))
        assertTrue(flightScheduleIsDue(base, base))
        assertTrue(flightScheduleIsDue(base + 1_000L, base))
        assertTrue(flightScheduleIsDue(base - 60_000L, base))
    }

    private fun sample(
        seconds: Int = 0,
        alt: Double? = 100.0,
        speed: Float? = 0f,
        accuracy: Float? = 5f,
    ) = FlightSample(seconds, 0, base + seconds * 1000, 45.0, 10.0, alt, speed, 90f, accuracy)

    private fun accept(
        state: FlightTrackingState,
        p: FlightSample,
        config: FlightPreparation = FlightPreparation(),
    ) = state.accept(p, p.timestampMillis, config)

    @Test
    fun groundWaitingDoesNotStopAfterThirtyMinutes() {
        var state = FlightTrackingState()
        for (s in 0..4000) state = accept(state, sample(s))
        assertEquals(FlightTrackingPhase.WAITING, state.phase)
        assertNull(state.slowSinceMillis)
    }

    @Test
    fun takeoffNeedsBothHeightAndSpeed() {
        val ground = accept(FlightTrackingState(), sample())
        assertEquals(FlightTrackingPhase.WAITING, accept(ground, sample(1, 1099.0, 100f)).phase)
        assertEquals(FlightTrackingPhase.WAITING, accept(ground, sample(1, 1500.0, 50f)).phase)
        assertEquals(FlightTrackingPhase.AIRBORNE, accept(ground, sample(1, 1100.0, 60f)).phase)
        assertNull(accept(FlightTrackingState(), sample(0, null)).baselineAltitude)
    }

    private fun airborne() = accept(accept(FlightTrackingState(), sample()), sample(1, 1100.0, 60f))

    @Test
    fun landingRequiresTheEntireContinuousInterval() {
        var state = airborne()
        for (s in 2..1801) state = accept(state, sample(s, 100.0, 10f))
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        state = accept(state, sample(1802, 100.0, 10f))
        assertEquals(FlightTrackingPhase.LANDED, state.phase)
        assertEquals(state, accept(state, sample(1803, 12000.0, 200f)))
    }

    @Test
    fun missingGpsAndBadAccuracyNeverCountAsLanding() {
        var state = airborne()
        for (s in 2..1700) state = accept(state, sample(s))
        val stale = sample(1701)
        assertNull(
            state.accept(stale, stale.timestampMillis + 20_000, FlightPreparation()).slowSinceMillis
        )
        assertNull(accept(state, sample(1701, accuracy = 500f)).slowSinceMillis)
        assertNull(accept(state, sample(1701, speed = null)).slowSinceMillis)
        val afterGap = accept(state, sample(1900))
        assertEquals(base + 1_900_000, afterGap.slowSinceMillis)
        assertEquals(FlightTrackingPhase.AIRBORNE, afterGap.phase)
        assertNull(accept(state, sample(1701, speed = 60f)).slowSinceMillis)
        assertNull(accept(state, sample(1600)).slowSinceMillis)
    }

    @Test
    fun configurableThresholdsApply() {
        val config =
            FlightPreparation(
                airborneGainMeters = 500,
                airborneSpeedKmh = 100,
                stopMinutes = 5,
                stopSpeedKmh = 20,
            )
        var s = accept(FlightTrackingState(), sample(), config)
        s = accept(s, sample(1, 600.0, 30f), config)
        assertEquals(FlightTrackingPhase.AIRBORNE, s.phase)
        for (t in 2..302) s = accept(s, sample(t, 100.0, 1f), config)
        assertEquals(FlightTrackingPhase.LANDED, s.phase)
    }

    @Test
    fun offsetsAndDatesRoundTripWithoutPhoneTimezone() {
        for (offset in listOf(-720, -30, 0, 60, 345, 840)) {
            assertEquals(
                offset,
                FlightPreparation.parseOffset(FlightPreparation.offsetText(offset)),
            )
            val time = FlightPreparation.parseDate("2026-09-14 12:30", offset)!!
            assertEquals("2026-09-14 12:30", FlightPreparation.dateText(time, offset))
            assertEquals(
                FlightPreparation.parseDate("2026-09-14 12:30", 0)!!,
                time + offset * 60_000L,
            )
        }
        assertNull(FlightPreparation.parseDate("2026-02-30 12:00", 0))
        assertNull(FlightPreparation.parseDate("2026-09-14 12:30 rubbish", 0))
        for (offset in listOf("+14:01", "-12:01", "+01:60", "2", "")) assertNull(
            FlightPreparation.parseOffset(offset)
        )
        val p =
            FlightPreparation(
                departureMillis = base,
                arrivalMillis = base + 600_000,
                departureOffsetMinutes = 120,
                automatic = true,
            )
        assertEquals(p, FlightPreparation.fromJson(p.toJson()))
        assertEquals(base - 900_000, p.startMillis)
    }

    @Test
    fun batterySlopeSeparatesChargingFromDischarging() {
        val history = (0..60).map { FlightBatteryPoint(base + it * 60_000, 100f - it / 5f, false) }
        assertEquals(12.0, flightBatteryDrainPerHour(history)!!, 0.001)
        assertNull(flightBatteryDrainPerHour(history.take(3)))
        assertNull(
            flightBatteryDrainPerHour(history + FlightBatteryPoint(base + 61 * 60_000, 89f, true))
        )
        assertNull(
            flightBatteryDrainPerHour(
                history +
                    FlightBatteryPoint(base + 61 * 60_000, 89f, true) +
                    FlightBatteryPoint(base + 62 * 60_000, 88f, false)
            )
        )
    }

    private fun route(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        radius: Int = 20,
        z: Int = 9,
    ) =
        FlightPlan(
            listOf(FlightStop("A", a.first, a.second), FlightStop("B", b.first, b.second)),
            preparation = FlightPreparation(bands = listOf(FlightOfflineBand(radius, z, z))),
        )

    private fun move(a: Pair<Double, Double>, bearing: Double, km: Double): Pair<Double, Double> {
        val p = Math.toRadians(a.first)
        val l = Math.toRadians(a.second)
        val b = Math.toRadians(bearing)
        val d = km / 6371.0
        val lat = asin(sin(p) * cos(d) + cos(p) * sin(d) * cos(b))
        val lon = l + atan2(sin(b) * sin(d) * cos(p), cos(d) - sin(p) * sin(lat))
        return Math.toDegrees(lat) to ((Math.toDegrees(lon) + 540) % 360 - 180)
    }

    @Test
    fun offlineCoverageContainsCorridorAtRequestedZoomAndAllParents() = runBlocking {
        val routes =
            listOf(
                45.0 to 10.0 to (46.0 to 15.0),
                10.0 to 179.0 to (11.0 to -179.0),
                -42.0 to 171.0 to (-45.0 to 174.0),
                72.0 to -40.0 to (73.0 to -25.0),
            )
        for ((a, b) in routes) {
            val p = route(a, b)
            val q = FlightOfflinePreparation.quote(p)
            val set = q.requests.map { it.satellite to it.tile }.toSet()
            assertEquals(set.size, q.requests.size)
            for (i in 0..50) {
                val center = FlightTerrainTilePlanner.greatCircleInterpolate(a, b, i / 50.0)
                for (angle in 0 until 360 step 15) {
                    val at = move(center, angle.toDouble(), 19.99)
                    val tile =
                        TerrainTileId(
                            9,
                            floor(FlightTerrainTilePlanner.longitudeToTileX(at.second, 9)).toInt(),
                            floor(FlightTerrainTilePlanner.latitudeToTileY(at.first, 9)).toInt(),
                        )
                    assertTrue("missing $a $b $tile", true to tile in set && false to tile in set)
                }
            }
            for (r in q.requests) for (z in 3 until r.tile.zoom) {
                val shift = r.tile.zoom - z
                assertTrue(
                    r.satellite to TerrainTileId(z, r.tile.x shr shift, r.tile.y shr shift) in set
                )
            }
            assertTrue(q.requests.zipWithNext().all { (a, b) -> a.tile.zoom <= b.tile.zoom })
        }
    }

    @Test
    fun terrainAndImageryZoomsRemainIndependent() = runBlocking {
        val p =
            route(45.0 to 10.0, 45.2 to 10.1).let {
                it.copy(
                    preparation =
                        it.preparation!!.copy(bands = listOf(FlightOfflineBand(10, 8, 11)))
                )
            }
        val q = FlightOfflinePreparation.quote(p)
        assertEquals(8, q.requests.filter { it.satellite }.maxOf { it.tile.zoom })
        assertEquals(11, q.requests.filter { !it.satellite }.maxOf { it.tile.zoom })
        assertTrue(q.estimatedBytes > 0)
    }

    @Test
    fun unsupportedPolarCoverageFailsInsteadOfPretendingReady() = runBlocking {
        assertTrue(
            runCatching { FlightOfflinePreparation.quote(route(84.0 to 0.0, 84.0 to 2.0, 300, 5)) }
                .isFailure
        )
    }

    @Test
    fun intermediatePointIsNotASyntheticLanding() {
        val plan =
            route(45.0 to 0.0, 45.0 to 10.0).let {
                it.copy(
                    stops = listOf(it.stops.first(), FlightStop("Via", 45.0, 5.0), it.stops.last()),
                    preparation =
                        it.preparation!!.copy(
                            departureMillis = base,
                            arrivalMillis = base + 2 * 3_600_000,
                        ),
                )
            }
        val trip = FlightOfflinePreparation.simulation(plan)
        assertEquals(1, trip.legs.size)
        assertEquals(base, trip.samples.first().timestampMillis)
        assertEquals(base + 2 * 3_600_000, trip.samples.last().timestampMillis)
        assertTrue(trip.samples[trip.samples.size / 2].altitudeMeters!! > 5000)
        assertEquals(0.0, trip.samples.last().altitudeMeters!!, 0.01)
        assertEquals(10.0, trip.samples.last().longitude, 0.001)
    }

    @Test
    fun hypothesisStartsAtActualPositionAndEndsAtDestination() {
        val plan = route(45.0 to 0.0, 45.0 to 10.0)
        val at = sample().copy(latitude = 46.0, longitude = 6.0)
        val remaining = FlightRouteHypothesis.remaining(plan, at)
        assertEquals(at.latitude, remaining.first().latitude, 1e-7)
        assertEquals(at.longitude, remaining.first().longitude, 1e-7)
        assertEquals(45.0, remaining.last().latitude, 1e-7)
        assertEquals(10.0, remaining.last().longitude, 1e-7)
        assertTrue(FlightRouteHypothesis.distanceToPlanKm(plan, at)!! > 0)
    }

    @Test
    fun displayPredictionFreezesAfterFiveSecondsAndCorrectsWithoutJump() {
        val predictor = FlightLivePredictor()
        val original = sample(speed = 250f)
        predictor.accept(original, 0)
        val before = predictor.position(1000)!!
        predictor.accept(sample(1, speed = 250f).copy(longitude = 10.003), 1000)
        assertEquals(before.longitude, predictor.position(1000)!!.longitude, 1e-9)
        assertTrue(predictor.position(2200)!!.longitude > before.longitude)
        assertEquals(predictor.position(6000), predictor.position(60000))
        assertEquals(10.0, original.longitude, 0.0)
    }
}
