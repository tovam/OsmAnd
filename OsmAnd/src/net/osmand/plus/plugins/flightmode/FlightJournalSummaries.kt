package net.osmand.plus.plugins.flightmode

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.Reader
import org.json.JSONObject

/** Small, disposable sidecars. Listing never materializes a journal's measurements or media. */
internal object FlightJournalSummaries {
    private val indexLock = Any()
    private const val SUMMARY_SCHEMA_VERSION = 2

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
                if (json.optInt("schemaVersion", 0) == SUMMARY_SCHEMA_VERSION &&
                    json.getLong("length") == length && json.getLong("modified") == modified) {
                    return FlightJourneySummary(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        updatedAtMillis = json.getLong("updated"),
                        sampleCount = json.getInt("samples"),
                        photoCount = json.getInt("photos"),
                        simulation = json.optBoolean("simulation", false),
                        departureMillis = json.optNullableLong("departureMillis"),
                        terrainTileCount = json.optInt("terrainTileCount", 0),
                        satelliteTileCount = json.optInt("satelliteTileCount", 0),
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
                    .put("schemaVersion", SUMMARY_SCHEMA_VERSION)
                    .put("length", length)
                    .put("modified", modified)
                    .put("id", summary.id)
                    .put("name", summary.name)
                    .put("updated", summary.updatedAtMillis)
                    .put("samples", summary.sampleCount)
                    .put("photos", summary.photoCount)
                    .put("simulation", summary.simulation)
                    .putOptional("departureMillis", summary.departureMillis)
                    .put("terrainTileCount", summary.terrainTileCount)
                    .put("satelliteTileCount", summary.satelliteTileCount)
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
        var simulation = false
        var departureMillis: Long? = null
        var terrainTileCount = 0
        var satelliteTileCount = 0
        val stops = mutableListOf<FlightStop>()
        JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "id" -> id = nextStringOrEmpty(reader)
                "name" -> name = nextStringOrEmpty(reader)
                "updatedAtMillis" -> updated = nextLongOrDefault(reader, modified)
                "photos" -> photos = countArray(reader)
                "simulation" -> simulation = nextBooleanOrFalse(reader)
                "trip" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "samples" -> samples = countArray(reader)
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                    }
                }
                "plan" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "stops" -> {
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                } else {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        var label = ""
                                        if (reader.peek() == JsonToken.NULL) {
                                            reader.nextNull()
                                        } else {
                                            reader.beginObject()
                                            while (reader.hasNext()) when (reader.nextName()) {
                                                "name" -> label = nextStringOrEmpty(reader)
                                                else -> reader.skipValue()
                                            }
                                            reader.endObject()
                                            stops += FlightStop(label)
                                        }
                                    }
                                    reader.endArray()
                                }
                            }
                            "preparation" -> departureMillis = scanDeparture(reader)
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                    }
                }
                "offlineAssets" -> {
                    val counts = scanOfflineAssets(reader)
                    terrainTileCount = counts.first
                    satelliteTileCount = counts.second
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
            simulation,
            departureMillis,
            terrainTileCount,
            satelliteTileCount,
        )
    }

    private fun countArray(reader: JsonReader): Int {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return 0
        }
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            reader.skipValue()
            count++
        }
        reader.endArray()
        return count
    }

    private fun nextBooleanOrFalse(reader: JsonReader): Boolean {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return false
        }
        return reader.nextBoolean()
    }

    private fun nextStringOrEmpty(reader: JsonReader): String {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ""
        }
        return reader.nextString()
    }

    private fun nextLongOrDefault(reader: JsonReader, default: Long): Long {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return default
        }
        return reader.nextLong()
    }

    private fun scanDeparture(reader: JsonReader): Long? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        var departure: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "departure" -> departure = if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    null
                } else {
                    reader.nextLong().takeIf { it > 0L }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return departure
    }

    private fun scanOfflineAssets(reader: JsonReader): Pair<Int, Int> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return 0 to 0
        }
        var terrain = 0
        var satellite = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "terrainTiles" -> terrain = countArray(reader)
                "standardSatelliteTiles" -> satellite = countArray(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return terrain to satellite
    }

    private fun JSONObject.putOptional(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
