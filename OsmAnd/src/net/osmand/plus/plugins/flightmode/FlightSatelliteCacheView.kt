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

data class FlightSatelliteCacheInfo(
	val loading: Boolean = true,
	val tileCount: Int = 0,
	val zoom: Int? = null
)

/** Displays the JPEG XYZ tiles already stored by [FlightTerrainRepository]. */
class FlightSatelliteCacheView @JvmOverloads constructor(
	context: Context,
	attributes: AttributeSet? = null
) : View(context, attributes) {

	private data class CachedTile(
		val zoom: Int,
		val x: Int,
		val y: Int,
		val file: File,
		var displayX: Int = x
	)

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
	private var quality: FlightSatelliteQuality = FlightSatelliteQuality.HIGH
	private var scanGeneration = 0
	private var detached = false

	var onCacheInfoChanged: ((FlightSatelliteCacheInfo) -> Unit)? = null

	fun setQuality(quality: FlightSatelliteQuality) {
		this.quality = quality
	}

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
		val generation = ++scanGeneration
		loading = true
		onCacheInfoChanged?.invoke(FlightSatelliteCacheInfo())
		invalidate()
		worker.execute {
			val snapshot = scanCache()
			post {
				if (detached || generation != scanGeneration) return@post
				loading = false
				tiles = snapshot
				// Keep decoded tiles that are still useful. Evicting the whole LRU on
				// every scene refresh was the source of the grey/image flashing.
				failedKeys.clear()
				updateBounds()
				fitContent()
				onCacheInfoChanged?.invoke(
					FlightSatelliteCacheInfo(
						loading = false,
						tileCount = tiles.size,
						zoom = tiles.firstOrNull()?.zoom
					)
				)
				invalidate()
			}
		}
	}

	private fun scanCache(): List<CachedTile> {
		val root = File(context.applicationContext.filesDir, FlightSatelliteSource.renderDirectory(quality))
		val byZoom = root.listFiles().orEmpty()
			.filter { it.isDirectory }
			.mapNotNull { zoomDirectory ->
				val zoom = zoomDirectory.name.toIntOrNull() ?: return@mapNotNull null
				val found = zoomDirectory.listFiles().orEmpty()
					.filter { it.isDirectory }
					.flatMap { xDirectory ->
						val x = xDirectory.name.toIntOrNull() ?: return@flatMap emptyList()
						xDirectory.listFiles().orEmpty().mapNotNull { file ->
							val y = file.nameWithoutExtension.toIntOrNull()
							if (y != null && file.isFile && file.length() > 0L && file.extension.equals("jpg", true)) {
								CachedTile(zoom, x, y, file)
							} else {
								null
							}
						}
					}
				zoom to found
			}
			.filter { it.second.isNotEmpty() }
			.toMap()
		val selectedZoom = byZoom.entries.maxWithOrNull(
			compareBy<Map.Entry<Int, List<CachedTile>>> { it.value.size }.thenBy { it.key }
		)?.key ?: return emptyList()
		return normalizeWrappedTileX(byZoom.getValue(selectedZoom), selectedZoom)
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
			val path = tile.file.absolutePath
			val bitmap = cachedBitmap(path, desiredSampleSize)
			if (bitmap != null) {
				canvas.drawBitmap(bitmap, null, destination, tilePaint)
			} else {
				canvas.drawRect(destination, placeholderPaint)
			}
			queueBitmap(path, desiredSampleSize)
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

	private fun cacheKey(path: String, sampleSize: Int): String = "$path#$sampleSize"

	private fun cachedBitmap(path: String, desiredSampleSize: Int): Bitmap? {
		bitmapCache.get(cacheKey(path, desiredSampleSize))?.let { return it }
		var closest: Bitmap? = null
		var closestDistance = Int.MAX_VALUE
		for (sampleSize in SAMPLE_SIZES) {
			val candidate = bitmapCache.get(cacheKey(path, sampleSize)) ?: continue
			val distance = kotlin.math.abs(sampleSize - desiredSampleSize)
			if (distance < closestDistance) {
				closest = candidate
				closestDistance = distance
			}
		}
		return closest
	}

	private fun queueBitmap(path: String, sampleSize: Int) {
		val key = cacheKey(path, sampleSize)
		if (bitmapCache.get(key) != null || key in failedKeys || key in queuedKeys ||
			queuedKeys.size >= MAXIMUM_QUEUED_BITMAPS
		) return
		queuedKeys += key
		worker.execute {
			val bitmap = BitmapFactory.decodeFile(
				path,
				BitmapFactory.Options().apply {
					inSampleSize = sampleSize
					inPreferredConfig = Bitmap.Config.RGB_565
				}
			)
			post {
				queuedKeys -= key
				if (detached) return@post
				if (bitmap != null) bitmapCache.put(key, bitmap) else failedKeys += key
				invalidate()
			}
		}
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
