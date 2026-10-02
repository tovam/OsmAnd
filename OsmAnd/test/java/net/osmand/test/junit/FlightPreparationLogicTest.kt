package net.osmand.test.junit

import kotlin.math.*
import kotlinx.coroutines.runBlocking
import net.osmand.plus.plugins.flightmode.*
import net.osmand.plus.plugins.flightmode.FlightPrecisionProfile
import net.osmand.plus.plugins.flightmode.FlightReadiness
import org.junit.Assert.*
import org.junit.Test

/** Synthetic routes only; no files, devices, accounts or network are used by this suite. */
class FlightPreparationLogicTest {
    @Test
    fun readinessRequiresRealVerificationAndDoesNotRequireAutomaticDeparture() {
        val plan =
            FlightPlan(
                listOf(FlightStop("A", 0.0, 0.0), FlightStop("B", 1.0, 1.0)),
                preparation = FlightPreparation(departureMillis = 1000, arrivalMillis = 3000),
            )
        val quote =
            FlightOfflineQuote(
                emptyList(),
                listOf(0.0 to 0.0, 1.0 to 1.0),
                plan.preparation!!.bands,
            )
        val state =
            FlightUiState(
                plan = plan,
                offlineQuote = quote,
                offlinePreloadStatus =
                    FlightTerrainStatus(
                        phase = FlightTerrainPhase.READY,
                        offlineFilesVerified = true,
                    ),
            )
        assertTrue(FlightReadiness.evaluate(state, true, 1024L * 1024 * 1024, 2000).ready)
        assertFalse(FlightReadiness.evaluate(state, true, 1024L * 1024 * 1024, 2000).scheduled)
        assertFalse(
            FlightReadiness.evaluate(
                    state.copy(offlinePreloadStatus = FlightTerrainStatus()),
                    true,
                    1024L * 1024 * 1024,
                    2000,
                )
                .ready
        )
        assertFalse(FlightReadiness.evaluate(state, false, 1024L * 1024 * 1024, 2000).ready)
        assertFalse(FlightReadiness.evaluate(state, true, 10, 2000).ready)
        assertFalse(FlightReadiness.evaluate(state, true, 1024L * 1024 * 1024, 4000).ready)
    }

    @Test
    fun precisionPresetsPreserveRouteDatesAndCoverageAndRecognizeCustomSettings() {
        val original =
            FlightPlan(
                emptyList(),
                terrainCorridorKm = 150,
                preparation =
                    FlightPreparation(
                        departureMillis = 1234L,
                        arrivalMillis = 5678L,
                        bands =
                            listOf(FlightOfflineBand(20, 12, 12), FlightOfflineBand(150, 10, 10)),
                    ),
            )
        for (preset in FlightPrecisionProfile.entries) {
            val plan = preset.apply(original)
            assertEquals(original.stops, plan.stops)
            assertEquals(1234L, plan.preparation!!.departureMillis)
            assertEquals(150, plan.terrainCorridorKm)
            assertEquals(listOf(20, 150), plan.preparation!!.bands.map { it.radiusKm })
            assertEquals(preset, FlightPrecisionProfile.selected(plan))
            assertEquals(plan, preset.apply(plan))
        }
        assertNull(
            FlightPrecisionProfile.selected(
                original.copy(satelliteQuality = FlightSatelliteQuality.ULTRA_PLUS_PLUS)
            )
        )
    }

    @Test
    fun offlineEstimateRespondsToCorridorWidthQualityAndRouteLength() = runBlocking {
        fun plan(radius: Int = 20, satellite: Int = 9, terrain: Int = 9, longitude: Double = 11.0) =
            FlightPlan(
                stops =
                    listOf(
                        FlightStop("A", latitude = 45.0, longitude = 10.0),
                        FlightStop("B", latitude = 45.0, longitude = longitude),
                    ),
                preparation =
                    FlightPreparation(bands = listOf(FlightOfflineBand(radius, satellite, terrain))),
            )
        val base = FlightOfflinePreparation.quote(plan())
        val wide = FlightOfflinePreparation.quote(plan(radius = 100))
        val satellite = FlightOfflinePreparation.quote(plan(satellite = 11))
        val terrain = FlightOfflinePreparation.quote(plan(terrain = 11))
        val longer = FlightOfflinePreparation.quote(plan(longitude = 13.0))
        assertTrue(wide.estimatedBytes > base.estimatedBytes)
        assertTrue(satellite.satelliteEstimatedBytes > base.satelliteEstimatedBytes)
        assertEquals(base.terrainEstimatedBytes, satellite.terrainEstimatedBytes)
        assertTrue(terrain.terrainEstimatedBytes > base.terrainEstimatedBytes)
        assertEquals(base.satelliteEstimatedBytes, terrain.satelliteEstimatedBytes)
        assertTrue(longer.estimatedBytes > base.estimatedBytes)
        assertTrue(longer.distanceKm > base.distanceKm)
        for (quote in listOf(base, wide, satellite, terrain, longer)) {
            assertEquals(quote.estimatedBytes, quote.bandEstimatedBytes.values.sum())
            assertEquals(
                quote.estimatedBytes,
                quote.satelliteEstimatedBytes + quote.terrainEstimatedBytes,
            )
            assertEquals(
                quote.requests.size,
                quote.requests.map { it.satellite to it.tile }.toSet().size,
            )
        }
    }

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

