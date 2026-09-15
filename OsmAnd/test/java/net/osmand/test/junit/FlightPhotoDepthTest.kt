package net.osmand.test.junit

import kotlin.math.*
import net.osmand.plus.plugins.flightmode.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic in-memory meshes and photo metadata: never load user images, cache, or journals. */
class FlightPhotoDepthTest {
    private val pose = FlightPhotoSpatialPose(0.0, null, 0.0, 0.0, 10000f, 0f, 0f, -90f, 60f, 1f)

    private fun scene(vararg meshes: FlightTerrainMesh) =
        FlightTerrainScene(
            0.0,
            0.0,
            null,
            0.0,
            0.0,
            100,
            10,
            12,
            11,
            FlightSatelliteQuality.STANDARD,
            meshes.toList(),
            meshes.size,
            0,
            0,
            0,
            0,
            false,
            0f,
            1L,
        )

    private fun ground(
        height: Float = 0f,
        x0: Float = -30000f,
        x1: Float = 30000f,
    ): FlightTerrainMesh {
        val vertices = FloatArray(4 * 9)
        for (y in 0..1) for (x in 0..1) {
            val i = (y * 2 + x) * 9
            vertices[i] = if (x == 0) x0 else x1
            vertices[i + 1] = height
            vertices[i + 2] = if (y == 0) -30000f else 30000f
            vertices[i + 6] = height
        }
        return FlightTerrainMesh(
            TerrainTileId(10, 0, 0),
            vertices,
            shortArrayOf(0, 2, 1, 1, 2, 3),
            gridQuads = 1,
        )
    }

    private fun photo() =
        FlightPhotoAttachment(
            "synthetic",
            "synthetic.jpg",
            "not-a-real-file",
            null,
            0.0,
            windowAlignment = FlightPhotoWindowAlignment(spatialPose = pose),
            calibration = FlightPhotoCalibration(imageWidth = 1600, imageHeight = 1600),
        )

    @Test
    fun cameraAboveFlatGroundHasCorrectMetricRange() {
        val projection = FlightPhotoProjection(pose, 1f)
        val result = FlightPhotoDepth.calculate(projection, scene(ground()))
        assertEquals(100, result.coveragePercent)
        assertEquals(10f, result.minimumDistanceKm, .02f)
        assertEquals(12.9f, result.maximumDistanceKm, .2f)
        assertTrue(result.opticalKm.all { it != null && it in 5f..9f })
    }

    @Test
    fun occlusionUsesNearestSurfaceRegardlessOfMeshOrder() {
        val projection = FlightPhotoProjection(pose, 1f)
        val a = FlightPhotoDepth.calculate(projection, scene(ground(), ground(5000f)))
        val b = FlightPhotoDepth.calculate(projection, scene(ground(5000f), ground()))
        assertEquals(a, b)
        assertEquals(5f, a.minimumDistanceKm, .02f)
        assertTrue(a.maximumDistanceKm < 7f)
    }

    @Test
    fun skyAndPlaceholderTerrainStayUnknown() {
        val down = FlightPhotoProjection(pose, 1f)
        assertEquals(
            0,
            FlightPhotoDepth.calculate(down, scene(ground().copy(terrainAvailable = false)))
                .coveragePercent,
        )
        val sky = down.copy(pose = pose.copy(viewElevationDegrees = 90f))
        assertEquals(0, FlightPhotoDepth.calculate(sky, scene(ground())).coveragePercent)
        assertEquals(0, FlightPhotoDepth.calculate(down, scene()).coveragePercent)
    }

    @Test
    fun rotationOffsetsAndZoomFollowTheSamePhotoPlane() {
        val half = scene(ground(x0 = 0f))
        val projection = FlightPhotoProjection(pose, 1f)
        val before = FlightPhotoDepth.calculate(projection, half)
        val rotated = FlightPhotoDepth.calculate(projection.copy(rotation = 180f), half)
        assertTrue(before.coveragePercent in 49..51)
        assertNotNull(before.opticalKm[32 * 64 + 50])
        assertNull(before.opticalKm[32 * 64 + 10])
        assertNull(rotated.opticalKm[32 * 64 + 50])
        assertNotNull(rotated.opticalKm[32 * 64 + 10])
        val shifted = FlightPhotoDepth.calculate(projection.copy(offsetX = .6f, scale = .2f), half)
        assertEquals(100, shifted.coveragePercent)
    }

    @Test
    fun densityIntegratesDistanceAndAltitudeNotJustHorizontalRange() {
        val near = FlightPhotoDepth.atmosphericPathKm(20.0, 10000.0, 0.0)
        val far = FlightPhotoDepth.atmosphericPathKm(100.0, 10000.0, 0.0)
        assertTrue(far > near * 4.9 && far < near * 5.2)
        val high = FlightPhotoDepth.atmosphericPathKm(100.0, 12000.0, 10000.0)
        assertTrue(high < far * .6)
        assertEquals(0.0, FlightPhotoDepth.atmosphericPathKm(0.0, 10000.0, 0.0), 0.0)
    }

