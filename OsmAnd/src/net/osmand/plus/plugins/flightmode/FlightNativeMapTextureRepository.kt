package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.osmand.data.RotatedTileBox
import net.osmand.plus.OsmandApplication
import net.osmand.plus.Version
import net.osmand.plus.settings.enums.ThemeUsageContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Renders the currently selected OsmAnd vector style into textures that use exactly the same
 * Web-Mercator tile boundaries as the Terrarium meshes. The result can therefore be draped over
 * real elevation without changing the shared/native map view or its global 3D preference.
 */
class FlightNativeMapTextureRepository(private val app: OsmandApplication) {

	suspend fun renderTextures(
		tileIds: Collection<TerrainTileId>,
		onProgress: suspend (completed: Int, available: Int, failed: Int) -> Unit
	): FlightNativeMapTextureResult {
		val requested = tileIds.distinct()
		if (requested.isEmpty()) return FlightNativeMapTextureResult(emptyMap(), 0)
		val styleKey = styleCacheKey()
		val result = linkedMapOf<TerrainTileId, String>()
		val missing = mutableListOf<TerrainTileId>()
		withContext(Dispatchers.IO) {
			requested.forEach { tileId ->
				val cached = textureFile(styleKey, tileId)
				if (isDecodableImage(cached)) result[tileId] = cached.absolutePath else missing += tileId
			}
		}
		var completed = result.size
		var failed = 0
		onProgress(completed, result.size, failed)

		val blocks = missing.groupBy(::blockFor).entries.sortedWith(
			compareBy<Map.Entry<NativeMapBlock, List<TerrainTileId>>>({ it.key.zoom }, { it.key.y }, { it.key.x })
		)
		for ((block, blockTiles) in blocks) {
			kotlin.coroutines.coroutineContext.ensureActive()
			val rendered = withContext(Dispatchers.IO) {
				renderBlock(styleKey, block, blockTiles)
			}
			result.putAll(rendered.mapValues { it.value.absolutePath })
			completed += blockTiles.size
			failed += blockTiles.size - rendered.size
			onProgress(completed, result.size, failed)
		}
		return FlightNativeMapTextureResult(result, failed)
	}

	private fun renderBlock(
		styleKey: String,
		block: NativeMapBlock,
		tiles: List<TerrainTileId>
	): Map<TerrainTileId, File> {
		val centerTileX = block.x + MAP_TILES_PER_BLOCK / 2.0
		val centerTileY = block.y + MAP_TILES_PER_BLOCK / 2.0
		val tileBox = RotatedTileBox.RotatedTileBoxBuilder()
			.setLocation(
				FlightTerrainTilePlanner.tileYToLatitude(centerTileY, block.zoom),
				FlightTerrainTilePlanner.tileXToLongitude(centerTileX, block.zoom)
			)
			.setZoom(block.zoom)
			.setMapDensity(1.0)
			.density(1f)
			.setPixelDimensions(MAP_RENDER_SIZE_PIXELS, MAP_RENDER_SIZE_PIXELS)
			.build()
		val resources = app.resourceManager
		val renderer = resources.renderer
		return synchronized(renderer) {
			renderer.loadMap(tileBox, resources.mapTileDownloader)
			val bitmap = renderer.bitmap
			val bitmapLocation = renderer.bitmapLocation
			if (renderer.wasInterrupted() || bitmap == null || bitmap.isRecycled ||
				bitmap.width != MAP_RENDER_SIZE_PIXELS || bitmap.height != MAP_RENDER_SIZE_PIXELS ||
				!matchesRequest(bitmapLocation, tileBox)
			) {
				return@synchronized emptyMap()
			}
			buildMap {
				for (tileId in tiles) {
					val left = MAP_BLOCK_PADDING_PIXELS + (tileId.x - block.x) * MAP_TILE_SIZE_PIXELS
					val top = MAP_BLOCK_PADDING_PIXELS + (tileId.y - block.y) * MAP_TILE_SIZE_PIXELS
					if (left < 0 || top < 0 || left + MAP_TILE_SIZE_PIXELS > bitmap.width ||
						top + MAP_TILE_SIZE_PIXELS > bitmap.height
					) continue
					val destination = textureFile(styleKey, tileId)
					try {
						val tileBitmap = Bitmap.createBitmap(
							bitmap,
							left,
							top,
							MAP_TILE_SIZE_PIXELS,
							MAP_TILE_SIZE_PIXELS
						)
						try {
							writeTexture(tileBitmap, destination)
							put(tileId, destination)
						} finally {
							tileBitmap.recycle()
						}
					} catch (_: RuntimeException) {
						// A missing vector map or a transient renderer interruption must not hide relief.
					} catch (_: IOException) {
						// The caller reports this tile as unavailable while retaining the other layers.
					}
				}
			}
		}
	}