    @Test
    fun cancelledOrPostponedAlarmsDoNotStartFromAnOldBroadcast() {
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
    fun takeoffNeedsSustainedSpeedAndHeightWhenAvailable() {
        val ground = accept(FlightTrackingState(), sample())
        assertEquals(FlightTrackingPhase.WAITING, accept(ground, sample(1, 1100.0, 100f)).phase)
        var low = ground
        for (s in 1..45) low = accept(low, sample(s, 1099.0, 100f))
        assertEquals(FlightTrackingPhase.WAITING, low.phase)
        assertEquals(FlightTrackingPhase.AIRBORNE, airborne().phase)
        assertNull(accept(FlightTrackingState(), sample(0, null)).baselineAltitude)
    }

    private fun airborne(): FlightTrackingState {
        var state = accept(FlightTrackingState(), sample())
        for (s in 1..45) state = accept(state, sample(s, 1100.0, 100f))
        return state
    }

    @Test
    fun taxiIsRetainedAndFinalStableStopEndsAutomatically() {
        var state = airborne()
        for (s in 46..180) state = accept(state, sample(s, 100.0, 10f))
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        for (s in 181..295) state = accept(state, sample(s))
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        for (s in 296..330) state = accept(state, sample(s))
        assertEquals(FlightTrackingPhase.LANDED, state.phase)
        assertEquals(state, accept(state, sample(331, 12000.0, 200f)))
    }

    @Test
    fun missingGpsBadAccuracyAndClockReversalNeverCountAsLanding() {
        var state = airborne()
        for (s in 46..140) state = accept(state, sample(s))
        val stale = sample(141)
        assertNull(
            state.accept(stale, stale.timestampMillis + 20_000, FlightPreparation()).slowSinceMillis
        )
        assertNull(accept(state, sample(141, accuracy = 500f)).slowSinceMillis)
        assertNull(accept(state, sample(141, speed = null).copy(latitude = 45.01)).slowSinceMillis)
        val afterGap = accept(state, sample(1900))
        assertEquals(base + 1_900_000, afterGap.slowSinceMillis)
        assertEquals(FlightTrackingPhase.AIRBORNE, afterGap.phase)
        assertNull(accept(state, sample(120)).slowSinceMillis)
        // A stopped GPS fix with continued movement is not a stationary gate arrival.
        val moved = sample(141).copy(latitude = 45.01)
        assertNull(accept(state, moved).slowSinceMillis)
    }

    @Test
    fun configurableConfirmationDurationApplies() {
        val config =
            FlightPreparation(airborneGainMeters = 500, airborneSpeedKmh = 100, stopMinutes = 5)
        var state = accept(FlightTrackingState(), sample(), config)
        for (s in 1..45) state = accept(state, sample(s, 600.0, 30f), config)
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        for (s in 46..340) state = accept(state, sample(s), config)
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        for (s in 341..365) state = accept(state, sample(s), config)
        assertEquals(FlightTrackingPhase.LANDED, state.phase)
    }

    @Test
    fun layoverResumesBeforeNextDepartureAndFinalArrivalStops() {
        val config =
            FlightPreparation(
                departureMillis = base,
                arrivalMillis = base + 300000,
                additionalFlights = listOf(FlightScheduledLeg(base + 3600000, base + 7200000)),
            )
        var state = airborne()
        for (s in 46..200) state = accept(state, sample(s), config)
        assertEquals(FlightTrackingPhase.LAYOVER, state.phase)
        assertEquals(1, state.completedFlights)
        assertEquals(base + 2700000, state.resumeAtMillis)
        assertEquals(FlightTrackingPhase.LAYOVER, state.resumeIfDue(base + 2600000, config).phase)
        state = state.resumeIfDue(base + 2700000, config)
        assertEquals(FlightTrackingPhase.WAITING, state.phase)
        state = accept(state, sample(2700), config)
        for (s in 2701..2745) state = accept(state, sample(s, 1100.0, 100f), config)
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        for (s in 2746..2900) state = accept(state, sample(s), config)
        assertEquals(FlightTrackingPhase.LANDED, state.phase)
        assertEquals(2, state.completedFlights)
    }

    @Test
    fun scheduleRoundTripValidationAndLegacyLandingMigration() {
        val plan =
            FlightPreparation(
                departureMillis = base,
                arrivalMillis = base + 600000,
                additionalFlights =
                    listOf(FlightScheduledLeg(base + 1200000, base + 1800000, 60, 120)),
            )
        assertTrue(plan.validSchedule())
        assertEquals(plan, FlightPreparation.fromJson(plan.toJson()))
        assertFalse(
            plan
                .copy(additionalFlights = listOf(FlightScheduledLeg(base + 100, base + 500)))
                .validSchedule()
        )
        val legacy = org.json.JSONObject().put("stopSpeed", 50).put("stopMinutes", 30)
        assertEquals(2, FlightPreparation.fromJson(legacy)!!.stopMinutes)
        assertEquals(3, FlightPreparation.fromJson(legacy)!!.stopSpeedKmh)
    }

    @Test
    fun layoverRespectsLivePostponementEarlyActivationAndRemovedNextFlight() {
        val first = FlightPreparation(departureMillis = base, arrivalMillis = base + 300000,
            additionalFlights = listOf(FlightScheduledLeg(base + 3600000, base + 7200000)))
        val waiting = FlightTrackingState(phase = FlightTrackingPhase.LAYOVER, completedFlights = 1,
            resumeAtMillis = base + 2700000)
        val delayed = first.copy(additionalFlights = listOf(FlightScheduledLeg(base + 7200000, base + 10800000)))
        val updated = waiting.resumeIfDue(base + 2700000, delayed)
        assertEquals(FlightTrackingPhase.LAYOVER, updated.phase)
        assertEquals(base + 6300000, updated.resumeAtMillis)
        val advanced = first.copy(additionalFlights = listOf(FlightScheduledLeg(base + 2000000, base + 3600000)))
        assertEquals(FlightTrackingPhase.WAITING, waiting.resumeIfDue(base + 2700000, advanced).phase)
        assertNull(waiting.resumeIfDue(base + 2700000, first.copy(additionalFlights = emptyList())).resumeAtMillis)
    }

    @Test
    fun cancellingNextFlightAfterGpsResumesStopsAtRestWithoutCountingAnotherFlight() {
        val config = FlightPreparation(departureMillis = base, arrivalMillis = base + 600000)
        var state = FlightTrackingState(phase = FlightTrackingPhase.WAITING, completedFlights = 1)
        for (s in 1000..1130) state = accept(state, sample(s), config)
        assertEquals(FlightTrackingPhase.LANDED, state.phase)
        assertEquals(1, state.completedFlights)
    }

    @Test
    fun cancelledConnectionClearsOldFastEvidenceBeforeAnotherTakeoff() {
        val config = FlightPreparation(departureMillis = base, arrivalMillis = base + 600000)
        var state = FlightTrackingState(phase = FlightTrackingPhase.WAITING, completedFlights = 1,
            baselineAltitude = 100.0, fastSinceMillis = base, lastFixMillis = base + 999000)
        state = accept(state, sample(1000), config)
        assertNull(state.fastSinceMillis)
        for (s in 1001..1050) state = accept(state, sample(s), config)
        for (s in 1051..1070) state = accept(state, sample(s, 1100.0, 100f), config)
        assertEquals(FlightTrackingPhase.WAITING, state.phase)
    }

    @Test
    fun firstFixAtCruiseConfirmsAutomaticallyWithoutInventingAGroundAltitude() {
        var state = FlightTrackingState()
        for (s in 1000..1045) state = accept(state, sample(s, 11000.0, 220f))
        assertNull(state.baselineAltitude)
        assertEquals(FlightTrackingPhase.AIRBORNE, state.phase)
        for (s in 1046..1220) state = accept(state, sample(s, 100.0, 0f))
        assertEquals(FlightTrackingPhase.LANDED, state.phase)
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
                    stops =
                        listOf(
                            it.stops.first(),
                            FlightStop("Via", 45.0, 5.0, FlightStopType.WAYPOINT),
                            it.stops.last(),
                        ),
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