    @Test
    fun signatureTracksOnlyPhotoOptics() {
        val original = FlightPhotoProjection(pose, 1f)
        val signature = original.signature()
        assertEquals(
            signature,
            original
                .copy(
                    pose =
                        pose.copy(
                            timestampMillis = 42L,
                            samplePosition = 300.5,
                            aircraftBearingDegrees = 234f,
                        )
                )
                .signature(),
        )
        for (p in
            listOf(
                original.copy(scale = 2f),
                original.copy(rotation = 22f),
                original.copy(offsetX = .2f),
                original.copy(offsetY = -.2f),
                original.copy(imageAspect = 2f),
                original.copy(pose = pose.copy(eyeAltitudeMeters = 12000f)),
                original.copy(pose = pose.copy(viewAzimuthDegrees = 30f)),
                original.copy(pose = pose.copy(verticalFieldOfViewDegrees = 3f)),
            )) assertNotEquals(signature, p.signature())
    }

    @Test
    fun savedProfileNeverAppliesAfterCalibrationChange() {
        val base = photo()
        val profile = FlightPhotoDepth.calculate(base.dehazeProjection()!!, scene(ground()))
        val enabled =
            base.copy(
                imageAdjustments = FlightPhotoImageAdjustments(dehaze = .7f, depthProfile = profile)
            )
        assertNotNull(enabled.effectiveImageAdjustments().depthProfile)
        assertNull(enabled.copy(rotationDegrees = 90f).effectiveImageAdjustments().depthProfile)
        assertNull(
            enabled
                .copy(windowAlignment = base.windowAlignment!!.copy(scale = 2f))
                .effectiveImageAdjustments()
                .depthProfile
        )
        assertNull(
            enabled
                .copy(imageAdjustments = enabled.imageAdjustments.copy(depthEnabled = false))
                .effectiveImageAdjustments()
                .depthProfile
        )
        assertNotNull(
            enabled
                .copy(calibration = base.calibration.copy(pickerRotation = 25f))
                .effectiveImageAdjustments()
                .depthProfile
        )
        assertNull(base.copy(windowAlignment = null).dehazeProjection())
    }

    @Test
    fun adjustmentAndMaskedDepthRoundTripWithoutChangingOldPhotos() {
        val old =
            FlightPhotoTreatmentJson.read(JSONObject("{\"contrast\":0.4,\"temperature\":0.3}"))
        assertEquals(0f, old.dehaze, 0f)
        assertNull(old.depthProfile)
        val profile =
            FlightPhotoDepth.calculate(FlightPhotoProjection(pose, 1f), scene(ground(x0 = 0f)))
        val settings = old.copy(dehaze = .7f, depthEnabled = true, depthProfile = profile)
        val json = FlightPhotoTreatmentJson.write(settings)
        val restored = FlightPhotoTreatmentJson.read(JSONObject(json.toString()))
        assertEquals(settings, restored)
        assertTrue(json.toString().length < 80000)
        val bad = profile.toJson().put("width", 999999)
        assertNull(FlightPhotoDepthProfile.fromJson(bad))
        assertEquals(FlightPhotoImageAdjustments(), FlightPhotoTreatmentJson.read(null))
        assertArrayEquals(
            FlightPhotoColorMatrix.values(FlightPhotoImageAdjustments()),
            FlightPhotoColorMatrix.values(FlightPhotoImageAdjustments(dehaze = .7f)),
            0f,
        )
    }

    @Test
    fun partialSceneAccumulatesCoverageWithoutLosingKnownDepth() {
        val original =
            FlightPhotoDepthProfile("a".repeat(64), 2, 2, listOf(10f, 20f, null, null), 20f, 100f)
        val incoming = original.copy(opticalKm = listOf(11f, null, 30f, null))
        val combined = incoming.includingPrevious(original)
        assertEquals(listOf(11f, 20f, 30f, null), combined.opticalKm)
        assertEquals(
            original,
            original.copy(opticalKm = List(4) { null }).includingPrevious(original),
        )
        val unrelated = incoming.copy(signature = "b".repeat(64))
        assertEquals(unrelated, unrelated.includingPrevious(original))
    }

    @Test
    fun terrainOriginShiftDoesNotMoveTheDepth() {
        val coordinates = FlightTerrainCoordinates(.1, .1)
        val base = FlightTerrainCoordinates(0.0, 0.0)
        val flat = ground()
        val shifted = flat.vertices.clone()
        for (i in 0..3) {
            val geo = base.toGeographic(DoubleArray(3) { flat.vertices[i * 9 + it].toDouble() })
            val local = coordinates.toLocal(geo[0], geo[1], geo[2])
            for (a in 0..2) shifted[i * 9 + a] = local[a]
        }
        val p = FlightPhotoProjection(pose, 1f)
        val a = FlightPhotoDepth.calculate(p, scene(flat))
        val b =
            FlightPhotoDepth.calculate(
                p,
                scene(flat.copy(vertices = shifted))
                    .copy(coordinateOriginLatitude = .1, coordinateOriginLongitude = .1),
            )
        assertEquals(a.coveragePercent, b.coveragePercent)
        assertEquals(a.minimumDistanceKm, b.minimumDistanceKm, .005f)
        assertEquals(a.maximumDistanceKm, b.maximumDistanceKm, .005f)
    }
}
