package net.osmand.plus.plugins.flightmode

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** An offset is explicit at each endpoint: changing the phone timezone never moves a flight. */
data class FlightPreparation(
    val departureMillis: Long = 0,
    val arrivalMillis: Long = 0,
    val departureOffsetMinutes: Int = 0,
    val arrivalOffsetMinutes: Int = 0,
    val automatic: Boolean = false,
    val startMinutesBefore: Int = 15,
    val airborneGainMeters: Int = 1000,
    val airborneSpeedKmh: Int = 200,
    val stopSpeedKmh: Int = 50,
    val stopMinutes: Int = 30,
    val bands: List<FlightOfflineBand> =
        listOf(
            FlightOfflineBand(50, 12, 12),
            FlightOfflineBand(150, 10, 10),
            FlightOfflineBand(300, 8, 8),
        ),
) {
    val startMillis
        get() = departureMillis - startMinutesBefore * 60_000L

    fun toJson() =
        JSONObject().apply {
            put("departure", departureMillis)
            put("arrival", arrivalMillis)
            put("departureOffset", departureOffsetMinutes)
            put("arrivalOffset", arrivalOffsetMinutes)
            put("automatic", automatic)
            put("before", startMinutesBefore)
            put("gain", airborneGainMeters)
            put("takeoffSpeed", airborneSpeedKmh)
            put("stopSpeed", stopSpeedKmh)
            put("stopMinutes", stopMinutes)
            put(
                "bands",
                JSONArray().apply {
                    bands.forEach {
                        put(JSONArray(listOf(it.radiusKm, it.satelliteZoom, it.terrainZoom)))
                    }
                },
            )
        }

    companion object {
        fun fromJson(j: JSONObject?): FlightPreparation? {
            if (j == null) return null
            val defaults = FlightPreparation()
            val a = j.optJSONArray("bands")
            return FlightPreparation(
                j.optLong("departure"),
                j.optLong("arrival"),
                j.optInt("departureOffset").coerceIn(-720, 840),
                j.optInt("arrivalOffset").coerceIn(-720, 840),
                j.optBoolean("automatic"),
                j.optInt("before", 15).coerceIn(0, 180),
                j.optInt("gain", 1000).coerceIn(100, 3000),
                j.optInt("takeoffSpeed", 200).coerceIn(100, 400),
                j.optInt("stopSpeed", 50).coerceIn(5, 100),
                j.optInt("stopMinutes", 30).coerceIn(5, 120),
                a?.let {
                        List(it.length().coerceAtMost(6)) { i ->
                            val b = it.getJSONArray(i)
                            FlightOfflineBand(
                                b.getInt(0).coerceIn(1, 600),
                                b.getInt(1).coerceIn(3, 14),
                                b.getInt(2).coerceIn(3, 14),
                            )
                        }
                    }
                    ?.sortedBy { it.radiusKm }
                    ?.takeIf { it.isNotEmpty() } ?: defaults.bands,
            )
        }

        fun dateText(millis: Long, offset: Int): String =
            if (millis <= 0) "" else formatter(offset).format(Date(millis))

        fun parseDate(text: String, offset: Int): Long? =
            runCatching {
                    val parser = formatter(offset).apply { isLenient = false }
                    val position = java.text.ParsePosition(0)
                    val parsed = parser.parse(text, position)
                    parsed?.time?.takeIf { position.index == text.length }
                }
                .getOrNull()

        private fun formatter(offset: Int) =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
                timeZone = SimpleTimeZone(offset * 60_000, "explicit-flight-offset")
            }

        fun offsetText(offset: Int) =
            "%s%02d:%02d"
                .format(
                    Locale.ROOT,
                    if (offset < 0) "-" else "+",
                    abs(offset) / 60,
                    abs(offset) % 60,
                )

        fun parseOffset(text: String): Int? {
            val m = Regex("([+-])(\\d{2}):?(\\d{2})").matchEntire(text) ?: return null
            val hours = m.groupValues[2].toInt()
            val minutes = m.groupValues[3].toInt()
            if (minutes > 59) return null
            return ((hours * 60 + minutes) * (if (m.groupValues[1] == "-") -1 else 1)).takeIf {
                it in -720..840
            }
        }
    }
}

