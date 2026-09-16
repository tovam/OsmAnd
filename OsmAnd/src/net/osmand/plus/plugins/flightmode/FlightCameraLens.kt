package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import net.osmand.plus.R

/** A selectable output, not an arbitrary index in CameraX's logical-camera list. */
internal data class FlightCameraLens(
    val parent: CameraInfo,
    val physicalId: String?,
    val characteristics: CameraCharacteristics,
    val title: String,
    val ratio: Float,
    val front: Boolean,
    val locked: Boolean,
)

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal fun flightCameraLenses(
    context: Context,
    cameras: List<CameraInfo>,
): List<FlightCameraLens> {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val result = mutableListOf<FlightCameraLens>()
    val seen = mutableSetOf<String>()
    // Logical parents expose otherwise hidden physical cameras. Prefer them to duplicate entries.
    val sorted =
        cameras.sortedByDescending { info ->
            val c = manager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId)
            if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.size else 0
        }
    for (info in sorted) {
        val id = Camera2CameraInfo.from(info).cameraId
        val parent = manager.getCameraCharacteristics(id)
        val front =
            parent[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val physical =
            if (Build.VERSION.SDK_INT >= 28) parent.physicalCameraIds.sorted() else emptyList()
        val outputs =
            physical.mapNotNull { child ->
                // Hidden sensors are not readable on every vendor/API. Never substitute another
                // lens silently.
                runCatching { child to manager.getCameraCharacteristics(child) }.getOrNull()
            }
        val entries = if (outputs.isEmpty()) listOf(id to parent) else outputs
        // Identify the wide module by equivalent field of view, not vendor ID/order.
        val reference =
            FlightCameraOptics.mainScale(entries.map { focalScale(it.second) }, focalScale(parent))
        for ((child, c) in entries) {
            if (!seen.add(child)) continue
            val ratio = if (reference > 0f) focalScale(c) / reference else 1f
            val pinned = outputs.isNotEmpty()
            val logical =
                Build.VERSION.SDK_INT >= 28 &&
                    parent[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    ) == true
            val locked = pinned || !logical
            val title =
                when {
                    !locked -> context.getString(R.string.flight_camera_system_lens)
                    front -> context.getString(R.string.flight_camera_front)
                    ratio < 0.8f -> context.getString(R.string.flight_camera_ultrawide)
                    ratio > 1.5f -> context.getString(R.string.flight_camera_telephoto)
                    else -> context.getString(R.string.flight_camera_main)
                }
            result +=
                FlightCameraLens(
                    info,
                    child.takeIf { pinned },
                    c,
                    if (front || !locked) title else "$title\n${"%.1f".format(ratio)}×",
                    ratio,
                    front,
                    locked,
                )
        }
    }
    return result.sortedWith(compareBy<FlightCameraLens> { it.front }.thenBy { it.ratio })
}

private fun focalScale(c: CameraCharacteristics): Float {
    val focal =
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return 0f
    val width = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.width ?: return 0f
    return FlightCameraOptics.fieldScale(focal, width)
}

internal fun defaultFlightLens(lenses: List<FlightCameraLens>): Int =
    FlightCameraOptics.defaultIndex(
        lenses.map { FlightCameraOptics.Preference(it.front, it.locked, it.ratio) }
    )
