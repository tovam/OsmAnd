package net.osmand.plus.plugins.flightmode

import java.util.concurrent.CancellationException
import kotlin.math.*

/** A terrain selection, independent of either viewport and of calibration control points. */
internal data class FlightPickedPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
)

internal data class FlightPickingRay(val eye: DoubleArray, val direction: DoubleArray) {
    companion object {
        fun through(eye: DoubleArray, target: DoubleArray): FlightPickingRay? {
            val delta = DoubleArray(3) { target[it] - eye[it] }
            val length = sqrt(delta.sumOf { it * it })
            if (!length.isFinite() || length < 1e-9) return null
            return FlightPickingRay(eye, DoubleArray(3) { delta[it] / length })
        }
    }
}

/** Tile bounds reject most geometry; exact triangles are tested off the UI/GL threads. */
internal class FlightTerrainPicker(val scene: FlightTerrainScene) {
    val coordinates =
        FlightTerrainCoordinates(scene.coordinateOriginLatitude, scene.coordinateOriginLongitude)

    private data class BoundedMesh(
        val mesh: FlightTerrainMesh,
        val low: DoubleArray,
        val high: DoubleArray,
    )

    private val meshes =
        scene.meshes
            .filter { it.terrainAvailable }
            .map { mesh ->
                checkCancellation()
                val low = DoubleArray(3) { Double.POSITIVE_INFINITY }
                val high = DoubleArray(3) { Double.NEGATIVE_INFINITY }
                for (offset in mesh.vertices.indices step 9) {
                    for (axis in 0..2) {
                        val value = mesh.vertices[offset + axis].toDouble()
                        low[axis] = min(low[axis], value)
                        high[axis] = max(high[axis], value)
                    }
                }
                BoundedMesh(mesh, low, high)
            }

    fun local(point: FlightPickedPoint) =
        coordinates
            .toLocal(point.latitude, point.longitude, point.altitude)
            .map { it.toDouble() }
            .toDoubleArray()

    fun pointAt(ray: FlightPickingRay): FlightPickedPoint? =
        hit(ray)?.let { distance ->
            val local = DoubleArray(3) { ray.eye[it] + ray.direction[it] * distance }
            val geo = coordinates.toGeographic(local)
            FlightPickedPoint(geo[0], geo[1], geo[2])
        }

    fun groundAt(latitude: Double, longitude: Double): FlightPickedPoint? {
        val eye =
            coordinates
                .toLocal(latitude, longitude, 100_000.0)
                .map { it.toDouble() }
                .toDoubleArray()
        val below =
            coordinates
                .toLocal(latitude, longitude, -15_000.0)
                .map { it.toDouble() }
                .toDoubleArray()
        return FlightPickingRay.through(eye, below)?.let(::pointAt)
    }

    fun opacity(eye: DoubleArray, target: DoubleArray): Float {
        val ray = FlightPickingRay.through(eye, target) ?: return 1f
        val distance = sqrt(target.indices.sumOf { (target[it] - eye[it]).pow(2) })
        // The selected surface must not occlude itself because of float/ellipsoid round trips.
        val tolerance = max(1.0, distance * 0.00001)
        return if (hit(ray, distance - tolerance) != null) 0.5f else 1f
    }

    fun hit(ray: FlightPickingRay, limit: Double = Double.POSITIVE_INFINITY): Double? {
        var nearest = limit
        var found = false
        for (bounded in meshes) {
            checkCancellation()
            if (!intersectsBounds(ray, bounded.low, bounded.high, nearest)) continue
            val mesh = bounded.mesh
            for (i in mesh.indices.indices step 3) {
                if (i % 3072 == 0) checkCancellation()
                val a = (mesh.indices[i].toInt() and 0xffff) * 9
                val b = (mesh.indices[i + 1].toInt() and 0xffff) * 9
                val c = (mesh.indices[i + 2].toInt() and 0xffff) * 9
                val distance = triangle(ray, mesh.vertices, a, b, c) ?: continue
                if (distance < nearest) {
                    nearest = distance
                    found = true
                }
            }
        }
        return nearest.takeIf { found }
    }

    private fun intersectsBounds(
        ray: FlightPickingRay,
        low: DoubleArray,
        high: DoubleArray,
        limit: Double,
    ): Boolean {
        var near = 0.0
        var far = limit
        for (axis in 0..2) {
            val direction = ray.direction[axis]
            if (abs(direction) < 1e-12) {
                if (ray.eye[axis] < low[axis] || ray.eye[axis] > high[axis]) return false
            } else {
                val a = (low[axis] - ray.eye[axis]) / direction
                val b = (high[axis] - ray.eye[axis]) / direction
                near = max(near, min(a, b))
                far = min(far, max(a, b))
                if (far < near) return false
            }
        }
        return far >= near
    }