data class FlightOfflineBand(val radiusKm: Int, val satelliteZoom: Int, val terrainZoom: Int)

enum class FlightTrackingPhase {
    WAITING,
    AIRBORNE,
    LANDED,
    STOPPED,
}

/** Only consecutive, fresh, reliable fixes can establish landing; GPS loss is never low speed. */
data class FlightTrackingState(
    val phase: FlightTrackingPhase = FlightTrackingPhase.WAITING,
    val baselineAltitude: Double? = null,
    val slowSinceMillis: Long? = null,
    val lastFixMillis: Long? = null,
) {
    /** Explicit recovery when the first usable GPS fix arrives after takeoff. */
    fun confirmAirborne(sample: FlightSample, nowMillis: Long, plan: FlightPreparation): FlightTrackingState {
        if (phase != FlightTrackingPhase.WAITING ||
            nowMillis - sample.timestampMillis !in -2000..15_000 ||
            sample.horizontalAccuracyMeters?.let { it in 0f..100f } != true ||
            sample.speedMetersPerSecond?.let { it.isFinite() && it * 3.6 > plan.airborneSpeedKmh } != true
        ) return this
        return copy(phase = FlightTrackingPhase.AIRBORNE, slowSinceMillis = null, lastFixMillis = sample.timestampMillis)
    }

    fun accept(
        sample: FlightSample,
        nowMillis: Long,
        plan: FlightPreparation,
    ): FlightTrackingState {
        if (phase == FlightTrackingPhase.LANDED || phase == FlightTrackingPhase.STOPPED) return this
        val age = nowMillis - sample.timestampMillis
        val reliable =
            age in -2000..15_000 && sample.horizontalAccuracyMeters?.let { it in 0f..100f } == true
        if (!reliable || (lastFixMillis != null && sample.timestampMillis <= lastFixMillis))
            return copy(slowSinceMillis = null)
        val baseline = baselineAltitude ?: sample.altitudeMeters
        val speed = sample.speedMetersPerSecond?.times(3.6)
        if (phase == FlightTrackingPhase.WAITING) {
            val airborne =
                baseline != null &&
                    sample.altitudeMeters != null &&
                    sample.altitudeMeters - baseline >= plan.airborneGainMeters &&
                    speed != null &&
                    speed > plan.airborneSpeedKmh
            return copy(
                phase = if (airborne) FlightTrackingPhase.AIRBORNE else phase,
                baselineAltitude = baseline,
                lastFixMillis = sample.timestampMillis,
                slowSinceMillis = null,
            )
        }
        val continuous = lastFixMillis != null && sample.timestampMillis - lastFixMillis <= 30_000
        val since =
            if (speed != null && speed < plan.stopSpeedKmh)
                if (continuous) slowSinceMillis ?: sample.timestampMillis
                else sample.timestampMillis
            else null
        return copy(
            phase =
                if (since != null && sample.timestampMillis - since >= plan.stopMinutes * 60_000L)
                    FlightTrackingPhase.LANDED
                else phase,
            slowSinceMillis = since,
            lastFixMillis = sample.timestampMillis,
        )
    }
}

data class FlightBatteryPoint(val timeMillis: Long, val percent: Float, val charging: Boolean)

/** Regression uses only the latest discharge segment; charging and clock resets break the fit. */
fun flightBatteryDrainPerHour(history: List<FlightBatteryPoint>): Double? {
    val end = history.lastOrNull() ?: return null
    if (end.charging) return null
    val points =
        history.asReversed().takeWhile {
            !it.charging && end.timeMillis - it.timeMillis in 0..3_600_000
        }
    if (points.size < 3 || end.timeMillis - points.last().timeMillis < 300_000) return null
    val x = points.map { (it.timeMillis - end.timeMillis) / 3_600_000.0 }
    val y = points.map { it.percent.toDouble() }
    val mx = x.average()
    val my = y.average()
    val variance = x.sumOf { (it - mx).pow(2) }
    if (variance <= 0) return null
    return (-x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / variance).coerceAtLeast(0.0)
}
