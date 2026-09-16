package net.osmand.plus.plugins.flightmode

import kotlin.math.atan
import kotlin.math.tan

/**
 * Renderer failures are independent of download progress; a finished download cannot clear them.
 */
data class FlightRendererRecovery(val revision: Int = 0, val error: String? = null) {
    fun failed(message: String) = copy(error = message)

    fun retry() = FlightRendererRecovery(revision + 1)
}

enum class FlightFixHealth {
    WAITING,
    FRESH,
    STALE,
}

internal object FlightLiveSafety {
    const val FRESH_FIX_MILLIS = 15_000L

    fun fixHealth(sample: FlightSample?, receivedAt: Long, now: Long): FlightFixHealth =
        when {
            sample == null -> FlightFixHealth.WAITING
            receivedAt <= 0L || now < receivedAt || now - receivedAt > FRESH_FIX_MILLIS ->
                FlightFixHealth.STALE
            else -> FlightFixHealth.FRESH
        }

    /** Only a measured fix, never the display predictor, may be flushed on manual stop. */
    fun finalFix(recorded: FlightSample?, latest: FlightSample?): FlightSample? =
        latest?.takeIf { recorded == null || it.timestampMillis > recorded.timestampMillis }
}

/** Projection in viewport-height units, shared by the mask and its recovery affordance. */
internal data class FlightWindowAperture(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val shear: Float,
    val visible: Boolean,
) {
    companion object {
        fun project(
            placement: FlightWindowPlacement,
            look: FlightWindowLook,
            aspectRatio: Float,
        ): FlightWindowAperture {
            val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            val geometry = placement.geometry()
            val halfFov = Math.toRadians(placement.verticalFieldOfViewDegrees() / 2.0).toFloat()
            val distance = geometry.eyeToWindowDistanceMeters.coerceAtLeast(0.2f)
            val diameter =
                ((FlightWindowPlacement.WINDOW_DIAMETER_METERS / 2f / distance) / tan(halfFov))
                    .coerceIn(0.16f, 0.82f)
            val rx = diameter * geometry.horizontalIncidence.coerceIn(0.30f, 1f) / 2f
            val ry = diameter * geometry.verticalIncidence.coerceIn(0.45f, 1f) / 2f
            val shear =
                (placement.forwardOffsetMeters * placement.verticalOffsetMeters /
                        (FlightWindowPlacement.WALL_DISTANCE_METERS *
                            FlightWindowPlacement.WALL_DISTANCE_METERS))
                    .coerceIn(-0.45f, 0.45f) * rx
            val horizontalFov = 2f * atan(tan(halfFov) * aspect)
            val x =
                aspect / 2f -
                    Math.toRadians(look.yawDegrees.toDouble()).toFloat() /
                        horizontalFov.coerceAtLeast(0.01f) * aspect
            val y =
                0.5f +
                    Math.toRadians(look.pitchDegrees.toDouble()).toFloat() /
                        (halfFov * 2f).coerceAtLeast(0.01f)
            return FlightWindowAperture(
                x,
                y,
                rx,
                ry,
                shear,
                x + rx >= 0f && x - rx <= aspect && y + ry >= 0f && y - ry <= 1f,
            )
        }
    }
}

/** Read the existing mesh under the eye, not the ground at the scene's old centre. No I/O. */
internal object FlightTerrainFloor {
    fun elevationAt(meshes: List<FlightTerrainMesh>, latitude: Double, longitude: Double): Float? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        var zoom = -1
        var elevation: Float? = null
        for (mesh in meshes) {
            if (!mesh.terrainAvailable || mesh.tileId.zoom < zoom || mesh.gridQuads < 1) continue
            val x =
                FlightTerrainTilePlanner.longitudeToTileX(longitude, mesh.tileId.zoom) -
                    mesh.tileId.x
            val y =
                FlightTerrainTilePlanner.latitudeToTileY(latitude, mesh.tileId.zoom) - mesh.tileId.y
            if (x !in 0.0..1.0 || y !in 0.0..1.0) continue
            val q = mesh.gridQuads
            val stride = FlightTerrainMeshBuilder.VERTEX_COMPONENTS
            if (mesh.vertices.size < (q + 1) * (q + 1) * stride) continue
            val column = (x * q).toInt().coerceIn(0, q - 1)
            val row = (y * q).toInt().coerceIn(0, q - 1)
            val u = (x * q - column).toFloat()
            val v = (y * q - row).toFloat()
            fun at(dx: Int, dy: Int) =
                mesh.vertices[((row + dy) * (q + 1) + column + dx) * stride + 6]
            // Same diagonal as the two triangles emitted by FlightTerrainMeshBuilder.
            val height =
                if (u + v <= 1f) at(0, 0) * (1f - u - v) + at(1, 0) * u + at(0, 1) * v
                else at(1, 1) * (u + v - 1f) + at(1, 0) * (1f - v) + at(0, 1) * (1f - u)
            if (height.isFinite()) {
                elevation = height
                zoom = mesh.tileId.zoom
            }
        }
        return elevation
    }
}