	private fun matchesRequest(actual: RotatedTileBox?, requested: RotatedTileBox): Boolean =
		actual != null &&
			actual.zoom == requested.zoom &&
			actual.pixWidth == requested.pixWidth &&
			actual.pixHeight == requested.pixHeight &&
			abs(actual.latitude - requested.latitude) < LOCATION_EPSILON &&
			abs(normalizeLongitude(actual.longitude - requested.longitude)) < LOCATION_EPSILON

	private fun writeTexture(bitmap: Bitmap, destination: File) {
		val parent = destination.parentFile ?: throw IOException("Dossier de texture OsmAnd invalide")
		if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le cache carte OsmAnd")
		val partial = File(parent, destination.name + PARTIAL_SUFFIX)
		if (partial.exists() && !partial.delete()) throw IOException("Texture OsmAnd temporaire verrouillée")
		try {
			FileOutputStream(partial).buffered().use { output ->
				if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
					throw IOException("Impossible d’enregistrer la texture OsmAnd")
				}
			}
			if (destination.exists() && !destination.delete()) {
				throw IOException("Texture OsmAnd existante verrouillée")
			}
			if (!partial.renameTo(destination)) throw IOException("Impossible de finaliser la texture OsmAnd")
		} finally {
			if (partial.exists()) partial.delete()
		}
	}

	private fun isDecodableImage(file: File): Boolean {
		if (!file.isFile || file.length() <= 0L) return false
		val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.absolutePath, options)
		return options.outWidth == MAP_TILE_SIZE_PIXELS && options.outHeight == MAP_TILE_SIZE_PIXELS
	}

	private fun textureFile(styleKey: String, tileId: TerrainTileId): File =
		File(
			File(File(File(app.filesDir, CACHE_DIRECTORY), styleKey), tileId.zoom.toString()),
			"${tileId.x}_${tileId.y}.png"
		)

	private fun blockFor(tileId: TerrainTileId): NativeMapBlock = NativeMapBlock(
		zoom = tileId.zoom,
		x = tileId.x / MAP_TILES_PER_BLOCK * MAP_TILES_PER_BLOCK,
		y = tileId.y / MAP_TILES_PER_BLOCK * MAP_TILES_PER_BLOCK
	)

	private fun styleCacheKey(): String {
		val renderer = app.settings.RENDERER.get().orEmpty().ifBlank { "default" }
		val nightMode = app.daynightHelper.isNightMode(ThemeUsageContext.MAP)
		val signature = "$CACHE_SCHEMA|${Version.getAppVersion(app)}|$renderer|$nightMode"
		return Integer.toHexString(signature.hashCode())
	}

	private fun normalizeLongitude(longitude: Double): Double {
		var result = longitude
		while (result > 180.0) result -= 360.0
		while (result < -180.0) result += 360.0
		return result
	}

	private data class NativeMapBlock(val zoom: Int, val x: Int, val y: Int)

	companion object {
		const val CACHE_DIRECTORY = "flight-terrain/osmand-map-render"
		private const val CACHE_SCHEMA = 1
		private const val MAP_TILE_SIZE_PIXELS = 256
		private const val MAP_TILES_PER_BLOCK = 4
		private const val MAP_BLOCK_PADDING_PIXELS = 64
		private const val MAP_RENDER_SIZE_PIXELS =
			MAP_TILE_SIZE_PIXELS * MAP_TILES_PER_BLOCK + MAP_BLOCK_PADDING_PIXELS * 2
		private const val LOCATION_EPSILON = 1e-7
		private const val PARTIAL_SUFFIX = ".rendering"
	}
}

data class FlightNativeMapTextureResult(
	val texturePaths: Map<TerrainTileId, String>,
	val failedTiles: Int
)
