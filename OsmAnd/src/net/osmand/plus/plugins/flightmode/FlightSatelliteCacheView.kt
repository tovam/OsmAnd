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
import java.util.concurrent.ConcurrentHashMap
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
		// Capture metadata on the inventory worker, never stat files while drawing.
		val sourceKey: String = listOfNotNull(satelliteFile, terrainFile).joinToString("|") { file ->
				"${file.absolutePath}:${file.length()}:${file.lastModified()}"
			}
	}

	private var worker: ExecutorService = Executors.newSingleThreadExecutor()
	private var scanner: ExecutorService = Executors.newSingleThreadExecutor()
	private var refreshQueued = false
	private val refreshTask = Runnable { refreshQueued = false; if (!scanRunning) launchCacheScan() }
	private var quote: FlightOfflineQuote? = null
	private var showImages = false
	private var selectedZoom: Int? = null
	private val sourceChanges = ConcurrentHashMap<FlightOfflineTileKey, Long>()
	private var stopSourceChanges: (() -> Unit)? = null
	private var inventoryRequired = true
	private var workVisible = false
	private var workGeneration = 0L
	private val workLifecycle = FlightViewVisibility(this) { visible ->
		workVisible = visible
		workGeneration++
		if (visible) {
			// Downloads can finish while this view is stopped. Reconcile once on
			// resume without discarding the visible snapshot or changing its camera.
			inventoryRequired = true
			stopSourceChanges = FlightOfflineTileChanges.observe { key, bytes ->
				sourceChanges[key] = bytes
				post { if (workVisible) reload() }
			}
			if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
			if (scanner.isShutdown) scanner = Executors.newSingleThreadExecutor()
			reload()
		} else {
			stopSourceChanges?.invoke()
			stopSourceChanges = null
			worker.shutdownNow()
			scanner.shutdownNow()
			removeCallbacks(refreshTask)
			refreshQueued = false
			queuedKeys.clear()
			scanRunning = false
		}
	}
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
	private var tileRows: Map<Int, List<CachedTile>> = emptyMap()
	private var coverageOverview: Bitmap? = null
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
			if (scaleDetector.isInProgress || moveEvent.pointerCount > 1) return true
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
		// File completion events drive incremental refreshes. Progress counters
		// must not rescan the entire manifest on every downloaded file.
		if (tiles.isEmpty()) reload()
	}

	fun setQuote(value: FlightOfflineQuote?) {
		if (quote === value) return
		quote = value
		tiles = emptyList()
		reload()
	}

	fun setShowImages(value: Boolean) {
		if (showImages == value) return
		showImages = value
		invalidate()
	}

	fun setZoom(value: Int?) {
		if (selectedZoom == value) return
		selectedZoom = value
		tiles = emptyList()
		reload()
	}

	private fun reload() {
		if (detached || !workVisible) return
		scanGeneration++
		// Keep the previous immutable snapshot visible during rescans. Replacing it
		// with a loading frame on every downloaded tile caused the visible flashing.
		loading = tiles.isEmpty()
		if (loading) onCacheInfoChanged?.invoke(FlightSatelliteCacheInfo())
		invalidate()
		if (!scanRunning && !refreshQueued) {
			refreshQueued = true
			postDelayed(refreshTask, if (tiles.isEmpty()) 0L else 1000L)
		}
	}

	private fun launchCacheScan() {
		if (detached || !workVisible) return
		scanRunning = true
		val lifecycleGeneration = workGeneration
		val generation = scanGeneration
		val requested = quote
		val requestedZoom = selectedZoom
		val previousTiles = tiles
		val reconcile = inventoryRequired
		inventoryRequired = false
		val changes = sourceChanges.toMap()
		changes.forEach { (key, bytes) -> sourceChanges.remove(key, bytes) }
		scanner.execute {
			val snapshot = if (previousTiles.isNotEmpty() && !reconcile) {
				previousTiles.map { tile ->
					val id = TerrainTileId(tile.zoom, tile.x, tile.y)
					val satellite = changes[FlightOfflineTileKey(id, true)]
					val terrain = changes[FlightOfflineTileKey(id, false)]
					if (satellite == null && terrain == null) tile else CachedTile(tile.zoom, tile.x, tile.y,
						if (satellite == null) tile.satelliteFile else if (satellite > 0) File(context.applicationContext.filesDir,
							"${FlightSatelliteSource.CACHE_DIRECTORY}/${id.zoom}/${id.x}/${id.y}.jpg") else null,
						if (terrain == null) tile.terrainFile else if (terrain > 0) File(context.applicationContext.filesDir,
							"${FlightTerrainRepository.TERRAIN_DIRECTORY}/${id.zoom}/${id.x}/${id.y}.png") else null,
						displayX = tile.displayX)
				}
			} else scanCache(requested, requestedZoom)
			val previousById = previousTiles.associateBy { TerrainTileId(it.zoom, it.x, it.y) }
			snapshot.forEach { current ->
				previousById[TerrainTileId(current.zoom, current.x, current.y)]?.let { previous ->
					current.fallbackSourceKey = if (previous.sourceKey == current.sourceKey) previous.fallbackSourceKey
						else if (hasCachedBitmap(previous.sourceKey)) previous.sourceKey else previous.fallbackSourceKey
				}
			}
			val bounds = intArrayOf(snapshot.minOfOrNull { it.displayX } ?: 0, snapshot.maxOfOrNull { it.displayX } ?: 0,
				snapshot.minOfOrNull { it.y } ?: 0, snapshot.maxOfOrNull { it.y } ?: 0)
			val rows = snapshot.groupBy { it.y }.mapValues { it.value.sortedBy { tile -> tile.displayX } }
			val overview = buildOverview(snapshot, bounds)
			val info = FlightSatelliteCacheInfo(false, snapshot.size, snapshot.count { it.satelliteFile != null },
				snapshot.count { it.terrainFile != null }, snapshot.firstOrNull()?.zoom)
			post {
				if (lifecycleGeneration != workGeneration || !workVisible) return@post
				scanRunning = false
				if (detached) return@post
				if (requested !== quote || requestedZoom != selectedZoom) { launchCacheScan(); return@post }
				val firstSnapshot = tiles.isEmpty()
				val previousMinX = minDisplayX
				val previousMinY = minY
				loading = false
				if (snapshot.isNotEmpty() || firstSnapshot) {
					tiles = snapshot
					tileRows = rows
					coverageOverview = overview
					minDisplayX = bounds[0]; maxDisplayX = bounds[1]; minY = bounds[2]; maxY = bounds[3]
				}
				// Keep decoded tiles that are still useful. Evicting the whole LRU on
				// every scene refresh was the source of the grey/image flashing.
				if (firstSnapshot) {
					fitContent()
				} else {
					offsetX += (minDisplayX - previousMinX) * TILE_SIZE * contentScale
					offsetY += (minY - previousMinY) * TILE_SIZE * contentScale
				}
				onCacheInfoChanged?.invoke(info)
				invalidate()
				// Publish completed work even when progress changed during the scan.
				if (generation != scanGeneration && !refreshQueued) {
					refreshQueued = true
					postDelayed(refreshTask, 1000L)
				}
			}
		}
	}

	private fun coverageColor(tile: CachedTile): Int = when {
		tile.satelliteFile != null && tile.terrainFile != null -> Color.rgb(44, 163, 115)
		tile.satelliteFile != null -> Color.rgb(65, 143, 220)
		tile.terrainFile != null -> Color.rgb(215, 164, 51)
		else -> Color.rgb(64, 68, 77)
	}

	/** One small image for a zoomed-out corridor; no 250,000-rectangle UI-thread redraw. */
	private fun buildOverview(source: List<CachedTile>, bounds: IntArray): Bitmap? {
		if (source.isEmpty() || Thread.currentThread().isInterrupted) return null
		val columns = bounds[1] - bounds[0] + 1
		val rows = bounds[3] - bounds[2] + 1
		val scale = minOf(1f, 1024f / maxOf(columns, rows))
		val bitmap = Bitmap.createBitmap(kotlin.math.ceil(columns * scale).toInt().coerceAtLeast(1),
			kotlin.math.ceil(rows * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val paint = Paint()
		// Missing/partial tiles are painted last: aggregation must not hide holes in green.
		for (status in 0..1) for (tile in source) {
			if (Thread.currentThread().isInterrupted) return null
			if ((tile.satelliteFile != null && tile.terrainFile != null) != (status == 0)) continue
			paint.color = coverageColor(tile)
			val x = (tile.displayX - bounds[0]) * scale
			val y = (tile.y - bounds[2]) * scale
			canvas.drawRect(x, y, x + maxOf(1f, scale), y + maxOf(1f, scale), paint)
		}
		return bitmap
	}

	private fun scanCache(requested: FlightOfflineQuote?, requestedZoom: Int?): List<CachedTile> {
		if (requested != null) {
			val satelliteRoot = File(context.applicationContext.filesDir, FlightSatelliteSource.CACHE_DIRECTORY)
			val terrainRoot = File(context.applicationContext.filesDir, FlightTerrainRepository.TERRAIN_DIRECTORY)
			val levels = requested.requestsByZoom
			val selected = levels.entries.firstOrNull { it.key == requestedZoom }
				?: levels.maxByOrNull { it.value.size } ?: return emptyList()
			val cells = selected.value.groupBy { it.tile }.map { (id, requests) ->
				if (Thread.currentThread().isInterrupted) return emptyList()
				fun cached(root: File, extension: String) = File(root, "${id.zoom}/${id.x}/${id.y}.$extension")
					.takeIf { it.isFile && it.length() > 0L }
				CachedTile(id.zoom, id.x, id.y,
					if (requests.any { it.satellite }) cached(satelliteRoot, "jpg") else null,
					if (requests.any { !it.satellite }) cached(terrainRoot, "png") else null)
			}
			return normalizeWrappedTileX(cells, selected.key)
		}
		// The quote is prepared asynchronously. Do not scan unrelated journeys
		// while waiting for it; setQuote triggers the scoped inventory when ready.
		return emptyList()
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

	fun fitContent() {
		if (width <= 0 || height <= 0 || tiles.isEmpty()) return
		val contentWidth = (maxDisplayX - minDisplayX + 1) * TILE_SIZE.toFloat()
		val contentHeight = (maxY - minY + 1) * TILE_SIZE.toFloat()
		fittedScale = min(width * FIT_FRACTION / contentWidth, height * FIT_FRACTION / contentHeight)
			.coerceAtLeast(MINIMUM_SCALE)
		contentScale = fittedScale
		offsetX = (width - contentWidth * contentScale) / 2f
		offsetY = (height - contentHeight * contentScale) / 2f
		invalidate()
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
		coverageOverview?.let { overview ->
			canvas.drawBitmap(overview, null, RectF(offsetX, offsetY,
				offsetX + (maxDisplayX - minDisplayX + 1) * scaledTileSize,
				offsetY + (maxY - minY + 1) * scaledTileSize), tilePaint)
		}
		if (scaledTileSize < 12f) return
		val desiredSampleSize = sampleSizeFor(scaledTileSize)
		val viewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
		val firstX = kotlin.math.floor(-offsetX / scaledTileSize).toInt() + minDisplayX
		val lastX = kotlin.math.ceil((width - offsetX) / scaledTileSize).toInt() + minDisplayX
		val firstY = maxOf(minY, kotlin.math.floor(-offsetY / scaledTileSize).toInt() + minY)
		val lastY = minOf(maxY, kotlin.math.ceil((height - offsetY) / scaledTileSize).toInt() + minY)
		for (y in firstY..lastY) {
			val row = tileRows[y] ?: continue
			val found = row.binarySearch { it.displayX.compareTo(firstX) }
			var index = if (found >= 0) found else -found - 1
			while (index < row.size && row[index].displayX <= lastX) {
			val tile = row[index++]
			val left = offsetX + (tile.displayX - minDisplayX) * scaledTileSize
			val top = offsetY + (tile.y - minY) * scaledTileSize
			val destination = RectF(left, top, left + scaledTileSize, top + scaledTileSize)
			if (!RectF.intersects(destination, viewport)) continue
			if (!showImages) {
				placeholderPaint.color = coverageColor(tile)
				canvas.drawRect(destination, placeholderPaint)
				canvas.drawRect(destination, gridPaint)
				continue
			}
			val key = tile.sourceKey
			val bitmap = cachedBitmap(key, desiredSampleSize)
				?: tile.fallbackSourceKey?.let { cachedBitmap(it, desiredSampleSize) }
			if (bitmap != null) {
				canvas.drawBitmap(bitmap, null, destination, tilePaint)
			} else {
				placeholderPaint.color = coverageColor(tile)
				canvas.drawRect(destination, placeholderPaint)
			}
			queueBitmap(tile, desiredSampleSize)
			canvas.drawRect(destination, gridPaint)
			}
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
		if (detached || !workVisible) return
		val key = cacheKey(tile.sourceKey, sampleSize)
		if (bitmapCache.get(key) != null || key in failedKeys || key in queuedKeys ||
			queuedKeys.size >= MAXIMUM_QUEUED_BITMAPS
		) return
		queuedKeys += key
		val generation = workGeneration
		worker.execute {
			val bitmap = decodeCombinedTile(tile, sampleSize)
			post {
				if (generation != workGeneration || !workVisible) return@post
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
		parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
		scaleDetector.onTouchEvent(event)
		gestureDetector.onTouchEvent(event)
		if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
		return true
	}

	override fun performClick(): Boolean {
		super.performClick()
		return true
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		detached = false
		workLifecycle.attach()
	}

	override fun onDetachedFromWindow() {
		detached = true
		workLifecycle.detach()
		scanGeneration++
		worker.shutdownNow()
		scanner.shutdownNow()
		removeCallbacks(refreshTask)
		bitmapCache.evictAll()
		super.onDetachedFromWindow()
	}

	companion object {
		private const val TILE_SIZE = 256f
		private const val FIT_FRACTION = 0.94f
		private const val MINIMUM_SCALE = 0.002f
		private const val MAXIMUM_SOURCE_PIXEL_SCALE = 8f
		private const val BITMAP_CACHE_KIB = 32 * 1024
		private const val MAXIMUM_QUEUED_BITMAPS = 12
		private val SAMPLE_SIZES = listOf(1, 2, 4, 8, 16)
	}
}
