package net.osmand.plus.plugins.flightmode

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

internal const val FLIGHT_CLOUD_MAX_BYTES = 512L * 1024 * 1024
private const val CLOUD_JSON_MAX_BYTES = 32L * 1024 * 1024

/** The cloud payload is separate from the full offline export. Tile files cannot enter it. */
internal fun FlightJourneyStore.writeCloudArchive(
    journey: FlightJourney,
    photoIds: Set<String>,
    target: File,
) {
    require(photoIds.all { id -> journey.photos.any { it.id == id } })
    val selected = journey.photos.filter { it.id in photoIds }
    require(selected.size <= 1000)
    val portable =
        journey.copy(
            photos = selected,
            offlineAssets = FlightOfflineAssets(),
            offlineRequest = FlightOfflineAssets(),
            plan =
                journey.plan.copy(preparation = journey.plan.preparation?.copy(automatic = false)),
        )
    // Opaque names avoid local path leakage and collisions between identical original filenames.
    val names =
        selected
            .mapIndexed { index, p -> p.id to "image-$index.${cloudImageExtension(p.fileName)}" }
            .toMap()
    val json = journeyToJson(portable, names).toString().toByteArray(Charsets.UTF_8)
    require(json.size <= CLOUD_JSON_MAX_BYTES)
    var uncompressed = json.size.toLong()
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        fun write(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        }
        write("journey.json", json)
        val gpx = buildGpx(portable).toByteArray(Charsets.UTF_8)
        uncompressed += gpx.size
        require(uncompressed <= FLIGHT_CLOUD_MAX_BYTES)
        write("track.gpx", gpx)
        selected.forEach { photo ->
            val file = File(photo.localPath)
            if (!file.isFile) throw IOException("cloud_photo_missing")
            zip.putNextEntry(ZipEntry("photos/${names.getValue(photo.id)}").apply { time = 0L })
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    uncompressed += count
                    if (uncompressed > FLIGHT_CLOUD_MAX_BYTES)
                        throw IOException("archive_too_large")
                    zip.write(buffer, 0, count)
                }
            }
            zip.closeEntry()
        }
    }
    if (target.length() > FLIGHT_CLOUD_MAX_BYTES) throw IOException("archive_too_large")
}

/** Always import a new local journal. Existing edits and recordings are never overwritten. */
internal fun FlightJourneyStore.importCloudArchive(archive: File): FlightJourney {
    val created = mutableListOf<File>()
    var saved = false
    try {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(
                entries.size in 2..1002 && entries.map { it.name }.distinct().size == entries.size
            )
            require(entries.sumOf { it.size.coerceAtLeast(0) } <= FLIGHT_CLOUD_MAX_BYTES)
            require(
                entries.all { e ->
                    !e.isDirectory &&
                        e.size in 0..FLIGHT_CLOUD_MAX_BYTES &&
                        !e.name.contains('\\') &&
                        (e.name in listOf("journey.json", "track.gpx") ||
                            (e.name.startsWith("photos/") &&
                                e.name.count { it == '/' } == 1 &&
                                e.name.length < 240 &&
                                !e.name.substringAfter('/').startsWith('.')))
                }
            )
            val manifest = requireNotNull(zip.getEntry("journey.json"))
            require(manifest.size in 1..CLOUD_JSON_MAX_BYTES && zip.getEntry("track.gpx") != null)
            val bytes =
                zip.getInputStream(manifest).use { it.readBytesBounded(CLOUD_JSON_MAX_BYTES) }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val photos = root.optJSONArray("photos") ?: org.json.JSONArray()
            val names =
                (0 until photos.length()).map { photos.getJSONObject(it).getString("storageName") }
            require(names.distinct().size == names.size)
            require(
                names.map { "photos/$it" }.toSet() ==
                    entries.filter { it.name.startsWith("photos/") }.map { it.name }.toSet()
            )
            val paths =
                names.associateWith { name ->
                    // Never construct a destination path from an archive name.
                    val destination = createCloudPhotoFile(cloudImageExtension(name))
                    created += destination
                    val entry = requireNotNull(zip.getEntry("photos/$name"))
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                if (Thread.currentThread().isInterrupted)
                                    throw InterruptedException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= entry.size)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    destination.absolutePath
                }
            val parsed = journeyFromJson(root) { requireNotNull(paths[it]) }
            val imported =
                save(
                    parsed.copy(
                        id = UUID.randomUUID().toString(),
                        updatedAtMillis = System.currentTimeMillis(),
                        offlineAssets = FlightOfflineAssets(),
                        offlineRequest = FlightOfflineAssets(),
                        plan =
                            parsed.plan.copy(
                                preparation = parsed.plan.preparation?.copy(automatic = false)
                            ),
                    )
                )
            saved = true
            return imported
        }
    } finally {
        if (!saved) created.forEach { it.delete() }
    }
}

private fun cloudImageExtension(name: String): String =
    name.substringAfterLast('.', "bin").lowercase(java.util.Locale.ROOT).takeIf {
        it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    } ?: "bin"

internal fun java.io.InputStream.readBytesBounded(limit: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
