package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Lightweight, north-up overview for the window view. It uses only satellite
 * tiles already cached by the flight feature: opening it never starts a network
 * request. The square stays centered on the aircraft. Its initial physical side
 * equals the complete loaded track distance; vertical drags change only this overview's scale.
 */
class FlightWindowOverviewView @JvmOverloads constructor(
	context: Context,
	attributes: AttributeSet? = null
) : View(context, attributes) {

	private data class CachedTile(
		val zoom: Int,
		val x: Int,
		val y: Int,
		val file: File,
		val fallbackFile: File? = null,
		val detailLayer: Int
	)

	private val worker = Executors.newSingleThreadExecutor()
	private val bitmaps = object : LruCache<String, Bitmap>(BITMAP_CACHE_KIB) {
		override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1_024).coerceAtLeast(1)
	}
	private val queued = mutableSetOf<String>()
	private val failed = mutableSetOf<String>()
	private val satellitePaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 30, 38) }
	private val trackHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(180, 4, 9, 12)
		style = Paint.Style.STROKE
		strokeWidth = density(3.2f)
	}
	private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(255, 105, 52)
		style = Paint.Style.STROKE
		strokeWidth = density(1.5f)
	}
	private val gazePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(85, 70, 210, 255)
		style = Paint.Style.FILL
	}
	private val gazeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(93, 216, 255)
		style = Paint.Style.STROKE
		strokeWidth = density(1.2f)
	}
	private val aircraftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		style = Paint.Style.FILL
	}
	private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(220, 205, 219, 227)
		style = Paint.Style.STROKE
		strokeWidth = density(1f)
	}
	private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textSize = density(8f)
		setShadowLayer(density(2f), 0f, density(1f), Color.BLACK)
	}

	private var trip: FlightTrip? = null
	private var sample: FlightSample? = null
	private var viewAzimuthDegrees = 0f
	private var viewConeDegrees = FlightWindowPlacement.DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES
	private var quality = FlightSatelliteQuality.HIGH
	private var baseZoom: Int? = null
	private var cacheKey: String? = null
	private var tiles: List<CachedTile> = emptyList()
	private var scanGeneration = 0
	private var scanRunning = false
	private var detached = false
	private var overviewZoom = 1.0
	private var activePointerId = NO_POINTER
	private var lastTouchY = 0f
	private var touchMoved = false

	init {
		setLayerType(LAYER_TYPE_SOFTWARE, null)
		isClickable = true
		contentDescription = context.getString(net.osmand.plus.R.string.flight_overview_drag_zoom)
	}

	// Own the entire gesture so it cannot also rotate the surrounding Hublot view or its photo.
	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				activePointerId = event.getPointerId(0)
				lastTouchY = event.y
				touchMoved = false
				parent?.requestDisallowInterceptTouchEvent(true)
			}
			MotionEvent.ACTION_MOVE -> {
				val index = event.findPointerIndex(activePointerId)
				if (index >= 0) {
					val deltaY = event.getY(index) - lastTouchY
					lastTouchY = event.getY(index)
					if (event.pointerCount == 1 && deltaY != 0f) {
						touchMoved = true
						val base = baseSideMeters()
						overviewZoom = (overviewZoom * exp(-2.0 * deltaY / height.coerceAtLeast(1)))
							.coerceIn(base / MAXIMUM_VISIBLE_SIDE_METERS, base / MINIMUM_VISIBLE_SIDE_METERS)
						invalidate()
					}
				}
			}
			MotionEvent.ACTION_POINTER_DOWN -> touchMoved = true
			MotionEvent.ACTION_POINTER_UP -> {
				// Rebase after a finger is lifted; never interpret a pointer switch as a zoom jump.
				val remaining = if (event.actionIndex == 0) 1 else 0
				activePointerId = event.getPointerId(remaining)
				lastTouchY = event.getY(remaining)
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				activePointerId = NO_POINTER
				parent?.requestDisallowInterceptTouchEvent(false)
				if (event.actionMasked == MotionEvent.ACTION_UP && !touchMoved) performClick()
			}
		}
		return true
	}

	override fun performClick(): Boolean {
		super.performClick()
		return true
	}

	private fun baseSideMeters(): Double = max(MINIMUM_SIDE_METERS, trip?.totalDistanceMeters ?: DEFAULT_SIDE_METERS)

	fun update(
		trip: FlightTrip?,
		sample: FlightSample?,
		viewAzimuthDegrees: Float,
		viewConeDegrees: Float,
		quality: FlightSatelliteQuality,
		baseZoom: Int?,
		cacheKey: String
	) {
		this.trip = trip
		this.sample = sample
		this.viewAzimuthDegrees = viewAzimuthDegrees
		this.viewConeDegrees = viewConeDegrees.coerceIn(0.25f, 170f)
		this.quality = quality
		this.baseZoom = baseZoom
		if (this.cacheKey != cacheKey) {
			this.cacheKey = cacheKey
			reloadTiles()
		} else {
			invalidate()
		}
	}

	private fun reloadTiles() {
		if (detached) return
		scanGeneration++
		if (!scanRunning) launchTileScan()
	}

	private fun launchTileScan() {
		if (detached) return
		scanRunning = true
		val generation = scanGeneration
		val requestedQuality = quality
		val requestedBaseZoom = baseZoom
		worker.execute {
			val standard = scan(
				File(context.applicationContext.filesDir, FlightSatelliteSource.CACHE_DIRECTORY),
				detailLayer = 0
			)
			val detailQualities = FlightSatelliteQuality.values().filter { candidate ->
				candidate != FlightSatelliteQuality.STANDARD && candidate.ordinal <= requestedQuality.ordinal
			}
			val detailed = detailQualities.flatMapIndexed { index, detailQuality ->
				scan(
					File(context.applicationContext.filesDir, FlightSatelliteSource.renderDirectory(detailQuality)),
					detailLayer = index + 1
				)
			}
			val selectedZoom = requestedBaseZoom ?: (standard + detailed).groupingBy { it.zoom }.eachCount()
				.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key
			val scanned = (standard + detailed).filter { it.zoom == selectedZoom }
				.groupBy { TerrainTileId(it.zoom, it.x, it.y) }
				.map { (id, candidates) ->
					val orderedCandidates = candidates.sortedByDescending { it.detailLayer }
					val primary = orderedCandidates.first()
					CachedTile(
						zoom = id.zoom,
						x = id.x,
						y = id.y,
						file = primary.file,
						fallbackFile = orderedCandidates.getOrNull(1)?.file,
						detailLayer = primary.detailLayer
					)
				}.sortedWith(compareBy({ it.y }, { it.x }))
			post {
				scanRunning = false
				if (detached) return@post
				if (generation == scanGeneration) {
					if (scanned.isNotEmpty()) tiles = scanned
					invalidate()
				} else {
					// Coalesce every intermediate cache notification into one fresh scan.
					launchTileScan()
				}
			}
		}
	}

	private fun scan(root: File, detailLayer: Int): List<CachedTile> {
		val groups = root.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { zoomDirectory ->
			val zoom = zoomDirectory.name.toIntOrNull() ?: return@mapNotNull null
			zoom to zoomDirectory.listFiles().orEmpty().filter(File::isDirectory).flatMap { xDirectory ->
				val x = xDirectory.name.toIntOrNull() ?: return@flatMap emptyList()
				xDirectory.listFiles().orEmpty().mapNotNull { file ->
					val y = file.nameWithoutExtension.toIntOrNull()
					if (y != null && file.isFile && file.length() > 0L) {
						CachedTile(zoom = zoom, x = x, y = y, file = file, detailLayer = detailLayer)
					} else null
				}
			}
		}.filter { it.second.isNotEmpty() }
		// Callers choose one scene zoom. Within that zoom, the highest durable detail layer wins and
		// Standard remains its fallback until decoding finishes, which prevents texture oscillation.
		return groups.flatMap { it.second }
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
		val current = sample
		val currentTrip = trip
		if (current == null || width <= 0 || height <= 0) {
			canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
			return
		}
		val sideMeters = (baseSideMeters() / overviewZoom)
			.coerceIn(MINIMUM_VISIBLE_SIDE_METERS, MAXIMUM_VISIBLE_SIDE_METERS)
		val pixelsPerMeter = minOf(width, height).toDouble() / sideMeters
		val centerX = width / 2f
		val centerY = height / 2f

		tiles.forEach { tile ->
			val west = FlightTerrainTilePlanner.tileXToLongitude(tile.x.toDouble(), tile.zoom)
			val east = FlightTerrainTilePlanner.tileXToLongitude(tile.x + 1.0, tile.zoom)
			val north = FlightTerrainTilePlanner.tileYToLatitude(tile.y.toDouble(), tile.zoom)
			val south = FlightTerrainTilePlanner.tileYToLatitude(tile.y + 1.0, tile.zoom)
			val left = centerX + eastMeters(current.longitude, west, current.latitude) * pixelsPerMeter
			val right = centerX + eastMeters(current.longitude, east, current.latitude) * pixelsPerMeter
			val top = centerY - northMeters(current.latitude, north) * pixelsPerMeter
			val bottom = centerY - northMeters(current.latitude, south) * pixelsPerMeter
			val destination = RectF(
				left.toFloat() - TILE_OVERLAP_PIXELS,
				top.toFloat() - TILE_OVERLAP_PIXELS,
				right.toFloat() + TILE_OVERLAP_PIXELS,
				bottom.toFloat() + TILE_OVERLAP_PIXELS
			)
			if (!RectF.intersects(destination, RectF(0f, 0f, width.toFloat(), height.toFloat()))) return@forEach
			val bitmap = bitmaps.get(tile.file.absolutePath)
				?: tile.fallbackFile?.let { bitmaps.get(it.absolutePath) }
			if (bitmap != null) canvas.drawBitmap(bitmap, null, destination, satellitePaint)
			tile.fallbackFile?.let(::queueBitmap)
			queueBitmap(tile.file)
		}

		currentTrip?.samples?.takeIf { it.size > 1 }?.let { samples ->
			val path = Path()
			var pathStarted = false
			val step = (samples.size / MAXIMUM_TRACK_POINTS).coerceAtLeast(1)
			samples.forEachIndexed { index, point ->
				if (index % step != 0 && index != samples.lastIndex) return@forEachIndexed
				val x = centerX + (eastMeters(current.longitude, point.longitude, current.latitude) * pixelsPerMeter).toFloat()
				val y = centerY - (northMeters(current.latitude, point.latitude) * pixelsPerMeter).toFloat()
				if (!pathStarted) {
					path.moveTo(x, y)
					pathStarted = true
				} else {
					path.lineTo(x, y)
				}
			}
			canvas.drawPath(path, trackHaloPaint)
			canvas.drawPath(path, trackPaint)
		}

		drawGaze(canvas, centerX, centerY, minOf(width, height) * 0.42f)
		drawAircraft(canvas, centerX, centerY, current.bearingDegrees ?: 0f)
		canvas.drawText(formatDistance(sideMeters), density(4f), height - density(5f), labelPaint)
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
	}

	private fun drawGaze(canvas: Canvas, centerX: Float, centerY: Float, length: Float) {
		val halfAngle = (viewConeDegrees / 2f).coerceIn(4f, 85f)
		val left = directionPoint(centerX, centerY, viewAzimuthDegrees - halfAngle, length)
		val right = directionPoint(centerX, centerY, viewAzimuthDegrees + halfAngle, length)
		val middle = directionPoint(centerX, centerY, viewAzimuthDegrees, length)
		canvas.drawPath(Path().apply {
			moveTo(centerX, centerY)
			lineTo(left.first, left.second)
			lineTo(right.first, right.second)
			close()
		}, gazePaint)
		canvas.drawLine(centerX, centerY, middle.first, middle.second, gazeLinePaint)
		canvas.drawCircle(centerX, centerY, density(1.5f), gazeLinePaint)
	}

	private fun drawAircraft(canvas: Canvas, centerX: Float, centerY: Float, bearing: Float) {
		val nose = directionPoint(centerX, centerY, bearing, density(3.4f))
		val left = directionPoint(centerX, centerY, bearing - 145f, density(2.1f))
		val right = directionPoint(centerX, centerY, bearing + 145f, density(2.1f))
		canvas.drawPath(Path().apply {
			moveTo(nose.first, nose.second)
			lineTo(left.first, left.second)
			lineTo(centerX, centerY)
			lineTo(right.first, right.second)
			close()
		}, aircraftPaint)
	}

	private fun directionPoint(x: Float, y: Float, azimuth: Float, length: Float): Pair<Float, Float> {
		val radians = Math.toRadians(azimuth.toDouble())
		return x + sin(radians).toFloat() * length to y - cos(radians).toFloat() * length
	}

	private fun queueBitmap(file: File) {
		val key = file.absolutePath
		if (bitmaps.get(key) != null || key in queued || key in failed || queued.size >= MAXIMUM_QUEUED_BITMAPS) return
		queued += key
		worker.execute {
			val bitmap = decodeOverviewBitmap(key)
			post {
				queued -= key
				if (detached) return@post
				if (bitmap != null) bitmaps.put(key, bitmap) else failed += key
				invalidate()
			}
		}
	}

	private fun decodeOverviewBitmap(path: String): Bitmap? {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(path, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
		var sampleSize = 1
		while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAXIMUM_TILE_DECODE_PIXELS) {
			sampleSize *= 2
		}
		return BitmapFactory.decodeFile(
			path,
			BitmapFactory.Options().apply {
				inPreferredConfig = Bitmap.Config.RGB_565
				inSampleSize = sampleSize
			}
		)
	}

	private fun eastMeters(referenceLongitude: Double, longitude: Double, latitude: Double): Double {
		var delta = longitude - referenceLongitude
		while (delta > 180.0) delta -= 360.0
		while (delta < -180.0) delta += 360.0
		return Math.toRadians(delta) * EARTH_RADIUS_METERS * cos(Math.toRadians(latitude))
	}

	private fun northMeters(referenceLatitude: Double, latitude: Double): Double =
		Math.toRadians(latitude - referenceLatitude) * EARTH_RADIUS_METERS

	private fun formatDistance(meters: Double): String = if (meters >= 1_000.0) {
		"%.0f km".format(meters / 1_000.0)
	} else {
		"%.0f m".format(meters)
	}

	private fun density(dp: Float): Float = dp * resources.displayMetrics.density

	override fun onDetachedFromWindow() {
		detached = true
		scanGeneration++
		worker.shutdownNow()
		bitmaps.evictAll()
		super.onDetachedFromWindow()
	}

	companion object {
		private const val NO_POINTER = -1
		private const val EARTH_RADIUS_METERS = 6_371_008.8
		private const val MINIMUM_SIDE_METERS = 10_000.0
		private const val DEFAULT_SIDE_METERS = 1_000_000.0
		private const val MINIMUM_VISIBLE_SIDE_METERS = 500.0
		private const val MAXIMUM_VISIBLE_SIDE_METERS = 20_000_000.0
		private const val MAXIMUM_TRACK_POINTS = 1_200
		private const val MAXIMUM_TILE_DECODE_PIXELS = 64
		private const val BITMAP_CACHE_KIB = 32 * 1_024
		private const val MAXIMUM_QUEUED_BITMAPS = 24
		private const val TILE_OVERLAP_PIXELS = 0.75f
	}
}
