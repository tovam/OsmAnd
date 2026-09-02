package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.media.MediaMetadataUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Persists one editable flight log and exports a portable archive. */
class FlightJourneyStore(private val context: Context) {

	private val journeysDirectory = File(context.filesDir, JOURNEYS_DIRECTORY).also { it.mkdirs() }
	private val mediaDirectory = File(context.filesDir, MEDIA_DIRECTORY).also { it.mkdirs() }

	fun storageUsage(
		currentJourneyId: String?,
		currentPhotos: List<FlightPhotoAttachment>
	): FlightStorageUsage {
		val currentJournalBytes = currentJourneyId?.let { id ->
			runCatching {
				File(journeysDirectory, "${validatedId(id)}.$JOURNEY_FILE_EXTENSION")
					.takeIf(File::isFile)?.length() ?: 0L
			}.getOrDefault(0L)
		} ?: 0L
		val mediaRoot = runCatching { mediaDirectory.canonicalFile }.getOrNull()
		val currentPhotosBytes = currentPhotos.mapNotNull { photo ->
			runCatching { File(photo.localPath).canonicalFile }.getOrNull()
		}.filter { file ->
			file.isFile && mediaRoot != null && file.parentFile == mediaRoot
		}.distinctBy(File::getAbsolutePath).sumOf(File::length)

		val terrainRoot = File(context.filesDir, FLIGHT_TERRAIN_DIRECTORY)
		val terrainBytes = treeSize(File(context.filesDir, TERRARIUM_DIRECTORY))
		val satelliteSourceBytes = treeSize(File(context.filesDir, FlightSatelliteSource.CACHE_DIRECTORY))
		val satelliteRenderBytes = treeSize(File(context.filesDir, FlightSatelliteSource.RENDER_CACHE_DIRECTORY))
		val categorizedTerrainBytes = terrainBytes + satelliteSourceBytes + satelliteRenderBytes
		val otherTerrainBytes = (treeSize(terrainRoot) - categorizedTerrainBytes).coerceAtLeast(0L)
		val allJournalBytes = treeSize(journeysDirectory)
		val allPhotosBytes = treeSize(mediaDirectory)
		val allFlightFilesBytes = context.filesDir.listFiles().orEmpty()
			.filter { it.name.startsWith("flight-") }
			.sumOf(::treeSize)
		val knownPrivateBytes = allJournalBytes + allPhotosBytes + treeSize(terrainRoot)

		val installedGraphicsBytes = (context.applicationContext as? OsmandApplication)?.let { app ->
			treeSize(File(app.getAppPath(IndexConstants.MODEL_3D_DIR), FLIGHT_AIRCRAFT_MODEL_DIRECTORY))
		} ?: 0L
		return FlightStorageUsage(
			currentJournalBytes = currentJournalBytes,
			currentPhotosBytes = currentPhotosBytes,
			allJournalBytes = allJournalBytes,
			allPhotosBytes = allPhotosBytes,
			terrainBytes = terrainBytes,
			satelliteSourceBytes = satelliteSourceBytes,
			satelliteRenderBytes = satelliteRenderBytes,
			graphicsBytes = assetTreeSize(FLIGHT_GRAPHICS_ASSET_DIRECTORY) + installedGraphicsBytes,
			otherBytes = otherTerrainBytes + (allFlightFilesBytes - knownPrivateBytes).coerceAtLeast(0L)
		)
	}

	private fun treeSize(root: File): Long {
		if (!root.exists()) return 0L
		val pending = java.util.ArrayDeque<File>()
		pending.add(root)
		var bytes = 0L
		while (pending.isNotEmpty()) {
			val file = pending.removeLast()
			when {
				file.isFile -> bytes += file.length()
				file.isDirectory -> file.listFiles().orEmpty().forEach(pending::add)
			}
		}
		return bytes
	}