    private fun triangle(
        ray: FlightPickingRay,
        vertices: FloatArray,
        a: Int,
        b: Int,
        c: Int,
    ): Double? {
        val e1x = (vertices[b] - vertices[a]).toDouble()
        val e1y = (vertices[b + 1] - vertices[a + 1]).toDouble()
        val e1z = (vertices[b + 2] - vertices[a + 2]).toDouble()
        val e2x = (vertices[c] - vertices[a]).toDouble()
        val e2y = (vertices[c + 1] - vertices[a + 1]).toDouble()
        val e2z = (vertices[c + 2] - vertices[a + 2]).toDouble()
        val d = ray.direction
        val px = d[1] * e2z - d[2] * e2y
        val py = d[2] * e2x - d[0] * e2z
        val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-10) return null
        val tx = ray.eye[0] - vertices[a]
        val ty = ray.eye[1] - vertices[a + 1]
        val tz = ray.eye[2] - vertices[a + 2]
        val u = (tx * px + ty * py + tz * pz) / det
        if (u < -1e-9 || u > 1 + 1e-9) return null
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) / det
        if (v < -1e-9 || u + v > 1 + 1e-9) return null
        return ((e2x * qx + e2y * qy + e2z * qz) / det).takeIf { it >= 0 && it.isFinite() }
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }
}

internal data class FlightPhotoPickedMarker(
    val point: FlightPickedPoint,
    val x: Double?,
    val y: Double?,
    val opacity: Float,
)

/** Matrices and eye come from the actual rendered frame, including its altitude safety clamp. */
internal class FlightPickingFrame(
    val scene: FlightTerrainScene,
    val eye: DoubleArray,
    val matrix: FloatArray,
    private val inverse: FloatArray,
) {
    fun sameView(other: FlightPickingFrame) =
        scene.meshes === other.scene.meshes &&
            scene.coordinateOriginLatitude == other.scene.coordinateOriginLatitude &&
            scene.coordinateOriginLongitude == other.scene.coordinateOriginLongitude &&
            matrix.contentEquals(other.matrix) &&
            eye.contentEquals(other.eye)

    private fun transform(matrix: FloatArray, x: Double, y: Double, z: Double) =
        DoubleArray(4) { row ->
            matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row]
        }

    fun ray(x: Double, y: Double): FlightPickingRay? {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        val near = transform(inverse, 2 * x - 1, 1 - 2 * y, -1.0)
        if (abs(near[3]) < 1e-12) return null
        return FlightPickingRay.through(eye, DoubleArray(3) { near[it] / near[3] })
    }

    fun project(local: DoubleArray): Pair<Double, Double>? {
        val clip = transform(matrix, local[0], local[1], local[2])
        if (clip[3] <= 0 || !clip.all { it.isFinite() }) return null
        if (clip[2] < -clip[3] || clip[2] > clip[3]) return null
        val x = (clip[0] / clip[3] + 1) / 2
        val y = (1 - clip[1] / clip[3]) / 2
        return if (x in 0.0..1.0 && y in 0.0..1.0) x to y else null
    }
}

/** Inverse and forward projections use the same fixed image plane as the GL photo renderer. */
internal class FlightPhotoPicker(
    projection: FlightPhotoProjection,
    private val terrain: FlightTerrainPicker,
) {
    val eye = projection.eye(terrain.coordinates).map { it.toDouble() }.toDoubleArray()
    private val corners = projection.vertices(terrain.coordinates, 1000f)
    private val corner = DoubleArray(3) { corners[it].toDouble() }
    private val right = DoubleArray(3) { (corners[it + 6] - corners[it]).toDouble() }
    private val down = DoubleArray(3) { (corners[it + 3] - corners[it]).toDouble() }
    private val normal =
        doubleArrayOf(
            right[1] * down[2] - right[2] * down[1],
            right[2] * down[0] - right[0] * down[2],
            right[0] * down[1] - right[1] * down[0],
        )

    private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

    fun pointAt(x: Double, y: Double): FlightPickedPoint? {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        val target = DoubleArray(3) { corner[it] + x * right[it] + y * down[it] }
        return FlightPickingRay.through(eye, target)?.let(terrain::pointAt)
    }

    fun project(point: FlightPickedPoint): FlightPhotoPickedMarker {
        val local = terrain.local(point)
        val direction = DoubleArray(3) { local[it] - eye[it] }
        val denominator = dot(normal, direction)
        val numerator = dot(normal, DoubleArray(3) { corner[it] - eye[it] })
        val t = numerator / denominator
        if (!t.isFinite() || t <= 0) return FlightPhotoPickedMarker(point, null, null, 1f)
        val delta = DoubleArray(3) { eye[it] + t * direction[it] - corner[it] }
        val x = dot(delta, right) / dot(right, right)
        val y = dot(delta, down) / dot(down, down)
        return FlightPhotoPickedMarker(point, x, y, terrain.opacity(eye, local))
    }
}
