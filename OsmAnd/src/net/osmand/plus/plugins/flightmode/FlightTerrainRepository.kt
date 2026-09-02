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

class FlightTerrainRepository(private val app: OsmandApplication) {

	private val terrainDirectory = File(app.filesDir, TERRAIN_DIRECTORY)
	private val satelliteDirectory = File(app.filesDir, FlightSatelliteSource.CACHE_DIRECTORY)
	private val satelliteRenderDirectory = File(app.filesDir, FlightSatelliteSource.RENDER_CACHE_DIRECTORY)
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
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainScene {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val plan = withContext(Dispatchers.Default) {
			FlightTerrainTilePlanner.scenePlan(latitude, longitude, radiusKm)
		}
		val tiles = linkedMapOf<TerrainTileId, TerrariumTile>()
		val satelliteTexturePaths = linkedMapOf<TerrainTileId, String>()
		var available = 0
		var downloaded = 0
		var failed = 0
		var satelliteAvailable = 0
		var satelliteFailed = 0
		var satelliteDownloadsEnabled = true
		var bytesDownloaded = 0L
		onStatus(
			FlightTerrainStatus(
				phase = FlightTerrainPhase.DOWNLOADING,
				requestedTiles = plan.tiles.size,
				zoom = plan.zoom
			)
		)

		for (chunk in plan.tiles.chunked(PARALLEL_DOWNLOADS)) {
			val allowSatelliteDownload = satelliteDownloadsEnabled
			val results = coroutineScope {
				chunk.map { tileId ->
					async(Dispatchers.IO) {
						runCatching { loadTile(tileId, satelliteQuality, allowSatelliteDownload) }
					}
				}.awaitAll()
			}
			results.forEach { result ->
				result.onSuccess { loaded ->
					tiles[loaded.tile.id] = loaded.tile
					loaded.satelliteFile?.let { file ->
						satelliteTexturePaths[loaded.tile.id] = file.absolutePath
						satelliteAvailable++
					}
					if (loaded.satelliteFailed) satelliteFailed++
					available++
					if (loaded.downloaded) downloaded++
					bytesDownloaded += loaded.downloadedBytes
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
		}
		if (tiles.isEmpty()) {
			throw IOException("Aucune tuile de relief disponible")
		}
		onStatus(
			FlightTerrainStatus(
				phase = FlightTerrainPhase.BUILDING,
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
		return withContext(Dispatchers.Default) {
			FlightTerrainMeshBuilder.build(
				latitude,
				longitude,
				radiusKm,
				plan,
				tiles,
				satelliteQuality,
				satelliteTexturePaths
			)
		}
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
						runCatching { ensureTileFiles(tileId, plan.satelliteQuality, allowSatelliteDownload) }
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

	private fun loadTile(
		tileId: TerrainTileId,
		satelliteQuality: FlightSatelliteQuality,
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
			ensureSatelliteTexture(tileId, satelliteQuality, allowSatelliteDownload)
		}.getOrNull()
		return LoadedTerrainTile(
			tile = tile ?: throw IOException("Tuile Terrarium illisible: ${tileId.zoom}/${tileId.x}/${tileId.y}"),
			satelliteFile = satellite?.file,
			satelliteFailed = satellite == null && allowSatelliteDownload,
			downloaded = cached.downloaded || satellite?.downloaded == true,
			downloadedBytes = cached.downloadedBytes + (satellite?.downloadedBytes ?: 0L)
		)
	}

	private fun ensureTileFiles(
		tileId: TerrainTileId,
		satelliteQuality: FlightSatelliteQuality,
		allowSatelliteDownload: Boolean
	): CachedTileFiles {
		val terrain = ensureTerrainFile(tileId)
		// The parent Standard tile is the portable/offline source attached to a Flight Journal.
		// Higher qualities are optional local render derivatives and must never replace it.
		val standardSatellite = runCatching {
			ensureSatelliteSourceFile(tileId, allowSatelliteDownload)
		}.getOrNull()
		val selectedSatellite = if (satelliteQuality == FlightSatelliteQuality.STANDARD) {
			standardSatellite
		} else {
			runCatching {
				ensureSatelliteTexture(tileId, satelliteQuality, allowSatelliteDownload)
			}.getOrNull()
		}
		return CachedTileFiles(
			terrain = terrain,
			standardSatellite = standardSatellite,
			selectedSatellite = selectedSatellite,
			satelliteAttempted = allowSatelliteDownload
		)
	}

	private fun ensureTerrainFile(tileId: TerrainTileId): CachedAsset {
		val file = tileFile(tileId)
		if (file.isFile && file.length() > 0L) {
			return CachedAsset(file, downloaded = false, downloadedBytes = 0L)
		}
		return downloadTerrainTile(tileId, file)
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
		if (destination.isFile && destination.length() > 0L && isDecodableImage(destination)) {
			return CachedAsset(destination, downloaded = false, downloadedBytes = 0L)
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
				bitmaps += BitmapFactory.decodeFile(cached.file.absolutePath)
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
		return CachedAsset(
			file = destination,
			downloaded = cachedChildren.any { it.downloaded },
			downloadedBytes = downloadedBytes
		)
	}

	private fun ensureSatelliteSourceFile(tileId: TerrainTileId, allowDownload: Boolean): CachedAsset {
		val file = satelliteFile(tileId)
		if (file.isFile && file.length() > 0L && isDecodableImage(file)) {
			return CachedAsset(file, downloaded = false, downloadedBytes = 0L)
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
		return downloaded
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
		val selectedSatellite: CachedAsset?,
		val satelliteAttempted: Boolean
	) {
		val downloaded: Boolean
			get() = terrain.downloaded || standardSatellite?.downloaded == true || selectedSatellite?.downloaded == true

		val downloadedBytes: Long
			get() {
				val satelliteAssets = listOfNotNull(standardSatellite, selectedSatellite)
					.distinctBy { it.file.absolutePath }
				return terrain.downloadedBytes + satelliteAssets.sumOf { it.downloadedBytes }
			}
	}

	private data class LoadedTerrainTile(
		val tile: TerrariumTile,
		val satelliteFile: File?,
		val satelliteFailed: Boolean,
		val downloaded: Boolean,
		val downloadedBytes: Long
	)

	companion object {
		private const val TERRAIN_DIRECTORY = "flight-terrain/terrarium"
		private const val TERRARIUM_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
		private const val PARTIAL_SUFFIX = ".download"
		private const val PARALLEL_DOWNLOADS = 6
		private const val SATELLITE_FAILURE_CIRCUIT_BREAKER = PARALLEL_DOWNLOADS
		private const val CONNECT_TIMEOUT_MILLIS = 15_000
		private const val READ_TIMEOUT_MILLIS = 30_000
		private const val SATELLITE_CONNECT_TIMEOUT_MILLIS = 8_000
		private const val SATELLITE_READ_TIMEOUT_MILLIS = 15_000
		private const val DOWNLOAD_BUFFER_SIZE = 16 * 1_024
		private const val MAX_TILE_BYTES = 4L * 1_024L * 1_024L
		private const val COMPOSITE_JPEG_QUALITY = 92
		private const val MAXIMUM_DECODED_TERRAIN_TILES = 128
	}
}
