package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/** Persists one editable flight log and exports a portable archive. */
class FlightJourneyStore(private val context: Context) {
	private data class DetectedPhotoTimestamp(
		val timestampMillis: Long,
		val source: FlightPhotoTimestampSource
	)

	private val journeysDirectory = File(context.filesDir, JOURNEYS_DIRECTORY).also { it.mkdirs() }
	private val mediaDirectory = File(context.filesDir, MEDIA_DIRECTORY).also { it.mkdirs() }

	fun storageUsage(
		currentJourneyId: String?,
		currentPhotos: List<FlightPhotoAttachment>,
		currentOfflineAssets: FlightOfflineAssets
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
		val currentTerrainBytes = currentOfflineAssets.terrainTiles
			.map(::terrainFile)
			.filter { it.isFile && it.length() > 0L }
			.distinctBy(File::getAbsolutePath)
			.sumOf(File::length)
		val currentSatelliteStandardBytes = currentOfflineAssets.standardSatelliteTiles
			.map(::standardSatelliteFile)
			.filter { it.isFile && it.length() > 0L }
			.distinctBy(File::getAbsolutePath)
			.sumOf(File::length)

		val terrainRoot = File(context.filesDir, FLIGHT_TERRAIN_DIRECTORY)
		val terrainBytes = treeSize(File(context.filesDir, TERRARIUM_DIRECTORY))
		val satelliteSourceBytes = treeSize(File(context.filesDir, FlightSatelliteSource.CACHE_DIRECTORY))
		val satelliteRenderBytes = treeSize(File(context.filesDir, FlightSatelliteSource.RENDER_CACHE_DIRECTORY))
		val nativeMapRenderBytes = treeSize(File(context.filesDir, FlightNativeMapTextureRepository.CACHE_DIRECTORY))
		val categorizedTerrainBytes = terrainBytes + satelliteSourceBytes + satelliteRenderBytes + nativeMapRenderBytes
		val otherTerrainBytes = (treeSize(terrainRoot) - categorizedTerrainBytes).coerceAtLeast(0L)
		val allJournalBytes = treeSize(journeysDirectory)
		val allPhotosBytes = treeSize(mediaDirectory)
		val allFlightFilesBytes = context.filesDir.listFiles().orEmpty()
			.filter { it.name.startsWith("flight-") }
			.sumOf(::treeSize)
		val knownPrivateBytes = allJournalBytes + allPhotosBytes + treeSize(terrainRoot)

		val installedGraphicsBytes = (context.applicationContext as? OsmandApplication)?.let { app ->
			treeSize(File(app.getAppPath(IndexConstants.MODEL_3D_DIR), FlightAircraftModelProvider.MODEL_DIRECTORY_NAME))
		} ?: 0L
		return FlightStorageUsage(
			currentJournalBytes = currentJournalBytes,
			currentPhotosBytes = currentPhotosBytes,
			currentTerrainBytes = currentTerrainBytes,
			currentSatelliteStandardBytes = currentSatelliteStandardBytes,
			allJournalBytes = allJournalBytes,
			allPhotosBytes = allPhotosBytes,
			terrainBytes = terrainBytes,
			satelliteSourceBytes = satelliteSourceBytes,
			satelliteRenderBytes = satelliteRenderBytes,
			nativeMapRenderBytes = nativeMapRenderBytes,
			graphicsBytes = assetTreeSize(FLIGHT_GRAPHICS_ASSET_DIRECTORY) + installedGraphicsBytes,
			otherBytes = otherTerrainBytes + (allFlightFilesBytes - knownPrivateBytes).coerceAtLeast(0L)
		)
	}

