package net.osmand.plus.plugins.flightmode

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.*
import net.osmand.util.PhotoPlaneGeometry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The same fixed image plane is used for GL and depth projection, independently of the viewing eye.
 */
data class FlightPhotoProjection(
    val pose: FlightPhotoSpatialPose,
    val imageAspect: Float,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
) {
    fun vertices(coordinates: FlightTerrainCoordinates, distance: Float): FloatArray {
        val eye = eye(coordinates)
        val azimuth = Math.toRadians(pose.viewAzimuthDegrees.toDouble())
        val elevation = Math.toRadians(pose.viewElevationDegrees.toDouble())
        val forward =
            normalized(
                coordinates.vectorToLocal(
                    pose.eyeLatitude,
                    pose.eyeLongitude,
                    (sin(azimuth) * cos(elevation)).toFloat(),
                    sin(elevation).toFloat(),
                    (-cos(azimuth) * cos(elevation)).toFloat(),
                )
            )
        // Analytic right remains defined for a straight-down camera too.
        val right =
            normalized(
                coordinates.vectorToLocal(
                    pose.eyeLatitude,
                    pose.eyeLongitude,
                    cos(azimuth).toFloat(),
                    0f,
                    sin(azimuth).toFloat(),
                )
            )
        val up = normalized(cross(right, forward))
        return PhotoPlaneGeometry.vertices(
            eye,
            forward,
            right,
            up,
            distance,
            pose.verticalFieldOfViewDegrees,
            imageAspect,
            pose.referenceAspectRatio ?: imageAspect,
            scale,
            offsetX,
            offsetY,
            rotation,
        )
    }

    fun eye(coordinates: FlightTerrainCoordinates) =
        coordinates.toLocal(
            pose.eyeLatitude,
            pose.eyeLongitude,
            requireNotNull(pose.eyeAltitudeMeters).toDouble(),
        )

    /**
     * Only optical geometry invalidates depth, not exposure, current replay time, or display
     * rotation.
     */
    fun signature(): String {
        val values =
            listOf(
                    pose.eyeLatitude,
                    pose.eyeLongitude,
                    pose.eyeAltitudeMeters,
                    pose.viewAzimuthDegrees,
                    pose.viewElevationDegrees,
                    pose.verticalFieldOfViewDegrees,
                    pose.referenceAspectRatio,
                    imageAspect,
                    scale,
                    offsetX,
                    offsetY,
                    rotation,
                )
                .joinToString("/")
        return MessageDigest.getInstance("SHA-256")
            .digest(values.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        internal fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
            )

        private fun normalized(a: FloatArray): FloatArray {
            val size = sqrt(a.sumOf { it.toDouble() * it }).coerceAtLeast(1e-12)
            return FloatArray(3) { (a[it] / size).toFloat() }
        }
    }
}

/**
 * Small, portable geometric hint. Unknown/sky pixels stay null, never an invented infinite depth.
 */
data class FlightPhotoDepthProfile(
    val signature: String,
    val width: Int,
    val height: Int,
    val opticalKm: List<Float?>,
    val minimumDistanceKm: Float,
    val maximumDistanceKm: Float,
) {
    /**
     * Accumulate static terrain coverage without forgetting loaded areas when another scene is
     * shown.
     */
    fun includingPrevious(previous: FlightPhotoDepthProfile?): FlightPhotoDepthProfile {
        if (
            previous == null ||
                previous.signature != signature ||
                previous.width != width ||
                previous.height != height
        )
            return this
        if (opticalKm.none { it != null }) return previous
        val retained =
            opticalKm.indices.any { opticalKm[it] == null && previous.opticalKm[it] != null }
        if (!retained) return this
        return copy(
            opticalKm = opticalKm.indices.map { opticalKm[it] ?: previous.opticalKm[it] },
            minimumDistanceKm = min(minimumDistanceKm, previous.minimumDistanceKm),
            maximumDistanceKm = max(maximumDistanceKm, previous.maximumDistanceKm),
        )
    }

    val coveragePercent: Int
        get() = (100 * opticalKm.count { it != null } / opticalKm.size.coerceAtLeast(1))

    fun toJson() =
        JSONObject().apply {
            put("version", 1)
            put("signature", signature)
            put("width", width)
            put("height", height)
            put("opticalKm", JSONArray(opticalKm))
            put("minKm", minimumDistanceKm)
            put("maxKm", maximumDistanceKm)
        }

    companion object {
        fun fromJson(json: JSONObject?): FlightPhotoDepthProfile? =
            runCatching {
                    if (json == null || json.optInt("version") != 1) return null
                    val w = json.getInt("width")
                    val h = json.getInt("height")
                    require(w in 1..96 && h in 1..96)
                    val values = json.getJSONArray("opticalKm")
                    require(values.length() == w * h)
                    val signature = json.getString("signature")
                    require(signature.matches(Regex("[a-f0-9]{64}")))
                    FlightPhotoDepthProfile(
                        signature,
                        w,
                        h,
                        List(w * h) { i ->
                            values
                                .optDouble(i, Double.NaN)
                                .takeIf { it.isFinite() && it in 0.0..1000.0 }
                                ?.toFloat()
                        },
                        json.optDouble("minKm", 0.0).toFloat().takeIf { it.isFinite() && it >= 0 }
                            ?: 0f,
                        json.optDouble("maxKm", 0.0).toFloat().takeIf { it.isFinite() && it >= 0 }
                            ?: 0f,
                    )
                }
                .getOrNull()
    }
}

