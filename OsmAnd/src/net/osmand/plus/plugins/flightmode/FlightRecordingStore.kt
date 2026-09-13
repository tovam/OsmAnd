package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject

/** Append-only measurements; photo edits and metadata saves cannot overwrite a running recorder. */
internal class FlightRecordingStore(context: Context, id: String) {
    private val root = File(context.filesDir, "flight-recordings").apply { mkdirs() }
    private val safeId = id.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    private val events = File(root, "$safeId.jsonl")
    private val stateFile = AtomicFile(File(root, "$safeId.state"))
    private val codec = FlightJourneyStore(context)

    fun sizeBytes(): Long = events.length() + stateFile.baseFile.length()

    fun append(sample: FlightSample) =
        appendObject(JSONObject().put("sample", codec.sampleToJson(sample)))

    fun append(battery: FlightBatteryPoint) =
        appendObject(
            JSONObject()
                .put(
                    "battery",
                    JSONObject()
                        .put("time", battery.timeMillis)
                        .put("percent", battery.percent)
                        .put("charging", battery.charging),
                )
        )

    private fun appendObject(json: JSONObject) =
        synchronized(EVENT_LOCK) {
            FlightRecordingLines.recoverTail(events)
            FileOutputStream(events, true).use { out ->
                out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }

    fun samplesAndBattery(): Pair<List<FlightSample>, List<FlightBatteryPoint>> =
        synchronized(EVENT_LOCK) {
            FlightRecordingLines.recoverTail(events)
            val samples = mutableListOf<FlightSample>()
            val battery = mutableListOf<FlightBatteryPoint>()
            if (events.isFile)
                events.useLines { rows ->
                    rows.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val j =
                            try {
                                JSONObject(line)
                            } catch (e: Exception) {
                                throw IOException(
                                    "Incomplete flight recording: original measurements preserved",
                                    e,
                                )
                            }
                        j.optJSONObject("sample")?.let { samples += codec.sampleFromJson(it) }
                        j.optJSONObject("battery")?.let {
                            battery +=
                                FlightBatteryPoint(
                                    it.getLong("time"),
                                    it.getDouble("percent").toFloat(),
                                    it.getBoolean("charging"),
                                )
                        }
                    }
                }
            samples to battery
        }

    fun readState(): FlightTrackingState {
        if (!stateFile.baseFile.exists()) return FlightTrackingState()
        val j = JSONObject(stateFile.openRead().bufferedReader().use { it.readText() })
        return FlightTrackingState(
            FlightTrackingPhase.valueOf(j.getString("phase")),
            j.optDouble("baseline").takeIf(Double::isFinite),
            j.optLong("slow").takeIf { it > 0 },
            j.optLong("fix").takeIf { it > 0 },
        )
    }

    fun writeState(state: FlightTrackingState) {
        val j =
            JSONObject()
                .put("phase", state.phase.name)
                .put("baseline", state.baselineAltitude)
                .put("slow", state.slowSinceMillis)
                .put("fix", state.lastFixMillis)
        val stream = stateFile.startWrite()
        try {
            stream.write(j.toString().toByteArray())
            stateFile.finishWrite(stream)
        } catch (e: Exception) {
            stateFile.failWrite(stream)
            throw e
        }
    }

    fun merge(journey: FlightJourney): FlightJourney {
        if (!events.isFile) return journey
        val (samples, battery) = samplesAndBattery()
        return journey.copy(
            trip =
                if (samples.isEmpty()) journey.trip else recordedFlightTrip(journey.name, samples),
            batteryHistory = battery.ifEmpty { journey.batteryHistory },
        )
    }

    companion object {
        private val EVENT_LOCK = Any()
    }
}
