package net.osmand.plus.plugins.flightmode

import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.osmand.util.PhotoPoseSolver
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates refer to the EXIF-upright image, before the user's display rotation. */
data class FlightPhotoControlPoint(
    val x: Double? = null,
    val y: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
)

data class FlightPhotoFit(
    val originLatitude: Double,
    val originLongitude: Double,
    val parameters: List<Double>,
    val errors: List<Double>,
    val rms: Double,
    val weak: Boolean,
) {
    fun pose(reference: FlightPhotoSpatialPose, aspect: Float): FlightPhotoSpatialPose {
        val coordinates = FlightTerrainCoordinates(originLatitude, originLongitude)
        val geo = coordinates.toGeographic(DoubleArray(3) { parameters[it] * 1000 })
        val direction = coordinates.vectorFromLocal(geo[0], geo[1], forward())
        return reference.copy(
            eyeLatitude = geo[0],
            eyeLongitude = geo[1],
            eyeAltitudeMeters = geo[2].toFloat(),
            viewAzimuthDegrees =
                Math.toDegrees(atan2(direction[0].toDouble(), -direction[2].toDouble())).toFloat(),
            viewElevationDegrees =
                Math.toDegrees(
                        atan2(
                            direction[1].toDouble(),
                            hypot(direction[0].toDouble(), direction[2].toDouble()),
                        )
                    )
                    .toFloat(),
            verticalFieldOfViewDegrees =
                Math.toDegrees(2 * atan(0.5 / exp(parameters[6]))).toFloat(),
            referenceAspectRatio = aspect,
        )
    }

    private fun forward() =
        floatArrayOf(
            (sin(parameters[3]) * cos(parameters[4])).toFloat(),
            sin(parameters[4]).toFloat(),
            (-cos(parameters[3]) * cos(parameters[4])).toFloat(),
        )

    /** PhotoPlaneGeometry uses clockwise image roll, opposite to the camera basis roll. */
    fun imageRotationDegrees(): Float {
        val coordinates = FlightTerrainCoordinates(originLatitude, originLongitude)
        val geo = coordinates.toGeographic(DoubleArray(3) { parameters[it] * 1000 })
        val y = parameters[3]
        val t = parameters[4]
        val r = parameters[5]
        val right =
            floatArrayOf(
                (cos(y) * cos(r) - sin(y) * sin(t) * sin(r)).toFloat(),
                (cos(t) * sin(r)).toFloat(),
                (sin(y) * cos(r) + cos(y) * sin(t) * sin(r)).toFloat(),
            )
        val f = coordinates.vectorFromLocal(geo[0], geo[1], forward())
        val rr = coordinates.vectorFromLocal(geo[0], geo[1], right)
        val yaw = atan2(f[0].toDouble(), -f[2].toDouble())
        val pitch = asin(f[1].toDouble().coerceIn(-1.0, 1.0))
        val dotRight = rr[0] * cos(yaw) + rr[2] * sin(yaw)
        val dotUp =
            -rr[0] * sin(yaw) * sin(pitch) + rr[1] * cos(pitch) + rr[2] * cos(yaw) * sin(pitch)
        return -Math.toDegrees(atan2(dotUp, dotRight)).toFloat()
    }
}

data class FlightPhotoEditorView(
    val mode: Int = 0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val zoom: Float = 1f,
    val opacity: Float = 0.5f,
)