/**
 * Use a deliberate manual alignment first, otherwise a reliable point-based fit. Never guess a
 * heading.
 */
fun FlightPhotoAttachment.dehazeProjection(): FlightPhotoProjection? {
    val aspect =
        (calibration.imageWidth.toFloat() / calibration.imageHeight.coerceAtLeast(1)).takeIf {
            it > 0 && it.isFinite()
        }
    windowAlignment?.clamped()?.let { alignment ->
        alignment.spatialPose
            ?.clampedOrNull()
            ?.takeIf { it.eyeAltitudeMeters != null && it.referenceAspectRatio != null }
            ?.let { pose ->
                if (aspect != null)
                    return FlightPhotoProjection(
                        pose,
                        aspect,
                        alignment.scale,
                        alignment.offsetXFraction,
                        alignment.offsetYFraction,
                        rotationDegrees,
                    )
            }
    }
    val fit =
        calibration.fit?.takeIf { !it.weak && it.rms <= 8 && it.parameters.size == 7 }
            ?: return null
    if (aspect == null) return null
    val reference =
        FlightPhotoSpatialPose(
            matchedSamplePosition ?: 0.0,
            timestampMillis,
            fit.originLatitude,
            fit.originLongitude,
            0f,
            0f,
            0f,
            0f,
            60f,
            aspect,
        )
    return runCatching {
            fit.pose(reference, aspect).clampedOrNull()?.let {
                FlightPhotoProjection(it, aspect, rotation = fit.imageRotationDegrees())
            }
        }
        .getOrNull()
}

/**
 * Invalidate stale geometry in every consumer; never alter saved calibration or the original
 * bitmap.
 */
fun FlightPhotoAttachment.effectiveImageAdjustments(): FlightPhotoImageAdjustments {
    val settings = imageAdjustments.clamped()
    val valid =
        settings.depthProfile?.takeIf {
            settings.depthEnabled && it.signature == dehazeProjection()?.signature()
        }
    return if (valid === settings.depthProfile) settings else settings.copy(depthProfile = valid)
}

