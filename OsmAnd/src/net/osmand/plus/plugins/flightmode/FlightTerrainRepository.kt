package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.osm.io.NetworkUtils
import net.osmand.plus.OsmandApplication
import net.osmand.plus.Version
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FlightTerrainRepository(private val app: OsmandApplication) {

	private val terrainDirectory = File(app.filesDir, TERRAIN_DIRECTORY)
	private val satelliteDirectory = File(app.filesDir, FlightSatelliteSource.CACHE_DIRECTORY)
	private val satelliteRenderDirectory = File(app.filesDir, FlightSatelliteSource.RENDER_CACHE_DIRECTORY)
	private val nativeMapTextureRepository = FlightNativeMapTextureRepository(app)
	private val assetLocks = ConcurrentHashMap<String, Any>()
	private val decodedTerrainCache = object : LinkedHashMap<TerrainTileId, TerrariumTile>(
		MAXIMUM_DECODED_TERRAIN_TILES + 1,
		0.75f,
		true
	) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TerrainTileId, TerrariumTile>?): Boolean =
			size > MAXIMUM_DECODED_TERRAIN_TILES
	}

	suspend fun loadScene(
		latitude: Double,
		longitude: Double,
		radiusKm: Int,
		satelliteQuality: FlightSatelliteQuality,
		includeNativeMap: Boolean,
		onScene: suspend (FlightTerrainScene) -> Unit = {},
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainScene {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val plan = withContext(Dispatchers.Default) {
			FlightTerrainTilePlanner.scenePlan(latitude, longitude, radiusKm)
		}
		val renderQualityByTile = withContext(Dispatchers.Default) {
			selectRenderQualities(plan, latitude, longitude, satelliteQuality)
		}
		val tiles = linkedMapOf<TerrainTileId, TerrariumTile>()
		val satelliteTexturePaths = linkedMapOf<TerrainTileId, String>()
		var available = 0
		var downloaded = 0
		var failed = 0
		var satelliteAvailable = 0
		var satelliteFailed = 0
		var detailedAvailable = 0
		var detailedFailed = 0
		val satelliteDownloadsEnabled = AtomicBoolean(true)
		var bytesDownloaded = 0L
		var completedRequests = 0
		var visualUpdates = 0
		var lastScenePublishNanos = 0L
		var lastPublishedVisualUpdates = 0
		var latestScene: FlightTerrainScene? = null
		val orderedTiles = plan.tiles.sortedBy { tile ->
			FlightTerrainTilePlanner.distanceKm(
				latitude,
				longitude,
				FlightTerrainTilePlanner.tileYToLatitude(tile.y + 0.5, tile.zoom),
				FlightTerrainTilePlanner.tileXToLongitude(tile.x + 0.5, tile.zoom)
			)
		}
		onStatus(
			FlightTerrainStatus(
				phase = FlightTerrainPhase.DOWNLOADING,
				requestedTiles = plan.tiles.size,
				zoom = plan.zoom
			)
		)

		suspend fun publishProgressiveScene(force: Boolean = false) {
			if (tiles.isEmpty()) return
			val now = System.nanoTime()
			val enoughNewTiles = visualUpdates - lastPublishedVisualUpdates >= SCENE_PUBLISH_TILE_BATCH
			val enoughTime = now - lastScenePublishNanos >= SCENE_PUBLISH_INTERVAL_NANOS
			if (!force && latestScene != null && !enoughNewTiles && !enoughTime) return
			latestScene = withContext(Dispatchers.Default) {
				FlightTerrainMeshBuilder.build(
					latitude,
					longitude,
					radiusKm,
					plan,
					tiles.toMap(),
					satelliteQuality,
					satelliteTexturePaths.toMap(),
					emptyMap(),
					0,
					includeNativeMap
				)
			}
			lastScenePublishNanos = now
			lastPublishedVisualUpdates = visualUpdates
			onScene(latestScene ?: return)
		}

		coroutineScope {
			val work = Channel<TerrainTileId>(PARALLEL_DOWNLOADS * 2)
			val results = Channel<Result<LoadedTerrainTile>>(PARALLEL_DOWNLOADS * 2)
			val producer = launch {
				orderedTiles.forEach { work.send(it) }
				work.close()
			}
			val workers = List(PARALLEL_DOWNLOADS) {
				launch(Dispatchers.IO) {
					for (tileId in work) {
						results.send(
							runCatching {
								loadBaseTile(tileId, satelliteDownloadsEnabled.get())
							}
						)
					}
				}
			}
			repeat(orderedTiles.size) {
				val result = results.receive()
				completedRequests++
				result.onSuccess { loaded ->
					tiles[loaded.tile.id] = loaded.tile
					loaded.satelliteFile?.let { file ->
						satelliteTexturePaths[loaded.tile.id] = file.absolutePath
						satelliteAvailable++
					}
					if (loaded.satelliteFailed) satelliteFailed++
					available++
					visualUpdates++
					if (loaded.downloaded) downloaded++
					bytesDownloaded += loaded.downloadedBytes
				}.onFailure {
					failed++
				}
				if (satelliteAvailable == 0 && satelliteFailed >= SATELLITE_FAILURE_CIRCUIT_BREAKER) {
					satelliteDownloadsEnabled.set(false)
				}
				onStatus(
					FlightTerrainStatus(
						phase = FlightTerrainPhase.DOWNLOADING,
						requestedTiles = plan.tiles.size,
						availableTiles = available,
						downloadedTiles = downloaded,
						failedTiles = failed,
						satelliteTiles = satelliteAvailable,
						satelliteFailedTiles = satelliteFailed,
						bytesDownloaded = bytesDownloaded,
						zoom = plan.zoom
					)
				)
				publishProgressiveScene(force = completedRequests == orderedTiles.size)
			}
			producer.join()
			workers.forEach { it.join() }
			results.close()
		}

		// The complete scene first becomes usable with light Standard textures. Higher
		// detail is then swapped in from the aircraft outward, in a smaller worker pool,
		// so Ultra/Ultra+ composition never blocks interaction with Hublot.
		val detailRequests = orderedTiles.mapNotNull { tileId ->
			val quality = renderQualityByTile[tileId] ?: FlightSatelliteQuality.STANDARD
			if (quality != FlightSatelliteQuality.STANDARD && tileId in tiles) tileId to quality else null
		}
		if (detailRequests.isNotEmpty()) {
			coroutineScope {
				val work = Channel<Pair<TerrainTileId, FlightSatelliteQuality>>(DETAIL_PARALLEL_DOWNLOADS * 2)
				val results = Channel<Result<LoadedSatelliteTexture>>(DETAIL_PARALLEL_DOWNLOADS * 2)
				val producer = launch {
					detailRequests.forEach { work.send(it) }
					work.close()
				}
				val workers = List(DETAIL_PARALLEL_DOWNLOADS) {
					launch(Dispatchers.IO) {
						for ((tileId, quality) in work) {
							results.send(
								runCatching {
									loadDetailedSatelliteTexture(tileId, quality, satelliteDownloadsEnabled.get())
								}
							)
						}
					}
				}
				repeat(detailRequests.size) { index ->
					results.receive().onSuccess { detailed ->
						satelliteTexturePaths[detailed.tileId] = detailed.file.absolutePath
						bytesDownloaded += detailed.downloadedBytes
						detailedAvailable++
						visualUpdates++
					}.onFailure {
						detailedFailed++
					}
					onStatus(
						FlightTerrainStatus(
							phase = FlightTerrainPhase.DOWNLOADING,
							requestedTiles = plan.tiles.size,
							availableTiles = available,
							downloadedTiles = downloaded,
							failedTiles = failed,
							satelliteTiles = satelliteAvailable,
							satelliteFailedTiles = satelliteFailed,
							bytesDownloaded = bytesDownloaded,
							zoom = plan.zoom,
							message = "Détail $detailedAvailable/${detailRequests.size}" +
								if (detailedFailed > 0) " · $detailedFailed en Standard" else ""
						)
					)
					publishProgressiveScene(force = index == detailRequests.lastIndex)
				}
				producer.join()
				workers.forEach { it.join() }
				results.close()
			}
		}
		if (tiles.isEmpty()) {
			throw IOException("Aucune tuile de relief disponible")
		}
		val buildingStatus = FlightTerrainStatus(
			phase = FlightTerrainPhase.BUILDING,
			requestedTiles = plan.tiles.size,
			availableTiles = available,
			downloadedTiles = downloaded,
			failedTiles = failed,
			satelliteTiles = satelliteAvailable,
			satelliteFailedTiles = satelliteFailed,
			bytesDownloaded = bytesDownloaded,
			zoom = plan.zoom,
			message = when {
				includeNativeMap -> "Rendu de la carte OsmAnd sur le relief…"
				detailedFailed > 0 -> "Maillage GPU · $detailedFailed texture(s) détaillée(s) gardée(s) en Standard"
				else -> "Construction du maillage GPU…"
			}
		)
		onStatus(buildingStatus)
		val nativeMapResult = if (includeNativeMap) {
			nativeMapTextureRepository.renderTextures(tiles.keys) { completed, mapAvailable, mapFailed ->
				onStatus(
					buildingStatus.copy(
						nativeMapTiles = mapAvailable,
						nativeMapFailedTiles = mapFailed,
						message = "Carte OsmAnd sur relief : $completed/${tiles.size} tuiles"
					)
				)
			}
		} else {
			FlightNativeMapTextureResult(emptyMap(), 0)
		}
		val finalScene = withContext(Dispatchers.Default) {
			FlightTerrainMeshBuilder.build(
				latitude,
				longitude,
				radiusKm,
				plan,
				tiles,
				satelliteQuality,
				satelliteTexturePaths,
				nativeMapResult.texturePaths,
				nativeMapResult.failedTiles,
				includeNativeMap
			)
		}
		onScene(finalScene)
		return finalScene
	}

	suspend fun preloadCorridor(
		plan: FlightPlan,
		trip: FlightTrip?,
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainStatus {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val tilePlan = withContext(Dispatchers.Default) {
			trip?.samples?.takeIf { it.size >= 2 }?.let {
				FlightTerrainTilePlanner.trackCorridorPlan(it, plan.terrainCorridorKm)
			} ?: FlightTerrainTilePlanner.corridorPlan(plan.stops, plan.terrainCorridorKm)
		} ?: throw IllegalArgumentException("Il faut une trace ou les coordonnées d’au moins deux villes")
		var available = 0
		var downloaded = 0
		var failed = 0
		var satelliteAvailable = 0
		var satelliteFailed = 0
		var satelliteDownloadsEnabled = true
		var bytesDownloaded = 0L
		for (chunk in tilePlan.tiles.chunked(PARALLEL_DOWNLOADS)) {
			val allowSatelliteDownload = satelliteDownloadsEnabled
			val results = coroutineScope {
				chunk.map { tileId ->
					async(Dispatchers.IO) {
						runCatching { ensureOfflineTileFiles(tileId, allowSatelliteDownload) }
					}
				}.awaitAll()
			}
			results.forEach { result ->
				result.onSuccess { cached ->
					available++
					if (cached.downloaded) downloaded++
					if (cached.standardSatellite != null) satelliteAvailable++
					else if (cached.satelliteAttempted) satelliteFailed++
					bytesDownloaded += cached.downloadedBytes
				}.onFailure {
					failed++
				}
			}
			if (satelliteAvailable == 0 && satelliteFailed >= SATELLITE_FAILURE_CIRCUIT_BREAKER) {
				satelliteDownloadsEnabled = false
			}
			onStatus(
				FlightTerrainStatus(
					phase = FlightTerrainPhase.DOWNLOADING,
					requestedTiles = tilePlan.tiles.size,
					availableTiles = available,
					downloadedTiles = downloaded,
					failedTiles = failed,
					satelliteTiles = satelliteAvailable,
					satelliteFailedTiles = satelliteFailed,
					bytesDownloaded = bytesDownloaded,
					zoom = tilePlan.zoom
				)
			)
		}
		if (available == 0) throw IOException("Aucune tuile de relief n’a pu être préchargée")
		return FlightTerrainStatus(
			phase = FlightTerrainPhase.READY,
			requestedTiles = tilePlan.tiles.size,
			availableTiles = available,
			downloadedTiles = downloaded,
			failedTiles = failed,
			satelliteTiles = satelliteAvailable,
			satelliteFailedTiles = satelliteFailed,
			bytesDownloaded = bytesDownloaded,
			zoom = tilePlan.zoom,
			message = when {
				failed > 0 -> "$failed tuiles de relief indisponibles"
				satelliteFailed > 0 -> "Relief prêt · satellite Standard partiel ($satelliteFailed manquantes)"
				else -> "Relief + satellite Standard rattachés au trajet"
			}
		)
	}

	private fun loadBaseTile(
		tileId: TerrainTileId,
		allowSatelliteDownload: Boolean
	): LoadedTerrainTile {
		var cached = ensureTerrainFile(tileId)
		var tile = decodeTile(tileId, cached.file)
		if (tile == null && !cached.downloaded) {
			cached.file.delete()
			cached = downloadTerrainTile(tileId, cached.file)
			tile = decodeTile(tileId, cached.file)
		}
		val satellite = runCatching {
			ensureSatelliteSourceFile(tileId, allowSatelliteDownload)
		}.getOrNull()
		return LoadedTerrainTile(
			tile = tile ?: throw IOException("Tuile Terrarium illisible: ${tileId.zoom}/${tileId.x}/${tileId.y}"),
			satelliteFile = satellite?.file,
			satelliteFailed = satellite == null && allowSatelliteDownload,
			downloaded = cached.downloaded || satellite?.downloaded == true,
			downloadedBytes = cached.downloadedBytes + (satellite?.downloadedBytes ?: 0L)
		)
	}

	private fun loadDetailedSatelliteTexture(
		tileId: TerrainTileId,
		quality: FlightSatelliteQuality,
		allowSatelliteDownload: Boolean
	): LoadedSatelliteTexture {
		require(quality != FlightSatelliteQuality.STANDARD)
		val cached = ensureSatelliteTexture(tileId, quality, allowSatelliteDownload)
		return LoadedSatelliteTexture(tileId, cached.file, cached.downloadedBytes)
	}

	private fun ensureOfflineTileFiles(
		tileId: TerrainTileId,
		allowSatelliteDownload: Boolean
	): CachedTileFiles {
		val terrain = ensureTerrainFile(tileId)
		// Offline journals always own the immutable parent Standard tile. Detailed composites are
		// transient render derivatives around the aircraft and would make a 300 km corridor huge.
		val standardSatellite = runCatching {
			ensureSatelliteSourceFile(tileId, allowSatelliteDownload)
		}.getOrNull()
		return CachedTileFiles(
			terrain = terrain,
			standardSatellite = standardSatellite,
			satelliteAttempted = allowSatelliteDownload
		)
	}

	/**
	 * Keep detailed textures local to the aircraft. A naive Ultra+ scene would upload up to 144
	 * 2048 px textures at once, which is enough to stall the complete Android graphics stack.
	 * Geometry and coverage stay complete; only texture resolution decreases with distance.
	 */
	private fun selectRenderQualities(
		plan: TerrainTilePlan,
		latitude: Double,
		longitude: Double,
		requested: FlightSatelliteQuality
	): Map<TerrainTileId, FlightSatelliteQuality> {
		if (requested == FlightSatelliteQuality.STANDARD) {
			return plan.tiles.associateWith { FlightSatelliteQuality.STANDARD }
		}
		val ranked = plan.tiles.sortedBy { tile ->
			FlightTerrainTilePlanner.distanceKm(
				latitude,
				longitude,
				FlightTerrainTilePlanner.tileYToLatitude(tile.y + 0.5, tile.zoom),
				FlightTerrainTilePlanner.tileXToLongitude(tile.x + 0.5, tile.zoom)
			)
		}
		return ranked.mapIndexed { index, tile ->
			val quality = when (requested) {
				FlightSatelliteQuality.STANDARD -> FlightSatelliteQuality.STANDARD
				FlightSatelliteQuality.HIGH -> if (index < MAXIMUM_HIGH_TILES) {
					FlightSatelliteQuality.HIGH
				} else FlightSatelliteQuality.STANDARD
				FlightSatelliteQuality.ULTRA -> when {
					index < MAXIMUM_ULTRA_TILES -> FlightSatelliteQuality.ULTRA
					index < MAXIMUM_ULTRA_TILES + MAXIMUM_HIGH_TILES_AFTER_ULTRA -> FlightSatelliteQuality.HIGH
					else -> FlightSatelliteQuality.STANDARD
				}
				FlightSatelliteQuality.ULTRA_PLUS -> when {
					index < MAXIMUM_ULTRA_PLUS_TILES -> FlightSatelliteQuality.ULTRA_PLUS
					index < MAXIMUM_ULTRA_PLUS_TILES + MAXIMUM_ULTRA_TILES_AFTER_ULTRA_PLUS -> FlightSatelliteQuality.ULTRA
					index < MAXIMUM_ULTRA_PLUS_TILES + MAXIMUM_ULTRA_TILES_AFTER_ULTRA_PLUS +
						MAXIMUM_HIGH_TILES_AFTER_ULTRA_PLUS -> FlightSatelliteQuality.HIGH
					else -> FlightSatelliteQuality.STANDARD
				}
			}
			tile to quality
		}.toMap()
	}

	private fun ensureTerrainFile(tileId: TerrainTileId): CachedAsset {
		val file = tileFile(tileId)
		return synchronized(assetLock(file)) {
			if (file.isFile && file.length() > 0L) {
				CachedAsset(file, downloaded = false, downloadedBytes = 0L)
			} else {
				downloadTerrainTile(tileId, file)
			}
		}
	}

	private fun ensureSatelliteTexture(
		tileId: TerrainTileId,
		quality: FlightSatelliteQuality,
		allowDownload: Boolean
	): CachedAsset {
		if (quality == FlightSatelliteQuality.STANDARD) {
			return ensureSatelliteSourceFile(tileId, allowDownload)
		}
		val destination = satelliteRenderFile(tileId, quality)
		return synchronized(assetLock(destination)) {
			if (destination.isFile && destination.length() > 0L && isDecodableImage(destination)) {
				return@synchronized CachedAsset(destination, downloaded = false, downloadedBytes = 0L)
			}
			if (destination.exists() && !destination.delete()) {
				throw IOException("Texture satellite calculée verrouillée")
			}

			val factor = 1 shl quality.zoomDelta
			val childTiles = buildList {
				for (childY in 0 until factor) {
					for (childX in 0 until factor) {
						add(
							TerrainTileId(
								zoom = tileId.zoom + quality.zoomDelta,
								x = tileId.x * factor + childX,
								y = tileId.y * factor + childY
							)
						)
					}
				}
			}
			val cachedChildren = childTiles.map { ensureSatelliteSourceFile(it, allowDownload) }
			val downloadedBytes = cachedChildren.sumOf { it.downloadedBytes }
			val bitmaps = mutableListOf<Bitmap>()
			try {
				cachedChildren.forEach { cached ->
					bitmaps += BitmapFactory.decodeFile(
						cached.file.absolutePath,
						BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
					)
						?: throw IOException("Texture satellite source illisible")
				}
				val tileWidth = bitmaps.first().width
				val tileHeight = bitmaps.first().height
				if (bitmaps.any { it.width != tileWidth || it.height != tileHeight }) {
					throw IOException("Textures satellite de tailles incompatibles")
				}
				val composed = Bitmap.createBitmap(tileWidth * factor, tileHeight * factor, Bitmap.Config.RGB_565)
				try {
					val canvas = Canvas(composed)
					val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
					bitmaps.forEachIndexed { index, bitmap ->
						val childX = index % factor
						val childY = index / factor
						canvas.drawBitmap(
							bitmap,
							null,
							Rect(
								childX * tileWidth,
								childY * tileHeight,
								(childX + 1) * tileWidth,
								(childY + 1) * tileHeight
							),
							paint
						)
					}
					val parent = destination.parentFile ?: throw IOException("Dossier satellite invalide")
					if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le cache satellite")
					val partial = File(parent, destination.name + PARTIAL_SUFFIX)
					if (partial.exists() && !partial.delete()) throw IOException("Texture satellite temporaire verrouillée")
					try {
						FileOutputStream(partial).buffered().use { output ->
							if (!composed.compress(Bitmap.CompressFormat.JPEG, COMPOSITE_JPEG_QUALITY, output)) {
								throw IOException("Impossible de composer la texture satellite")
							}
						}
						if (!partial.renameTo(destination)) throw IOException("Impossible de finaliser la texture satellite")
					} finally {
						if (partial.exists()) partial.delete()
					}
				} finally {
					composed.recycle()
				}
			} finally {
				bitmaps.forEach(Bitmap::recycle)
			}
			CachedAsset(
				file = destination,
				downloaded = cachedChildren.any { it.downloaded },
				downloadedBytes = downloadedBytes
			)
		}
	}

	private fun ensureSatelliteSourceFile(tileId: TerrainTileId, allowDownload: Boolean): CachedAsset {
		val file = satelliteFile(tileId)
		return synchronized(assetLock(file)) {
			if (file.isFile && file.length() > 0L && isDecodableImage(file)) {
				return@synchronized CachedAsset(file, downloaded = false, downloadedBytes = 0L)
			}
			if (!allowDownload) throw IOException("Téléchargement satellite temporairement désactivé")
			if (file.exists() && !file.delete()) throw IOException("Texture satellite en cache verrouillée")
			val downloaded = downloadAsset(
				url = FlightSatelliteSource.tileUrl(tileId),
				destination = file,
				accept = "image/jpeg",
				sourceName = "EOX Sentinel-2",
				connectTimeoutMillis = SATELLITE_CONNECT_TIMEOUT_MILLIS,
				readTimeoutMillis = SATELLITE_READ_TIMEOUT_MILLIS
			)
			if (!isDecodableImage(file)) {
				file.delete()
				throw IOException("Texture satellite illisible: ${tileId.zoom}/${tileId.x}/${tileId.y}")
			}
			downloaded
		}
	}

	private fun assetLock(file: File): Any {
		val key = file.absolutePath
		val candidate = Any()
		return assetLocks.putIfAbsent(key, candidate) ?: candidate
	}

	private fun downloadTerrainTile(tileId: TerrainTileId, destination: File): CachedAsset =
		downloadAsset(
			url = "$TERRARIUM_BASE_URL/${tileId.zoom}/${tileId.x}/${tileId.y}.png",
			destination = destination,
			accept = "image/png",
			sourceName = "Terrain Tiles"
		)

	private fun downloadAsset(
		url: String,
		destination: File,
		accept: String,
		sourceName: String,
		connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
		readTimeoutMillis: Int = READ_TIMEOUT_MILLIS
	): CachedAsset {
		val parent = destination.parentFile ?: throw IOException("Dossier de cache invalide")
		if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le cache du relief")
		val partial = File(parent, destination.name + PARTIAL_SUFFIX)
		if (partial.exists() && !partial.delete()) throw IOException("Téléchargement temporaire verrouillé")
		val connection = NetworkUtils.getHttpURLConnection(url)
		try {
			connection.requestMethod = "GET"
			connection.connectTimeout = connectTimeoutMillis
			connection.readTimeout = readTimeoutMillis
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app))
			connection.setRequestProperty("Accept", accept)
			connection.connect()
			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				throw IOException("$sourceName HTTP ${connection.responseCode}")
			}
			var total = 0L
			BufferedInputStream(connection.inputStream).use { input ->
				BufferedOutputStream(FileOutputStream(partial)).use { output ->
					val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						total += count
						if (total > MAX_TILE_BYTES) throw IOException("Réponse $sourceName anormalement volumineuse")
						output.write(buffer, 0, count)
					}
				}
			}
			if (total == 0L) throw IOException("Réponse $sourceName vide")
			if (!partial.renameTo(destination)) {
				throw IOException("Impossible de finaliser la tuile $sourceName")
			}
			return CachedAsset(destination, downloaded = true, downloadedBytes = total)
		} finally {
			connection.disconnect()
			if (partial.exists()) partial.delete()
		}
	}

	private fun isDecodableImage(file: File): Boolean {
		val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.absolutePath, options)
		return options.outWidth > 0 && options.outHeight > 0
	}

	private fun decodeTile(tileId: TerrainTileId, file: File): TerrariumTile? {
		synchronized(decodedTerrainCache) { decodedTerrainCache[tileId] }?.let { return it }
		val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
		val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
		try {
			val pixels = IntArray(bitmap.width * bitmap.height)
			bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
			val elevations = FloatArray(pixels.size)
			for (index in pixels.indices) elevations[index] = TerrariumCodec.decodeArgb(pixels[index])
			return TerrariumTile(tileId, bitmap.width, bitmap.height, elevations).also { tile ->
				synchronized(decodedTerrainCache) { decodedTerrainCache[tileId] = tile }
			}
		} finally {
			bitmap.recycle()
		}
	}

	private fun tileFile(tileId: TerrainTileId): File =
		File(File(File(terrainDirectory, tileId.zoom.toString()), tileId.x.toString()), "${tileId.y}.png")

	private fun satelliteFile(tileId: TerrainTileId): File =
		File(File(File(satelliteDirectory, tileId.zoom.toString()), tileId.x.toString()), "${tileId.y}.jpg")

	private fun satelliteRenderFile(tileId: TerrainTileId, quality: FlightSatelliteQuality): File =
		File(
			File(
				File(File(satelliteRenderDirectory, quality.name.lowercase()), tileId.zoom.toString()),
				tileId.x.toString()
			),
			"${tileId.y}.jpg"
		)

	private data class CachedAsset(
		val file: File,
		val downloaded: Boolean,
		val downloadedBytes: Long
	)

	private data class CachedTileFiles(
		val terrain: CachedAsset,
		val standardSatellite: CachedAsset?,
		val satelliteAttempted: Boolean
	) {
		val downloaded: Boolean
			get() = terrain.downloaded || standardSatellite?.downloaded == true

		val downloadedBytes: Long
			get() = terrain.downloadedBytes + (standardSatellite?.downloadedBytes ?: 0L)
	}

	private data class LoadedTerrainTile(
		val tile: TerrariumTile,
		val satelliteFile: File?,
		val satelliteFailed: Boolean,
		val downloaded: Boolean,
		val downloadedBytes: Long
	)

	private data class LoadedSatelliteTexture(
		val tileId: TerrainTileId,
		val file: File,
		val downloadedBytes: Long
	)

	companion object {
		const val TERRAIN_DIRECTORY = "flight-terrain/terrarium"
		private const val TERRARIUM_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
		private const val PARTIAL_SUFFIX = ".download"
		private const val PARALLEL_DOWNLOADS = 6
		private const val DETAIL_PARALLEL_DOWNLOADS = 2
		private const val SCENE_PUBLISH_INTERVAL_NANOS = 250_000_000L
		private const val SCENE_PUBLISH_TILE_BATCH = 4
		private const val SATELLITE_FAILURE_CIRCUIT_BREAKER = PARALLEL_DOWNLOADS
		private const val CONNECT_TIMEOUT_MILLIS = 15_000
		private const val READ_TIMEOUT_MILLIS = 30_000
		private const val SATELLITE_CONNECT_TIMEOUT_MILLIS = 8_000
		private const val SATELLITE_READ_TIMEOUT_MILLIS = 15_000
		private const val DOWNLOAD_BUFFER_SIZE = 16 * 1_024
		private const val MAX_TILE_BYTES = 4L * 1_024L * 1_024L
		private const val COMPOSITE_JPEG_QUALITY = 92
		private const val MAXIMUM_DECODED_TERRAIN_TILES = 128
		private const val MAXIMUM_HIGH_TILES = 24
		private const val MAXIMUM_ULTRA_TILES = 6
		private const val MAXIMUM_HIGH_TILES_AFTER_ULTRA = 18
		private const val MAXIMUM_ULTRA_PLUS_TILES = 1
		private const val MAXIMUM_ULTRA_TILES_AFTER_ULTRA_PLUS = 4
		private const val MAXIMUM_HIGH_TILES_AFTER_ULTRA_PLUS = 12
	}
}