data class FlightPhotoCalibration(
    val points: List<FlightPhotoControlPoint> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val fitFocal: Boolean = true,
    val verticalFov: Double = 60.0,
    val fit: FlightPhotoFit? = null,
    val editorView: FlightPhotoEditorView = FlightPhotoEditorView(),
    val pickerRotation: Float = 0f,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("version", 1)
            put("width", imageWidth)
            put("height", imageHeight)
            put("fitFocal", fitFocal)
            put("verticalFov", verticalFov)
            put("pickerRotation", pickerRotation)
            put(
                "view",
                JSONObject().apply {
                    put("mode", editorView.mode)
                    put("yaw", editorView.yaw)
                    put("pitch", editorView.pitch)
                    put("zoom", editorView.zoom)
                    put("opacity", editorView.opacity)
                },
            )
            put(
                "points",
                JSONArray().apply {
                    points.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("x", p.x)
                                put("y", p.y)
                                put("lat", p.latitude)
                                put("lon", p.longitude)
                                put("alt", p.altitude)
                            }
                        )
                    }
                },
            )
            fit?.let { f ->
                put(
                    "fit",
                    JSONObject().apply {
                        put("originLat", f.originLatitude)
                        put("originLon", f.originLongitude)
                        put("parameters", JSONArray(f.parameters))
                        put("errors", JSONArray(f.errors))
                        put("rms", f.rms)
                        put("weak", f.weak)
                    },
                )
            }
        }

    companion object {
        fun fromJson(json: JSONObject?): FlightPhotoCalibration {
            if (json == null || json.optInt("version") != 1) return FlightPhotoCalibration()
            return runCatching {
                    fun number(j: JSONObject, k: String) =
                        j.optDouble(k, Double.NaN).takeIf(Double::isFinite)
                    val arr = json.getJSONArray("points")
                    val points =
                        List(arr.length()) { i ->
                            val p = arr.getJSONObject(i)
                            FlightPhotoControlPoint(
                                number(p, "x")?.takeIf { it in 0.0..1.0 },
                                number(p, "y")?.takeIf { it in 0.0..1.0 },
                                number(p, "lat")?.takeIf { it in -85.0..85.0 },
                                number(p, "lon")?.takeIf { it in -180.0..180.0 },
                                number(p, "alt"),
                            )
                        }
                    val fit =
                        json.optJSONObject("fit")?.let { f ->
                            runCatching {
                                    fun list(key: String): List<Double> {
                                        val a = f.getJSONArray(key)
                                        return List(a.length()) {
                                            a.getDouble(it).also { v -> require(v.isFinite()) }
                                        }
                                    }
                                    val params = list("parameters")
                                    val errors = list("errors")
                                    require(
                                        params.size == 7 &&
                                            errors.size >= 4 &&
                                            errors.size <= points.size &&
                                            errors.all { it >= 0 }
                                    )
                                    require(f.getDouble("originLat") in -85.0..85.0)
                                    require(f.getDouble("originLon") in -180.0..180.0)
                                    require(
                                        f.getDouble("rms").isFinite() && f.getDouble("rms") >= 0
                                    )
                                    require(params[6] in -3.0..3.0)
                                    FlightPhotoFit(
                                        f.getDouble("originLat"),
                                        f.getDouble("originLon"),
                                        params,
                                        errors,
                                        f.getDouble("rms"),
                                        f.optBoolean("weak", true),
                                    )
                                }
                                .getOrNull()
                        }
                    val v = json.optJSONObject("view") ?: JSONObject()
                    fun safe(key: String, default: Double, min: Double, max: Double) =
                        v.optDouble(key, default)
                            .takeIf { it.isFinite() }
                            ?.coerceIn(min, max)
                            ?.toFloat() ?: default.toFloat()
                    FlightPhotoCalibration(
                        points,
                        json.optInt("width").coerceIn(0, 100000),
                        json.optInt("height").coerceIn(0, 100000),
                        json.optBoolean("fitFocal", true),
                        json.optDouble("verticalFov", 60.0).takeIf {
                            it.isFinite() && it in 8.0..170.0
                        } ?: 60.0,
                        fit,
                        FlightPhotoEditorView(
                            v.optInt("mode").coerceIn(0, 2),
                            safe("yaw", 0.0, -360.0, 360.0),
                            safe("pitch", 0.0, -180.0, 180.0),
                            safe("zoom", 1.0, 0.3, 8.0),
                            safe("opacity", 0.5, 0.0, 1.0),
                        ),
                        json.optDouble("pickerRotation", 0.0).takeIf(Double::isFinite)?.toFloat()
                            ?: 0f,
                    )
                }
                .getOrDefault(FlightPhotoCalibration())
        }
    }
}

suspend fun solveFlightPhotoCalibration(
    calibration: FlightPhotoCalibration,
    reference: FlightPhotoSpatialPose,
    terrain: FlightTerrainRepository,
): FlightPhotoCalibration {
    require(reference.eyeAltitudeMeters != null) { "Recorded camera altitude is missing" }
    require(
        calibration.points.count {
            it.x != null && it.y != null && it.latitude != null && it.longitude != null
        } >= 4
    ) {
        "Four pairs required"
    }
    val points =
        calibration.points.map { p ->
            if (p.latitude != null && p.longitude != null && p.altitude == null)
                p.copy(altitude = terrain.calibrationElevation(p.latitude, p.longitude))
            else p
        }
    val complete =
        points.filter {
            it.x != null &&
                it.y != null &&
                it.latitude != null &&
                it.longitude != null &&
                it.altitude != null
        }
    val coordinates = FlightTerrainCoordinates(reference.eyeLatitude, reference.eyeLongitude)
    val world =
        complete
            .map { p ->
                coordinates
                    .toLocal(p.latitude!!, p.longitude!!, p.altitude!!)
                    .map { it / 1000.0 }
                    .toDoubleArray()
            }
            .toTypedArray()
    val image = complete.map { doubleArrayOf(it.x!!, it.y!!) }.toTypedArray()
    val initial =
        doubleArrayOf(
            0.0,
            (reference.eyeAltitudeMeters ?: 10000f) / 1000.0,
            0.0,
            Math.toRadians(reference.viewAzimuthDegrees.toDouble()),
            Math.toRadians(reference.viewElevationDegrees.toDouble()),
            0.0,
            ln(0.5 / tan(Math.toRadians(calibration.verticalFov / 2))),
        )
    val result =
        runInterruptible(Dispatchers.Default) {
            PhotoPoseSolver.solve(
                world,
                image,
                initial,
                calibration.imageWidth,
                calibration.imageHeight,
                calibration.fitFocal,
            )
        }
    return calibration.copy(
        points = points,
        fit =
            FlightPhotoFit(
                reference.eyeLatitude,
                reference.eyeLongitude,
                result.parameters.toList(),
                result.errorsPixels.toList(),
                result.rmsPixels,
                result.weakGeometry,
            ),
    )
}