	/**
	 * Finds immutable Standard imagery and Terrarium tiles already present for this journey.
	 * The manifest gives a journal logical ownership while files remain physically shared once.
	 */
	fun discoverOfflineAssets(
		plan: FlightPlan,
		trip: FlightTrip,
		previous: FlightOfflineAssets = FlightOfflineAssets()
	): FlightOfflineAssets {
		val planned = FlightTerrainTilePlanner.trackCorridorPlan(trip.samples, plan.terrainCorridorKm)
			?: FlightTerrainTilePlanner.corridorPlan(plan.stops, plan.terrainCorridorKm)
		val plannedTiles = planned?.tiles.orEmpty()
		return FlightOfflineAssets(
			terrainTiles = normalizedTiles(previous.terrainTiles + plannedTiles)
				.filter { terrainFile(it).isUsableFile() },
			standardSatelliteTiles = normalizedTiles(previous.standardSatelliteTiles + plannedTiles)
				.filter { standardSatelliteFile(it).isUsableFile() }
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
		val storedJourney = journey.copy(
			offlineAssets = discoverOfflineAssets(journey.plan, journey.trip, journey.offlineAssets)
		)
		val safeId = validatedId(storedJourney.id)
		val destination = File(journeysDirectory, "$safeId.$JOURNEY_FILE_EXTENSION")
		val temporary = File(journeysDirectory, ".$safeId.tmp")
		temporary.writeText(journeyToJson(storedJourney).toString())
		if (destination.exists() && !destination.delete()) {
			temporary.delete()
			throw IOException("Impossible de remplacer le voyage enregistré")
		}
		if (!temporary.renameTo(destination)) {
			temporary.delete()
			throw IOException("Impossible d’enregistrer le voyage")
		}
		return storedJourney
	}

	fun load(id: String): FlightJourney {
		val file = File(journeysDirectory, "${validatedId(id)}.$JOURNEY_FILE_EXTENSION")
		if (!file.isFile) throw IOException("Journal de vol introuvable")
		val loaded = journeyFromJson(JSONObject(file.readText())) { storageName ->
			File(mediaDirectory, safeFileName(storageName)).absolutePath
		}
		val discoveredAssets = discoverOfflineAssets(loaded.plan, loaded.trip, loaded.offlineAssets)
		return if (discoveredAssets != loaded.offlineAssets) {
			save(loaded.copy(offlineAssets = discoveredAssets))
		} else loaded
	}

	fun isJourneyArchive(uri: Uri): Boolean = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
		val first = input.read()
		val second = input.read()
		first == 'P'.code && second == 'K'.code
	} ?: false

