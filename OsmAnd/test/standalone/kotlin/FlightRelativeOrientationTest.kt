package net.osmand.test.junit

import kotlin.math.*
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightRelativeOrientationTest {
    private fun camera(yaw: Double, pitch: Double = 0.0): FloatArray {
        val a = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return floatArrayOf(
            cos(a).toFloat(),
            (-sin(a) * sin(p)).toFloat(),
            (-sin(a) * cos(p)).toFloat(),
            -sin(a).toFloat(),
            (-cos(a) * sin(p)).toFloat(),
            (-cos(a) * cos(p)).toFloat(),
            0f,
            cos(p).toFloat(),
            -sin(p).toFloat(),
        )
    }

    private fun controller(
        look: FlightWindowLook = FlightWindowLook(),
        bearing: Float? = 0f,
        azimuth: Float = 0f,
        elevation: Float = 0f,
    ) = FlightPhoneAimController().also { it.reset(look, bearing, azimuth, elevation) }

    @Test
    fun delayedUiCannotAccumulateDuplicateCorrections() {
        val aim = controller()
        aim.update(camera(0.0), 1L, 0f)
        var target = FlightWindowLook()
        repeat(160) { target = aim.update(camera(30.0), (it + 1) * 50_000_000L, 0f)!! }
        // No intermediate target was applied to a UI, unlike the old delta-based API.
        assertEquals(30f, target.yawDegrees, 0.001f)
        assertEquals(0f, target.pitchDegrees, 0.001f)
    }

    @Test
    fun aircraftHeadingChangesAreNotCountedTwice() {
        val aim = controller(bearing = 20f, azimuth = 20f)
        aim.update(camera(20.0), 1L, 20f)
        var target = FlightWindowLook()
        repeat(160) { target = aim.update(camera(40.0), (it + 1) * 50_000_000L, 40f)!! }
        assertEquals(0f, target.yawDegrees, 0.001f)
    }

    @Test
    fun verticalPhoneNoiseDoesNotSpinTheView() {
        val aim = controller()
        aim.update(camera(0.0), 1L, 0f)
        var target = FlightWindowLook()
        repeat(100) { target = aim.update(camera(0.0, -89.99), (it + 1) * 50_000_000L, 0f)!! }
        val heading = target.yawDegrees
        repeat(100) {
            target =
                aim.update(
                    camera(if (it % 2 == 0) 170.0 else -170.0, -89.99),
                    (it + 101) * 50_000_000L,
                    0f,
                )!!
        }
        assertEquals(heading, target.yawDegrees, 0.01f)
        assertEquals(FlightWindowLook.MIN_PITCH_DEGREES, target.pitchDegrees, 0.01f)
    }

    @Test
    fun returningFromPitchClampRestoresNeutral() {
        val aim = controller()
        aim.update(camera(0.0), 1L, 0f)
        var target = FlightWindowLook()
        repeat(100) { target = aim.update(camera(0.0, 70.0), (it + 1) * 50_000_000L, 0f)!! }
        assertEquals(FlightWindowLook.MAX_PITCH_DEGREES, target.pitchDegrees, 0.001f)
        repeat(100) { target = aim.update(camera(0.0), (it + 101) * 50_000_000L, 0f)!! }
        assertEquals(0f, target.pitchDegrees, 0.001f)
    }

    @Test
    fun referencePreservesTheDisplayedDirectionRegardlessOfSensorNorth() {
        val look = FlightWindowLook(35f, -15f)
        val aim = controller(look, bearing = 80f, azimuth = 25f, elevation = -20f)
        val rotation = camera(155.0, 35.0)
        aim.update(rotation, 1L, 80f)
        repeat(50) {
            val target = aim.update(rotation, (it + 1) * 50_000_000L, 80f)!!
            assertEquals(look.yawDegrees, target.yawDegrees, 0.001f)
            assertEquals(look.pitchDegrees, target.pitchDegrees, 0.001f)
        }
    }

    @Test
    fun rollAroundTheOpticalAxisDoesNotAimElsewhere() {
        val aim = controller()
        val base = camera(0.0)
        aim.update(base, 1L, 0f)
        val angle = Math.toRadians(60.0)
        val roll =
            doubleArrayOf(cos(angle), -sin(angle), 0.0, sin(angle), cos(angle), 0.0, 0.0, 0.0, 1.0)
        val rolled =
            FloatArray(9) { i ->
                (0..2).sumOf { k -> base[i / 3 * 3 + k] * roll[k * 3 + i % 3] }.toFloat()
            }
        repeat(50) {
            val target = aim.update(rolled, (it + 1) * 50_000_000L, 0f)!!
            assertEquals(0f, target.yawDegrees, 0.001f)
            assertEquals(0f, target.pitchDegrees, 0.001f)
        }
    }

    @Test
    fun staleAndInvalidSensorSamplesAreIgnored() {
        val aim = controller()
        aim.update(camera(0.0), 100L, null)
        assertNull(aim.update(camera(60.0), 99L, null))
        assertNull(aim.update(floatArrayOf(Float.NaN), 200L, null))
    }

    @Test
    fun altitudeGroupingIsStable() {
        assertEquals("10\u202f500 m", formatFlightAltitude(10500.0))
        assertEquals("—", formatFlightAltitude(Double.NaN))
    }
}
