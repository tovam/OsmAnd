package net.osmand.plus.plugins.flightmode

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Archive tests substitute only Android persistence; production models and ZIP code are used. */
class FlightJourneyStore(private val directory: File) {
    lateinit var serialized: FlightJourney
    lateinit var imported: FlightJourney
    var saved: FlightJourney? = null

    internal fun journeyToJson(journey: FlightJourney, names: Map<String, String>): JSONObject {
        serialized = journey
        return JSONObject()
            .put("schemaVersion", 9)
            .put("name", journey.name)
            .put(
                "trip",
                JSONObject()
                    .put(
                        "samples",
                        JSONArray(
                            journey.trip.samples.map {
                                JSONArray(listOf(it.latitude, it.longitude))
                            }
                        ),
                    ),
            )
            .put(
                "photos",
                JSONArray(
                    journey.photos.map {
                        JSONObject().put("id", it.id).put("storageName", names.getValue(it.id))
                    }
                ),
            )
    }

    internal fun journeyFromJson(root: JSONObject, path: (String) -> String): FlightJourney {
        val photos = root.getJSONArray("photos")
        return imported.copy(
            photos =
                (0 until photos.length()).map { index ->
                    val row = photos.getJSONObject(index)
                    imported.photos
                        .first { it.id == row.getString("id") }
                        .copy(localPath = path(row.getString("storageName")))
                }
        )
    }

    internal fun buildGpx(journey: FlightJourney): String = "<gpx/>"

    internal fun createCloudPhotoFile(extension: String): File =
        File(directory, "${UUID.randomUUID()}.$extension")

    fun save(journey: FlightJourney): FlightJourney = journey.also { saved = it }
}
