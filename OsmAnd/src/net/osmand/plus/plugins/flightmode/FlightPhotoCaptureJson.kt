package net.osmand.plus.plugins.flightmode

import org.json.JSONArray
import org.json.JSONObject

internal fun FlightPhotoCapture.toJson(encodeFix: (FlightSample) -> JSONObject): JSONObject =
    JSONObject().apply {
        put("shutterMillis", shutterMillis)
        put("shutterElapsedNanos", shutterElapsedNanos)
        fix?.let { put("fix", encodeFix(it)) }
        magneticMicroTesla?.let { put("magneticMicroTesla", JSONArray(it)) }
        magneticElapsedNanos?.let { put("magneticElapsedNanos", it) }
        magneticAccuracy?.let { put("magneticAccuracy", it) }
        rotationVector?.let { put("rotationVector", JSONArray(it)) }
        rotationElapsedNanos?.let { put("rotationElapsedNanos", it) }
    }

internal fun photoCaptureFromJson(
    json: JSONObject?,
    decodeFix: (JSONObject) -> FlightSample,
): FlightPhotoCapture? {
    json ?: return null
    if (json.optLong("shutterMillis") <= 0) return null
    fun values(key: String): List<Float>? =
        json.optJSONArray(key)?.let { a ->
            if (a.length() !in 3..5) return@let null
            (0 until a.length())
                .map { a.optDouble(it).toFloat() }
                .takeIf { it.all(Float::isFinite) }
        }
    fun time(key: String) = json.optLong(key).takeIf { it > 0 }
    return FlightPhotoCapture(
        json.getLong("shutterMillis"),
        json.optLong("shutterElapsedNanos"),
        json.optJSONObject("fix")?.let(decodeFix),
        values("magneticMicroTesla"),
        time("magneticElapsedNanos"),
        json.optInt("magneticAccuracy", -1).takeIf { it >= 0 },
        values("rotationVector"),
        time("rotationElapsedNanos"),
    )
}
