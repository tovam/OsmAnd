package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class FlightTerrainRepository(private val app: OsmandApplication) {

	private val terrainDirectory = File(app.filesDir, TERRAIN_DIRECTORY)

	suspend fun loadScene(
		latitude: Double,
		longitude: Double,
		radiusKm: Int,
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainScene {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val plan = withContext(Dispatchers.Default) {
			FlightTerrainTilePlanner.scenePlan(latitude, longitude, radiusKm)
		}
		val tiles = linkedMapOf<TerrainTileId, TerrariumTile>()
		var available = 0
		var downloaded = 0
		var failed = 0
		var bytesDownloaded = 0L
		onStatus(
			FlightTerrainStatus(
				phase = FlightTerrainPhase.DOWNLOADING,
				requestedTiles = plan.tiles.size,
				zoom = plan.zoom
			)
		)

		for (chunk in plan.tiles.chunked(PARALLEL_DOWNLOADS)) {
			val results = coroutineScope {
				chunk.map { tileId ->
					async(Dispatchers.IO) { runCatching { loadTile(tileId) } }
				}.awaitAll()
			}
			results.forEach { result ->
				result.onSuccess { loaded ->
					tiles[loaded.tile.id] = loaded.tile
					available++
					if (loaded.downloaded) downloaded++
					bytesDownloaded += loaded.downloadedBytes
				}.onFailure {
					failed++
				}
			}
			onStatus(
				FlightTerrainStatus(
					phase = FlightTerrainPhase.DOWNLOADING,
					requestedTiles = plan.tiles.size,
					availableTiles = available,
					downloadedTiles = downloaded,
					failedTiles = failed,
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
				bytesDownloaded = bytesDownloaded,
				zoom = plan.zoom
			)
		)
		return withContext(Dispatchers.Default) {
			FlightTerrainMeshBuilder.build(latitude, longitude, radiusKm, plan, tiles)
		}
	}

	suspend fun preloadCorridor(
		plan: FlightPlan,
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainStatus {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val tilePlan = withContext(Dispatchers.Default) {
			FlightTerrainTilePlanner.corridorPlan(plan.stops, plan.terrainCorridorKm)
		} ?: throw IllegalArgumentException("Il faut des coordonnées pour au moins deux villes")
		var available = 0
		var downloaded = 0
		var failed = 0
		var bytesDownloaded = 0L
		for (chunk in tilePlan.tiles.chunked(PARALLEL_DOWNLOADS)) {
			val results = coroutineScope {
				chunk.map { tileId ->
					async(Dispatchers.IO) { runCatching { ensureTileFile(tileId) } }
				}.awaitAll()
			}
			results.forEach { result ->
				result.onSuccess { cached ->
					available++
					if (cached.downloaded) downloaded++
					bytesDownloaded += cached.downloadedBytes
				}.onFailure {
					failed++
				}
			}
			onStatus(
				FlightTerrainStatus(
					phase = FlightTerrainPhase.DOWNLOADING,
					requestedTiles = tilePlan.tiles.size,
					availableTiles = available,
					downloadedTiles = downloaded,
					failedTiles = failed,
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
			bytesDownloaded = bytesDownloaded,
			zoom = tilePlan.zoom,
			message = if (failed == 0) "Relief du trajet disponible hors ligne" else "$failed tuiles indisponibles"
		)
	}

	private fun loadTile(tileId: TerrainTileId): LoadedTerrainTile {
		var cached = ensureTileFile(tileId)
		var tile = decodeTile(tileId, cached.file)
		if (tile == null && !cached.downloaded) {
			cached.file.delete()
			cached = downloadTile(tileId, cached.file)
			tile = decodeTile(tileId, cached.file)
		}
		return LoadedTerrainTile(
			tile = tile ?: throw IOException("Tuile Terrarium illisible: ${tileId.zoom}/${tileId.x}/${tileId.y}"),
			downloaded = cached.downloaded,
			downloadedBytes = cached.downloadedBytes
		)
	}

	private fun ensureTileFile(tileId: TerrainTileId): CachedTerrainFile {
		val file = tileFile(tileId)
		if (file.isFile && file.length() > 0L) {
			return CachedTerrainFile(file, downloaded = false, downloadedBytes = 0L)
		}
		return downloadTile(tileId, file)
	}

	private fun downloadTile(tileId: TerrainTileId, destination: File): CachedTerrainFile {
		val parent = destination.parentFile ?: throw IOException("Dossier de cache invalide")
		if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le cache du relief")
		val partial = File(parent, destination.name + PARTIAL_SUFFIX)
		if (partial.exists() && !partial.delete()) throw IOException("Téléchargement temporaire verrouillé")
		val url = "$TERRARIUM_BASE_URL/${tileId.zoom}/${tileId.x}/${tileId.y}.png"
		val connection = NetworkUtils.getHttpURLConnection(url)
		try {
			connection.requestMethod = "GET"
			connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
			connection.readTimeout = READ_TIMEOUT_MILLIS
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app))
			connection.setRequestProperty("Accept", "image/png")
			connection.connect()
			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				throw IOException("Terrain Tiles HTTP ${connection.responseCode}")
			}
			var total = 0L
			BufferedInputStream(connection.inputStream).use { input ->
				BufferedOutputStream(FileOutputStream(partial)).use { output ->
					val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						total += count
						if (total > MAX_TILE_BYTES) throw IOException("Tuile Terrarium anormalement volumineuse")
						output.write(buffer, 0, count)
					}
				}
			}
			if (total == 0L) throw IOException("Tuile Terrarium vide")
			if (!partial.renameTo(destination)) {
				throw IOException("Impossible de finaliser la tuile Terrarium")
			}
			return CachedTerrainFile(destination, downloaded = true, downloadedBytes = total)
		} finally {
			connection.disconnect()
			if (partial.exists()) partial.delete()
		}
	}

	private fun decodeTile(tileId: TerrainTileId, file: File): TerrariumTile? {
		val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
		val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
		try {
			val pixels = IntArray(bitmap.width * bitmap.height)
			bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
			val elevations = FloatArray(pixels.size)
			for (index in pixels.indices) elevations[index] = TerrariumCodec.decodeArgb(pixels[index])
			return TerrariumTile(tileId, bitmap.width, bitmap.height, elevations)
		} finally {
			bitmap.recycle()
		}
	}

	private fun tileFile(tileId: TerrainTileId): File =
		File(File(File(terrainDirectory, tileId.zoom.toString()), tileId.x.toString()), "${tileId.y}.png")

	private data class CachedTerrainFile(
		val file: File,
		val downloaded: Boolean,
		val downloadedBytes: Long
	)

	private data class LoadedTerrainTile(
		val tile: TerrariumTile,
		val downloaded: Boolean,
		val downloadedBytes: Long
	)

	companion object {
		private const val TERRAIN_DIRECTORY = "flight-terrain/terrarium"
		private const val TERRARIUM_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
		private const val PARTIAL_SUFFIX = ".download"
		private const val PARALLEL_DOWNLOADS = 6
		private const val CONNECT_TIMEOUT_MILLIS = 15_000
		private const val READ_TIMEOUT_MILLIS = 30_000
		private const val DOWNLOAD_BUFFER_SIZE = 16 * 1_024
		private const val MAX_TILE_BYTES = 4L * 1_024L * 1_024L
	}
}
