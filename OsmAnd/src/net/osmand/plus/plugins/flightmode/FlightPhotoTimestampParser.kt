package net.osmand.plus.plugins.flightmode

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Extracts the local capture time used by common Android camera file names. */
internal object FlightPhotoTimestampParser {
	private val cameraTimestamp = Regex(
		"(?:^|[^0-9])" +
			"((?:19|20)\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})" +
			"[ T_.-]*" +
			"((?:[01]\\d|2[0-3]))[-_.:]?([0-5]\\d)[-_.:]?([0-5]\\d)"
	)

	fun parse(fileName: String, utcOffsetMinutes: Int? = null): Long? {
        val offset = utcOffsetMinutes?.takeIf { it in -720..840 } ?: return null
        return parseLocal(fileName)?.let { it - offset * 60_000L }
    }

    fun parseLocal(fileName: String): Long? {
		val encoded = cameraTimestamp.find(fileName)
			?.groupValues
			?.drop(1)
			?.joinToString("")
			?: return null
		return runCatching {
			SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
				isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
			}.parse(encoded)?.time
		}.getOrNull()
	}
}
