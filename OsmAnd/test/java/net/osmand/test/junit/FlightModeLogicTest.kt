package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightLeg
import net.osmand.plus.plugins.flightmode.FlightCabinSide
import net.osmand.plus.plugins.flightmode.FlightPlan
import net.osmand.plus.plugins.flightmode.FlightProfilePlanner
import net.osmand.plus.plugins.flightmode.FlightRecordingPolicy
import net.osmand.plus.plugins.flightmode.FlightReplayEngine
import net.osmand.plus.plugins.flightmode.FlightSample
import net.osmand.plus.plugins.flightmode.FlightSatelliteSource
import net.osmand.plus.plugins.flightmode.FlightStop
import net.osmand.plus.plugins.flightmode.FlightSunPosition
import net.osmand.plus.plugins.flightmode.FlightTerrainCoordinates
import net.osmand.plus.plugins.flightmode.FlightTerrainMeshBuilder
import net.osmand.plus.plugins.flightmode.FlightTerrainTilePlanner
import net.osmand.plus.plugins.flightmode.FlightTrackMath
import net.osmand.plus.plugins.flightmode.FlightTrip
import net.osmand.plus.plugins.flightmode.FlightWindowLook
import net.osmand.plus.plugins.flightmode.FlightWindowPlacement
import net.osmand.plus.plugins.flightmode.geometry
import net.osmand.plus.plugins.flightmode.viewAzimuthDegrees
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
	fun recordedProfileUsesTheSameTimelineAsReplayWithoutInventingStopNames() {
		val samples = listOf(
			sample(0, 1_000L, 48.0, 2.0).copy(altitudeMeters = 100.0),
			sample(1, 11_000L, 48.1, 2.1).copy(altitudeMeters = 3_000.0),
			sample(2, 101_000L, 49.0, 3.0).copy(altitudeMeters = 10_000.0)
		)
		val trip = FlightTrip(
			"recorded",
			samples,
			listOf(FlightLeg(0, "segment GPX", 0, 2, 1_000.0, 1_000L, 101_000L)),
			true,
			1_000.0,
			"synthetic.gpx"
		)

		val profile = FlightProfilePlanner.fromTrip(trip)

		assertTrue(profile.recorded)
		assertEquals(0.10f, profile.points[1].progress, 0.0001f)
		assertTrue(profile.legs.all { it.from.name.isBlank() && it.to.name.isBlank() })
	}

	@Test
	fun cruiseDefaultsToAboutOnePointPerKilometre() {
		val policy = FlightRecordingPolicy()
		val interval = policy.intervalSeconds(250f)
		assertEquals(4f, interval, 0.01f)
	}

	@Test
	fun windowPlacementIsBoundedToPlausibleCabinGeometry() {
		val placement = FlightWindowPlacement(
			forwardOffsetMeters = 2f,
			verticalOffsetMeters = -2f,
			zoom = 12f
		).clamped()
		assertEquals(FlightWindowPlacement.MAX_FORWARD_OFFSET_METERS, placement.forwardOffsetMeters, 0f)
		assertEquals(FlightWindowPlacement.MIN_VERTICAL_OFFSET_METERS, placement.verticalOffsetMeters, 0f)
		assertEquals(FlightWindowPlacement.MAX_ZOOM, placement.zoom, 0f)
	}

	@Test
	fun windowLookWrapsHorizontallyAndOnlyLimitsTheVerticalView() {
		val look = FlightWindowLook(yawDegrees = 450f, pitchDegrees = 80f).clamped()
		assertEquals(90f, look.yawDegrees, 0f)
		assertEquals(45f, look.pitchDegrees, 0f)
		assertEquals(-90f, FlightWindowLook(yawDegrees = -450f).clamped().yawDegrees, 0f)
	}

	@Test
	fun missingGpxBearingsAreDerivedFromTheTrack() {
		val eastbound = listOf(
			sample(0, 0L, 48.0, 2.0).copy(bearingDegrees = null),
			sample(1, 1_000L, 48.0, 2.1).copy(bearingDegrees = null)
		)
		val resolved = FlightTrackMath.fillMissingBearings(eastbound)
		assertEquals(90f, resolved.first().bearingDegrees ?: -1f, 0.2f)
		assertEquals(90f, resolved.last().bearingDegrees ?: -1f, 0.2f)
	}

	@Test
	fun missingHeadingPointsToTheNextPointWithoutCrossingFlightLegs() {
		val samples = listOf(
			sample(0, 0L, 48.0, 2.0).copy(legIndex = 0, bearingDegrees = null),
			sample(1, 1_000L, 48.1, 2.0).copy(legIndex = 0, bearingDegrees = null),
			sample(2, 2_000L, 48.1, 3.0).copy(legIndex = 1, bearingDegrees = null),
			sample(3, 3_000L, 48.1, 3.1).copy(legIndex = 1, bearingDegrees = null)
		)

		val resolved = FlightTrackMath.fillMissingBearings(samples)

		assertEquals(0f, resolved[0].bearingDegrees ?: -1f, 0.2f)
		assertEquals(0f, resolved[1].bearingDegrees ?: -1f, 0.2f)
		assertEquals(90f, resolved[2].bearingDegrees ?: -1f, 0.2f)
		assertEquals(90f, resolved[3].bearingDegrees ?: -1f, 0.2f)
	}

	@Test
	fun windowViewUsesAircraftHeadingAndCabinSideConsistently() {
		val eastbound = 90f
		assertEquals(
			0f,
			FlightWindowPlacement(side = FlightCabinSide.LEFT)
				.viewAzimuthDegrees(eastbound, FlightWindowLook()),
			0.001f
		)
		assertEquals(
			180f,
			FlightWindowPlacement(side = FlightCabinSide.RIGHT)
				.viewAzimuthDegrees(eastbound, FlightWindowLook()),
			0.001f
		)
	}

	@Test
	fun windowGeometryDrivesAnObliqueViewTowardTheWindowCenter() {
		val centered = FlightWindowPlacement().geometry()
		assertEquals((-Math.PI / 2.0).toFloat(), centered.relativeAzimuthRadians, 0.0001f)
		assertEquals(0f, centered.elevationRadians, 0f)
		assertEquals(1f, centered.horizontalIncidence, 0f)

		val aheadAndAbove = FlightWindowPlacement(
			forwardOffsetMeters = 0.60f,
			verticalOffsetMeters = 0.20f
		).geometry()
		assertTrue(kotlin.math.abs(aheadAndAbove.relativeAzimuthRadians) < (Math.PI / 2.0).toFloat())
		assertTrue(aheadAndAbove.elevationRadians > 0f)
		assertTrue(aheadAndAbove.horizontalIncidence < 1f)
		assertTrue(aheadAndAbove.verticalIncidence < 1f)
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
	fun satelliteUsesTheSameWebMercatorTileWithWmtsRowBeforeColumn() {
		val tile = TerrainTileId(8, 141, 95)
		assertEquals(
			"https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/8/95/141.jpg",
			FlightSatelliteSource.tileUrl(tile)
		)
	}

	@Test
	fun terrainMeshCarriesSatelliteUvCoordinatesAndTexturePath() {
		val zoom = 8
		val tileId = TerrainTileId(zoom, 141, 95)
		val tile = TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { 100f })
		val texturePath = "/synthetic/satellite.jpg"
		val mesh = FlightTerrainMeshBuilder.build(
			centerLatitude = 42.0,
			centerLongitude = 19.0,
			radiusKm = 50,
			plan = TerrainTilePlan(zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile),
			satelliteTexturePaths = mapOf(tileId to texturePath)
		).meshes.single()

		assertEquals(texturePath, mesh.satelliteTexturePath)
		assertEquals(0f, mesh.vertices[7], 0f)
		assertEquals(0f, mesh.vertices[8], 0f)
		val lastVertexOffset = (33 * 33 - 1) * FlightTerrainMeshBuilder.VERTEX_COMPONENTS
		assertEquals(1f, mesh.vertices[lastVertexOffset + 7], 0f)
		assertEquals(1f, mesh.vertices[lastVertexOffset + 8], 0f)
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
