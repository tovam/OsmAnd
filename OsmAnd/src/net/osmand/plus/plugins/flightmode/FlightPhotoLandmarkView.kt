package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.plus.OsmandApplication

/** Isolated top-down map / full-image picker. It never takes over OsmAnd's main map. */
class FlightPhotoLandmarkView(context: Context) : View(context) {
    var onImagePoint: (Double, Double) -> Unit = { _, _ -> }
    var onMapPoint: (Double, Double) -> Unit = { _, _ -> }
    var onSelectPoint: (Int) -> Unit = {}
    var onRotation: (Float) -> Unit = {}
    var coverage: List<Pair<TerrainTileId, Int>> = emptyList()
    var routeOverview: Boolean = false
    var autoFitRoute: Boolean = false
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
    private var previousAngle: Float? = null
    private var multiTouch = false
    private var satellite = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository =
        FlightTerrainRepository(context.applicationContext as OsmandApplication)
    private val nativeMap =
        FlightNativeMapTextureRepository(context.applicationContext as OsmandApplication)
    private val tiles =
        object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private var tileJob: Job? = null
    private var requestedKey = ""
    private val scale =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (mode == 0) {
                        val old = imageScale
                        imageScale =
                            net.osmand.util.PhotoCalibrationInput.scalePhoto(
                                imageScale,
                                detector.scaleFactor,
                            )
                        val ratio = imageScale / old
                        imagePanX =
                            (imagePanX - (detector.focusX - width / 2f)) * ratio +
                                (detector.focusX - width / 2f)
                        imagePanY =
                            (imagePanY - (detector.focusY - height / 2f)) * ratio +
                                (detector.focusY - height / 2f)
                    } else {
                        zoom =
                            (zoom + ln(detector.scaleFactor.toDouble()) / ln(2.0)).coerceIn(
                                if(routeOverview)1.0 else 3.0,
                                23.0,
                            )
                        requestTiles()
                    }
                    invalidate()
                    return true
                }
            },
        )
    private val gestures =
        GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onScroll(
                        first: MotionEvent?,
                        event: MotionEvent,
                        dx: Float,
                        dy: Float,
                    ): Boolean {
                        if (scale.isInProgress) return true
                        if (mode == 0) {
                            imagePanX -= dx
                            imagePanY -= dy
                        } else {
                            val size = 256 * 2.0.pow(zoom)
                            longitude = normalize(longitude + dx / size * 360)
                            latitude =
                                FlightTerrainTilePlanner.tileYToLatitude(
                                        FlightTerrainTilePlanner.latitudeToTileY(latitude, 0) +
                                            dy / size,
                                        0,
                                    )
                                    .coerceIn(-85.0, 85.0)
                            requestTiles()
                        }
                        invalidate()
                        return true
                    }

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        val hit = hitPoint(e.x, e.y)
                        if (hit >= 0 && mode <= 1) {
                            onSelectPoint(hit)
                            return true
                        }
                        if (mode == 0) {
                            if (image == null) return false
                            val rect = imageRect()
                            val at =
                                net.osmand.util.PhotoLandmarkGeometry.rotate(
                                    e.x.toDouble(),
                                    e.y.toDouble(),
                                    rect.centerX().toDouble(),
                                    rect.centerY().toDouble(),
                                    -rotation.toDouble(),
                                )
                            val x = (at[0] - rect.left) / rect.width()
                            val y = (at[1] - rect.top) / rect.height()
                            if (x in 0.0..1.0 && y in 0.0..1.0) onImagePoint(x, y)
                        } else if (mode == 1) {
                            val size = 256 * 2.0.pow(zoom)
                            onMapPoint(
                                FlightTerrainTilePlanner.tileYToLatitude(
                                        FlightTerrainTilePlanner.latitudeToTileY(latitude, 0) +
                                            (e.y - height / 2) / size,
                                        0,
                                    )
                                    .coerceIn(-85.0, 85.0),
                                normalize(longitude + (e.x - width / 2) / size * 360),
                            )
                        }
                        return true
                    }
                },
            )
            .apply {
                // Each tap places one landmark immediately; two quick taps are not a double-tap
                // command.
                setOnDoubleTapListener(null)
            }

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
        val routeChanged=routeOverview && data.points!=calibration.points
        image = bitmap
        calibration = data
        rotation = data.pickerRotation
        selected = index
        reference = original
        estimated = result
        trip = track
        if (mode != newMode) {
            mode = newMode
            if (mode == 0) {
                imageScale = 1f
                imagePanX = 0f
                imagePanY = 0f
            } else fit()
        }
        if (satellite != useSatellite) {
            satellite = useSatellite
            requestedKey = ""
        }
        if(routeChanged && autoFitRoute) fit()
        if (mode != 0) requestTiles()
        invalidate()
    }

    fun fit() {
        val ref = reference ?: return
        requestedKey = "" // Reframe is also an explicit retry of missing map tiles.
        if (routeOverview) {
            zoom=2.0
            val coordinates = calibration.points.mapNotNull { p -> p.latitude?.let { lat -> p.longitude?.let { lat to it } } }
            if(coordinates.isNotEmpty()) {
                latitude=coordinates.map { it.first }.average()
                val origin=coordinates.first().second
                val longs=coordinates.map { origin+normalize(it.second-origin) }
                longitude=normalize((longs.minOrNull()!!+longs.maxOrNull()!!)/2)
                val dx=((longs.maxOrNull()!!-longs.minOrNull()!!)+2*routePaddingKm/(111*cos(Math.toRadians(latitude)).coerceAtLeast(0.1))).coerceAtLeast(2.0)/360
                val ys=coordinates.map { FlightTerrainTilePlanner.latitudeToTileY(it.first,0) }
                val dy=(ys.maxOrNull()!!-ys.minOrNull()!!+2*routePaddingKm/(40_075*cos(Math.toRadians(latitude)).coerceAtLeast(0.1))).coerceAtLeast(0.015)
                zoom=log2(min(width.coerceAtLeast(300)/(256*dx*1.3),height.coerceAtLeast(250)/(256*dy*1.3))).coerceIn(1.0,14.0)
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
        requestTiles()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw == 0 && mode != 0) fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        if (event.pointerCount >= 2) {
            multiTouch = true
            val angle =
                Math.toDegrees(
                        atan2(
                            (event.getY(1) - event.getY(0)).toDouble(),
                            (event.getX(1) - event.getX(0)).toDouble(),
                        )
                    )
                    .toFloat()
            if (mode == 0 && event.actionMasked == MotionEvent.ACTION_MOVE) {
                previousAngle?.let { before ->
                    val delta = ((angle - before + 540f) % 360f) - 180f
                    val rect = imageRect()
                    val focusX = (event.getX(0) + event.getX(1)) / 2
                    val focusY = (event.getY(0) + event.getY(1)) / 2
                    val center =
                        net.osmand.util.PhotoLandmarkGeometry.rotate(
                            rect.centerX().toDouble(),
                            rect.centerY().toDouble(),
                            focusX.toDouble(),
                            focusY.toDouble(),
                            delta.toDouble(),
                        )
                    imagePanX += (center[0] - rect.centerX()).toFloat()
                    imagePanY += (center[1] - rect.centerY()).toFloat()
                    rotation = ((rotation + delta + 540f) % 360f) - 180f
                    onRotation(rotation)
                    invalidate()
                }
            }
            previousAngle = angle
        } else previousAngle = null
        scale.onTouchEvent(event)
        if (!multiTouch) gestures.onTouchEvent(event)
        else if (
            event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            gestures.onTouchEvent(cancel)
            cancel.recycle()
        }
        return true
    }

    private fun hitPoint(x: Float, y: Float): Int {
        val rect = imageRect()
        val positions =
            calibration.points
                .map { p ->
                    if (mode == 0 && p.x != null && p.y != null)
                        net.osmand.util.PhotoLandmarkGeometry.rotate(
                            rect.left + p.x * rect.width(),
                            rect.top + p.y * rect.height(),
                            rect.centerX().toDouble(),
                            rect.centerY().toDouble(),
                            rotation.toDouble(),
                        )
                    else if (mode == 1 && p.latitude != null && p.longitude != null)
                        project(p.latitude, p.longitude).let {
                            doubleArrayOf(it.x.toDouble(), it.y.toDouble())
                        }
                    else null
                }
                .toTypedArray()
        return net.osmand.util.PhotoLandmarkGeometry.hit(
            positions,
            x.toDouble(),
            y.toDouble(),
            24.0 * resources.displayMetrics.density,
        )
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
                if (p.x != null && p.y != null)
                    mark(
                        canvas,
                        rect.left + p.x.toFloat() * rect.width(),
                        rect.top + p.y.toFloat() * rect.height(),
                        i + 1,
                        if (i == selected) Color.YELLOW else Color.CYAN,
                    )
            }
            val fit = calibration.fit
            if (fit != null) {
                val coordinates = FlightTerrainCoordinates(fit.originLatitude, fit.originLongitude)
                calibration.points.forEach { p ->
                    if (
                        p.latitude != null &&
                            p.longitude != null &&
                            p.altitude != null &&
                            p.x != null &&
                            p.y != null
                    ) {
                        val world =
                            coordinates
                                .toLocal(p.latitude, p.longitude, p.altitude)
                                .map { it / 1000.0 }
                                .toDoubleArray()
                        val uv =
                            net.osmand.util.PhotoPoseSolver.project(
                                fit.parameters.toDoubleArray(),
                                world,
                                calibration.imageWidth.toDouble() / calibration.imageHeight,
                            )
                        if (uv != null) {
                            paint.color = Color.MAGENTA
                            paint.strokeWidth = 2f
                            val x = rect.left + uv[0].toFloat() * rect.width()
                            val y = rect.top + uv[1].toFloat() * rect.height()
                            canvas.drawLine(
                                rect.left + p.x.toFloat() * rect.width(),
                                rect.top + p.y.toFloat() * rect.height(),
                                x,
                                y,
                                paint,
                            )
                            canvas.drawCircle(x, y, 4f, paint)
                        }
                    }
                }
            }
            canvas.restore()
        } else drawMap(canvas)
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
            tiles.get(key(id))?.let {
                paint.color = Color.WHITE
                canvas.drawBitmap(it, null, rect, paint)
            }
        }
        val path = Path()
        coverage.forEach { (id, color) ->
            val top=FlightTerrainTilePlanner.tileYToLatitude(id.y.toDouble(),id.zoom)
            val bottom=FlightTerrainTilePlanner.tileYToLatitude(id.y+1.0,id.zoom)
            val left=id.x.toDouble()/(1 shl id.zoom)*360-180
            val a=project(top,left)
            val size=(256*2.0.pow(zoom-id.zoom)).toFloat()
            val b=project(bottom,left)
            if(a.x+size>=0 && a.x<=width && b.y>=0 && a.y<=height) {
                paint.color=color
                canvas.drawRect(a.x,a.y,a.x+size,b.y,paint)
            }
        }
        val samples = trip?.samples.orEmpty()
        val stride = max(1, samples.size / 2000)
        samples
            .filterIndexed { i, _ -> i % stride == 0 || i == samples.lastIndex }
            .forEachIndexed { i, s ->
                val p = project(s.latitude, s.longitude)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
        paint.color = Color.rgb(255, 140, 55)
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        calibration.points.forEachIndexed { i, p ->
            if (p.latitude != null && p.longitude != null) {
                val at = project(p.latitude, p.longitude)
                mark(canvas, at.x, at.y, i + 1, if (i == selected) Color.YELLOW else Color.CYAN)
            }
        }
        reference?.let {
            val p = project(it.eyeLatitude, it.eyeLongitude)
            mark(canvas, p.x, p.y, 0, Color.rgb(255, 140, 55))
        }
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

    private fun key(id: TerrainTileId) = "$satellite/${id.zoom}/${id.x}/${id.y}"

    private fun requestTiles() {
        if (mode == 0 || width == 0) return
        val z = floor(zoom).toInt().coerceIn(3, if (satellite) 14 else 18)
        val cx = FlightTerrainTilePlanner.longitudeToTileX(longitude, z).toInt()
        val cy = FlightTerrainTilePlanner.latitudeToTileY(latitude, z).toInt()
        val sourceSatellite = satellite
        val n = 1 shl z
        val tileSize = 256 * 2.0.pow(zoom - z)
        val rx = (ceil(width / tileSize / 2).toInt() + 1).coerceAtMost(5)
        val ry = (ceil(height / tileSize / 2).toInt() + 1).coerceAtMost(6)
        val next = "$satellite/$z/$cx/$cy/$rx/$ry/$width/$height"
        if (next == requestedKey) return
        requestedKey = next
        tileJob?.cancel()
        val ids =
            buildList {
                    for (dy in -ry..ry) for (dx in -rx..rx) if (cy + dy in 0 until n)
                        add(TerrainTileId(z, Math.floorMod(cx + dx, n), cy + dy))
                }
                .sortedBy { abs(it.x - cx) + abs(it.y - cy) }
        tileJob =
            scope.launch {
                delay(250)
                var failed = 0
                for (id in ids) {
                    ensureActive()
                    val k = "$sourceSatellite/${id.zoom}/${id.x}/${id.y}"
                    if (tiles.get(k) != null) continue
                    try {
                        val file =
                            if (sourceSatellite) repository.calibrationSatellite(id)
                            else
                                nativeMap.renderTextures(listOf(id)) { _, _, _ -> }.texturePaths[id]
                        val bitmap =
                            withContext(Dispatchers.IO) {
                                file?.let {
                                    val bounds =
                                        BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(it, bounds)
                                    var sampling = 1
                                    while (
                                        max(bounds.outWidth, bounds.outHeight) / sampling > 256
                                    ) sampling *= 2
                                    BitmapFactory.decodeFile(
                                        it,
                                        BitmapFactory.Options().apply {
                                            inSampleSize = sampling
                                            inPreferredConfig = Bitmap.Config.RGB_565
                                        },
                                    )
                                }
                            }
                        if (bitmap != null) {
                            tiles.put(k, bitmap)
                            invalidate()
                        } else failed++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed++
                    }
                }
                onStatus(
                    if (failed > 0)
                        context.getString(net.osmand.plus.R.string.flight_cal_map_missing, failed)
                    else context.getString(net.osmand.plus.R.string.flight_cal_map_ready, z)
                )
            }
    }

    private fun normalize(value: Double) = ((value + 180) % 360 + 360) % 360 - 180

    override fun onDetachedFromWindow() {
        scope.cancel()
        repository.close()
        tiles.evictAll()
        super.onDetachedFromWindow()
    }
}
