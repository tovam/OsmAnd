package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import net.osmand.plus.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class FlightSatelliteCacheInfo(
	val loading: Boolean = true,
	val tileCount: Int = 0,
	val satelliteTileCount: Int = 0,
	val terrainTileCount: Int = 0,
	val zoom: Int? = null
)

/** Displays the durable Standard satellite and Terrarium tiles stored with flight journals. */
class FlightSatelliteCacheView @JvmOverloads constructor(
	context: Context,
	attributes: AttributeSet? = null
) : View(context, attributes) {

	private data class CachedTile(
		val zoom: Int,
		val x: Int,
		val y: Int,
		val satelliteFile: File?,
		val terrainFile: File?,
		var displayX: Int = x,
		var fallbackSourceKey: String? = null
	) {
		val sourceKey: String
			get() = listOfNotNull(satelliteFile, terrainFile).joinToString("|") { file ->
				"${file.absolutePath}:${file.length()}:${file.lastModified()}"
			}
	}

	private val worker: ExecutorService = Executors.newSingleThreadExecutor()
	private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_KIB) {
		override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
	}
	private val queuedKeys = mutableSetOf<String>()
	private val failedKeys = mutableSetOf<String>()
	private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(30, 42, 50)
		style = Paint.Style.FILL
	}
	private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(95, 208, 221, 230)
		style = Paint.Style.STROKE
		strokeWidth = 1f
	}
	private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(197, 208, 215)
		textAlign = Paint.Align.CENTER
		textSize = resources.displayMetrics.scaledDensity * 14f
	}

	private var tiles: List<CachedTile> = emptyList()
	private var minDisplayX = 0
	private var maxDisplayX = 0
	private var minY = 0
	private var maxY = 0
	private var contentScale = 1f
	private var fittedScale = 1f
	private var offsetX = 0f
	private var offsetY = 0f
	private var loading = true
	private var refreshKey: String? = null
	private var scanGeneration = 0
	private var scanRunning = false
	private var detached = false

	var onCacheInfoChanged: ((FlightSatelliteCacheInfo) -> Unit)? = null

	private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
		override fun onDown(event: MotionEvent): Boolean = true

		override fun onScroll(
			downEvent: MotionEvent?,
			moveEvent: MotionEvent,
			distanceX: Float,
			distanceY: Float
		): Boolean {
			offsetX -= distanceX
			offsetY -= distanceY
			invalidate()
			return true
		}

		override fun onDoubleTap(event: MotionEvent): Boolean {
			fitContent()
			invalidate()
			return true
		}
	})

	private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
		override fun onScale(detector: ScaleGestureDetector): Boolean {
			val previousScale = contentScale
			val minimumScale = (fittedScale * 0.5f).coerceAtLeast(MINIMUM_SCALE)
			val maximumScale = max(fittedScale * 32f, MAXIMUM_SOURCE_PIXEL_SCALE)
			contentScale = (contentScale * detector.scaleFactor).coerceIn(minimumScale, maximumScale)
			val ratio = contentScale / previousScale
			offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
			offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
			invalidate()
			return true
		}
	})

	init {
		setBackgroundColor(Color.rgb(7, 12, 16))
		isClickable = true
	}

	fun setRefreshKey(key: String) {
		if (refreshKey == key) return
		refreshKey = key
		reload()
	}

	private fun reload() {
		if (detached) return
		scanGeneration++
		// Keep the previous immutable snapshot visible during rescans. Replacing it
		// with a loading frame on every downloaded tile caused the visible flashing.
		loading = tiles.isEmpty()
		if (loading) onCacheInfoChanged?.invoke(FlightSatelliteCacheInfo())
		invalidate()
		if (!scanRunning) launchCacheScan()
	}

	private fun launchCacheScan() {
		if (detached) return
		scanRunning = true
		val generation = scanGeneration
		worker.execute {
			val snapshot = scanCache()
			post {
				scanRunning = false
				if (detached) return@post
				if (generation != scanGeneration) {
					launchCacheScan()
					return@post
				}
				val firstSnapshot = tiles.isEmpty()
				val previousMinX = minDisplayX
				val previousMinY = minY
				loading = false
				if (snapshot.isNotEmpty() || firstSnapshot) {
					val previousById = tiles.associateBy { TerrainTileId(it.zoom, it.x, it.y) }
					snapshot.forEach { current ->
						val previous = previousById[TerrainTileId(current.zoom, current.x, current.y)]
						if (previous != null) {
							current.fallbackSourceKey = if (previous.sourceKey == current.sourceKey) {
								previous.fallbackSourceKey
							} else if (hasCachedBitmap(previous.sourceKey)) {
								previous.sourceKey
							} else {
								previous.fallbackSourceKey
							}
						}
					}
					tiles = snapshot
				}
				// Keep decoded tiles that are still useful. Evicting the whole LRU on
				// every scene refresh was the source of the grey/image flashing.
				updateBounds()
				if (firstSnapshot) {
					fitContent()
				} else {
					offsetX += (minDisplayX - previousMinX) * TILE_SIZE * contentScale
					offsetY += (minY - previousMinY) * TILE_SIZE * contentScale
				}
				onCacheInfoChanged?.invoke(
					FlightSatelliteCacheInfo(
						loading = false,
						tileCount = tiles.size,
						satelliteTileCount = tiles.count { it.satelliteFile != null },
						terrainTileCount = tiles.count { it.terrainFile != null },
						zoom = tiles.firstOrNull()?.zoom
					)
				)
				invalidate()
			}
		}
	}

	private fun scanCache(): List<CachedTile> {
		val satellite = scanTileFiles(
			File(context.applicationContext.filesDir, FlightSatelliteSource.CACHE_DIRECTORY),
			"jpg"
		)
		val terrain = scanTileFiles(
			File(context.applicationContext.filesDir, FlightTerrainRepository.TERRAIN_DIRECTORY),
			"png"
		)
		val preferredKeys = terrain.keys.takeIf { it.isNotEmpty() } ?: satellite.keys
		val selectedZoom = preferredKeys.groupingBy { it.zoom }.eachCount().maxWithOrNull(
			compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }
		)?.key ?: return emptyList()
		val keys = satellite.keys + terrain.keys
		val combined = keys.asSequence().filter { it.zoom == selectedZoom }.distinct().map { id ->
			CachedTile(
				zoom = id.zoom,
				x = id.x,
				y = id.y,
				satelliteFile = satellite[id],
				terrainFile = terrain[id]
			)
		}.toList()
		return normalizeWrappedTileX(combined, selectedZoom)
	}

	private fun scanTileFiles(root: File, extension: String): Map<TerrainTileId, File> = buildMap {
		root.listFiles().orEmpty().filter(File::isDirectory).forEach zoomLoop@ { zoomDirectory ->
			val zoom = zoomDirectory.name.toIntOrNull() ?: return@zoomLoop
			zoomDirectory.listFiles().orEmpty().filter(File::isDirectory).forEach xLoop@ { xDirectory ->
				val x = xDirectory.name.toIntOrNull() ?: return@xLoop
				xDirectory.listFiles().orEmpty().forEach { file ->
					val y = file.nameWithoutExtension.toIntOrNull()
					if (y != null && file.isFile && file.length() > 0L && file.extension.equals(extension, true)) {
						put(TerrainTileId(zoom, x, y), file)
					}
				}
			}
		}
	}

	private fun normalizeWrappedTileX(source: List<CachedTile>, zoom: Int): List<CachedTile> {
		val uniqueX = source.map { it.x }.distinct().sorted()
		if (uniqueX.size < 2 || zoom !in 0..29) return source
		val worldWidth = 1 shl zoom
		var largestGap = -1
		var cut = uniqueX.first()
		uniqueX.forEachIndexed { index, x ->
			val next = if (index == uniqueX.lastIndex) uniqueX.first() + worldWidth else uniqueX[index + 1]
			val gap = next - x
			if (gap > largestGap) {
				largestGap = gap
				cut = next % worldWidth
			}
		}
		return source.map { tile ->
			tile.apply { displayX = if (x < cut) x + worldWidth else x }
		}
	}

	private fun updateBounds() {
		minDisplayX = tiles.minOfOrNull { it.displayX } ?: 0
		maxDisplayX = tiles.maxOfOrNull { it.displayX } ?: 0
		minY = tiles.minOfOrNull { it.y } ?: 0
		maxY = tiles.maxOfOrNull { it.y } ?: 0
	}

	private fun fitContent() {
		if (width <= 0 || height <= 0 || tiles.isEmpty()) return
		val contentWidth = (maxDisplayX - minDisplayX + 1) * TILE_SIZE.toFloat()
		val contentHeight = (maxY - minY + 1) * TILE_SIZE.toFloat()
		fittedScale = min(width * FIT_FRACTION / contentWidth, height * FIT_FRACTION / contentHeight)
			.coerceAtLeast(MINIMUM_SCALE)
		contentScale = fittedScale
		offsetX = (width - contentWidth * contentScale) / 2f
		offsetY = (height - contentHeight * contentScale) / 2f
	}

	override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
		super.onSizeChanged(width, height, oldWidth, oldHeight)
		if (oldWidth == 0 || oldHeight == 0) fitContent()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (loading) {
			drawMessage(canvas, context.getString(R.string.flight_mode_satellite_loading))
			return
		}
		if (tiles.isEmpty()) {
			drawMessage(canvas, context.getString(R.string.flight_mode_satellite_empty))
			return
		}
		val scaledTileSize = TILE_SIZE * contentScale
		val desiredSampleSize = sampleSizeFor(scaledTileSize)
		tiles.forEach { tile ->
			val left = offsetX + (tile.displayX - minDisplayX) * scaledTileSize
			val top = offsetY + (tile.y - minY) * scaledTileSize
			val destination = RectF(left, top, left + scaledTileSize, top + scaledTileSize)
			if (!RectF.intersects(destination, RectF(0f, 0f, width.toFloat(), height.toFloat()))) return@forEach
			val key = tile.sourceKey
			val bitmap = cachedBitmap(key, desiredSampleSize)
				?: tile.fallbackSourceKey?.let { cachedBitmap(it, desiredSampleSize) }
			if (bitmap != null) {
				canvas.drawBitmap(bitmap, null, destination, tilePaint)
			} else {
				canvas.drawRect(destination, placeholderPaint)
			}
			queueBitmap(tile, desiredSampleSize)
			canvas.drawRect(destination, gridPaint)
		}
	}

	private fun drawMessage(canvas: Canvas, message: String) {
		canvas.drawText(message, width / 2f, height / 2f - (messagePaint.ascent() + messagePaint.descent()) / 2f, messagePaint)
	}

	private fun sampleSizeFor(renderedTileSize: Float): Int = when {
		renderedTileSize >= 320f -> 1
		renderedTileSize >= 160f -> 2
		renderedTileSize >= 80f -> 4
		renderedTileSize >= 40f -> 8
		else -> 16
	}

	private fun cacheKey(sourceKey: String, sampleSize: Int): String = "$sourceKey#$sampleSize"

	private fun cachedBitmap(sourceKey: String, desiredSampleSize: Int): Bitmap? {
		bitmapCache.get(cacheKey(sourceKey, desiredSampleSize))?.let { return it }
		var closest: Bitmap? = null
		var closestDistance = Int.MAX_VALUE
		for (sampleSize in SAMPLE_SIZES) {
			val candidate = bitmapCache.get(cacheKey(sourceKey, sampleSize)) ?: continue
			val distance = kotlin.math.abs(sampleSize - desiredSampleSize)
			if (distance < closestDistance) {
				closest = candidate
				closestDistance = distance
			}
		}
		return closest
	}

	private fun hasCachedBitmap(sourceKey: String): Boolean =
		SAMPLE_SIZES.any { sampleSize -> bitmapCache.get(cacheKey(sourceKey, sampleSize)) != null }

	private fun queueBitmap(tile: CachedTile, sampleSize: Int) {
		val key = cacheKey(tile.sourceKey, sampleSize)
		if (bitmapCache.get(key) != null || key in failedKeys || key in queuedKeys ||
			queuedKeys.size >= MAXIMUM_QUEUED_BITMAPS
		) return
		queuedKeys += key
		worker.execute {
			val bitmap = decodeCombinedTile(tile, sampleSize)
			post {
				queuedKeys -= key
				if (detached) return@post
				if (bitmap != null) bitmapCache.put(key, bitmap) else failedKeys += key
				invalidate()
			}
		}
	}

	private fun decodeCombinedTile(tile: CachedTile, sampleSize: Int): Bitmap? {
		val options = BitmapFactory.Options().apply {
			inSampleSize = sampleSize
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		val satellite = tile.satelliteFile?.let { BitmapFactory.decodeFile(it.absolutePath, options) }
		val terrain = tile.terrainFile?.let {
			BitmapFactory.decodeFile(
				it.absolutePath,
				BitmapFactory.Options().apply {
					inSampleSize = sampleSize
					inPreferredConfig = Bitmap.Config.ARGB_8888
				}
			)
		}
		if (terrain == null) return satellite
		val width = satellite?.width ?: terrain.width
		val height = satellite?.height ?: terrain.height
		if (width <= 0 || height <= 0) {
			satellite?.recycle()
			terrain.recycle()
			return null
		}
		val satellitePixels = satellite?.let { bitmap ->
			IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
		}
		val terrainPixels = IntArray(terrain.width * terrain.height).also { pixels ->
			terrain.getPixels(pixels, 0, terrain.width, 0, 0, terrain.width, terrain.height)
		}
		fun elevationAt(x: Int, y: Int): Float {
			val safeX = x.coerceIn(0, width - 1) * terrain.width / width
			val safeY = y.coerceIn(0, height - 1) * terrain.height / height
			return TerrariumCodec.decodeArgb(terrainPixels[safeY * terrain.width + safeX])
		}
		val composedPixels = IntArray(width * height)
		for (y in 0 until height) {
			for (x in 0 until width) {
				val elevation = elevationAt(x, y)
				val base = satellitePixels?.get(y * width + x) ?: terrainColor(elevation)
				val gradient = (elevationAt(x - 1, y) - elevationAt(x + 1, y)) * 0.0015f +
					(elevationAt(x, y - 1) - elevationAt(x, y + 1)) * 0.0011f
				val brightness = (0.91f + gradient).coerceIn(0.56f, 1.22f)
				composedPixels[y * width + x] = Color.rgb(
					(Color.red(base) * brightness).roundToInt().coerceIn(0, 255),
					(Color.green(base) * brightness).roundToInt().coerceIn(0, 255),
					(Color.blue(base) * brightness).roundToInt().coerceIn(0, 255)
				)
			}
		}
		return Bitmap.createBitmap(composedPixels, width, height, Bitmap.Config.RGB_565).also {
			satellite?.recycle()
			terrain.recycle()
		}
	}

	private fun terrainColor(elevationMeters: Float): Int = when {
		elevationMeters < 0f -> Color.rgb(30, 88, 120)
		elevationMeters < 400f -> Color.rgb(78, 112, 65)
		elevationMeters < 1_500f -> Color.rgb(126, 112, 76)
		elevationMeters < 2_700f -> Color.rgb(132, 130, 123)
		else -> Color.rgb(218, 222, 224)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		scaleDetector.onTouchEvent(event)
		gestureDetector.onTouchEvent(event)
		if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
		return true
	}

	override fun performClick(): Boolean {
		super.performClick()
		return true
	}

	override fun onDetachedFromWindow() {
		detached = true
		scanGeneration++
		worker.shutdownNow()
		bitmapCache.evictAll()
		super.onDetachedFromWindow()
	}

	companion object {
		private const val TILE_SIZE = 256f
		private const val FIT_FRACTION = 0.94f
		private const val MINIMUM_SCALE = 0.002f
		private const val MAXIMUM_SOURCE_PIXEL_SCALE = 8f
		private const val BITMAP_CACHE_KIB = 64 * 1024
		private const val MAXIMUM_QUEUED_BITMAPS = 64
		private val SAMPLE_SIZES = listOf(1, 2, 4, 8, 16)
	}
}