	private fun assetTreeSize(path: String): Long {
		val children = context.assets.list(path).orEmpty()
		if (children.isEmpty()) {
			return runCatching { context.assets.open(path).use { input ->
				var total = 0L
				val buffer = ByteArray(16 * 1_024)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					total += count
				}
				total
			} }.getOrDefault(0L)
		}
		return children.sumOf { child -> assetTreeSize("$path/$child") }
	}

	fun list(): List<FlightJourneySummary> = journeyFiles()
		.mapNotNull { file ->
			runCatching { summaryFromJson(JSONObject(file.readText()), file) }.getOrNull()
		}
		.sortedByDescending { it.updatedAtMillis }

	/** Returns the newest journal containing the exact same ordered GPX trace, if one exists. */
	fun findMatchingJourney(trip: FlightTrip): FlightJourneySummary? {
		val expectedFingerprint = FlightTripFingerprint.create(trip)
		return journeyFiles().mapNotNull { file ->
			runCatching {
				val root = JSONObject(file.readText())
				val storedFingerprint = root.optString("tripFingerprint").ifBlank {
					FlightTripFingerprint.create(tripFromJson(root.getJSONObject("trip")))
				}
				if (storedFingerprint == expectedFingerprint) summaryFromJson(root, file) else null
			}.getOrNull()
		}.maxByOrNull { it.updatedAtMillis }
	}

	private fun journeyFiles(): List<File> = journeysDirectory.listFiles()
		.orEmpty()
		.filter { it.isFile && it.extension == JOURNEY_FILE_EXTENSION }

	private fun summaryFromJson(root: JSONObject, file: File): FlightJourneySummary = FlightJourneySummary(
		id = root.getString("id"),
		name = root.optString("name").ifBlank { "Journal de vol" },
		updatedAtMillis = root.optLong("updatedAtMillis", file.lastModified()),
		sampleCount = root.optJSONObject("trip")?.optJSONArray("samples")?.length() ?: 0,
		photoCount = root.optJSONArray("photos")?.length() ?: 0
	)

	fun save(journey: FlightJourney): FlightJourney {
		val safeId = validatedId(journey.id)
		val destination = File(journeysDirectory, "$safeId.$JOURNEY_FILE_EXTENSION")
		val temporary = File(journeysDirectory, ".$safeId.tmp")
		temporary.writeText(journeyToJson(journey).toString())
		if (destination.exists() && !destination.delete()) {
			temporary.delete()
			throw IOException("Impossible de remplacer le voyage enregistré")
		}
		if (!temporary.renameTo(destination)) {
			temporary.delete()
			throw IOException("Impossible d’enregistrer le voyage")
		}
		return journey
	}

	fun load(id: String): FlightJourney {
		val file = File(journeysDirectory, "${validatedId(id)}.$JOURNEY_FILE_EXTENSION")
		if (!file.isFile) throw IOException("Journal de vol introuvable")
		return journeyFromJson(JSONObject(file.readText())) { storageName ->
			File(mediaDirectory, safeFileName(storageName)).absolutePath
		}
	}

	fun isJourneyArchive(uri: Uri): Boolean = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
		val first = input.read()
		val second = input.read()
		first == 'P'.code && second == 'K'.code
	} ?: false

	fun importArchive(uri: Uri): FlightJourney {
		val importedMedia = linkedMapOf<String, String>()
		var journeyJson: String? = null
		var totalBytes = 0L
		context.contentResolver.openInputStream(uri)?.buffered()?.use { raw ->
			ZipInputStream(raw).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					if (entry.isDirectory) continue
					when {
						entry.name == JOURNEY_JSON_ENTRY -> {
							journeyJson = readLimited(zip, MAXIMUM_JSON_BYTES).toString(Charsets.UTF_8)
						}
						entry.name.startsWith("photos/") -> {
							val archiveName = safeFileName(entry.name.substringAfterLast('/'))
							val destination = File(mediaDirectory, "${UUID.randomUUID()}_$archiveName")
							destination.outputStream().buffered().use { output ->
								totalBytes += copyLimited(zip, output, MAXIMUM_ARCHIVE_BYTES - totalBytes)
							}
							importedMedia[archiveName] = destination.absolutePath
						}
					}
					zip.closeEntry()
				}
			}
		} ?: throw IOException("Impossible d’ouvrir cette archive")
		val parsed = journeyFromJson(
			JSONObject(journeyJson ?: throw IOException("Archive sans journey.json"))
		) { storageName ->
			importedMedia[safeFileName(storageName)] ?: File(mediaDirectory, safeFileName(storageName)).absolutePath
		}
		val now = System.currentTimeMillis()
		return save(parsed.copy(id = UUID.randomUUID().toString(), updatedAtMillis = now))
	}

	fun exportArchive(journey: FlightJourney, uri: Uri) {
		val archiveNames = journey.photos.associate { photo ->
			photo.id to "${safeFileName(photo.id)}_${safeFileName(photo.fileName)}"
		}
		context.contentResolver.openOutputStream(uri, "wt")?.buffered()?.use { raw ->
			ZipOutputStream(raw).use { zip ->
				zip.putNextEntry(ZipEntry(JOURNEY_JSON_ENTRY))
				zip.write(journeyToJson(journey, archiveNames).toString(2).toByteArray())
				zip.closeEntry()
				zip.putNextEntry(ZipEntry(TRACK_GPX_ENTRY))
				zip.write(buildGpx(journey).toByteArray())
				zip.closeEntry()
				journey.photos.forEach { photo ->
					val file = File(photo.localPath)
					if (!file.isFile) return@forEach
					zip.putNextEntry(ZipEntry("photos/${archiveNames.getValue(photo.id)}"))
					file.inputStream().buffered().use { it.copyTo(zip) }
					zip.closeEntry()
				}
			}
		} ?: throw IOException("Impossible de créer l’archive")
	}

	fun importPhotos(uris: List<Uri>, trip: FlightTrip?): List<FlightPhotoAttachment> = uris.mapNotNull { uri ->
		runCatching {
			val originalName = displayName(uri) ?: "photo.jpg"
			val extension = originalName.substringAfterLast('.', "jpg")
				.lowercase(Locale.ROOT)
				.replace(Regex("[^a-z0-9]"), "")
				.take(8)
				.ifBlank { "jpg" }
			val destination = File(mediaDirectory, "${UUID.randomUUID()}.$extension")
			context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
				destination.outputStream().buffered().use { output -> input.copyTo(output) }
			} ?: throw IOException("Photo inaccessible")
			val timestamp = photoTimestamp(uri, destination)
			FlightPhotoAttachment(
				id = UUID.randomUUID().toString(),
				fileName = originalName,
				localPath = destination.absolutePath,
				timestampMillis = timestamp,
				matchedSampleIndex = matchSampleIndex(trip, timestamp)
			)
		}.getOrNull()
	}

	fun createCaptureFile(): File = File(mediaDirectory, "${UUID.randomUUID()}.jpg")

	fun capturedPhoto(
		file: File,
		fallbackTimestampMillis: Long,
		trip: FlightTrip?,
		includeMainCamera: Boolean,
		includeSelfie: Boolean,
		includeMap: Boolean,
		includeScene3d: Boolean
	): FlightPhotoAttachment {
		val timestamp = runCatching { MediaMetadataUtils.getPhotoCreationTime(file) }.getOrNull()
			?: fallbackTimestampMillis
		return FlightPhotoAttachment(
			id = UUID.randomUUID().toString(),
			fileName = file.name,
			localPath = file.absolutePath,
			timestampMillis = timestamp,
			matchedSampleIndex = matchSampleIndex(trip, timestamp),
			includeMainCamera = includeMainCamera,
			includeSelfie = includeSelfie,
			includeMap = includeMap,
			includeScene3d = includeScene3d
		)
	}

	fun discardPhotos(photos: List<FlightPhotoAttachment>) {
		val mediaRoot = mediaDirectory.canonicalFile
		photos.forEach { photo ->
			runCatching {
				val file = File(photo.localPath).canonicalFile
				if (file.parentFile == mediaRoot && file.isFile) file.delete()
			}
		}
	}

	private fun journeyToJson(
		journey: FlightJourney,
		photoStorageNames: Map<String, String> = emptyMap()
	): JSONObject = JSONObject().apply {
		put("schemaVersion", SCHEMA_VERSION)
		put("id", journey.id)
		put("name", journey.name)
		put("createdAtMillis", journey.createdAtMillis)
		put("updatedAtMillis", journey.updatedAtMillis)
		put("tripFingerprint", FlightTripFingerprint.create(journey.trip))
		put("plan", planToJson(journey.plan))
		put("trip", tripToJson(journey.trip))
		put("flightSpans", JSONArray().apply {
			journey.flightSpans.forEach { span ->
				put(JSONObject().put("start", span.startProgress).put("end", span.endProgress))
			}
		})
		put("photos", JSONArray().apply {
			journey.photos.forEach { photo ->
				put(JSONObject().apply {
					put("id", photo.id)
					put("fileName", photo.fileName)
					put("storageName", photoStorageNames[photo.id] ?: File(photo.localPath).name)
					putOptional("timestampMillis", photo.timestampMillis)
					putOptional("matchedSampleIndex", photo.matchedSampleIndex)
					put("includeMainCamera", photo.includeMainCamera)
					put("includeSelfie", photo.includeSelfie)
					put("includeMap", photo.includeMap)
					put("includeScene3d", photo.includeScene3d)
				})
			}
		})
	}

	private fun journeyFromJson(root: JSONObject, photoPath: (String) -> String): FlightJourney {
		if (root.optInt("schemaVersion") !in 1..SCHEMA_VERSION) throw IOException("Version de voyage incompatible")
		val trip = tripFromJson(root.getJSONObject("trip"))
		val photosJson = root.optJSONArray("photos") ?: JSONArray()
		val photos = (0 until photosJson.length()).mapNotNull { index ->
			photosJson.optJSONObject(index)?.let { json ->
				val storageName = safeFileName(json.optString("storageName"))
				FlightPhotoAttachment(
					id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
					fileName = json.optString("fileName").ifBlank { storageName },
					localPath = photoPath(storageName),
					timestampMillis = json.optNullableLong("timestampMillis"),
					matchedSampleIndex = json.optNullableInt("matchedSampleIndex"),
					includeMainCamera = json.optBoolean("includeMainCamera", true),
					includeSelfie = json.optBoolean("includeSelfie", false),
					includeMap = json.optBoolean("includeMap", true),
					includeScene3d = json.optBoolean("includeScene3d", true)
				)
			}
		}
		val spansJson = root.optJSONArray("flightSpans") ?: JSONArray()
		val spans = (0 until spansJson.length()).mapNotNull { index ->
			spansJson.optJSONObject(index)?.let {
				FlightSpan(it.optDouble("start").toFloat(), it.optDouble("end").toFloat()).normalized()
			}
		}
		return FlightJourney(
			id = validatedId(root.optString("id").ifBlank { UUID.randomUUID().toString() }),
			name = root.optString("name").ifBlank { trip.name },
			createdAtMillis = root.optLong("createdAtMillis", System.currentTimeMillis()),
			updatedAtMillis = root.optLong("updatedAtMillis", System.currentTimeMillis()),
			plan = planFromJson(root.optJSONObject("plan")),
			trip = trip,
			flightSpans = spans,
			photos = photos
		)
	}

	private fun planToJson(plan: FlightPlan): JSONObject = JSONObject().apply {
		put("terrainCorridorKm", plan.terrainCorridorKm)
		put("detailedSatelliteRadiusKm", plan.detailedSatelliteRadiusKm)
		put("satelliteQuality", plan.satelliteQuality.name)
		put("shadowsEnabled", plan.shadowsEnabled)
		put("resumeAfterRestart", plan.resumeAfterRestart)
		put("stops", JSONArray().apply {
			plan.stops.forEach { stop ->
				put(JSONObject().apply {
					put("name", stop.name)
					putOptional("latitude", stop.latitude)
					putOptional("longitude", stop.longitude)
				})
			}
		})
	}

	private fun planFromJson(json: JSONObject?): FlightPlan {
		if (json == null) return FlightPlan.preview()
		val stopsJson = json.optJSONArray("stops") ?: JSONArray()
		val stops = (0 until stopsJson.length()).mapNotNull { index ->
			stopsJson.optJSONObject(index)?.let {
				FlightStop(it.optString("name"), it.optNullableDouble("latitude"), it.optNullableDouble("longitude"))
			}
		}.takeIf { it.size >= 2 } ?: FlightPlan.preview().stops
		return FlightPlan(
			stops = stops,
			terrainCorridorKm = json.optInt("terrainCorridorKm", 300),
			detailedSatelliteRadiusKm = json.optInt("detailedSatelliteRadiusKm", 300),
			satelliteQuality = runCatching {
				FlightSatelliteQuality.valueOf(json.optString("satelliteQuality", FlightSatelliteQuality.HIGH.name))
			}.getOrDefault(FlightSatelliteQuality.HIGH),
			shadowsEnabled = json.optBoolean("shadowsEnabled", true),
			resumeAfterRestart = json.optBoolean("resumeAfterRestart", true)
		)
	}

	private fun tripToJson(trip: FlightTrip): JSONObject = JSONObject().apply {
		put("name", trip.name)
		put("hasUsableTimestamps", trip.hasUsableTimestamps)
		put("totalDistanceMeters", trip.totalDistanceMeters)
		put("sourceDescription", trip.sourceDescription)
		put("samples", JSONArray().apply { trip.samples.forEach { put(sampleToJson(it)) } })
		put("legs", JSONArray().apply {
			trip.legs.forEach { leg ->
				put(JSONObject().apply {
					put("index", leg.index)
					put("name", leg.name)
					put("startSampleIndex", leg.startSampleIndex)
					put("endSampleIndex", leg.endSampleIndex)
					put("distanceMeters", leg.distanceMeters)
					putOptional("startTimeMillis", leg.startTimeMillis)
					putOptional("endTimeMillis", leg.endTimeMillis)
				})
			}
		})
	}

	private fun tripFromJson(json: JSONObject): FlightTrip {
		val samplesJson = json.getJSONArray("samples")
		val samples = FlightTrackMath.fillMissingBearings((0 until samplesJson.length()).map { index ->
			sampleFromJson(samplesJson.getJSONObject(index))
		})
		if (samples.isEmpty()) throw IOException("Voyage sans point GPS")
		val legsJson = json.optJSONArray("legs") ?: JSONArray()
		val legs = (0 until legsJson.length()).mapNotNull { index ->
			legsJson.optJSONObject(index)?.let { leg ->
				FlightLeg(
					index = leg.optInt("index", index),
					name = leg.optString("name"),
					startSampleIndex = leg.optInt("startSampleIndex"),
					endSampleIndex = leg.optInt("endSampleIndex"),
					distanceMeters = leg.optDouble("distanceMeters"),
					startTimeMillis = leg.optNullableLong("startTimeMillis"),
					endTimeMillis = leg.optNullableLong("endTimeMillis")
				)
			}
		}.ifEmpty {
			listOf(FlightLeg(0, "", 0, samples.lastIndex, json.optDouble("totalDistanceMeters"),
				samples.first().timestampMillis, samples.last().timestampMillis))
		}
		return FlightTrip(
			name = json.optString("name").ifBlank { "Journal de vol" },
			samples = samples,
			legs = legs,
			hasUsableTimestamps = json.optBoolean("hasUsableTimestamps"),
			totalDistanceMeters = json.optDouble("totalDistanceMeters"),
			sourceDescription = json.optString("sourceDescription")
		)
	}

	private fun sampleToJson(sample: FlightSample): JSONObject = JSONObject().apply {
		put("index", sample.index)
		put("legIndex", sample.legIndex)
		put("timestampMillis", sample.timestampMillis)
		put("latitude", sample.latitude)
		put("longitude", sample.longitude)
		putOptional("altitudeMeters", sample.altitudeMeters)
		putOptional("speedMetersPerSecond", sample.speedMetersPerSecond)
		putOptional("bearingDegrees", sample.bearingDegrees)
		putOptional("horizontalAccuracyMeters", sample.horizontalAccuracyMeters)
		putOptional("hdop", sample.hdop)
		putOptional("satellitesUsed", sample.satellitesUsed)
		putOptional("satellitesFound", sample.satellitesFound)
		putOptional("soundDb", sample.soundDb)
		putOptional("vibrationHz", sample.vibrationHz)
		sample.soundSpectrum?.let { spectrum ->
			put("soundSpectrum", JSONArray().apply { spectrum.forEach { value -> put(value) } })
		}
	}

	private fun sampleFromJson(json: JSONObject): FlightSample {
		val spectrumJson = json.optJSONArray("soundSpectrum")
		return FlightSample(
			index = json.optInt("index"),
			legIndex = json.optInt("legIndex"),
			timestampMillis = json.optLong("timestampMillis"),
			latitude = json.getDouble("latitude"),
			longitude = json.getDouble("longitude"),
			altitudeMeters = json.optNullableDouble("altitudeMeters"),
			speedMetersPerSecond = json.optNullableDouble("speedMetersPerSecond")?.toFloat(),
			bearingDegrees = json.optNullableDouble("bearingDegrees")?.toFloat(),
			horizontalAccuracyMeters = json.optNullableDouble("horizontalAccuracyMeters")?.toFloat(),
			hdop = json.optNullableDouble("hdop")?.toFloat(),
			satellitesUsed = json.optNullableInt("satellitesUsed"),
			satellitesFound = json.optNullableInt("satellitesFound"),
			soundDb = json.optNullableDouble("soundDb")?.toFloat(),
			soundSpectrum = spectrumJson?.let { array ->
				(0 until array.length()).map { index -> array.optDouble(index).toFloat() }
			},
			vibrationHz = json.optNullableDouble("vibrationHz")?.toFloat()
		)
	}

	private fun buildGpx(journey: FlightJourney): String {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		return buildString {
			append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
			append("<gpx version=\"1.1\" creator=\"OsmAnd Smart Flight\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:osmandflight=\"https://osmand.net/xmlschemas/flight/1\">\n")
			append("  <trk><name>").append(xmlEscape(journey.name)).append("</name>\n")
			journey.trip.samples.groupBy { it.legIndex }.values.forEach { samples ->
				append("    <trkseg>\n")
				samples.forEach { sample ->
					append("      <trkpt lat=\"").append(sample.latitude).append("\" lon=\"").append(sample.longitude).append("\">\n")
					sample.altitudeMeters?.let { append("        <ele>").append(it).append("</ele>\n") }
					if (sample.timestampMillis > 0L) append("        <time>").append(dateFormat.format(Date(sample.timestampMillis))).append("</time>\n")
					append("        <extensions>\n")
					appendGpxExtension("accuracy", sample.horizontalAccuracyMeters)
					appendGpxExtension("hdop", sample.hdop)
					appendGpxExtension("satellitesUsed", sample.satellitesUsed)
					appendGpxExtension("satellitesFound", sample.satellitesFound)
					appendGpxExtension("bearing", sample.bearingDegrees)
					appendGpxExtension("speed", sample.speedMetersPerSecond)
					appendGpxExtension("soundDb", sample.soundDb)
					appendGpxExtension("vibrationHz", sample.vibrationHz)
					sample.soundSpectrum?.let { appendGpxExtension("soundSpectrum", it.joinToString(",")) }
					append("        </extensions>\n")
					append("      </trkpt>\n")
				}
				append("    </trkseg>\n")
			}
			append("  </trk>\n</gpx>\n")
		}
	}

	private fun StringBuilder.appendGpxExtension(name: String, value: Any?) {
		if (value != null) append("          <osmandflight:").append(name).append(">")
			.append(xmlEscape(value.toString())).append("</osmandflight:").append(name).append(">\n")
	}

	private fun matchSampleIndex(trip: FlightTrip?, timestamp: Long?): Int? {
		if (trip == null || timestamp == null || !trip.hasUsableTimestamps) return null
		val firstTime = trip.samples.first().timestampMillis
		val lastTime = trip.samples.last().timestampMillis
		if (timestamp < firstTime - PHOTO_MATCH_TOLERANCE_MILLIS ||
			timestamp > lastTime + PHOTO_MATCH_TOLERANCE_MILLIS
		) return null
		return trip.samples.minByOrNull { abs(it.timestampMillis - timestamp) }?.index
	}

	private fun displayName(uri: Uri): String? = context.contentResolver.query(
		uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
	)?.use { cursor ->
		val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
		if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
	}

	private fun photoTimestamp(uri: Uri, copiedFile: File): Long? {
		runCatching { MediaMetadataUtils.getPhotoCreationTime(copiedFile) }.getOrNull()?.let { return it }
		return runCatching {
			context.contentResolver.query(
				uri,
				arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED),
				null,
				null,
				null
			)?.use { cursor ->
				if (!cursor.moveToFirst()) return@use null
				val takenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
				val addedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
				val taken = if (takenColumn >= 0 && !cursor.isNull(takenColumn)) cursor.getLong(takenColumn) else 0L
				if (taken > 0L) taken else {
					val addedSeconds = if (addedColumn >= 0 && !cursor.isNull(addedColumn)) cursor.getLong(addedColumn) else 0L
					addedSeconds.takeIf { it > 0L }?.times(1_000L)
				}
			}
		}.getOrNull()
	}

	private fun validatedId(value: String): String {
		if (!value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) throw IOException("Identifiant de voyage invalide")
		return value
	}

	private fun safeFileName(value: String): String = value
		.replace(Regex("[^A-Za-z0-9._-]"), "_")
		.trim('_')
		.take(140)
		.ifBlank { "file" }

	private fun readLimited(input: ZipInputStream, maximumBytes: Long): ByteArray {
		val output = ByteArrayOutputStream()
		copyLimited(input, output, maximumBytes)
		return output.toByteArray()
	}

	private fun copyLimited(input: ZipInputStream, output: java.io.OutputStream, maximumBytes: Long): Long {
		if (maximumBytes <= 0L) throw IOException("Archive trop volumineuse")
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var total = 0L
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			total += read
			if (total > maximumBytes) throw IOException("Archive trop volumineuse")
			output.write(buffer, 0, read)
		}
		return total
	}

	private fun JSONObject.putOptional(key: String, value: Any?) {
		if (value != null) put(key, value)
	}

	private fun JSONObject.optNullableDouble(key: String): Double? =
		if (has(key) && !isNull(key)) optDouble(key) else null

	private fun JSONObject.optNullableLong(key: String): Long? =
		if (has(key) && !isNull(key)) optLong(key) else null

	private fun JSONObject.optNullableInt(key: String): Int? =
		if (has(key) && !isNull(key)) optInt(key) else null

	private fun xmlEscape(value: String): String = value
		.replace("&", "&amp;")
		.replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\"", "&quot;")
		.replace("'", "&apos;")

	companion object {
		const val ARCHIVE_EXTENSION = "osmandflight"
		private const val SCHEMA_VERSION = 1
		private const val JOURNEYS_DIRECTORY = "flight-journeys"
		private const val MEDIA_DIRECTORY = "flight-journey-media"
		private const val FLIGHT_TERRAIN_DIRECTORY = "flight-terrain"
		private const val TERRARIUM_DIRECTORY = "flight-terrain/terrarium"
		private const val FLIGHT_GRAPHICS_ASSET_DIRECTORY = "flightmode"
		private const val FLIGHT_AIRCRAFT_MODEL_DIRECTORY = "flight_aircraft_v1"
		private const val JOURNEY_FILE_EXTENSION = "json"
		private const val JOURNEY_JSON_ENTRY = "journey.json"
		private const val TRACK_GPX_ENTRY = "track.gpx"
		private const val MAXIMUM_JSON_BYTES = 32L * 1024L * 1024L
		private const val MAXIMUM_ARCHIVE_BYTES = 512L * 1024L * 1024L
		private const val PHOTO_MATCH_TOLERANCE_MILLIS = 15L * 60L * 1_000L
	}
}
