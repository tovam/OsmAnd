package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightCameraKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class FlightCameraKeyPolicyTest {
    @Test
    fun volumeDownCapturesOnlyOnFirstReadyDown() {
        assertEquals(
            FlightCameraKeyPolicy.Action.CAPTURE,
            FlightCameraKeyPolicy.action(
                isVolumeKey = true,
                isDown = true,
                isRepeat = false,
                alreadyPressed = false,
                busy = false,
                captureReady = true,
            ),
        )
        assertEquals(
            FlightCameraKeyPolicy.Action.CONSUME,
            FlightCameraKeyPolicy.action(
                isVolumeKey = true,
                isDown = true,
                isRepeat = true,
                alreadyPressed = true,
                busy = false,
                captureReady = true,
            ),
        )
        assertEquals(
            FlightCameraKeyPolicy.Action.CONSUME,
            FlightCameraKeyPolicy.action(
                isVolumeKey = true,
                isDown = false,
                isRepeat = false,
                alreadyPressed = true,
                busy = false,
                captureReady = true,
            ),
        )
    }

    @Test
    fun volumeKeysAreConsumedWithoutCaptureWhenBusyOrNotReady() {
        repeat(2) {
            assertEquals(
                FlightCameraKeyPolicy.Action.CONSUME,
                FlightCameraKeyPolicy.action(
                    isVolumeKey = true,
                    isDown = true,
                    isRepeat = false,
                    alreadyPressed = false,
                    busy = true,
                    captureReady = true,
                ),
            )
            assertEquals(
                FlightCameraKeyPolicy.Action.CONSUME,
                FlightCameraKeyPolicy.action(
                    isVolumeKey = true,
                    isDown = true,
                    isRepeat = false,
                    alreadyPressed = false,
                    busy = false,
                    captureReady = false,
                ),
            )
        }
    }

    @Test
    fun unrelatedKeysRemainWithOriginalDialogDispatch() {
        assertEquals(
            FlightCameraKeyPolicy.Action.IGNORE,
            FlightCameraKeyPolicy.action(
                isVolumeKey = false,
                isDown = true,
                isRepeat = false,
                alreadyPressed = false,
                busy = false,
                captureReady = true,
            ),
        )
    }
}