	fun importArchive(uri: Uri): FlightJourney {
		val importedMedia = linkedMapOf<String, String>()
		val importedTerrainTiles = linkedSetOf<TerrainTileId>()
		val importedSatelliteTiles = linkedSetOf<TerrainTileId>()
		var journeyJson: String? = null
		var totalBytes = 0L
		context.contentResolver.openInputStream(uri)?.buffered()?.use { raw ->
			ZipInputStream(raw).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					if (entry.isDirectory) continue
					val terrainTile = archiveTileId(entry.name, OFFLINE_TERRAIN_ENTRY_PREFIX, "png")
					val satelliteTile = archiveTileId(entry.name, OFFLINE_SATELLITE_ENTRY_PREFIX, "jpg")
					when {
						entry.name == JOURNEY_JSON_ENTRY -> {
							val bytes = readLimited(zip, minOf(MAXIMUM_JSON_BYTES, MAXIMUM_ARCHIVE_BYTES - totalBytes))
							totalBytes += bytes.size
							journeyJson = bytes.toString(Charsets.UTF_8)
						}
						entry.name.startsWith("photos/") -> {
							val archiveName = safeFileName(entry.name.substringAfterLast('/'))
							val destination = File(mediaDirectory, "${UUID.randomUUID()}_$archiveName")
							destination.outputStream().buffered().use { output ->
								totalBytes += copyLimited(zip, output, MAXIMUM_ARCHIVE_BYTES - totalBytes)
							}
							importedMedia[archiveName] = destination.absolutePath
						}
						terrainTile != null -> {
							totalBytes += importOfflineTile(
								zip,
								terrainFile(terrainTile),
								MAXIMUM_ARCHIVE_BYTES - totalBytes
							)
							importedTerrainTiles += terrainTile
						}
						satelliteTile != null -> {
							totalBytes += importOfflineTile(
								zip,
								standardSatelliteFile(satelliteTile),
								MAXIMUM_ARCHIVE_BYTES - totalBytes
							)
							importedSatelliteTiles += satelliteTile
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
		val importedAssets = FlightOfflineAssets(
			terrainTiles = normalizedTiles(parsed.offlineAssets.terrainTiles + importedTerrainTiles)
				.filter { terrainFile(it).isUsableFile() },
			standardSatelliteTiles = normalizedTiles(parsed.offlineAssets.standardSatelliteTiles + importedSatelliteTiles)
				.filter { standardSatelliteFile(it).isUsableFile() }
		)
		val now = System.currentTimeMillis()
		return save(
			parsed.copy(
				id = UUID.randomUUID().toString(),
				updatedAtMillis = now,
				offlineAssets = importedAssets
			)
		)
	}

	fun exportArchive(journey: FlightJourney, uri: Uri) {
		val portableJourney = journey.copy(
			offlineAssets = discoverOfflineAssets(journey.plan, journey.trip, journey.offlineAssets)
		)
		val archiveNames = portableJourney.photos.associate { photo ->
			photo.id to "${safeFileName(photo.id)}_${safeFileName(photo.fileName)}"
		}
		context.contentResolver.openOutputStream(uri, "wt")?.buffered()?.use { raw ->
			ZipOutputStream(raw).use { zip ->
				zip.putNextEntry(ZipEntry(JOURNEY_JSON_ENTRY))
				zip.write(journeyToJson(portableJourney, archiveNames).toString(2).toByteArray())
				zip.closeEntry()
				zip.putNextEntry(ZipEntry(TRACK_GPX_ENTRY))
				zip.write(buildGpx(portableJourney).toByteArray())
				zip.closeEntry()
				portableJourney.photos.forEach { photo ->
					val file = File(photo.localPath)
					if (!file.isFile) return@forEach
					zip.putNextEntry(ZipEntry("photos/${archiveNames.getValue(photo.id)}"))
					file.inputStream().buffered().use { it.copyTo(zip) }
					zip.closeEntry()
				}
				portableJourney.offlineAssets.terrainTiles.forEach { tile ->
					writeFileEntry(zip, terrainFile(tile), offlineEntryName(OFFLINE_TERRAIN_ENTRY_PREFIX, tile, "png"))
				}
				portableJourney.offlineAssets.standardSatelliteTiles.forEach { tile ->
					writeFileEntry(zip, standardSatelliteFile(tile), offlineEntryName(OFFLINE_SATELLITE_ENTRY_PREFIX, tile, "jpg"))
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
			val detectedTimestamp = photoTimestamp(uri, destination, originalName)
			val timestamp = detectedTimestamp?.timestampMillis
			FlightPhotoAttachment(
				id = UUID.randomUUID().toString(),
				fileName = originalName,
				localPath = destination.absolutePath,
				timestampMillis = timestamp,
				matchedSamplePosition = matchPhotoPosition(trip, timestamp),
				timestampSource = detectedTimestamp?.source,
				cameraVerticalFieldOfViewDegrees = FlightPhotoPerspective.detectVerticalFieldOfViewDegrees(destination)
			)
		}.getOrNull()
	}

	/**
	 * Re-runs the metadata checks for legacy attachments saved before date recovery was added.
	 * The copied file modification time is a weak fallback (it can be the import time), so it is
	 * accepted only when it overlaps the recorded trip.
	 */
	fun redetectMissingPhotoTimestamp(
		photo: FlightPhotoAttachment,
		trip: FlightTrip?
	): FlightPhotoAttachment {
		if (photo.timestampMillis != null) return photo
		val file = File(photo.localPath)
		if (!file.isFile) return photo
		val detectedTimestamp = storedPhotoTimestamp(file, photo.fileName, trip) ?: return photo
		return photo.copy(
			timestampMillis = detectedTimestamp.timestampMillis,
			timestampSource = detectedTimestamp.source
		)
	}

	fun matchPhotoSample(trip: FlightTrip?, timestamp: Long?): FlightSample? {
		return FlightSampleInterpolator.sampleAt(trip, matchPhotoPosition(trip, timestamp))
	}

	fun matchPhotoPosition(trip: FlightTrip?, timestamp: Long?): Double? =
		FlightSampleInterpolator.positionAtTimestamp(trip, timestamp, PHOTO_MATCH_TOLERANCE_MILLIS)
			?.let(FlightSampleInterpolator::quantizePosition)

	fun detectPhotoVerticalFieldOfViewDegrees(photo: FlightPhotoAttachment): Float? =
		FlightPhotoPerspective.detectVerticalFieldOfViewDegrees(File(photo.localPath))

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
		val exifTimestamp = runCatching { MediaMetadataUtils.getPhotoCreationTime(file) }
			.getOrNull()?.takeIf { it > 0L }
		val timestamp = exifTimestamp ?: fallbackTimestampMillis
		return FlightPhotoAttachment(
			id = UUID.randomUUID().toString(),
			fileName = file.name,
			localPath = file.absolutePath,
			timestampMillis = timestamp,
			matchedSamplePosition = matchPhotoPosition(trip, timestamp),
			timestampSource = if (exifTimestamp != null) {
				FlightPhotoTimestampSource.EXIF
			} else FlightPhotoTimestampSource.LIVE_CAPTURE,
			cameraVerticalFieldOfViewDegrees = FlightPhotoPerspective.detectVerticalFieldOfViewDegrees(file),
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
		put("offlineAssets", offlineAssetsToJson(journey.offlineAssets))
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
					putOptional("timestampSource", photo.timestampSource?.name)
					putOptional("matchedSamplePosition", photo.matchedSamplePosition)
					putOptional("cameraVerticalFieldOfViewDegrees", photo.cameraVerticalFieldOfViewDegrees)
					put("rotationDegrees", normalizePhotoRotation(photo.rotationDegrees))
					put("imageAdjustments", photoImageAdjustmentsToJson(photo.imageAdjustments))
					put("includeMainCamera", photo.includeMainCamera)
					put("includeSelfie", photo.includeSelfie)
					put("includeMap", photo.includeMap)
					put("includeScene3d", photo.includeScene3d)
					putOptional("windowAlignment", photo.windowAlignment?.let(::photoWindowAlignmentToJson))
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
				val storedPosition = json.optNullableDouble("matchedSamplePosition")
					?: json.optNullableDouble("matchedSampleIndex")
				FlightPhotoAttachment(
					id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
					fileName = json.optString("fileName").ifBlank { storageName },
					localPath = photoPath(storageName),
					timestampMillis = json.optNullableLong("timestampMillis"),
					matchedSamplePosition = storedPosition
						?.takeIf { it.isFinite() && trip.samples.isNotEmpty() }
						?.let { position ->
							FlightSampleInterpolator.quantizePosition(
								position.coerceIn(0.0, trip.samples.lastIndex.toDouble())
							)
						},
					timestampSource = json.optString("timestampSource").takeIf(String::isNotBlank)?.let { value ->
						runCatching { FlightPhotoTimestampSource.valueOf(value) }.getOrNull()
					},
					cameraVerticalFieldOfViewDegrees = json.optNullableDouble("cameraVerticalFieldOfViewDegrees")
						?.toFloat()
						?.takeIf { it.isFinite() }
						?.coerceIn(
							FlightWindowPlacement.MIN_VERTICAL_FIELD_OF_VIEW_DEGREES,
							FlightWindowPlacement.MAX_VERTICAL_FIELD_OF_VIEW_DEGREES
						),
					rotationDegrees = normalizePhotoRotation(json.optDouble("rotationDegrees", 0.0).toFloat()),
					imageAdjustments = photoImageAdjustmentsFromJson(json.optJSONObject("imageAdjustments")),
					includeMainCamera = json.optBoolean("includeMainCamera", true),
					includeSelfie = json.optBoolean("includeSelfie", false),
					includeMap = json.optBoolean("includeMap", true),
					includeScene3d = json.optBoolean("includeScene3d", true),
					windowAlignment = photoWindowAlignmentFromJson(json.optJSONObject("windowAlignment"))
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
			photos = photos,
			offlineAssets = offlineAssetsFromJson(root.optJSONObject("offlineAssets"))
		)
	}

	private fun offlineAssetsToJson(assets: FlightOfflineAssets): JSONObject = JSONObject().apply {
		put("terrainTiles", tilesToJson(assets.terrainTiles))
		put("standardSatelliteTiles", tilesToJson(assets.standardSatelliteTiles))
	}

	private fun photoWindowAlignmentToJson(alignment: FlightPhotoWindowAlignment): JSONObject {
		val safe = alignment.clamped()
		return JSONObject().apply {
			put("opacity", safe.opacity)
			put("scale", safe.scale)
			put("offsetXFraction", safe.offsetXFraction)
			put("offsetYFraction", safe.offsetYFraction)
			putOptional("altitudeOverrideMeters", safe.altitudeOverrideMeters)
			put("windowPlacement", JSONObject().apply {
				put("side", safe.windowPlacement.side.name)
				put("forwardOffsetMeters", safe.windowPlacement.forwardOffsetMeters)
				put("verticalOffsetMeters", safe.windowPlacement.verticalOffsetMeters)
				put("zoom", safe.windowPlacement.zoom)
				put("cabinTransparent", safe.windowPlacement.cabinTransparent)
				put("cabinHidden", safe.windowPlacement.cabinHidden)
			})
			put("windowLook", JSONObject().apply {
				put("yawDegrees", safe.windowLook.yawDegrees)
				put("pitchDegrees", safe.windowLook.pitchDegrees)
			})
			putOptional("spatialPose", safe.spatialPose?.let(::photoSpatialPoseToJson))
		}
	}

	private fun photoImageAdjustmentsToJson(adjustments: FlightPhotoImageAdjustments): JSONObject {
		val safe = adjustments.clamped()
		return JSONObject().apply {
			put("brightness", safe.brightness)
			put("contrast", safe.contrast)
			put("temperature", safe.temperature)
			put("tint", safe.tint)
			put("saturation", safe.saturation)
		}
	}

	private fun photoImageAdjustmentsFromJson(json: JSONObject?): FlightPhotoImageAdjustments {
		if (json == null) return FlightPhotoImageAdjustments()
		return FlightPhotoImageAdjustments(
			brightness = json.optDouble("brightness", 0.0).toFloat(),
			contrast = json.optDouble("contrast", 0.0).toFloat(),
			temperature = json.optDouble("temperature", 0.0).toFloat(),
			tint = json.optDouble("tint", 0.0).toFloat(),
			saturation = json.optDouble("saturation", 0.0).toFloat()
		).clamped()
	}

	private fun photoSpatialPoseToJson(pose: FlightPhotoSpatialPose): JSONObject = JSONObject().apply {
		put("samplePosition", pose.samplePosition)
		putOptional("timestampMillis", pose.timestampMillis)
		put("eyeLatitude", pose.eyeLatitude)
		put("eyeLongitude", pose.eyeLongitude)
		putOptional("eyeAltitudeMeters", pose.eyeAltitudeMeters)
		put("aircraftBearingDegrees", pose.aircraftBearingDegrees)
		put("viewAzimuthDegrees", pose.viewAzimuthDegrees)
		put("viewElevationDegrees", pose.viewElevationDegrees)
		put("verticalFieldOfViewDegrees", pose.verticalFieldOfViewDegrees)
	}

	private fun photoSpatialPoseFromJson(json: JSONObject?): FlightPhotoSpatialPose? {
		if (json == null) return null
		val samplePosition = json.optNullableDouble("samplePosition") ?: return null
		val eyeLatitude = json.optNullableDouble("eyeLatitude") ?: return null
		val eyeLongitude = json.optNullableDouble("eyeLongitude") ?: return null
		return FlightPhotoSpatialPose(
			samplePosition = samplePosition,
			timestampMillis = json.optNullableLong("timestampMillis"),
			eyeLatitude = eyeLatitude,
			eyeLongitude = eyeLongitude,
			eyeAltitudeMeters = json.optNullableDouble("eyeAltitudeMeters")?.toFloat(),
			aircraftBearingDegrees = json.optDouble("aircraftBearingDegrees", Double.NaN).toFloat(),
			viewAzimuthDegrees = json.optDouble("viewAzimuthDegrees", Double.NaN).toFloat(),
			viewElevationDegrees = json.optDouble("viewElevationDegrees", Double.NaN).toFloat(),
			verticalFieldOfViewDegrees = json.optDouble("verticalFieldOfViewDegrees", Double.NaN).toFloat()
		).clampedOrNull()
	}

	private fun photoWindowAlignmentFromJson(json: JSONObject?): FlightPhotoWindowAlignment? {
		if (json == null) return null
		val placementJson = json.optJSONObject("windowPlacement") ?: JSONObject()
		val lookJson = json.optJSONObject("windowLook") ?: JSONObject()
		val side = placementJson.optString("side")
			.takeIf(String::isNotBlank)
			?.let { saved -> FlightCabinSide.entries.firstOrNull { it.name == saved } }
			?: FlightCabinSide.LEFT
		return FlightPhotoWindowAlignment(
			opacity = json.optDouble("opacity", 0.55).toFloat(),
			scale = json.optDouble("scale", 1.0).toFloat(),
			offsetXFraction = json.optDouble("offsetXFraction", 0.0).toFloat(),
			offsetYFraction = json.optDouble("offsetYFraction", 0.0).toFloat(),
			windowPlacement = FlightWindowPlacement(
				side = side,
				forwardOffsetMeters = placementJson.optDouble("forwardOffsetMeters", 0.0).toFloat(),
				verticalOffsetMeters = placementJson.optDouble("verticalOffsetMeters", 0.0).toFloat(),
				zoom = placementJson.optDouble("zoom", 1.0).toFloat(),
				cabinTransparent = placementJson.optBoolean("cabinTransparent", false),
				cabinHidden = placementJson.optBoolean("cabinHidden", false)
			),
			windowLook = FlightWindowLook(
				yawDegrees = lookJson.optDouble("yawDegrees", 0.0).toFloat(),
				pitchDegrees = lookJson.optDouble("pitchDegrees", 0.0).toFloat()
			),
			altitudeOverrideMeters = json.optNullableDouble("altitudeOverrideMeters")?.toFloat(),
			spatialPose = photoSpatialPoseFromJson(json.optJSONObject("spatialPose"))
		).clamped()
	}

	private fun tilesToJson(tiles: List<TerrainTileId>): JSONArray = JSONArray().apply {
		normalizedTiles(tiles).forEach { tile ->
			put(JSONObject().put("z", tile.zoom).put("x", tile.x).put("y", tile.y))
		}
	}

	private fun offlineAssetsFromJson(json: JSONObject?): FlightOfflineAssets {
		if (json == null) return FlightOfflineAssets()
		return FlightOfflineAssets(
			terrainTiles = tilesFromJson(json.optJSONArray("terrainTiles")),
			standardSatelliteTiles = tilesFromJson(json.optJSONArray("standardSatelliteTiles"))
		)
	}

	private fun tilesFromJson(array: JSONArray?): List<TerrainTileId> {
		if (array == null) return emptyList()
		return normalizedTiles((0 until array.length()).mapNotNull { index ->
			array.optJSONObject(index)?.let { value ->
				TerrainTileId(value.optInt("z", -1), value.optInt("x", -1), value.optInt("y", -1))
			}
		})
	}

	private fun planToJson(plan: FlightPlan): JSONObject = JSONObject().apply {
		put("terrainCorridorKm", plan.terrainCorridorKm)
		put("detailedSatelliteRadiusKm", plan.detailedSatelliteRadiusKm)
		put("terrainFineZoom", plan.terrainFineZoom)
		put("terrainMiddleZoom", plan.terrainMiddleZoom)
		put("satelliteQuality", plan.satelliteQuality.name)
		put("shadowsEnabled", plan.shadowsEnabled)
		put("shadowIntensity", plan.shadowIntensity.coerceIn(0f, 1f).toDouble())
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
		val terrainFineZoom = json.optInt(
			"terrainFineZoom",
			FlightPlan.DEFAULT_TERRAIN_FINE_ZOOM
		).coerceIn(FlightPlan.MIN_TERRAIN_DETAIL_ZOOM, FlightPlan.MAX_TERRAIN_DETAIL_ZOOM)
		val terrainMiddleZoom = json.optInt(
			"terrainMiddleZoom",
			FlightPlan.DEFAULT_TERRAIN_MIDDLE_ZOOM
		).coerceIn(FlightPlan.MIN_TERRAIN_DETAIL_ZOOM, terrainFineZoom)
		return FlightPlan(
			stops = stops,
			terrainCorridorKm = json.optInt("terrainCorridorKm", 300),
			detailedSatelliteRadiusKm = json.optInt("detailedSatelliteRadiusKm", 300),
			terrainFineZoom = terrainFineZoom,
			terrainMiddleZoom = terrainMiddleZoom,
			satelliteQuality = runCatching {
				FlightSatelliteQuality.valueOf(json.optString("satelliteQuality", FlightSatelliteQuality.HIGH.name))
			}.getOrDefault(FlightSatelliteQuality.HIGH),
			shadowsEnabled = json.optBoolean("shadowsEnabled", true),
			shadowIntensity = json.optDouble("shadowIntensity", 0.85).toFloat().coerceIn(0f, 1f),
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

	private fun displayName(uri: Uri): String? {
		val providerName = runCatching {
			context.contentResolver.query(
				uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
			)?.use { cursor ->
				val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
				if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
			}
		}.getOrNull()?.takeIf(String::isNotBlank)
		if (providerName != null) return providerName
		return uri.lastPathSegment
			?.let(Uri::decode)
			?.substringAfterLast('/')
			?.substringAfterLast(':')
			?.takeIf(String::isNotBlank)
	}

	private fun photoTimestamp(uri: Uri, copiedFile: File, originalName: String): DetectedPhotoTimestamp? {
		runCatching { MediaMetadataUtils.getPhotoCreationTime(copiedFile) }
			.getOrNull()?.takeIf { it > 0L }?.let {
				return DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.EXIF)
			}
		val mediaDates = runCatching {
			context.contentResolver.query(
				uri,
				arrayOf(
					MediaStore.Images.Media.DATE_TAKEN,
					MediaStore.MediaColumns.DATE_MODIFIED,
					MediaStore.MediaColumns.DATE_ADDED
				),
				null,
				null,
				null
			)?.use { cursor ->
				if (!cursor.moveToFirst()) return@use null
				val takenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
				val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
				val addedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
				val taken = if (takenColumn >= 0 && !cursor.isNull(takenColumn)) cursor.getLong(takenColumn) else 0L
				val modifiedSeconds = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else 0L
				val addedSeconds = if (addedColumn >= 0 && !cursor.isNull(addedColumn)) cursor.getLong(addedColumn) else 0L
				Triple(taken, modifiedSeconds * 1_000L, addedSeconds * 1_000L)
			}
		}.getOrNull()
		mediaDates?.first?.takeIf { it > 0L }?.let {
			return DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.MEDIA_CAPTURE)
		}
		FlightPhotoTimestampParser.parse(originalName)?.let {
			return DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.FILE_NAME)
		}
		val documentModified = runCatching {
			context.contentResolver.query(
				uri,
				arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
				null,
				null,
				null
			)?.use { cursor ->
				val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
				if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else 0L
			}
		}.getOrNull()?.takeIf { it > 0L }
		(documentModified ?: mediaDates?.second)?.takeIf { it > 0L }?.let {
			return DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.FILE_MODIFIED)
		}
		return mediaDates?.third?.takeIf { it > 0L }?.let {
			DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.FILE_ADDED)
		}
	}

	private fun storedPhotoTimestamp(
		file: File,
		originalName: String,
		trip: FlightTrip?
	): DetectedPhotoTimestamp? {
		runCatching { MediaMetadataUtils.getPhotoCreationTime(file) }
			.getOrNull()?.takeIf { it > 0L }?.let {
				return DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.EXIF)
			}
		FlightPhotoTimestampParser.parse(originalName)?.let {
			return DetectedPhotoTimestamp(it, FlightPhotoTimestampSource.FILE_NAME)
		}
		val modified = file.lastModified().takeIf { it > 0L } ?: return null
		return if (matchPhotoSample(trip, modified) != null) {
			DetectedPhotoTimestamp(modified, FlightPhotoTimestampSource.FILE_MODIFIED)
		} else null
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

	private fun terrainFile(tile: TerrainTileId): File = tileFile(TERRARIUM_DIRECTORY, tile, "png")

	private fun standardSatelliteFile(tile: TerrainTileId): File =
		tileFile(FlightSatelliteSource.CACHE_DIRECTORY, tile, "jpg")

	private fun tileFile(directory: String, tile: TerrainTileId, extension: String): File = File(
		File(File(File(context.filesDir, directory), tile.zoom.toString()), tile.x.toString()),
		"${tile.y}.$extension"
	)

	private fun File.isUsableFile(): Boolean = isFile && length() > 0L

	private fun normalizedTiles(tiles: Iterable<TerrainTileId>): List<TerrainTileId> = tiles
		.filter(::isValidTileId)
		.distinct()
		.sortedWith(compareBy<TerrainTileId>({ it.zoom }, { it.y }, { it.x }))

	private fun isValidTileId(tile: TerrainTileId): Boolean {
		if (tile.zoom !in MINIMUM_ARCHIVE_TILE_ZOOM..MAXIMUM_ARCHIVE_TILE_ZOOM) return false
		val dimension = 1 shl tile.zoom
		return tile.x in 0 until dimension && tile.y in 0 until dimension
	}

	private fun offlineEntryName(prefix: String, tile: TerrainTileId, extension: String): String =
		"$prefix${tile.zoom}/${tile.x}/${tile.y}.$extension"

	private fun archiveTileId(entryName: String, prefix: String, extension: String): TerrainTileId? {
		if (!entryName.startsWith(prefix)) return null
		val parts = entryName.removePrefix(prefix).split('/')
		if (parts.size != 3 || !parts[2].endsWith(".$extension")) return null
		val tile = TerrainTileId(
			zoom = parts[0].toIntOrNull() ?: return null,
			x = parts[1].toIntOrNull() ?: return null,
			y = parts[2].removeSuffix(".$extension").toIntOrNull() ?: return null
		)
		return tile.takeIf(::isValidTileId)
	}

	private fun writeFileEntry(zip: ZipOutputStream, file: File, entryName: String) {
		if (!file.isUsableFile()) return
		zip.putNextEntry(ZipEntry(entryName))
		file.inputStream().buffered().use { input -> input.copyTo(zip) }
		zip.closeEntry()
	}

	private fun importOfflineTile(input: ZipInputStream, destination: File, remainingArchiveBytes: Long): Long {
		val maximumBytes = minOf(MAXIMUM_OFFLINE_TILE_BYTES, remainingArchiveBytes)
		if (maximumBytes <= 0L) throw IOException("Archive trop volumineuse")
		if (destination.isUsableFile()) return drainLimited(input, maximumBytes)
		val parent = destination.parentFile ?: throw IOException("Dossier de tuile invalide")
		if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le dossier hors ligne")
		val partial = File(parent, ".${destination.name}.${UUID.randomUUID()}.import")
		try {
			val copied = partial.outputStream().buffered().use { output -> copyLimited(input, output, maximumBytes) }
			if (copied <= 0L) throw IOException("Tuile hors ligne vide")
			if (destination.exists() && !destination.delete()) throw IOException("Tuile hors ligne verrouillée")
			if (!partial.renameTo(destination)) throw IOException("Impossible d’importer une tuile hors ligne")
			return copied
		} finally {
			if (partial.exists()) partial.delete()
		}
	}

	private fun drainLimited(input: ZipInputStream, maximumBytes: Long): Long {
		if (maximumBytes <= 0L) throw IOException("Archive trop volumineuse")
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var total = 0L
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			total += read
			if (total > maximumBytes) throw IOException("Archive trop volumineuse")
		}
		return total
	}

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

	private fun normalizePhotoRotation(value: Float): Float {
		if (!value.isFinite()) return 0f
		val normalized = value % 360f
		return if (normalized < 0f) normalized + 360f else normalized
	}

	private fun xmlEscape(value: String): String = value
		.replace("&", "&amp;")
		.replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\"", "&quot;")
		.replace("'", "&apos;")