object FlightPhotoDepth {
    /**
     * Low-resolution rasterisation of the loaded curved-earth mesh. No network requests, GL
     * readbacks or flat ground fallback.
     */
    fun calculate(
        projection: FlightPhotoProjection,
        scene: FlightTerrainScene,
    ): FlightPhotoDepthProfile {
        val w =
            if (projection.imageAspect >= 1) 64
            else max(8, (64 * projection.imageAspect).roundToInt())
        val h =
            if (projection.imageAspect <= 1) 64
            else max(8, (64 / projection.imageAspect).roundToInt())
        val coordinates =
            FlightTerrainCoordinates(
                scene.coordinateOriginLatitude,
                scene.coordinateOriginLongitude,
            )
        val eye = projection.eye(coordinates)
        val vertices = projection.vertices(coordinates, 1000f)
        val c = FloatArray(3) { vertices[it] - eye[it] }
        val u = FloatArray(3) { vertices[it + 6] - vertices[it] }
        val v = FloatArray(3) { vertices[it + 3] - vertices[it] }
        val n = FlightPhotoProjection.cross(u, v)
        fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }
        val length = sqrt(dot(n, n))
        for (i in n.indices) n[i] = (n[i] / length).toFloat()
        val planeDistance = dot(n, c)
        val depth = FloatArray(w * h) { Float.POSITIVE_INFINITY }
        val range = FloatArray(w * h) { Float.NaN }
        val ground = FloatArray(w * h) { Float.NaN }
        fun projected(mesh: FlightTerrainMesh, index: Int): DoubleArray? {
            val offset = index * 9
            val p = FloatArray(3) { mesh.vertices[offset + it] - eye[it] }
            val z = dot(n, p)
            if (z <= 1) return null
            val q = FloatArray(3) { (p[it] * planeDistance / z - c[it]).toFloat() }
            return doubleArrayOf(
                dot(q, u) / dot(u, u) * w,
                dot(q, v) / dot(v, v) * h,
                z,
                p[0].toDouble(),
                p[1].toDouble(),
                p[2].toDouble(),
                mesh.vertices[offset + 6].toDouble(),
            )
        }
        fun triangle(a: DoubleArray?, b: DoubleArray?, c: DoubleArray?) {
            if (a == null || b == null || c == null) return
            val det = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if (abs(det) < 1e-9) return
            val x0 = floor(min(a[0], min(b[0], c[0]))).toInt().coerceAtLeast(0)
            val x1 = ceil(max(a[0], max(b[0], c[0]))).toInt().coerceAtMost(w - 1)
            val y0 = floor(min(a[1], min(b[1], c[1]))).toInt().coerceAtLeast(0)
            val y1 = ceil(max(a[1], max(b[1], c[1]))).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val aa = ((b[1] - c[1]) * (x + .5 - c[0]) + (c[0] - b[0]) * (y + .5 - c[1])) / det
                val bb = ((c[1] - a[1]) * (x + .5 - c[0]) + (a[0] - c[0]) * (y + .5 - c[1])) / det
                val cc = 1 - aa - bb
                if (min(aa, min(bb, cc)) < -1e-6) continue
                val inv = aa / a[2] + bb / b[2] + cc / c[2]
                val z = 1 / inv
                val at = y * w + x
                if (z >= depth[at]) continue
                val px = (aa * a[3] / a[2] + bb * b[3] / b[2] + cc * c[3] / c[2]) / inv
                val py = (aa * a[4] / a[2] + bb * b[4] / b[2] + cc * c[4] / c[2]) / inv
                val pz = (aa * a[5] / a[2] + bb * b[5] / b[2] + cc * c[5] / c[2]) / inv
                val r = sqrt(px * px + py * py + pz * pz) / 1000
                if (r > 500) continue
                depth[at] = z.toFloat()
                range[at] = r.toFloat()
                ground[at] =
                    ((aa * a[6] / a[2] + bb * b[6] / b[2] + cc * c[6] / c[2]) / inv).toFloat()
            }
        }
        for (mesh in scene.meshes) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            if (!mesh.terrainAvailable) continue
            val quads = mesh.gridQuads
            if (quads < 1 || mesh.vertices.size < (quads + 1) * (quads + 1) * 9) continue
            // The guidance mask is only 64px across: cap each source tile to 32x32 cells.
            val divisions = min(32, quads)
            val samples =
                Array(divisions + 1) { y ->
                    Array(divisions + 1) { x ->
                        projected(
                            mesh,
                            (y * quads / divisions) * (quads + 1) + x * quads / divisions,
                        )
                    }
                }
            for (y in 0 until divisions) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                for (x in 0 until divisions) {
                    triangle(samples[y][x], samples[y + 1][x], samples[y][x + 1])
                    triangle(samples[y][x + 1], samples[y + 1][x], samples[y + 1][x + 1])
                }
            }
        }
        val known = range.filter(Float::isFinite)
        val eyeAltitude = requireNotNull(projection.pose.eyeAltitudeMeters).toDouble()
        return FlightPhotoDepthProfile(
            projection.signature(),
            w,
            h,
            List(w * h) { i ->
                if (range[i].isFinite())
                    atmosphericPathKm(range[i].toDouble(), eyeAltitude, ground[i].toDouble())
                        .toFloat()
                else null
            },
            known.minOrNull() ?: 0f,
            known.maxOrNull() ?: 0f,
        )
    }

    /**
     * Simple exponential molecular density (8km scale height), integrated along a curved-Earth
     * chord. Aerosols/weather remain unknown; the photo estimates extinction per equivalent
     * sea-level km.
     */
    fun atmosphericPathKm(distanceKm: Double, eyeMeters: Double, groundMeters: Double): Double {
        if (!distanceKm.isFinite() || distanceKm <= 0) return 0.0
        val distance = distanceKm * 1000
        val horizontalSquared = max(0.0, distance * distance - (eyeMeters - groundMeters).pow(2))
        var sum = 0.0
        repeat(24) { i ->
            val t = (i + .5) / 24
            val altitude =
                eyeMeters * (1 - t) + groundMeters * t -
                    horizontalSquared * t * (1 - t) / (2 * 6_371_000)
            sum += exp(-max(0.0, altitude) / 8000)
        }
        return distanceKm * sum / 24
    }
}
