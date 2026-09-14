package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.plus.OsmandApplication

/** Isolated top-down map / full-image picker. It never takes over OsmAnd's main map. */
class FlightPhotoLandmarkView(context: Context) : View(context) {
    init {
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
    }
    var onImagePoint: (Double, Double) -> Unit = { _, _ -> }
    var onMapPoint: (Double, Double) -> Unit = { _, _ -> }
    var onRotation: (Float) -> Unit = {}
    var dragToPlace: Boolean = false
    private var placementPreview: PointF? = null
    var coverage: List<Pair<TerrainTileId, Int>> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }
    var routeOverview: Boolean = false
    var autoFitRoute: Boolean = false
    var gesturesEnabled: Boolean = true
    var routePaddingKm: Double = 0.0
    var onStatus: (String) -> Unit = {}
    private var image: Bitmap? = null
    private var calibration = FlightPhotoCalibration()
    private var reference: FlightPhotoSpatialPose? = null
    private var estimated: FlightPhotoSpatialPose? = null
    private var trip: FlightTrip? = null
    private var selected = 0
    private var mode = -1
    private var zoom = 9.0
    private var longitude = 0.0
    private var latitude = 0.0
    private var imageScale = 1f
    private var imagePanX = 0f
    private var imagePanY = 0f
    private var rotation = 0f
    private var touchActive = false
    private var multiTouch = false
    private var moved = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var span = 0f
    private var angle = 0f
    private var fingerCount = 0
    private var satellite = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mapPoses = mutableMapOf<Int, Triple<Double, Double, Double>>()
    private var trackSamples: List<FlightSample> = emptyList()
    private val runningTiles = mutableMapOf<String, Job>()
    private var wantedTiles: List<Pair<Boolean, TerrainTileId>> = emptyList()
    private val failedUntil = mutableMapOf<String, Long>()
    private var requestedKey = ""
    private var tilesFramePending = false
    private var released = false
    private var attached = false

    fun update(
        bitmap: Bitmap?,
        data: FlightPhotoCalibration,
        index: Int,
        newMode: Int,
        original: FlightPhotoSpatialPose?,
        result: FlightPhotoSpatialPose?,
        track: FlightTrip?,
        useSatellite: Boolean,
    ) {
        val changed = image !== bitmap || calibration != data || selected != index || mode != newMode ||
            reference != original || estimated != result || trip !== track || satellite != useSatellite
        val routeChanged = routeOverview && data.points != calibration.points
        image = bitmap
        calibration = data
        if (!touchActive) rotation = data.pickerRotation
        selected = index
        reference = original
        estimated = result
        if (trip !== track) {
            val samples = track?.samples.orEmpty()
            val stride = max(1, samples.size / 1000)
            trackSamples =
                samples.filterIndexed { i, _ -> i % stride == 0 || i == samples.lastIndex }
            trip = track
        }
        if (mode != newMode) {
            if (mode > 0) mapPoses[mode] = Triple(latitude, longitude, zoom)
            mode = newMode
            if (mode > 0) {
                val saved = mapPoses[mode]
                if (saved != null) {
                    latitude = saved.first
                    longitude = saved.second
                    zoom = saved.third
                } else fit()
                requestedKey = ""
            }
        }
        if (mode > 0 && reference != null && mode !in mapPoses) fit()
        if (satellite != useSatellite) {
            satellite = useSatellite
            requestedKey = ""
        }
        if (routeChanged && autoFitRoute) fit()
        if (changed) {
            if (mode != 0) requestTiles()
            invalidate()
        }
    }

    fun fit() {
        val ref = reference ?: return
        failedUntil.clear()
        requestedKey = "" // Reframe is also an explicit retry of missing map tiles.
        if (routeOverview) {
            zoom = 2.0
            val coordinates =
                calibration.points.mapNotNull { p ->
                    p.latitude?.let { lat -> p.longitude?.let { lat to it } }
                }
            if (coordinates.isNotEmpty()) {
                latitude = coordinates.map { it.first }.average()
                val origin = coordinates.first().second
                val longs = coordinates.map { origin + normalize(it.second - origin) }
                longitude = normalize((longs.minOrNull()!! + longs.maxOrNull()!!) / 2)
                val dx =
                    ((longs.maxOrNull()!! - longs.minOrNull()!!) +
                            2 * routePaddingKm /
                                (111 * cos(Math.toRadians(latitude)).coerceAtLeast(0.1)))
                        .coerceAtLeast(2.0) / 360
                val ys = coordinates.map { FlightTerrainTilePlanner.latitudeToTileY(it.first, 0) }
                val dy =
                    (ys.maxOrNull()!! - ys.minOrNull()!! +
                            2 * routePaddingKm /
                                (40_075 * cos(Math.toRadians(latitude)).coerceAtLeast(0.1)))
                        .coerceAtLeast(0.015)
                zoom =
                    log2(
                            min(
                                width.coerceAtLeast(300) / (256 * dx * 1.3),
                                height.coerceAtLeast(250) / (256 * dy * 1.3),
                            )
                        )
                        .coerceIn(1.0, 14.0)
            }
        } else if (mode == 2 && estimated != null) {
            val end = estimated!!
            latitude = (ref.eyeLatitude + end.eyeLatitude) / 2
            longitude =
                normalize(ref.eyeLongitude + normalize(end.eyeLongitude - ref.eyeLongitude) / 2)
            val distance =
                FlightTerrainTilePlanner.distanceKm(
                    ref.eyeLatitude,
                    ref.eyeLongitude,
                    end.eyeLatitude,
                    end.eyeLongitude,
                ) * 1000
            val side = max(50.0, distance * 3)
            zoom =
                (ln(40_075_016.686 * cos(Math.toRadians(latitude)) * max(1, width) / side / 256) /
                        ln(2.0))
                    .coerceIn(3.0, 23.0)
        } else {
            val point = calibration.points.getOrNull(selected)
            latitude = point?.latitude ?: ref.eyeLatitude
            longitude = point?.longitude ?: ref.eyeLongitude
            zoom = 14.0
        }
        mapPoses[mode] = Triple(latitude, longitude, zoom)
        requestTiles()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw == 0 && mode != 0) fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchActive = true
                multiTouch = false
                moved = 0f
                placementPreview = null
                rebasePointers(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                placementPreview = null
                rebasePointers(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val count = min(2, event.pointerCount)
                val x = if (count == 2) (event.getX(0) + event.getX(1)) / 2 else event.x
                val y = if (count == 2) (event.getY(0) + event.getY(1)) / 2 else event.y
                if (count != fingerCount) {
                    rebasePointers(event)
                    return true
                }
                val newSpan =
                    if (count == 2)
                        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    else 0f
                val newAngle =
                    if (count == 2)
                        atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
                    else 0f
                val factor = if (count == 2 && span > 8f && newSpan > 8f) newSpan / span else 1f
                val delta =
                    if (count == 2) atan2(sin(newAngle - angle), cos(newAngle - angle)) else 0f
                moved += hypot(x - pointerX, y - pointerY)
                if (count == 1 && dragToPlace && !multiTouch) {
                    placementPreview = PointF(x, y)
                    pointerX = x
                    pointerY = y
                    postInvalidateOnAnimation()
                    return true
                }
                if (mode == 0) {
                    val nextScale =
                        net.osmand.util.PhotoCalibrationInput.scalePhoto(imageScale, factor)
                    val ratio = nextScale / imageScale
                    val center =
                        net.osmand.util.PhotoLandmarkGeometry.transformCenter(
                            (width / 2f + imagePanX).toDouble(),
                            (height / 2f + imagePanY).toDouble(),
                            pointerX.toDouble(),
                            pointerY.toDouble(),
                            x.toDouble(),
                            y.toDouble(),
                            ratio.toDouble(),
                            delta.toDouble(),
                        )
                    imagePanX = center[0].toFloat() - width / 2f
                    imagePanY = center[1].toFloat() - height / 2f
                    imageScale = nextScale
                    rotation = normalize(rotation + Math.toDegrees(delta.toDouble())).toFloat()
                } else {
                    val oldSize = 256 * 2.0.pow(zoom)
                    zoom =
                        (zoom + log2(factor.toDouble())).coerceIn(
                            if (routeOverview) 1.0 else 3.0,
                            23.0,
                        )
                    val newSize = 256 * 2.0.pow(zoom)
                    longitude =
                        normalize(
                            longitude +
                                360 *
                                    ((pointerX - width / 2f) / oldSize - (x - width / 2f) / newSize)
                        )
                    latitude =
                        FlightTerrainTilePlanner.tileYToLatitude(
                                FlightTerrainTilePlanner.latitudeToTileY(latitude, 0) +
                                    (pointerY - height / 2f) / oldSize -
                                    (y - height / 2f) / newSize,
                                0,
                            )
                            .coerceIn(-85.0, 85.0)
                    requestTiles()
                }
                pointerX = x
                pointerY = y
                span = newSpan
                angle = newAngle
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                multiTouch = true
                // Ignore the finger being lifted. The surviving finger resumes panning immediately.
                val index = if (event.actionIndex == 0) 1 else 0
                pointerX = event.getX(index)
                pointerY = event.getY(index)
                fingerCount = 1
                span = 0f
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (mode == 0 && rotation != calibration.pickerRotation) onRotation(rotation)
                if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                        !multiTouch &&
                        (dragToPlace || moved <= ViewConfiguration.get(context).scaledTouchSlop)
                ) {
                    performClick()
                    placeAt(event.x, event.y)
                }
                fingerCount = 0
                placementPreview = null
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun rebasePointers(event: MotionEvent) {
        fingerCount = min(2, event.pointerCount)
        pointerX = if (fingerCount == 2) (event.getX(0) + event.getX(1)) / 2 else event.x
        pointerY = if (fingerCount == 2) (event.getY(0) + event.getY(1)) / 2 else event.y
        span =
            if (fingerCount == 2)
                hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
            else 0f
        angle =
            if (fingerCount == 2)
                atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
            else 0f
    }

    private fun placeAt(x: Float, y: Float) {
        if (mode == 0) {
            if (image == null) return
            val rect = imageRect()
            val at =
                net.osmand.util.PhotoLandmarkGeometry.rotate(
                    x.toDouble(),
                    y.toDouble(),
                    rect.centerX().toDouble(),
                    rect.centerY().toDouble(),
                    -rotation.toDouble(),
                )
            val u = (at[0] - rect.left) / rect.width()
            val v = (at[1] - rect.top) / rect.height()
            if (u in 0.0..1.0 && v in 0.0..1.0) onImagePoint(u, v)
        } else if (mode == 1) {
            val size = 256 * 2.0.pow(zoom)
            onMapPoint(
                FlightTerrainTilePlanner.tileYToLatitude(
                        FlightTerrainTilePlanner.latitudeToTileY(latitude, 0) +
                            (y - height / 2f) / size,
                        0,
                    )
                    .coerceIn(-85.0, 85.0),
                normalize(longitude + (x - width / 2f) / size * 360),
            )
        }
    }

    private fun imageRect(): RectF {
        val b = image ?: return RectF(0f, 0f, 1f, 1f)
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height) * imageScale
        val w = b.width * s
        val h = b.height * s
        return RectF(
            (width - w) / 2 + imagePanX,
            (height - h) / 2 + imagePanY,
            (width + w) / 2 + imagePanX,
            (height + h) / 2 + imagePanY,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // AndroidView does NOT clip its Canvas to its Compose layout bounds. In particular
        // drawColor and a zoomed/rotated bitmap used to paint over the entire editor toolbar.
        val viewportSave = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.rgb(12, 18, 24))
        if (mode == 0) {
            val rect = imageRect()
            canvas.save()
            canvas.rotate(rotation, rect.centerX(), rect.centerY())
            image?.let {
                paint.color = Color.WHITE
                canvas.drawBitmap(it, null, rect, paint)
            }
            calibration.points.forEachIndexed { i, p ->
                if (p.x != null && p.y != null && !(i == selected && placementPreview != null))
                    mark(
                        canvas,
                        rect.left + p.x.toFloat() * rect.width(),
                        rect.top + p.y.toFloat() * rect.height(),
                        i + 1,
                        if (i == selected) Color.YELLOW else Color.CYAN,
                    )
            }
            canvas.restore()
        } else drawMap(canvas)
        placementPreview?.let { mark(canvas, it.x, it.y, selected + 1, Color.YELLOW) }
        canvas.restoreToCount(viewportSave)
    }

    private fun drawMap(canvas: Canvas) {
        val z = floor(zoom).toInt().coerceIn(3, if (satellite) 14 else 18)
        val tileSize = (256 * 2.0.pow(zoom - z)).toFloat()
        val cx = FlightTerrainTilePlanner.longitudeToTileX(longitude, z)
        val cy = FlightTerrainTilePlanner.latitudeToTileY(latitude, z)
        val rx = ceil(width / tileSize / 2).toInt() + 1
        val ry = ceil(height / tileSize / 2).toInt() + 1
        for (dy in -ry..ry) for (dx in -rx..rx) {
            val x = floor(cx).toInt() + dx
            val y = floor(cy).toInt() + dy
            val n = 1 shl z
            if (y !in 0 until n) continue
            val id = TerrainTileId(z, Math.floorMod(x, n), y)
            val rect =
                RectF(
                    width / 2f + ((x - cx) * tileSize).toFloat(),
                    height / 2f + ((y - cy) * tileSize).toFloat(),
                    width / 2f + ((x + 1 - cx) * tileSize).toFloat(),
                    height / 2f + ((y + 1 - cy) * tileSize).toFloat(),
                )
            drawCachedTile(canvas, id, rect)
        }
        val path = Path()
        val coverageLayer =
            if (coverage.isNotEmpty())
                canvas.saveLayer(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    Paint().apply { alpha = 128 },
                )
            else null
        coverage.forEach { (id, color) ->
            // Mercator tile coordinates are already projected. Avoid inverse + forward trig per cell.
            val size = (256 * 2.0.pow(zoom - id.zoom)).toFloat()
            val n = (1 shl id.zoom).toDouble()
            val dx = id.x - FlightTerrainTilePlanner.longitudeToTileX(longitude, id.zoom)
            val wrappedDx = dx - kotlin.math.floor(dx/n + 0.5)*n
            val x = width/2f + (wrappedDx*size).toFloat()
            val y = height/2f + ((id.y-FlightTerrainTilePlanner.latitudeToTileY(latitude,id.zoom))*size).toFloat()
            if (x + size >= 0 && x <= width && y + size >= 0 && y <= height) {
                paint.color = color or 0xFF000000.toInt()
                canvas.drawRect(x, y, x + size, y + size, paint)
            }
        }
        coverageLayer?.let { canvas.restoreToCount(it) }
        trackSamples.forEachIndexed { i, s ->
            val p = project(s.latitude, s.longitude)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        paint.color = Color.rgb(255, 140, 55)
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        calibration.points.forEachIndexed { i, p ->
            if (
                p.latitude != null &&
                    p.longitude != null &&
                    !(i == selected && placementPreview != null)
            ) {
                val at = project(p.latitude, p.longitude)
                mark(canvas, at.x, at.y, i + 1, if (i == selected) Color.YELLOW else Color.CYAN)
            }
        }
        if (mode == 2)
            reference?.let {
                val p = project(it.eyeLatitude, it.eyeLongitude)
                mark(canvas, p.x, p.y, 0, Color.rgb(255, 140, 55))
            }
        if (mode == 2)
            estimated?.let {
                val p = project(it.eyeLatitude, it.eyeLongitude)
                mark(canvas, p.x, p.y, 0, Color.rgb(190, 100, 255))
            }
        val metres =
            40_075_016.686 * cos(Math.toRadians(latitude)) / (256 * 2.0.pow(zoom)) * width / 4
        paint.color = Color.WHITE
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        canvas.drawText(
            if (metres >= 1000) "%.1f km".format(metres / 1000) else "%.0f m".format(metres),
            12f,
            height - 22f,
            paint,
        )
        paint.strokeWidth = 3f
        canvas.drawLine(12f, height - 14f, 12f + width / 4, height - 14f, paint)
    }

    private fun project(lat: Double, lon: Double): PointF {
        val size = 256 * 2.0.pow(zoom)
        return PointF(
            (width / 2 + normalize(lon - longitude) / 360 * size).toFloat(),
            (height / 2 +
                    (FlightTerrainTilePlanner.latitudeToTileY(lat, 0) -
                        FlightTerrainTilePlanner.latitudeToTileY(latitude, 0)) * size)
                .toFloat(),
        )
    }

    private fun mark(canvas: Canvas, x: Float, y: Float, label: Int, color: Int) {
        val r = if (label == 0) 7f else 9f
        paint.color = Color.BLACK
        canvas.drawCircle(x, y, r + 2, paint)
        paint.color = color
        canvas.drawCircle(x, y, r, paint)
        if (label > 0) {
            paint.textSize = 12 * resources.displayMetrics.scaledDensity
            paint.color = Color.WHITE
            canvas.drawText(label.toString(), x + 11, y - 8, paint)
        }
    }

    private fun drawCachedTile(canvas: Canvas, id: TerrainTileId, rect: RectF) {
        for (z in id.zoom downTo 3) {
            val shift = id.zoom - z
            val parent = TerrainTileId(z, id.x shr shift, id.y shr shift)
            val bitmap = FlightPhotoTileCache.get(satellite, parent) ?: continue
            val divisions = 1 shl shift
            val sx = (id.x % divisions).toFloat() / divisions * bitmap.width
            val sy = (id.y % divisions).toFloat() / divisions * bitmap.height
            val source =
                Rect(
                    sx.toInt(),
                    sy.toInt(),
                    ceil(sx + bitmap.width.toFloat() / divisions).toInt(),
                    ceil(sy + bitmap.height.toFloat() / divisions).toInt(),
                )
            paint.color = Color.WHITE
            canvas.drawBitmap(bitmap, source, rect, paint)
            return
        }
    }

    private fun requestTiles() {
        if (mode == 0 || width == 0 || released || tilesFramePending) return
        tilesFramePending = true
        postOnAnimation {
            tilesFramePending = false
            planTiles()
        }
    }

    private fun planTiles() {
        if (mode == 0 || width == 0 || released || !attached) return
        val z = floor(zoom).toInt().coerceIn(3, if (satellite) 14 else 18)
        val cx = floor(FlightTerrainTilePlanner.longitudeToTileX(longitude, z)).toInt()
        val cy = floor(FlightTerrainTilePlanner.latitudeToTileY(latitude, z)).toInt()
        val n = 1 shl z
        val tileSize = 256 * 2.0.pow(zoom - z)
        val rx = ceil(width / tileSize / 2).toInt() + 1
        val ry = ceil(height / tileSize / 2).toInt() + 1
        val next = "$satellite/$z/$cx/$cy/$rx/$ry"
        if (next == requestedKey) {
            pumpTiles()
            return
        }
        requestedKey = next
        val visible =
            buildList {
                    for (dy in -ry..ry) for (dx in -rx..rx) if (cy + dy in 0 until n)
                        add(TerrainTileId(z, Math.floorMod(cx + dx, n), cy + dy))
                }
                .distinct()
                .sortedBy { min(abs(it.x - cx), n - abs(it.x - cx)) + abs(it.y - cy) }
        // Fill a coarse backdrop first; refine without ever clearing the displayed parent.
        val parents =
            if (z > 3)
                visible
                    .map {
                        TerrainTileId(max(3, z - 2), it.x shr min(2, z - 3), it.y shr min(2, z - 3))
                    }
                    .distinct()
            else emptyList()
        wantedTiles = (parents + visible).map { satellite to it }
        pumpTiles()
    }

    private fun pumpTiles() {
        if (released || !attached || visibility != VISIBLE || mode == 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        for ((source, id) in wantedTiles) {
            if (runningTiles.size >= if (satellite) 3 else 1) break
            val key = FlightPhotoTileCache.key(source, id)
            if (
                key in runningTiles ||
                    FlightPhotoTileCache.get(source, id) != null ||
                    (failedUntil[key] ?: 0) > now
            )
                continue
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val bitmap =
                            FlightPhotoTileCache.load(
                                context.applicationContext as OsmandApplication,
                                source,
                                id,
                            )
                        FlightPhotoTileCache.put(source, id, bitmap)
                        postInvalidateOnAnimation()
                        onStatus("")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (failedUntil.size > 512) failedUntil.clear()
                        failedUntil[key] = android.os.SystemClock.elapsedRealtime() + 15_000
                        onStatus(
                            context.getString(
                                net.osmand.plus.R.string.flight_cal_map_missing,
                                failedUntil.size,
                            )
                        )
                    } finally {
                        runningTiles.remove(key)
                        pumpTiles()
                    }
                }
            runningTiles[key] = job
            job.start()
        }
    }

    private fun normalize(value: Double) = ((value + 180) % 360 + 360) % 360 - 180

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        tilesFramePending = false
        requestedKey = ""
        requestTiles()
    }

    override fun onDetachedFromWindow() {
        attached = false
        tilesFramePending = false
        touchActive = false
        if (mode == 0 && rotation != calibration.pickerRotation) onRotation(rotation)
        runningTiles.values.toList().forEach { it.cancel() }
        // The editor retains this view across tabs. Do not destroy its scope, camera or cache.
        super.onDetachedFromWindow()
    }

    fun release() {
        released = true
        scope.cancel()
    }
}
