package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightHeadPose
import net.osmand.plus.plugins.flightmode.FlightLeg
import net.osmand.plus.plugins.flightmode.FlightPlan
import net.osmand.plus.plugins.flightmode.FlightProfilePlanner
import net.osmand.plus.plugins.flightmode.FlightRecordingPolicy
import net.osmand.plus.plugins.flightmode.FlightReplayEngine
import net.osmand.plus.plugins.flightmode.FlightSample
import net.osmand.plus.plugins.flightmode.FlightStop
import net.osmand.plus.plugins.flightmode.FlightSunPosition
import net.osmand.plus.plugins.flightmode.FlightTerrainCoordinates
import net.osmand.plus.plugins.flightmode.FlightTerrainMeshBuilder
import net.osmand.plus.plugins.flightmode.FlightTerrainTilePlanner
import net.osmand.plus.plugins.flightmode.FlightTrip
import net.osmand.plus.plugins.flightmode.TerrainTileId
import net.osmand.plus.plugins.flightmode.TerrainTilePlan
import net.osmand.plus.plugins.flightmode.TerrariumCodec
import net.osmand.plus.plugins.flightmode.TerrariumTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightModeLogicTest {

	@Test
	fun everyStopoverDescendsBeforeNextClimb() {
		val profile = FlightProfilePlanner.build(
			FlightPlan(
				listOf(
					FlightStop("Paris", 49.0097, 2.5479),
					FlightStop("Vienne", 48.1103, 16.5697),
					FlightStop("Podgorica", 42.3594, 19.2519)
				)
			)
		)

		assertEquals(2, profile.legs.size)
		assertEquals(0f, profile.legs[0].points.last().altitudeMeters, 0f)
		assertEquals(0f, profile.legs[1].points.first().altitudeMeters, 0f)
		assertTrue(profile.legs[1].points.drop(1).any { it.altitudeMeters > 0f })
	}

	@Test
	fun replayDoesNotInventAcrossLongGap() {
		val samples = listOf(
			sample(0, 0L, 48.0, 2.0),
			sample(1, 10_000L, 48.1, 2.1),
			sample(2, 610_000L, 49.0, 3.0)
		)
		val trip = FlightTrip(
			"gap",
			samples,
			listOf(FlightLeg(0, "Vol", 0, 2, 1000.0, 0L, 610_000L)),
			true,
			1000.0,
			"synthetic.gpx"
		)
		val snapshot = FlightReplayEngine(trip).snapshotAt(0.5f)

		assertTrue(snapshot.dataGap)
		assertFalse(snapshot.interpolated)
	}

	@Test
	fun cruiseDefaultsToAboutOnePointPerKilometre() {
		val policy = FlightRecordingPolicy()
		val interval = policy.intervalSeconds(250f)
		assertEquals(4f, interval, 0.01f)
	}

	@Test
	fun headPoseIsBoundedToPlausibleEyeBox() {
		val pose = FlightHeadPose(2f, -2f, 0.01f).clamped()
		assertEquals(FlightHeadPose.MAX_HORIZONTAL_METERS, pose.horizontalMeters, 0f)
		assertEquals(-FlightHeadPose.MAX_VERTICAL_METERS, pose.verticalMeters, 0f)
		assertEquals(FlightHeadPose.MIN_DISTANCE_METERS, pose.distanceMeters, 0f)
	}

	@Test
	fun terrariumDecodesOfficialRgbExample() {
		assertEquals(2523.265625f, TerrariumCodec.decodeElevation(137, 219, 68), 0.0001f)
	}

	@Test
	fun sceneTilePlanKeepsThreeHundredKilometresBounded() {
		val plan = FlightTerrainTilePlanner.scenePlan(45.0, 12.0, 300)
		assertTrue(plan.tiles.isNotEmpty())
		assertTrue(plan.tiles.size <= 144)
		assertTrue(plan.zoom in 4..12)
	}

	@Test
	fun localTerrainCoordinatesIncludeEarthCurvature() {
		val projection = FlightTerrainCoordinates(0.0, 0.0)
		val pointAboutOneHundredKilometresEast = projection.toLocal(0.0, 1.0, 0.0)
		assertTrue(pointAboutOneHundredKilometresEast[1] < -900f)
	}

	@Test
	fun terrainIsBuiltAsTrianglesInsteadOfColumns() {
		val zoom = 8
		val tileId = TerrainTileId(
			zoom,
			FlightTerrainTilePlanner.longitudeToTileX(2.0, zoom).toInt(),
			FlightTerrainTilePlanner.latitudeToTileY(48.0, zoom).toInt()
		)
		val tile = TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { 120f })
		val scene = FlightTerrainMeshBuilder.build(
			centerLatitude = 48.0,
			centerLongitude = 2.0,
			radiusKm = 50,
			plan = TerrainTilePlan(zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile)
		)
		assertEquals(1, scene.meshes.size)
		assertEquals(33 * 33 * FlightTerrainMeshBuilder.VERTEX_COMPONENTS, scene.meshes.single().vertices.size)
		assertEquals(32 * 32 * 6, scene.meshes.single().indices.size)
	}

	@Test
	fun equatorialNoonSunPointsMostlyUp() {
		val vector = FlightSunPosition.direction(1710936000000L, 0.0, 0.0)
		assertTrue(vector.up > 0.9f)
	}

	private fun sample(index: Int, time: Long, lat: Double, lon: Double) = FlightSample(
		index = index,
		legIndex = 0,
		timestampMillis = time,
		latitude = lat,
		longitude = lon,
		altitudeMeters = 10_000.0,
		speedMetersPerSecond = 240f,
		bearingDegrees = 90f,
		horizontalAccuracyMeters = 8f
	)
}
