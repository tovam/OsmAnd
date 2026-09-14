package net.osmand.test.junit

import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All coordinates, photos and timestamps in these tests are synthetic. */
class FlightPhotoFitDiagnosticsTest {
    private val reference =
        FlightPhotoSpatialPose(0.0, 1800000000000L, 45.0, 10.0, 10000f, 0f, 0f, 0f, 60f)

    private fun fixture(count: Int = 7, badPoint: Boolean = true): FlightPhotoCalibration {
        val pixels =
            listOf(.15 to .2, .7 to .18, .35 to .7, .82 to .82, .5 to .4, .2 to .85, .9 to .5)
        val coordinates = FlightTerrainCoordinates(reference.eyeLatitude, reference.eyeLongitude)
        val points =
            pixels.take(count).mapIndexed { i, (u, v) ->
                val depth = 20000.0 + i * 3000
                val geo =
                    coordinates.toGeographic(
                        doubleArrayOf(
                            (u - .5) * depth * 1.5 / .9,
                            10000 - (v - .5) * depth / .9,
                            -depth,
                        )
                    )
                FlightPhotoControlPoint(
                    u + if (badPoint && i == 5) .045 else 0.0,
                    v - if (badPoint && i == 5) .035 else 0.0,
                    geo[0],
                    geo[1],
                    geo[2],
                )
            }
        // Keep a gap in the original numbering and an incomplete map-only landmark.
        return FlightPhotoCalibration(
            points =
                points.take(1) +
                    FlightPhotoControlPoint(.3, .3) +
                    points.drop(1) +
                    FlightPhotoControlPoint(latitude = 45.1, longitude = 10.2),
            imageWidth = 1500,
            imageHeight = 1000,
            fitFocal = true,
            verticalFov = Math.toDegrees(2 * atan(.5 / .9)),
        )
    }

    private suspend fun solve(
        data: FlightPhotoCalibration,
        progress: suspend (Int, Int) -> Unit = { _, _ -> },
    ) =
        calculateFlightPhotoCalibration(
            data,
            reference,
            { _, _ -> error("Unexpected terrain I/O") },
            progress,
        )

    @Test
    fun completeAndEveryExcludedFitUseStablePointNumbersAndReportProgress() = runBlocking {
        val original = fixture()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = solve(original) { completed, total -> progress += completed to total }
        assertEquals((0..8).map { it to 8 }, progress)
        assertEquals(original.points, result.points)
        assertNull(original.fit)
        val fit = result.fit!!
        assertEquals(listOf(0, 2, 3, 4, 5, 6, 7), fit.pointIndices)
        assertEquals(fit.pointIndices, fit.influences!!.map { it.pointIndex })
        val mostInfluential = fit.rankedInfluences().first()
        assertEquals(
            6,
            mostInfluential.pointIndex,
        ) // Bad complete pair #6 is displayed as original #7.
        assertTrue(mostInfluential.after!!.rms < .01)
        assertTrue(mostInfluential.excludedError!! > 70)
        assertTrue(mostInfluential.rmsReductionPercent(fit)!! > 99)
        val others = fit.errors.filterIndexed { i, _ -> fit.pointIndices[i] != 6 }
        assertEquals(others.sum(), mostInfluential.before(fit).cumulative, 1e-10)
        assertEquals(sqrt(others.sumOf { it * it } / 6), mostInfluential.before(fit).rms, 1e-10)
        assertEquals(
            result,
            FlightPhotoCalibration.fromJson(JSONObject(result.toJson().toString())),
        )
    }

    @Test
    fun fourPairsKeepTheOrdinaryFitWithoutExclusions() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        val result =
            solve(fixture(count = 4, badPoint = false)) { done, total -> progress += done to total }
        assertEquals(listOf(0 to 1, 1 to 1), progress)
        assertEquals(4, result.fit!!.errors.size)
        assertTrue(result.fit!!.weak)
        assertNull(result.fit!!.influences)
    }

    @Test
    fun cancellationBetweenExclusionsStopsWithoutMutatingTheInput() = runBlocking {
        val original = fixture()
        val progress = mutableListOf<Int>()
        try {
            solve(original) { done, _ ->
                progress += done
                if (done == 3) throw CancellationException("Synthetic user cancel")
            }
            fail("Cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals(listOf(0, 1, 2, 3), progress)
            assertNull(original.fit)
        }
    }

    @Test
    fun failedAndBehindCameraRowsSurviveSaving() = runBlocking {
        val solved = solve(fixture())
        val fit = solved.fit!!
        val rows =
            fit.influences!!.mapIndexed { i, row ->
                when (i) {
                    0 -> row.copy(remainingErrors = null, excludedError = null, weak = true)
                    1 -> row.copy(excludedError = null)
                    else -> row
                }
            }
        val data = solved.copy(fit = fit.copy(influences = rows))
        assertEquals(data, FlightPhotoCalibration.fromJson(JSONObject(data.toJson().toString())))
        assertEquals(rows[0], data.fit!!.rankedInfluences().last())
    }

    @Test
    fun malformedOptionalDiagnosticsNeverEraseTheFitOrPoints() = runBlocking {
        val solved = solve(fixture())
        for (damage in 0..3) {
            val json = solved.toJson()
            val fit = json.getJSONObject("fit")
            when (damage) {
                0 -> fit.put("influences", JSONArray())
                1 -> fit.getJSONArray("influences").getJSONObject(0).put("point", 999)
                2 ->
                    fit.getJSONArray("influences")
                        .getJSONObject(0)
                        .put("remainingErrors", JSONArray(listOf(-1)))
                3 -> fit.put("pointIndices", JSONArray(listOf(999)))
            }
            val restored = FlightPhotoCalibration.fromJson(json)
            assertEquals(solved.points, restored.points)
            assertEquals(solved.fit!!.parameters, restored.fit!!.parameters)
            assertEquals(solved.fit!!.errors, restored.fit!!.errors)
            assertNull(restored.fit!!.influences)
        }
    }

    @Test
    fun legacyResultRemainsUsableWithoutAutomaticRecalculation() = runBlocking {
        val solved = solve(fixture())
        val json = solved.toJson()
        json.getJSONObject("fit").remove("pointIndices")
        json.getJSONObject("fit").remove("influences")
        val legacy = FlightPhotoCalibration.fromJson(json)
        assertEquals(solved.fit!!.parameters, legacy.fit!!.parameters)
        assertEquals(solved.points, legacy.points)
        assertNull(legacy.fit!!.influences)
        assertTrue(legacy.fit!!.pointIndices.isEmpty())
    }

    @Test
    fun numericalZeroDoesNotProduceHugePercentageGains() {
        val fit =
            FlightPhotoFit(
                45.0,
                10.0,
                List(7) { 0.0 },
                List(5) { 1e-10 },
                1e-10,
                false,
                listOf(0, 1, 2, 3, 4),
            )
        val row = FlightPhotoPointInfluence(0, List(4) { 0.0 }, 0.0, true)
        assertNull(row.rmsReductionPercent(fit))
    }
}
