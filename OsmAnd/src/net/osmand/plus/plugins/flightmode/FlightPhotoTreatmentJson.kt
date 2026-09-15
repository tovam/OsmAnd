package net.osmand.plus.plugins.flightmode

import org.json.JSONObject

/** The journal/cloud archive transports settings, never a recompressed replacement of the photo. */
object FlightPhotoTreatmentJson {
    fun write(adjustments: FlightPhotoImageAdjustments): JSONObject {
        val safe = adjustments.clamped()
        return JSONObject().apply {
            put("brightness", safe.brightness)
            put("contrast", safe.contrast)
            put("temperature", safe.temperature)
            put("tint", safe.tint)
            put("saturation", safe.saturation)
            put("dehaze", safe.dehaze)
            put("depthEnabled", safe.depthEnabled)
            safe.depthProfile?.let { put("depthProfile", it.toJson()) }
        }
    }

    fun read(json: JSONObject?): FlightPhotoImageAdjustments {
        if (json == null) return FlightPhotoImageAdjustments()
        return FlightPhotoImageAdjustments(
                brightness = json.optDouble("brightness", 0.0).toFloat(),
                contrast = json.optDouble("contrast", 0.0).toFloat(),
                temperature = json.optDouble("temperature", 0.0).toFloat(),
                tint = json.optDouble("tint", 0.0).toFloat(),
                saturation = json.optDouble("saturation", 0.0).toFloat(),
                dehaze = json.optDouble("dehaze", 0.0).toFloat(),
                depthEnabled = json.optBoolean("depthEnabled", true),
                depthProfile = FlightPhotoDepthProfile.fromJson(json.optJSONObject("depthProfile")),
            )
            .clamped()
    }
}
