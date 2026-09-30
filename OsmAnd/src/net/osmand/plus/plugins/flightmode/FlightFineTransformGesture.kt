package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * No touch-slop dead zone on an optical calibration surface. Buttons still own consumed gestures.
 */
internal suspend fun PointerInputScope.detectFineFlightTransforms(
    onTap: ((Offset) -> Unit)? = null,
    transform: (Offset, Offset, Float, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!down.isConsumed) {
            var transformed = false
            var interrupted = false
            var panTotal = Offset.Zero
            var multiplePointers = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) {
                    interrupted = true
                    break
                }
                var pan = event.calculatePan()
                val zoom = event.calculateZoom()
                val rotation = event.calculateRotation()
                multiplePointers = multiplePointers || event.changes.size > 1
                if (onTap != null && !transformed) {
                    panTotal += pan
                    if (!multiplePointers && panTotal.getDistance() < viewConfiguration.touchSlop)
                        continue
                    pan = panTotal
                }
                if (pan != Offset.Zero || zoom != 1f || rotation != 0f) {
                    transformed = true
                    transform(event.calculateCentroid(useCurrent = true), pan, zoom, rotation)
                    event.changes
                        .filter { it.pressed && it.previousPressed }
                        .forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (!interrupted && !transformed && !multiplePointers) onTap?.invoke(down.position)
        }
    }
}
