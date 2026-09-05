package net.osmand.test.junit

import kotlinx.coroutines.runBlocking
import net.osmand.plus.plugins.flightmode.FlightLeg
import net.osmand.plus.plugins.flightmode.FlightCabinSide
import net.osmand.plus.plugins.flightmode.FlightPlan
import net.osmand.plus.plugins.flightmode.FlightPhotoColorMatrix
import net.osmand.plus.plugins.flightmode.FlightPhotoImageAdjustments
import net.osmand.plus.plugins.flightmode.FlightPhotoPerspective
import net.osmand.plus.plugins.flightmode.FlightPhotoTimestampParser
import net.osmand.plus.plugins.flightmode.FlightPhotoWindowAlignment
import net.osmand.plus.plugins.flightmode.FlightProfilePlanner
import net.osmand.plus.plugins.flightmode.FlightRecordingPolicy
import net.osmand.plus.plugins.flightmode.FlightReplayEngine
import net.osmand.plus.plugins.flightmode.FlightSample
import net.osmand.plus.plugins.flightmode.FlightSampleInterpolator
import net.osmand.plus.plugins.flightmode.FlightSatelliteSource
import net.osmand.plus.plugins.flightmode.FlightSatelliteQuality
import net.osmand.plus.plugins.flightmode.FlightSceneStreamingPolicy
import net.osmand.plus.plugins.flightmode.FlightStop
import net.osmand.plus.plugins.flightmode.FlightSunPosition
import net.osmand.plus.plugins.flightmode.FlightTerrainCoordinates
import net.osmand.plus.plugins.flightmode.FlightTerrainCpuScheduler
import net.osmand.plus.plugins.flightmode.FlightTerrainDetailFocus
import net.osmand.plus.plugins.flightmode.FlightTerrainGeometry
import net.osmand.plus.plugins.flightmode.FlightTerrainGeometryCacheKey
import net.osmand.plus.plugins.flightmode.FlightTerrainGeometryLodPolicy
import net.osmand.plus.plugins.flightmode.FlightTerrainLodPolicy
import net.osmand.plus.plugins.flightmode.FlightTerrainMeshBuilder
import net.osmand.plus.plugins.flightmode.FlightTerrainRefinementPolicy
import net.osmand.plus.plugins.flightmode.FlightTerrainTextureTier
import net.osmand.plus.plugins.flightmode.FlightTerrainTilePlanner
import net.osmand.plus.plugins.flightmode.FlightTrackMath
import net.osmand.plus.plugins.flightmode.FlightTrip
import net.osmand.plus.plugins.flightmode.FlightTripFingerprint
import net.osmand.plus.plugins.flightmode.FlightWindowLook
import net.osmand.plus.plugins.flightmode.FlightWindowGestureTarget
import net.osmand.plus.plugins.flightmode.FlightWindowPhotoOverlay
import net.osmand.plus.plugins.flightmode.FlightWindowPlacement
import net.osmand.plus.plugins.flightmode.FlightViewGeometry
import net.osmand.plus.plugins.flightmode.geometry
import net.osmand.plus.plugins.flightmode.dampedFlightPinchFactor
import net.osmand.plus.plugins.flightmode.flightReplayProgressAfterDrag
import net.osmand.plus.plugins.flightmode.flightTimelineWindow
import net.osmand.plus.plugins.flightmode.horizontalFieldOfViewDegrees
import net.osmand.plus.plugins.flightmode.linkedFlightWindowTransform
import net.osmand.plus.plugins.flightmode.minimumFlightTimelineWindowFraction
import net.osmand.plus.plugins.flightmode.stepFlightReplayProgress
import net.osmand.plus.plugins.flightmode.verticalFieldOfViewDegrees
import net.osmand.plus.plugins.flightmode.viewAzimuthDegrees
import net.osmand.plus.plugins.flightmode.TerrainTileId
import net.osmand.plus.plugins.flightmode.TerrainTilePlan
import net.osmand.plus.plugins.flightmode.TerrariumCodec
import net.osmand.plus.plugins.flightmode.TerrariumTile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FlightModeLogicTest {

	@Test
	fun sceneStreamingPolicyKeepsMovementAndRetentionConstraintsCentralized() {
		assertEquals(8.0, FlightSceneStreamingPolicy.AIRCRAFT_RETARGET_DISTANCE_KM, 0.0)
		assertEquals(1.5, FlightSceneStreamingPolicy.DETAIL_FOCUS_MINIMUM_CHANGE_KM, 0.0)
		assertEquals(100.0, FlightSceneStreamingPolicy.MAXIMUM_GAZE_FOCUS_DISTANCE_KM, 0.0)
		assertEquals(50.0, FlightSceneStreamingPolicy.NEARBY_RESOURCE_RETENTION_KM, 0.0)
		assertEquals(700L, FlightSceneStreamingPolicy.MANUAL_MOVEMENT_SETTLE_MILLIS)
		assertEquals(1_500L, FlightSceneStreamingPolicy.CAMERA_MOVEMENT_SETTLE_MILLIS)
		assertEquals(
			FlightSceneStreamingPolicy.NEARBY_RESOURCE_RETENTION_KM,
			FlightTerrainLodPolicy.NEARBY_DETAIL_RETENTION_KM,
			0.0
		)
	}

	@Test
	fun sceneStreamingPolicyIgnoresCameraJitterButAcceptsARealRetarget() {
		val origin = FlightTerrainDetailFocus(48.8566, 2.3522)
		val jitter = FlightTerrainDetailFocus(48.8616, 2.3522)
		val retarget = FlightTerrainDetailFocus(48.8766, 2.3522)

		assertFalse(FlightSceneStreamingPolicy.focusChanged(origin, jitter))
		assertTrue(FlightSceneStreamingPolicy.focusChanged(origin, retarget))
		assertTrue(FlightSceneStreamingPolicy.focusChanged(null, origin))
		assertFalse(FlightSceneStreamingPolicy.focusChanged(null, null))
	}

	@Test
	fun terrainGeometryWorkersAdaptToCpuCountWhileLeavingUiHeadroom() {
		assertEquals(1, FlightTerrainCpuScheduler.geometryWorkerCount(1))
		assertEquals(1, FlightTerrainCpuScheduler.geometryWorkerCount(2))
		assertEquals(2, FlightTerrainCpuScheduler.geometryWorkerCount(4))
		assertEquals(6, FlightTerrainCpuScheduler.geometryWorkerCount(8))
		assertEquals(7, FlightTerrainCpuScheduler.geometryWorkerCount(9))
		assertEquals(8, FlightTerrainCpuScheduler.geometryWorkerCount(16))
	}

	@Test
	fun parallelTerrainConstructionPreservesRequestedTileOrderAndGeometry() = runBlocking {
		val zoom = 8
		val tileIds = listOf(
			TerrainTileId(zoom, 141, 95),
			TerrainTileId(zoom, 142, 95),
			TerrainTileId(zoom, 141, 96),
			TerrainTileId(zoom, 142, 96)
		)
		val tiles = tileIds.associateWith { tileId ->
			TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { 120f })
		}
		val parallel = FlightTerrainMeshBuilder.buildParallel(
			centerLatitude = 42.0,
			centerLongitude = 19.0,
			radiusKm = 300,
			plan = TerrainTilePlan(zoom, tileIds),
			tiles = tiles,
			workerCount = 4
		)

		assertEquals(tileIds, parallel.meshes.map { it.tileId })
		assertEquals(tiles.size, parallel.loadedTiles)
		assertEquals(0, parallel.missingTiles)
		parallel.meshes.forEach { mesh ->
			assertEquals(120f, mesh.minimumElevationMeters, 0f)
			assertEquals(120f, mesh.maximumElevationMeters, 0f)
		}
	}

	@Test
	fun neutralPhotoAdjustmentsProduceIdentityColorMatrix() {
		assertArrayEquals(
			floatArrayOf(
				1f, 0f, 0f, 0f, 0f,
				0f, 1f, 0f, 0f, 0f,
				0f, 0f, 1f, 0f, 0f,
				0f, 0f, 0f, 1f, 0f
			),
			FlightPhotoColorMatrix.values(FlightPhotoImageAdjustments()),
			0f
		)
	}

	@Test
	fun photoAdjustmentsAreFiniteAndClampedBeforeRendering() {
		val safe = FlightPhotoImageAdjustments(
			brightness = 2f,
			contrast = -2f,
			temperature = Float.NaN,
			tint = Float.POSITIVE_INFINITY,
			saturation = 0.4f
		).clamped()

		assertEquals(1f, safe.brightness, 0f)
		assertEquals(-1f, safe.contrast, 0f)
		assertEquals(0f, safe.temperature, 0f)
		assertEquals(0f, safe.tint, 0f)
		assertEquals(0.4f, safe.saturation, 0f)
		assertTrue(FlightPhotoColorMatrix.values(safe).all(Float::isFinite))
	}

	@Test
	fun androidCameraFileNamesExposeTheirCaptureTime() {
		val names = listOf(
			"IMG_20260901_142355_815.jpg",
			"IMG_20260901_142355_XXX.jpg",
			"IMG_20260901_142355.jpg",
			"PXL_20260901_142355815.jpg",
			"IMG-2026-09-01-14-23-55.jpg"
		)
		val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
		names.forEach { name ->
			val timestamp = FlightPhotoTimestampParser.parse(name)
			assertTrue("Timestamp absent pour $name", timestamp != null)
			assertEquals("20260901142355", formatter.format(Date(timestamp!!)))
		}
	}

	@Test
	fun satelliteQualityDefaultsToOneLevelAboveSourceTiles() {
		assertEquals(
			FlightSatelliteQuality.HIGH,
			FlightPlan(listOf(FlightStop("A"), FlightStop("B"))).satelliteQuality
		)
	}

	@Test
	fun sunShadowsRemainEnabledByDefaultWithVisibleIntensity() {
		val plan = FlightPlan(listOf(FlightStop("A"), FlightStop("B")))
		assertTrue(plan.shadowsEnabled)
		assertEquals(0.85f, plan.shadowIntensity, 0f)
	}

	@Test
	fun loadedTrackCanDriveOfflineCorridorPlanning() {
		val samples = listOf(
			FlightSample(0, 0, 0L, 48.8566, 2.3522, null, null, null, null),
			FlightSample(1, 0, 1L, 48.2082, 16.3738, null, null, null, null)
		)
		val plan = FlightTerrainTilePlanner.trackCorridorPlan(samples, radiusKm = 300)
		assertTrue(plan != null && plan.tiles.isNotEmpty())
	}

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
	fun replayTimelineKeepsAFocusedWindowInsideTheWholeTrip() {
		val middle = flightTimelineWindow(0.5f, 0.1f)
		assertEquals(0.45f, middle.startProgress, 0.0001f)
		assertEquals(0.55f, middle.endProgress, 0.0001f)
		assertEquals(0.5f, middle.progressAt(0.5f), 0.0001f)

		val start = flightTimelineWindow(0.01f, 0.1f)
		assertEquals(0f, start.startProgress, 0f)
		assertEquals(0.1f, start.endProgress, 0.0001f)

		val end = flightTimelineWindow(0.99f, 0.1f)
		assertEquals(0.9f, end.startProgress, 0.0001f)
		assertEquals(1f, end.endProgress, 0f)
	}

	@Test
	fun replayTimelineCanZoomToFiveSecondsAndScrubOneHundredTimesMoreFinely() {
		val durationMillis = 4L * 60L * 60L * 1_000L
		val trip = FlightTrip(
			"four-hours",
			listOf(sample(0, 1_000L, 48.0, 2.0), sample(1, 1_000L + durationMillis, 49.0, 3.0)),
			emptyList(),
			true,
			1_000.0,
			"synthetic.gpx"
		)
		assertEquals(5_000.0 / durationMillis, minimumFlightTimelineWindowFraction(trip).toDouble(), 0.0000001)

		val coarse = flightReplayProgressAfterDrag(0.5f, 0.1f, 0.2f, 1f)
		val fine = flightReplayProgressAfterDrag(0.5f, 0.1f, 0.2f, 100f)
		assertEquals(0.52f, coarse, 0.0001f)
		assertEquals((coarse - 0.5f) / 100f, fine - 0.5f, 0.000001f)
		assertEquals(0.5f + 1_000f / durationMillis, stepFlightReplayProgress(trip, 0.5f, 1_000L), 0.000001f)
	}

	@Test
	fun replayWithoutTimestampsStepsExactlyOneRecordedPoint() {
		val trip = FlightTrip(
			"points",
			(0..10).map { index -> sample(index, 0L, 48.0 + index / 100.0, 2.0) },
			emptyList(),
			false,
			1_000.0,
			"synthetic.gpx"
		)
		assertEquals(0.6f, stepFlightReplayProgress(trip, 0.5f, 1_000L), 0.0001f)
		assertEquals(0.4f, stepFlightReplayProgress(trip, 0.5f, -1_000L), 0.0001f)
	}

	@Test
	fun photoTimeProducesAHundredthPrecisionVirtualPointAndInterpolatedData() {
		val samples = listOf(
			sample(0, 1_000L, 10.0, 20.0).copy(
				altitudeMeters = 100.0,
				speedMetersPerSecond = 100f,
				bearingDegrees = 350f,
				horizontalAccuracyMeters = 20f,
				satellitesUsed = 4
			),
			sample(1, 2_000L, 20.0, 40.0).copy(
				altitudeMeters = 300.0,
				speedMetersPerSecond = 200f,
				bearingDegrees = 10f,
				horizontalAccuracyMeters = 10f,
				satellitesUsed = 8
			)
		)
		val trip = FlightTrip(
			"photo",
			samples,
			listOf(FlightLeg(0, "Vol", 0, 1, 1_000.0, 1_000L, 2_000L)),
			true,
			1_000.0,
			"synthetic.gpx"
		)

		val position = FlightSampleInterpolator.positionAtTimestamp(trip, 1_870L, 15_000L)
			?.let(FlightSampleInterpolator::quantizePosition)
		assertEquals(0.87, position ?: -1.0, 0.0001)

		val virtual = FlightSampleInterpolator.sampleAt(trip, position)!!
		assertEquals(18.7, virtual.latitude, 0.0001)
		assertEquals(37.4, virtual.longitude, 0.0001)
		assertEquals(274.0, virtual.altitudeMeters ?: -1.0, 0.0001)
		assertEquals(187f, virtual.speedMetersPerSecond ?: -1f, 0.0001f)
		assertEquals(7.4f, virtual.bearingDegrees ?: -1f, 0.0001f)
		assertEquals(11.3f, virtual.horizontalAccuracyMeters ?: -1f, 0.0001f)
		assertEquals(8, virtual.satellitesUsed ?: -1)
	}

	@Test
	fun photoVirtualPointNeverInterpolatesAcrossTwoFlightLegs() {
		val samples = listOf(
			sample(0, 1_000L, 10.0, 20.0).copy(legIndex = 0),
			sample(1, 2_000L, 50.0, 60.0).copy(legIndex = 1)
		)
		val trip = FlightTrip(
			"legs",
			samples,
			emptyList(),
			true,
			1_000.0,
			"synthetic.gpx"
		)

		val virtual = FlightSampleInterpolator.sampleAt(trip, 0.87)!!
		assertEquals(samples[1].latitude, virtual.latitude, 0.0)
		assertEquals(samples[1].longitude, virtual.longitude, 0.0)
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
	fun photoOverlayKeepsGesturesBoundedWithoutLosingSelectedTarget() {
		val overlay = FlightWindowPhotoOverlay(
			photoId = "photo-1",
			opacity = 2f,
			scale = 20f,
			offsetXFraction = -4f,
			offsetYFraction = 4f,
			gestureTarget = FlightWindowGestureTarget.PHOTO
		).clamped()

		assertEquals(1f, overlay.opacity, 0f)
		assertEquals(FlightWindowPhotoOverlay.MAX_SCALE, overlay.scale, 0f)
		assertEquals(-FlightWindowPhotoOverlay.MAX_OFFSET_FRACTION, overlay.offsetXFraction, 0f)
		assertEquals(FlightWindowPhotoOverlay.MAX_OFFSET_FRACTION, overlay.offsetYFraction, 0f)
		assertEquals(FlightWindowGestureTarget.PHOTO, overlay.gestureTarget)
	}

	@Test
	fun photoWindowAlignmentKeepsTheCompleteReproducibleViewBounded() {
		val alignment = FlightPhotoWindowAlignment(
			opacity = 2f,
			scale = 20f,
			offsetXFraction = -4f,
			offsetYFraction = 4f,
			windowPlacement = FlightWindowPlacement(
				side = FlightCabinSide.RIGHT,
				forwardOffsetMeters = 4f,
				verticalOffsetMeters = -4f,
				zoom = 12f,
				cabinTransparent = true,
				cabinHidden = true
			),
			windowLook = FlightWindowLook(yawDegrees = 450f, pitchDegrees = 80f),
			altitudeOverrideMeters = 30_000f
		).clamped()

		assertEquals(1f, alignment.opacity, 0f)
		assertEquals(FlightWindowPhotoOverlay.MAX_SCALE, alignment.scale, 0f)
		assertEquals(-FlightWindowPhotoOverlay.MAX_OFFSET_FRACTION, alignment.offsetXFraction, 0f)
		assertEquals(FlightWindowPhotoOverlay.MAX_OFFSET_FRACTION, alignment.offsetYFraction, 0f)
		assertEquals(FlightCabinSide.RIGHT, alignment.windowPlacement.side)
		assertTrue(alignment.windowPlacement.cabinTransparent)
		assertTrue(alignment.windowPlacement.cabinHidden)
		assertEquals(FlightWindowPlacement.MAX_ZOOM, alignment.windowPlacement.zoom, 0f)
		assertEquals(90f, alignment.windowLook.yawDegrees, 0f)
		assertEquals(45f, alignment.windowLook.pitchDegrees, 0f)
		assertEquals(FlightPhotoWindowAlignment.MAX_ALTITUDE_OVERRIDE_METERS, alignment.altitudeOverrideMeters ?: 0f, 0f)
	}

	@Test
	fun calibratedPhotoStoresBothTrackRelativeAndWorldSpaceCameraPose() {
		val trip = FlightTrip(
			"photo-pose",
			listOf(
				sample(0, 1_000L, 48.0, 2.0).copy(altitudeMeters = 10_000.0, bearingDegrees = 90f),
				sample(1, 2_000L, 48.1, 2.2).copy(altitudeMeters = 11_000.0, bearingDegrees = 100f)
			),
			emptyList(),
			true,
			20_000.0,
			"synthetic.gpx"
		)
		val placement = FlightWindowPlacement(side = FlightCabinSide.LEFT, zoom = 1.4f)
		val look = FlightWindowLook(yawDegrees = 12f, pitchDegrees = -18f)

		val pose = FlightViewGeometry.photoSpatialPose(trip, 0.25, placement, look, null)!!

		assertEquals(0.25, pose.samplePosition, 0.0001)
		assertEquals(48.025, pose.eyeLatitude, 0.0001)
		assertEquals(2.05, pose.eyeLongitude, 0.0001)
		assertEquals(10_250f, pose.eyeAltitudeMeters ?: -1f, 0.01f)
		assertEquals(92.5f, pose.aircraftBearingDegrees, 0.01f)
		assertEquals(14.5f, pose.viewAzimuthDegrees, 0.01f)
		assertEquals(-18f, pose.viewElevationDegrees, 0.01f)
		assertEquals(placement.verticalFieldOfViewDegrees(), pose.verticalFieldOfViewDegrees, 0.001f)
	}

	@Test
	fun downwardWindowLookTargetsGroundButSkyAndDistantHorizonDoNot() {
		val aircraft = sample(0, 1_000L, 48.0, 2.0).copy(
			altitudeMeters = 10_000.0,
			bearingDegrees = 90f
		)
		val placement = FlightWindowPlacement(side = FlightCabinSide.LEFT)
		val ground = FlightViewGeometry.groundDetailFocus(
			aircraft,
			placement,
			FlightWindowLook(pitchDegrees = -45f),
			null
		)!!

		assertEquals(10.0, FlightTerrainTilePlanner.distanceKm(48.0, 2.0, ground.latitude, ground.longitude), 0.02)
		assertTrue(ground.latitude > 48.0)
		assertTrue(
			FlightViewGeometry.groundDetailFocus(
				aircraft,
				placement,
				FlightWindowLook(pitchDegrees = 5f),
				null
			) == null
		)
		assertTrue(
			FlightViewGeometry.groundDetailFocus(
				aircraft,
				placement,
				FlightWindowLook(pitchDegrees = -2f),
				null
			) == null
		)
	}

	@Test
	fun sharedViewElevationUsesTheSameLimitsAsTheOpenGlCamera() {
		assertEquals(
			45f,
			FlightViewGeometry.viewElevationDegrees(
				FlightWindowPlacement(verticalOffsetMeters = FlightWindowPlacement.MAX_VERTICAL_OFFSET_METERS),
				FlightWindowLook(pitchDegrees = FlightWindowLook.MAX_PITCH_DEGREES)
			),
			0f
		)
		assertEquals(
			-89f,
			FlightViewGeometry.viewElevationDegrees(
				FlightWindowPlacement(verticalOffsetMeters = FlightWindowPlacement.MIN_VERTICAL_OFFSET_METERS),
				FlightWindowLook(pitchDegrees = FlightWindowLook.MIN_PITCH_DEGREES)
			),
			0f
		)
	}

	@Test
	fun hublotPinchUsesHalfTheLogarithmicZoomMovement() {
		assertEquals(2f, dampedFlightPinchFactor(4f), 0.0001f)
		assertEquals(0.5f, dampedFlightPinchFactor(0.25f), 0.0001f)
		assertEquals(1f, dampedFlightPinchFactor(Float.NaN), 0f)
	}

	@Test
	fun linkedHublotGestureMovesCameraAndPhotoWithTheSameProjection() {
		val placement = FlightWindowPlacement(zoom = 1f)
		val overlay = FlightWindowPhotoOverlay(
			photoId = "photo-1",
			scale = 1.2f,
			offsetXFraction = 0.10f,
			offsetYFraction = -0.05f,
			gestureTarget = FlightWindowGestureTarget.LINKED
		)
		val result = linkedFlightWindowTransform(
			placement = placement,
			look = FlightWindowLook(),
			photoOverlay = overlay,
			panXFraction = 0.08f,
			panYFraction = -0.04f,
			rawZoomFactor = 1.44f,
			viewAspectRatio = 1.5f
		)
		val expectedMagnification = (
			kotlin.math.tan(Math.toRadians((placement.verticalFieldOfViewDegrees() / 2f).toDouble())) /
				kotlin.math.tan(Math.toRadians((result.placement.verticalFieldOfViewDegrees() / 2f).toDouble()))
		).toFloat()
		val horizontalFovAfter = result.placement.horizontalFieldOfViewDegrees(1.5f)
		val expectedPanX = -0.5f * (
			kotlin.math.tan(Math.toRadians(result.look.yawDegrees.toDouble())) /
				kotlin.math.tan(Math.toRadians((horizontalFovAfter / 2f).toDouble()))
		).toFloat()
		val expectedPanY = 0.5f * (
			kotlin.math.tan(Math.toRadians(result.look.pitchDegrees.toDouble())) /
				kotlin.math.tan(Math.toRadians((result.placement.verticalFieldOfViewDegrees() / 2f).toDouble()))
		).toFloat()

		assertTrue(result.look.yawDegrees < 0f)
		assertTrue(result.look.pitchDegrees < 0f)
		assertEquals(1.2f, result.placement.zoom, 0.0001f)
		assertEquals(overlay.scale * expectedMagnification, result.photoOverlay.scale, 0.0001f)
		assertEquals(overlay.offsetXFraction * expectedMagnification + expectedPanX, result.photoOverlay.offsetXFraction, 0.0001f)
		assertEquals(overlay.offsetYFraction * expectedMagnification + expectedPanY, result.photoOverlay.offsetYFraction, 0.0001f)
		assertEquals(FlightWindowGestureTarget.LINKED, result.photoOverlay.gestureTarget)
	}

	@Test
	fun photoPerspectiveUsesEquivalentLensAndDisplayedOrientation() {
		val landscape = FlightPhotoPerspective.verticalFieldOfViewFrom35mm(24.0, 4_000, 3_000)
		val portrait = FlightPhotoPerspective.verticalFieldOfViewFrom35mm(24.0, 3_000, 4_000)

		assertTrue(landscape != null && landscape in 55f..58f)
		assertTrue(portrait != null && portrait in 70f..73f)
		assertEquals(
			FlightWindowPlacement.DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES / landscape!!,
			FlightPhotoPerspective.windowZoomForVerticalFieldOfView(landscape),
			0.0001f
		)
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
	fun localTerrainCoordinatesKeepOneMetreOfVerticalScaleAsOneMetre() {
		val projection = FlightTerrainCoordinates(42.0, 19.0)
		val point = projection.toLocal(42.0, 19.0, 1_234.5)

		assertEquals(0f, point[0], 0.01f)
		assertEquals(1_234.5f, point[1], 0.01f)
		assertEquals(0f, point[2], 0.01f)
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
		val mesh = scene.meshes.single()
		assertEquals(33 * 33 * FlightTerrainMeshBuilder.VERTEX_COMPONENTS, mesh.vertices.size)
		assertEquals(32 * 32 * 6, mesh.indices.size)
		assertEquals(32, mesh.gridQuads)
		assertEquals(256, mesh.sourceWidthPixels)
		assertEquals(256, mesh.sourceHeightPixels)
		assertEquals(120f, mesh.minimumElevationMeters, 0f)
		assertEquals(120f, mesh.maximumElevationMeters, 0f)
	}

	@Test
	fun terrainGeometryKeepsNearlyEverySourceSampleAroundTheAircraft() {
		val zoom = 8
		val tileId = TerrainTileId(
			zoom,
			FlightTerrainTilePlanner.longitudeToTileX(19.0, zoom).toInt(),
			FlightTerrainTilePlanner.latitudeToTileY(42.0, zoom).toInt()
		)
		val tile = TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { it.toFloat() })
		val scene = FlightTerrainMeshBuilder.build(
			centerLatitude = 42.0,
			centerLongitude = 19.0,
			radiusKm = 300,
			plan = TerrainTilePlan(zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile),
			geometryQuadsByTile = mapOf(tileId to FlightTerrainMeshBuilder.MAXIMUM_GRID_QUADS)
		)

		assertEquals(
			256 * 256 * FlightTerrainMeshBuilder.VERTEX_COMPONENTS,
			scene.meshes.single().vertices.size
		)
		assertEquals(255 * 255 * 6, scene.meshes.single().indices.size)
	}

	@Test
	fun terrainGeometryLodIsDetailedNearbyAndIndependentFromTextureQuality() {
		assertEquals(255, FlightTerrainGeometryLodPolicy.quadsForDistance(300, 0.0))
		assertEquals(128, FlightTerrainGeometryLodPolicy.quadsForDistance(300, 80.0))
		assertEquals(32, FlightTerrainGeometryLodPolicy.quadsForDistance(300, 200.0))
		assertEquals(255, FlightTerrainGeometryLodPolicy.stableQuadsForDistance(300, 40.0, 255))
		assertEquals(128, FlightTerrainGeometryLodPolicy.stableQuadsForDistance(300, 50.01, 255))
		assertEquals(255, FlightTerrainRefinementPolicy.FINE_GRID_QUADS)
		assertEquals(128, FlightTerrainRefinementPolicy.MIDDLE_GRID_QUADS)
	}

	@Test
	fun terrariumZoomTwelveIsTwiceAsFineAsZoomEleven() {
		val zoom11 = FlightTerrainTilePlanner.groundResolutionMeters(45.0, 11)
		val zoom12 = FlightTerrainTilePlanner.groundResolutionMeters(45.0, 12)

		assertEquals(zoom11 / 2.0, zoom12, 0.0001)
		assertTrue(zoom11 in 53.0..56.0)
		assertTrue(zoom12 in 26.0..28.0)
	}

	@Test
	fun refinementPlannerKeepsTheFinePatchBoundedAtTheRequestedZoom() {
		val plan = FlightTerrainTilePlanner.refinementPlan(
			foci = listOf(
				FlightTerrainDetailFocus(48.0, 2.0),
				FlightTerrainDetailFocus(48.2, 2.4)
			),
			radiusKm = FlightTerrainRefinementPolicy.FINE_RADIUS_KM,
			zoom = 12,
			maxTiles = FlightTerrainRefinementPolicy.MAXIMUM_FINE_TILES
		)

		assertEquals(12, plan.zoom)
		assertTrue(plan.tiles.isNotEmpty())
		assertTrue(plan.tiles.size <= FlightTerrainRefinementPolicy.MAXIMUM_FINE_TILES)
		assertTrue(plan.tiles.all { it.zoom == 12 })
	}

	@Test
	fun refinementPlannerAcceptsZoomFourteenWithoutSilentlyClampingIt() {
		val plan = FlightTerrainTilePlanner.refinementPlan(
			foci = listOf(FlightTerrainDetailFocus(48.0, 2.0)),
			radiusKm = 1.0,
			zoom = 14,
			maxTiles = 12
		)

		assertEquals(14, plan.zoom)
		assertTrue(plan.tiles.isNotEmpty())
		assertTrue(plan.tiles.all { it.zoom == 14 })
	}

	@Test
	fun refinementMeshReusesTheCorrectPartOfItsParentSatelliteTexture() {
		val base = TerrainTileId(10, 518, 352)
		val child = TerrainTileId(12, base.x * 4 + 2, base.y * 4 + 1)
		val baseTerrain = TerrariumTile(base, 256, 256, FloatArray(256 * 256) { 100f })
		val childTerrain = TerrariumTile(child, 256, 256, FloatArray(256 * 256) { 150f })
		val mesh = FlightTerrainMeshBuilder.buildRefinementMeshes(
			baseZoom = base.zoom,
			plan = TerrainTilePlan(child.zoom, listOf(child)),
			tiles = mapOf(child to childTerrain),
			boundaryZoom = base.zoom,
			boundaryTiles = mapOf(base to baseTerrain),
			baseSatelliteTexturePaths = mapOf(base to "parent.jpg"),
			baseStandardSatelliteTexturePaths = mapOf(base to "parent-standard.jpg"),
			baseSatelliteTextureTiers = mapOf(base to FlightTerrainTextureTier.HIGH),
			coordinateOriginLatitude = 48.0,
			coordinateOriginLongitude = 2.0,
			geometryQuadsByTile = mapOf(child to 32),
			geometryCache = null
		).single()
		val lastVertexOffset = mesh.vertices.size - FlightTerrainMeshBuilder.VERTEX_COMPONENTS

		assertEquals(2, mesh.refinementLevel)
		assertEquals("parent.jpg", mesh.satelliteTexturePath)
		assertEquals(0.5f, mesh.vertices[7], 0.0001f)
		assertEquals(0.25f, mesh.vertices[8], 0.0001f)
		assertEquals(0.75f, mesh.vertices[lastVertexOffset + 7], 0.0001f)
		assertEquals(0.5f, mesh.vertices[lastVertexOffset + 8], 0.0001f)
	}

	@Test
	fun terrainGeometryDoesNotClampPlacesBelowSeaLevel() {
		val tileId = TerrainTileId(8, 141, 95)
		val north = FlightTerrainTilePlanner.tileYToLatitude(tileId.y.toDouble(), tileId.zoom)
		val west = FlightTerrainTilePlanner.tileXToLongitude(tileId.x.toDouble(), tileId.zoom)
		val tile = TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { -100f })
		val scene = FlightTerrainMeshBuilder.build(
			centerLatitude = north,
			centerLongitude = west,
			radiusKm = 50,
			plan = TerrainTilePlan(tileId.zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile)
		)

		assertEquals(-100f, scene.meshes.single().vertices[1], 0.05f)
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
	fun terrainMeshCarriesDrapedTexturePathsAndSharedUvCoordinates() {
		val zoom = 8
		val tileId = TerrainTileId(zoom, 141, 95)
		val tile = TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { 100f })
		val texturePath = "/synthetic/satellite.jpg"
		val nativeMapTexturePath = "/synthetic/osmand-map.png"
		val scene = FlightTerrainMeshBuilder.build(
			centerLatitude = 42.0,
			centerLongitude = 19.0,
			radiusKm = 50,
			plan = TerrainTilePlan(zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile),
			satelliteTexturePaths = mapOf(tileId to texturePath),
			nativeMapTexturePaths = mapOf(tileId to nativeMapTexturePath)
		)
		val mesh = scene.meshes.single()

		assertEquals(texturePath, mesh.satelliteTexturePath)
		assertEquals(nativeMapTexturePath, mesh.nativeMapTexturePath)
		assertTrue(scene.nativeMapRequested)
		assertEquals(0f, mesh.vertices[7], 0f)
		assertEquals(0f, mesh.vertices[8], 0f)
		val lastVertexOffset = (33 * 33 - 1) * FlightTerrainMeshBuilder.VERTEX_COMPONENTS
		assertEquals(1f, mesh.vertices[lastVertexOffset + 7], 0f)
		assertEquals(1f, mesh.vertices[lastVertexOffset + 8], 0f)
	}

	@Test
	fun ultraTerrainUsesRealDistanceRingsAndAnImmediateOverview() {
		assertEquals(
			FlightTerrainTextureTier.ULTRA,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA, 300, 10.0)
		)
		assertEquals(
			FlightTerrainTextureTier.HIGH,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA, 300, 50.0)
		)
		assertEquals(
			FlightTerrainTextureTier.STANDARD,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA, 300, 120.0)
		)
		assertEquals(
			FlightTerrainTextureTier.OVERVIEW,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA, 300, 250.0)
		)
		assertEquals(
			FlightTerrainTextureTier.ULTRA,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA_PLUS, 300, 40.0)
		)
		assertEquals(
			FlightTerrainTextureTier.HIGH,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA_PLUS, 300, 80.0)
		)
		assertEquals(
			FlightTerrainTextureTier.ULTRA_PLUS_PLUS,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA_PLUS_PLUS, 300, 2.0)
		)
		assertEquals(
			FlightTerrainTextureTier.ULTRA_PLUS_PLUS_PLUS,
			FlightTerrainLodPolicy.tierForDistance(FlightSatelliteQuality.ULTRA_PLUS_PLUS_PLUS, 300, 1.0)
		)
	}

	@Test
	fun gazeFocusAddsAtLeastHighDetailWithoutReducingAircraftDetail() {
		assertEquals(
			FlightTerrainTextureTier.HIGH,
			FlightTerrainLodPolicy.tierForFoci(
				requested = FlightSatelliteQuality.STANDARD,
				radiusKm = 300,
				aircraftDistanceKm = 250.0,
				detailFocusDistanceKm = 0.0
			)
		)
		assertEquals(
			FlightTerrainTextureTier.ULTRA_PLUS,
			FlightTerrainLodPolicy.tierForFoci(
				requested = FlightSatelliteQuality.ULTRA_PLUS,
				radiusKm = 300,
				aircraftDistanceKm = 0.0,
				detailFocusDistanceKm = 80.0
			)
		)
	}

	@Test
	fun detailedTextureOnlyStepsDownAfterItsWholeTileLeavesFiftyKilometres() {
		assertTrue(
			FlightTerrainLodPolicy.shouldRetainNearbyDetail(
				previous = FlightTerrainTextureTier.ULTRA_PLUS,
				raw = FlightTerrainTextureTier.STANDARD,
				aircraftNearestDistanceKm = 50.0
			)
		)
		assertFalse(
			FlightTerrainLodPolicy.shouldRetainNearbyDetail(
				previous = FlightTerrainTextureTier.ULTRA_PLUS,
				raw = FlightTerrainTextureTier.STANDARD,
				aircraftNearestDistanceKm = 50.01
			)
		)
		assertFalse(
			FlightTerrainLodPolicy.shouldRetainNearbyDetail(
				previous = FlightTerrainTextureTier.STANDARD,
				raw = FlightTerrainTextureTier.ULTRA,
				aircraftNearestDistanceKm = 0.0
			)
		)
	}

	@Test
	fun textureQualityAndSceneCenterDoNotRebuildCachedTerrainGeometry() {
		val zoom = 8
		val tileId = TerrainTileId(zoom, 141, 95)
		val tile = TerrariumTile(tileId, 256, 256, FloatArray(256 * 256) { 100f })
		val cache = linkedMapOf<FlightTerrainGeometryCacheKey, FlightTerrainGeometry>()
		val standard = FlightTerrainMeshBuilder.build(
			centerLatitude = 42.0,
			centerLongitude = 19.0,
			radiusKm = 300,
			plan = TerrainTilePlan(zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile),
			satelliteQuality = FlightSatelliteQuality.STANDARD,
			coordinateOriginLatitude = 42.0,
			coordinateOriginLongitude = 19.0,
			geometryCache = cache
		)
		val ultra = FlightTerrainMeshBuilder.build(
			centerLatitude = 42.1,
			centerLongitude = 19.1,
			radiusKm = 300,
			plan = TerrainTilePlan(zoom, listOf(tileId)),
			tiles = mapOf(tileId to tile),
			satelliteQuality = FlightSatelliteQuality.ULTRA,
			coordinateOriginLatitude = 42.0,
			coordinateOriginLongitude = 19.0,
			geometryCache = cache
		)

		assertSame(standard.meshes.single().vertices, ultra.meshes.single().vertices)
		assertSame(standard.meshes.single().indices, ultra.meshes.single().indices)
	}

	@Test
	fun missingTerrainCanUseAnImmediateFourVertexPlaceholder() {
		val tileId = TerrainTileId(8, 141, 95)
		val scene = FlightTerrainMeshBuilder.build(
			centerLatitude = 42.0,
			centerLongitude = 19.0,
			radiusKm = 300,
			plan = TerrainTilePlan(8, listOf(tileId)),
			tiles = emptyMap(),
			coordinateOriginLatitude = 42.0,
			coordinateOriginLongitude = 19.0,
			includePlaceholders = true
		)

		assertEquals(1, scene.meshes.size)
		assertEquals(4 * FlightTerrainMeshBuilder.VERTEX_COMPONENTS, scene.meshes.single().vertices.size)
		assertEquals(6, scene.meshes.single().indices.size)
		assertFalse(scene.meshes.single().terrainAvailable)
		assertEquals(1, scene.meshes.single().gridQuads)
		assertEquals(0, scene.meshes.single().sourceWidthPixels)
		assertEquals(0f, scene.meshes.single().minimumElevationMeters, 0f)
		assertEquals(0f, scene.meshes.single().maximumElevationMeters, 0f)
		assertEquals(0, scene.loadedTiles)
		assertEquals(1, scene.missingTiles)
	}

	@Test
	fun stableTerrainOriginTransformsLocalDirectionsWithoutChangingTheirLength() {
		val coordinates = FlightTerrainCoordinates(42.0, 19.0)
		val atOrigin = coordinates.vectorToLocal(42.0, 19.0, 1f, 0f, 0f)
		assertEquals(1f, atOrigin[0], 1e-5f)
		assertEquals(0f, atOrigin[1], 1e-5f)
		assertEquals(0f, atOrigin[2], 1e-5f)

		val distantUp = coordinates.vectorToLocal(48.0, 2.0, 0f, 1f, 0f)
		val length = kotlin.math.sqrt(
			distantUp[0] * distantUp[0] + distantUp[1] * distantUp[1] + distantUp[2] * distantUp[2]
		)
		assertEquals(1f, length, 1e-5f)
	}

	@Test
	fun equatorialNoonSunPointsMostlyUp() {
		val vector = FlightSunPosition.direction(1710936000000L, 0.0, 0.0)
		assertTrue(vector.up > 0.9f)
	}

	@Test
	fun tripFingerprintRecognizesTheSameGpxAfterDerivedFieldsChange() {
		val samples = listOf(
			sample(0, 1_000L, 48.8566, 2.3522),
			sample(1, 2_000L, 48.8570, 2.3530)
		)
		val original = FlightTrip(
			"original.gpx",
			samples,
			listOf(FlightLeg(0, "segment", 0, 1, 50.0, 1_000L, 2_000L)),
			true,
			50.0,
			"original.gpx"
		)
		val reopened = original.copy(
			name = "nom modifié",
			sourceDescription = "archive",
			samples = samples.map { it.copy(bearingDegrees = null, speedMetersPerSecond = null) }
		)

		assertEquals(FlightTripFingerprint.create(original), FlightTripFingerprint.create(reopened))
		assertFalse(
			FlightTripFingerprint.create(original) ==
				FlightTripFingerprint.create(reopened.copy(samples = reopened.samples.dropLast(1)))
		)
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
