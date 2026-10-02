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
    val stopSpeedKmh: Int = 3,
    val stopMinutes: Int = 2,
    val bands: List<FlightOfflineBand> =
        listOf(
            FlightOfflineBand(50, 12, 12),
            FlightOfflineBand(150, 10, 10),
            FlightOfflineBand(300, 8, 8),
        ),
    val additionalFlights: List<FlightScheduledLeg> = emptyList(),
) {
    val startMillis
        get() = departureMillis - startMinutesBefore * 60_000L

    fun scheduledFlights(): List<FlightScheduledLeg> =
        listOf(
            FlightScheduledLeg(
                departureMillis,
                arrivalMillis,
                departureOffsetMinutes,
                arrivalOffsetMinutes,
            )
        ) + additionalFlights

    fun validSchedule(): Boolean {
        val flights = scheduledFlights()
        return flights.size <= 20 &&
            flights.all { it.departureMillis > 0 && it.arrivalMillis > it.departureMillis } &&
            flights.zipWithNext().all { (a, b) -> b.departureMillis >= a.arrivalMillis }
    }

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
            put("gateToGate", true)
            put(
                "additionalFlights",
                JSONArray().apply {
                    additionalFlights.forEach { leg ->
                        put(
                            JSONObject()
                                .put("departure", leg.departureMillis)
                                .put("arrival", leg.arrivalMillis)
                                .put("departureOffset", leg.departureOffsetMinutes)
                                .put("arrivalOffset", leg.arrivalOffsetMinutes)
                        )
                    }
                },
            )
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
                j.optInt("stopSpeed", 3)
                    .let { if (!j.optBoolean("gateToGate") && it == 50) 3 else it }
                    .coerceIn(1, 10),
                j.optInt("stopMinutes", 2)
                    .let { if (!j.optBoolean("gateToGate") && it == 30) 2 else it }
                    .coerceIn(1, 10),
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
                j.optJSONArray("additionalFlights")?.let { legs ->
                    (0 until legs.length().coerceAtMost(19)).mapNotNull { i ->
                        legs.optJSONObject(i)?.let { leg ->
                            FlightScheduledLeg(
                                leg.optLong("departure"),
                                leg.optLong("arrival"),
                                leg.optInt("departureOffset").coerceIn(-720, 840),
                                leg.optInt("arrivalOffset").coerceIn(-720, 840),
                            )
                        }
                    }
                } ?: emptyList(),
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

data class FlightScheduledLeg(
    val departureMillis: Long,
    val arrivalMillis: Long,
    val departureOffsetMinutes: Int = 0,
    val arrivalOffsetMinutes: Int = 0,
)

data class FlightOfflineBand(val radiusKm: Int, val satelliteZoom: Int, val terrainZoom: Int)

enum class FlightTrackingPhase {
    WAITING,
    AIRBORNE,
    LAYOVER,
    LANDED,
    STOPPED,
}

/** An already-delivered broadcast must not revive a cancelled or newly postponed alarm. */
internal fun flightScheduleIsDue(scheduledStartMillis: Long?, nowMillis: Long): Boolean =
    scheduledStartMillis != null &&
        scheduledStartMillis > 0 &&
        scheduledStartMillis <= nowMillis + 1_000L

/**
 * Fresh fixes confirm a fast core; landing requires near-zero motion, including taxi to the gate.
 */
data class FlightTrackingState(
    val phase: FlightTrackingPhase = FlightTrackingPhase.WAITING,
    val baselineAltitude: Double? = null,
    val slowSinceMillis: Long? = null,
    val lastFixMillis: Long? = null,
    val completedFlights: Int = 0,
    val resumeAtMillis: Long? = null,
    val stopLatitude: Double? = null,
    val stopLongitude: Double? = null,
    val fastSinceMillis: Long? = null,
    val speedWindow: List<FlightSample> = emptyList(),
) {
    fun resumeIfDue(nowMillis: Long, plan: FlightPreparation): FlightTrackingState {
        if (phase != FlightTrackingPhase.LAYOVER) return this
        val next = plan.scheduledFlights().getOrNull(completedFlights)
        val at = next?.departureMillis?.takeIf { it > 0 }?.minus(plan.startMinutesBefore * 60_000L)
        if (at != null && nowMillis >= at)
            return copy(
                phase = FlightTrackingPhase.WAITING,
                baselineAltitude = null,
                slowSinceMillis = null,
                lastFixMillis = null,
                resumeAtMillis = null,
                stopLatitude = null,
                stopLongitude = null,
                fastSinceMillis = null,
                speedWindow = emptyList(),
            )
        return copy(resumeAtMillis = at)
    }

    /** Explicit recovery when the first usable GPS fix arrives after takeoff. */
    fun confirmAirborne(
        sample: FlightSample,
        nowMillis: Long,
        plan: FlightPreparation,
    ): FlightTrackingState {
        if (
            phase != FlightTrackingPhase.WAITING ||
                nowMillis - sample.timestampMillis !in -2000..15_000 ||
                sample.horizontalAccuracyMeters?.let { it in 0f..100f } != true ||
                sample.speedMetersPerSecond?.let {
                    it.isFinite() && it * 3.6 > plan.airborneSpeedKmh
                } != true
        )
            return this
        return copy(
            phase = FlightTrackingPhase.AIRBORNE,
            slowSinceMillis = null,
            lastFixMillis = sample.timestampMillis,
        )
    }

    fun accept(
        sample: FlightSample,
        nowMillis: Long,
        plan: FlightPreparation,
    ): FlightTrackingState {
        if (phase == FlightTrackingPhase.LANDED || phase == FlightTrackingPhase.STOPPED) return this
        val resumed = resumeIfDue(nowMillis, plan)
        if (resumed != this) return resumed.accept(sample, nowMillis, plan)
        if (phase == FlightTrackingPhase.LAYOVER) return this
        val reliable =
            nowMillis - sample.timestampMillis in -2000..15_000 &&
                sample.horizontalAccuracyMeters?.let { it.isFinite() && it in 0f..100f } == true
        if (!reliable || (lastFixMillis != null && sample.timestampMillis <= lastFixMillis))
            return copy(
                slowSinceMillis = null,
                fastSinceMillis = null,
                speedWindow = emptyList(),
                stopLatitude = null,
                stopLongitude = null,
            )
        val continuous =
            lastFixMillis != null && sample.timestampMillis - lastFixMillis in 1..30_000
        val recent =
            if (continuous)
                speedWindow.filter { sample.timestampMillis - it.timestampMillis <= 20_000 }
            else emptyList()
        val window = (recent + sample).takeLast(21)
        val evidence = FlightAutomaticTimes.evidence(sample, recent.lastOrNull())
        val speeds =
            window
                .mapNotNull {
                    it.speedMetersPerSecond?.takeIf { v -> v.isFinite() && v >= 0 }?.times(3.6)
                }
                .sorted()
        val speed =
            if (evidence.speedKmh == null) null
            else if (speeds.isNotEmpty()) speeds[speeds.size / 2] else evidence.speedKmh
        val baseline = baselineAltitude ?: sample.altitudeMeters?.takeIf {
            speed != null && speed <= plan.stopSpeedKmh.coerceIn(1, 10)
        }
        val waitingForPlannedFlight = completedFlights == 0 || plan.scheduledFlights().size > completedFlights
        if (phase == FlightTrackingPhase.WAITING && (waitingForPlannedFlight || speed == null || speed >= plan.airborneSpeedKmh)) {
            val fast =
                if (speed != null && speed >= plan.airborneSpeedKmh)
                    if (continuous) fastSinceMillis ?: sample.timestampMillis
                    else sample.timestampMillis
                else null
            val airborne =
                fast != null &&
                    sample.timestampMillis - fast >= 30_000 &&
                    (baseline == null ||
                        sample.altitudeMeters == null ||
                        sample.altitudeMeters - baseline >= plan.airborneGainMeters)
            return copy(
                phase = if (airborne) FlightTrackingPhase.AIRBORNE else phase,
                baselineAltitude = baseline,
                lastFixMillis = sample.timestampMillis,
                fastSinceMillis = fast,
                speedWindow = window,
                slowSinceMillis = null,
            )
        }
        val nearAnchor =
            stopLatitude == null ||
                stopLongitude == null ||
                FlightTerrainTilePlanner.distanceKm(
                    stopLatitude,
                    stopLongitude,
                    sample.latitude,
                    sample.longitude,
                ) <= .02
        val still =
            speed != null &&
                speed <= plan.stopSpeedKmh.coerceIn(1, 10).toDouble() &&
                nearAnchor
        val since =
            if (still)
                if (continuous) slowSinceMillis ?: sample.timestampMillis
                else sample.timestampMillis
            else null
        val landed =
            since != null &&
                sample.timestampMillis - since >= plan.stopMinutes.coerceIn(1, 10) * 60_000L
        val completed = completedFlights + if (landed && phase == FlightTrackingPhase.AIRBORNE) 1 else 0
        val next =
            if (landed)
                plan.scheduledFlights().getOrNull(completed)?.takeIf { it.departureMillis > 0 }
            else null
        return copy(
            phase =
                if (landed)
                    if (next != null) FlightTrackingPhase.LAYOVER else FlightTrackingPhase.LANDED
                else phase,
            completedFlights = completed,
            baselineAltitude = baseline,
            fastSinceMillis = null,
            resumeAtMillis = next?.departureMillis?.minus(plan.startMinutesBefore * 60_000L),
            slowSinceMillis = since,
            lastFixMillis = sample.timestampMillis,
            speedWindow = window,
            stopLatitude = if (since != null) stopLatitude ?: sample.latitude else null,
            stopLongitude = if (since != null) stopLongitude ?: sample.longitude else null,
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
