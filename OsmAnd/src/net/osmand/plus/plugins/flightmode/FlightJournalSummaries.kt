package net.osmand.plus.plugins.flightmode

import android.util.AtomicFile
import android.util.JsonReader
import java.io.File
import java.io.Reader
import org.json.JSONObject

/** Small, disposable sidecars. Listing never materializes a journal's measurements or media. */
internal object FlightJournalSummaries {
    private val indexLock = Any()

    /** A list refresh cannot roll back a save or resurrect a copy removed while it was reading. */
    fun mergeListing(
        initial: List<FlightJourneySummary>,
        current: List<FlightJourneySummary>,
        incoming: List<FlightJourneySummary>,
        complete: Boolean,
    ): List<FlightJourneySummary> {
        val initialById = initial.associateBy { it.id }
        val removed = initialById.keys - current.map { it.id }.toSet()
        val retained = if (complete) current.filter { initialById[it.id] != it } else current
        return (incoming + retained)
            .filterNot { it.id in removed }
            .groupBy { it.id }
            .values
            .map { entries -> entries.maxBy { it.updatedAtMillis } }
            .sortedByDescending { it.updatedAtMillis }
    }

    fun read(file: File): FlightJourneySummary {
        val length = file.length()
        val modified = file.lastModified()
        synchronized(indexLock) {
            runCatching {
                val json =
                    JSONObject(
                        AtomicFile(sidecar(file)).openRead().bufferedReader().use { it.readText() }
                    )
                if (json.getLong("length") == length && json.getLong("modified") == modified) {
                    return FlightJourneySummary(
                        json.getString("id"),
                        json.getString("name"),
                        json.getLong("updated"),
                        json.getInt("samples"),
                        json.getInt("photos"),
                    )
                }
            }
        }
        // One-time migration, streamed: a large GPX/photo depth array is skipped, not allocated.
        val summary = file.bufferedReader().use { scan(it, modified) }
        if (file.length() == length && file.lastModified() == modified)
            runCatching { write(file, summary, length, modified) }
        return summary
    }

    fun write(
        file: File,
        summary: FlightJourneySummary,
        length: Long = file.length(),
        modified: Long = file.lastModified(),
    ) =
        synchronized(indexLock) {
            val json =
                JSONObject()
                    .put("length", length)
                    .put("modified", modified)
                    .put("id", summary.id)
                    .put("name", summary.name)
                    .put("updated", summary.updatedAtMillis)
                    .put("samples", summary.sampleCount)
                    .put("photos", summary.photoCount)
            val atomic = AtomicFile(sidecar(file))
            val stream = atomic.startWrite()
            try {
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        }

    fun sidecar(file: File) = File(file.path + ".summary")

    internal fun scan(input: Reader, modified: Long): FlightJourneySummary {
        var id = ""
        var name = ""
        var updated = modified
        var samples = 0
        var photos = 0
        val stops = mutableListOf<FlightStop>()
        JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "updatedAtMillis" -> updated = reader.nextLong()
                "photos" -> photos = countArray(reader)
                "trip" -> {
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "samples" -> samples = countArray(reader)
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                }
                "plan" -> {
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "stops" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                var label = ""
                                reader.beginObject()
                                while (reader.hasNext()) when (reader.nextName()) {
                                    "name" -> label = reader.nextString()
                                    else -> reader.skipValue()
                                }
                                reader.endObject()
                                stops += FlightStop(label)
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val plan = FlightPlan(stops)
        return FlightJourneySummary(
            id,
            FlightJourneyNaming.updated(name, plan, plan),
            updated,
            samples,
            photos,
        )
    }

    private fun countArray(reader: JsonReader): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            reader.skipValue()
            count++
        }
        reader.endArray()
        return count
    }
}
