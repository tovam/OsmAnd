package net.osmand.plus.plugins.flightmode

import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Numeric capture offsets are explicit user/metadata choices, never inferred from import time. */
internal object FlightPhotoTime {
    fun currentOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    fun formatOffset(minutes: Int): String = String.format(Locale.ROOT, "%s%02d:%02d",
        if (minutes < 0) "-" else "+", abs(minutes) / 60, abs(minutes) % 60)

    fun parseOffset(text: String): Int? {
        val match = Regex("^([+-]?)([0-9]{1,2})(?::([0-9]{2}))?$").matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].ifEmpty { "0" }.toInt()
        if (minutes > 59) return null
        val result = (hours * 60 + minutes) * if (match.groupValues[1] == "-") -1 else 1
        return result.takeIf { it in -720..840 }
    }

    /** Undo only the fields still owned by the correction; retain subsequent user edits. */
    fun restoreCorrection(current: FlightPhotoAttachment, original: FlightPhotoAttachment,
                          applied: FlightPhotoAttachment): FlightPhotoAttachment {
        if (current.timestampMillis != applied.timestampMillis ||
            current.captureUtcOffsetMinutes != applied.captureUtcOffsetMinutes ||
            current.captureLocalTimestampMillis != applied.captureLocalTimestampMillis) return current
        return current.copy(timestampMillis = original.timestampMillis,
            captureUtcOffsetMinutes = original.captureUtcOffsetMinutes,
            captureLocalTimestampMillis = original.captureLocalTimestampMillis,
            timestampSource = if (current.timestampSource == applied.timestampSource) original.timestampSource else current.timestampSource,
            matchedSamplePosition = if (current.matchedSamplePosition == applied.matchedSamplePosition)
                original.matchedSamplePosition else current.matchedSamplePosition)
    }

    fun correctedTimestamp(photo: FlightPhotoAttachment, previousOffset: Int, targetOffset: Int): Long? {
        require(previousOffset in -720..840 && targetOffset in -720..840)
        val wallTime = photo.captureLocalTimestampMillis ?: photo.timestampMillis?.let {
            Math.addExact(it, (photo.captureUtcOffsetMinutes ?: previousOffset) * 60_000L)
        } ?: FlightPhotoTimestampParser.parseLocal(photo.fileName) ?: return null
        return Math.subtractExact(wallTime, targetOffset * 60_000L)
    }
}
