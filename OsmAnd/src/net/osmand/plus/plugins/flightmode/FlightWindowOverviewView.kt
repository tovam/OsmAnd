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
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Lightweight, north-up overview for the window view. It uses only satellite
 * tiles already cached by the flight feature: opening it never starts a network
 * request. The square is centered on the aircraft and its physical side equals
 * the complete loaded track distance.
 */
class FlightWindowOverviewView @JvmOverloads constructor(
	context: Context,
	attributes: AttributeSet? = null
) : View(context, attributes) {

	private data class CachedTile(val zoom: Int, val x: Int, val y: Int, val file: File)

	private val worker = Executors.newSingleThreadExecutor()
	private val bitmaps = object : LruCache<String, Bitmap>(BITMAP_CACHE_KIB) {
		override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1_024).coerceAtLeast(1)
	}
	private val queued = mutableSetOf<String>()
	private val failed = mutableSetOf<String>()
	private val satellitePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
	private var quality = FlightSatelliteQuality.HIGH
	private var cacheKey: String? = null
	private var tiles: List<CachedTile> = emptyList()
	private var scanGeneration = 0
	private var detached = false

	init {
		setLayerType(LAYER_TYPE_SOFTWARE, null)
	}

	fun update(
		trip: FlightTrip?,
		sample: FlightSample?,
		viewAzimuthDegrees: Float,
		quality: FlightSatelliteQuality,
		cacheKey: String
	) {
		this.trip = trip
		this.sample = sample
		this.viewAzimuthDegrees = viewAzimuthDegrees
		this.quality = quality
		if (this.cacheKey != cacheKey) {
			this.cacheKey = cacheKey
			reloadTiles()
		} else {
			invalidate()
		}
	}

	private fun reloadTiles() {
		if (detached) return
		val generation = ++scanGeneration
		val directory = FlightSatelliteSource.renderDirectory(quality)
		val centerLatitude = sample?.latitude
		val centerLongitude = sample?.longitude
		worker.execute {
			val scanned = scan(
				File(context.applicationContext.filesDir, directory),
				centerLatitude,
				centerLongitude
			)
			post {
				if (detached || generation != scanGeneration) return@post
				tiles = scanned
				failed.clear()
				invalidate()
			}
		}
	}

	private fun scan(root: File, latitude: Double?, longitude: Double?): List<CachedTile> {
		val groups = root.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { zoomDirectory ->
			val zoom = zoomDirectory.name.toIntOrNull() ?: return@mapNotNull null
			zoom to zoomDirectory.listFiles().orEmpty().filter(File::isDirectory).flatMap { xDirectory ->
				val x = xDirectory.name.toIntOrNull() ?: return@flatMap emptyList()
				xDirectory.listFiles().orEmpty().mapNotNull { file ->
					val y = file.nameWithoutExtension.toIntOrNull()
					if (y != null && file.isFile && file.length() > 0L) CachedTile(zoom, x, y, file) else null
				}
			}
		}.filter { it.second.isNotEmpty() }
		if (latitude != null && longitude != null) {
			val local = groups.map { group ->
				val zoom = group.first
				val centerX = floor(FlightTerrainTilePlanner.longitudeToTileX(longitude, zoom)).toInt()
				val centerY = floor(FlightTerrainTilePlanner.latitudeToTileY(latitude, zoom)).toInt()
				val worldWidth = 1 shl zoom
				val localCount = group.second.count { tile ->
					val rawDeltaX = kotlin.math.abs(tile.x - centerX)
					val deltaX = minOf(rawDeltaX, worldWidth - rawDeltaX)
					deltaX <= 2 && kotlin.math.abs(tile.y - centerY) <= 2
				}
				Triple(localCount, zoom, group.second)
			}.filter { it.first > 0 }
			if (local.isNotEmpty()) {
				return local.maxWithOrNull(compareBy<Triple<Int, Int, List<CachedTile>>> { it.first }.thenBy { it.second })
					?.third.orEmpty()
			}
		}
		return groups.maxWithOrNull(compareBy<Pair<Int, List<CachedTile>>> { it.second.size }.thenBy { it.first })
			?.second.orEmpty()
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
		val sideMeters = max(MINIMUM_SIDE_METERS, currentTrip?.totalDistanceMeters ?: DEFAULT_SIDE_METERS)
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
			val destination = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
			if (!RectF.intersects(destination, RectF(0f, 0f, width.toFloat(), height.toFloat()))) return@forEach
			val bitmap = bitmaps.get(tile.file.absolutePath)
			if (bitmap != null) canvas.drawBitmap(bitmap, null, destination, satellitePaint)
			else queueBitmap(tile.file)
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
		val halfAngle = 7f
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
			val bitmap = BitmapFactory.decodeFile(
				key,
				BitmapFactory.Options().apply {
					inPreferredConfig = Bitmap.Config.RGB_565
					inSampleSize = OVERVIEW_SAMPLE_SIZE
				}
			)
			post {
				queued -= key
				if (detached) return@post
				if (bitmap != null) bitmaps.put(key, bitmap) else failed += key
				invalidate()
			}
		}
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
		private const val EARTH_RADIUS_METERS = 6_371_008.8
		private const val MINIMUM_SIDE_METERS = 10_000.0
		private const val DEFAULT_SIDE_METERS = 1_000_000.0
		private const val MAXIMUM_TRACK_POINTS = 1_200
		private const val OVERVIEW_SAMPLE_SIZE = 4
		private const val BITMAP_CACHE_KIB = 8 * 1_024
		private const val MAXIMUM_QUEUED_BITMAPS = 12
	}
}
