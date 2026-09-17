package net.osmand.test.junit

import kotlin.math.*
import net.osmand.plus.plugins.flightmode.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic metadata only: never open or migrate existing user photographs. */
class FlightPhotoCalibrationPersistenceTest {
    @Test
    fun linkedInspectionKeepsPlaneAndOtherPhotoWhileSavingCamera() {
        val plane = FlightPhotoSpatialPose(3.5, 1800000000000L, 45.0, 10.0, 10000f, 40f, 80f, -20f, 60f, 4f / 3f)
        val original = FlightPhotoWindowAlignment(scale = 1.7f, offsetXFraction = 0.2f, spatialPose = plane)
        val other = original.copy(windowLook = FlightWindowLook(yawDegrees = 90f))
        val placement = original.windowPlacement.copy(zoom = 3f)
        val look = FlightWindowLook(yawDegrees = -30f, pitchDegrees = -15f)
        val updated = original.withInspectionView(placement, look, 11000f)
        assertEquals(plane, updated.spatialPose)
        assertEquals(placement, updated.windowPlacement)
        assertEquals(look, updated.windowLook)
        assertEquals(original.scale, updated.scale)
        assertEquals(original.offsetXFraction, updated.offsetXFraction)
        assertEquals(90f, other.windowLook.yawDegrees, 0f)
        assertEquals(plane, other.spatialPose)
        val legacy = original.copy(spatialPose = null).withInspectionView(placement, look, null, plane)
        assertEquals(plane, legacy.spatialPose)
    }

    @Test
    fun ordinaryAndTelephotoCalibrationsSurviveSavingWithoutRecalculation() {
        for (fov in listOf(1.1, 2.0, 5.0, 14.5, 60.0)) {
            val fit =
                FlightPhotoFit(
                    45.0,
                    10.0,
                    listOf(0.0, 10.0, 0.0, 0.1, -0.2, 0.3, ln(0.5 / tan(Math.toRadians(fov / 2)))),
                    listOf(0.1, 0.2, 0.3, 0.4),
                    0.25,
                    false,
                )
            val data =
                FlightPhotoCalibration(
                    List(4) { FlightPhotoControlPoint(0.2, 0.3, 45.0, 10.0, 1000.0) },
                    4000,
                    3000,
                    true,
                    fov,
                    fit,
                    FlightPhotoEditorView(2, 32f, -15f, 1.7f, 0.6f),
                    13.5f,
                )
            val restored = FlightPhotoCalibration.fromJson(JSONObject(data.toJson().toString()))
            assertEquals(data, restored)
            val pose =
                restored.fit!!
                    .pose(
                        FlightPhotoSpatialPose(
                            3.5,
                            1800000000000L,
                            45.0,
                            10.0,
                            10000f,
                            40f,
                            80f,
                            -20f,
                            60f,
                        ),
                        4f / 3f,
                    )
                    .clampedOrNull()!!
            assertEquals(fov.toFloat(), pose.verticalFieldOfViewDegrees, 0.00001f)
        }
    }
}