	companion object {
		const val ARCHIVE_EXTENSION = "osmandflight"
		private const val SCHEMA_VERSION = 9
		private const val JOURNEYS_DIRECTORY = "flight-journeys"
		private const val MEDIA_DIRECTORY = "flight-journey-media"
		private const val FLIGHT_TERRAIN_DIRECTORY = "flight-terrain"
		private const val TERRARIUM_DIRECTORY = "flight-terrain/terrarium"
		private const val FLIGHT_GRAPHICS_ASSET_DIRECTORY = "flightmode"
		private const val JOURNEY_FILE_EXTENSION = "json"
		private const val JOURNEY_JSON_ENTRY = "journey.json"
		private const val TRACK_GPX_ENTRY = "track.gpx"
		private const val OFFLINE_TERRAIN_ENTRY_PREFIX = "offline/terrain/"
		private const val OFFLINE_SATELLITE_ENTRY_PREFIX = "offline/satellite-standard/"
		private const val MAXIMUM_JSON_BYTES = 32L * 1024L * 1024L
		private const val MAXIMUM_ARCHIVE_BYTES = 512L * 1024L * 1024L
		private const val MAXIMUM_OFFLINE_TILE_BYTES = 4L * 1024L * 1024L
		private const val MINIMUM_ARCHIVE_TILE_ZOOM = 0
		private const val MAXIMUM_ARCHIVE_TILE_ZOOM = 22
		private const val PHOTO_MATCH_TOLERANCE_MILLIS = 15L * 60L * 1_000L
	}
}
