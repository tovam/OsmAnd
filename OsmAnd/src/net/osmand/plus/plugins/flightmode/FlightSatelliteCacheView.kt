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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import net.osmand.plus.R

/** Camera zoom, inspection grid and source-file zoom are three independent things. */
class FlightSatelliteCacheView
@JvmOverloads
constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private data class ImageTile(
        val key: FlightOfflineTileKey,
        val record: FlightTileCatalog.FileRecord,
        val displayX: Int,
        val sample: Int,
    ) {
        val cacheKey: String
            get() = "$key:${record.revision}:$sample"
    }

    private data class Frame(
        val cells: List<FlightTileMapCell>,
        val images: List<ImageTile>,
        val gridZoom: Int,
        val ready: Boolean,
        val satellite: Boolean,
        val finerOnly: Boolean = false,
    )

    private val store = FlightSharedTileStore.get(context)
    private var scanner = Executors.newSingleThreadExecutor()
    private var decoder = Executors.newSingleThreadExecutor()
    private var stopChanges: (() -> Unit)? = null
    private var quote: FlightOfflineQuote? = null
    private var route: List<Pair<Double, Double>> = emptyList()
    private var camera = FlightTileMapCamera()
    private var fitted = false
    private var selectedZoom = 8
    private var layer = 0 // 0 satellite, 1 terrain, 2 availability
    private var gridVisible = false
    private var selectedCellId: TerrainTileId? = null
    private var position: Pair<Double, Double>? = null
    private var active = false
    private var generation = 0L
    private var scanRunning = false
    private var requestVersion = 0L
    private var queuedRefresh = false
    private val eventQueued = AtomicBoolean(false)
    private var frame: Frame? = null
    private var catalogSnapshot: FlightTileCatalog.Snapshot? = null // scanner thread only
    private val pendingImages = hashSetOf<String>()
    private val failedImages = hashSetOf<String>()
    private val cache =
        object : LruCache<String, Bitmap>(24 * 1024) {
            override fun sizeOf(key: String, bitmap: Bitmap) = max(1, bitmap.byteCount / 1024)
        }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val gridPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density
            color = Color.argb(160, 255, 255, 255)
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * resources.displayMetrics.scaledDensity
        }
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
    private val routePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = Color.WHITE
        }
    internal var onCellSelected: ((FlightTileMapCell) -> Unit)? = null
    internal var onCellUpdated: ((FlightTileMapCell?) -> Unit)? = null
    var onGridChanged: ((Int) -> Unit)? = null

    private val refresh = Runnable {
        queuedRefresh = false
        if (!scanRunning) buildFrame()
    }
    private val lifecycle =
        FlightViewVisibility(this) { visible ->
            active = visible
            generation++
            if (visible) {
                if (scanner.isShutdown) scanner = Executors.newSingleThreadExecutor()
                if (decoder.isShutdown) decoder = Executors.newSingleThreadExecutor()
                stopChanges =
                    store.catalog.observe { _, _ ->
                        // A batch of thousands of files queues one UI callback, not thousands.
                        if (eventQueued.compareAndSet(false, true))
                            postDelayed(
                                {
                                    eventQueued.set(false)
                                    if (active) schedule(0)
                                },
                                500,
                            )
                    }
                schedule(0)
            } else {
                stopChanges?.invoke()
                stopChanges = null
                scanner.shutdownNow()
                decoder.shutdownNow()
                removeCallbacks(refresh)
                queuedRefresh = false
                scanRunning = false
                pendingImages.clear()
            }
        }
    private val scale =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    camera =
                        camera.zoom(
                            detector.scaleFactor.toDouble(),
                            detector.focusX.toDouble(),
                            detector.focusY.toDouble(),
                            width,
                            height,
                        )
                    schedule(60)
                    invalidate()
                    return true
                }
            },
        )
    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true

                override fun onScroll(
                    down: MotionEvent?,
                    move: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    if (scale.isInProgress || move.pointerCount > 1) return true
                    camera = camera.pan(-dx.toDouble(), -dy.toDouble())
                    schedule(60)
                    invalidate()
                    return true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    camera = camera.zoom(2.0, event.x.toDouble(), event.y.toDouble(), width, height)
                    schedule(0)
                    invalidate()
                    return true
                }

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    frame
                        ?.cells
                        ?.firstOrNull { tileRect(it.id, it.displayX).contains(event.x, event.y) }
                        ?.let {
                            selectedCellId = it.id
                            invalidate()
                            onCellSelected?.invoke(it)
                        }
                    return true
                }
            },
        )

    init {
        setBackgroundColor(Color.rgb(13, 22, 30))
        isClickable = true
    }

    fun setQuote(value: FlightOfflineQuote?) {
        if (quote === value) return
        val sameRoute = quote?.route == value?.route
        quote = value
        route = value?.mapRoute.orEmpty()
        // A radius/quality change is not a request to move the user's camera.
        if (!sameRoute) {
            clearSelection()
            onCellUpdated?.invoke(null)
            fitted = false
            fitContent()
        }
        schedule(0)
    }

    fun setZoom(value: Int?) {
        val next = value ?: 8
        if (selectedZoom != next) {
            selectedZoom = next
            schedule(0)
        }
    }

    fun setLayer(value: Int) {
        if (layer != value) {
            layer = value
            schedule(0)
            invalidate()
        }
    }

    fun setGridVisible(value: Boolean) {
        if (gridVisible != value) {
            gridVisible = value
            invalidate()
        }
    }

    fun setPosition(sample: FlightSample?) {
        val next = sample?.let { it.latitude to it.longitude }
        if (position != next) {
            position = next
            invalidate()
        }
    }

    fun clearSelection() {
        selectedCellId = null
        invalidate()
    }

    fun zoomBy(factor: Double) {
        camera = camera.zoom(factor, width / 2.0, height / 2.0, width, height)
        schedule(0)
        invalidate()
    }

    fun release() {
        lifecycle.detach()
        onCellSelected = null
        onCellUpdated = null
        onGridChanged = null
        generation++
        removeCallbacks(refresh)
        scanner.shutdownNow()
        decoder.shutdownNow()
        cache.evictAll()
    }

    fun fitContent() {
        FlightTileMapCamera.fitProjected(
                route,
                width,
                (height - 140 * resources.displayMetrics.density).toInt().coerceAtLeast(height / 2),
            )
            ?.let {
                camera = it
                fitted = true
            }
        schedule(0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted) fitContent() else schedule(0)
    }

    private fun schedule(delay: Long) {
        requestVersion++
        if (!active || queuedRefresh || scanRunning || width == 0 || height == 0) return
        queuedRefresh = true
        postDelayed(refresh, delay)
    }

    private fun buildFrame() {
        if (!active || scanner.isShutdown) return
        scanRunning = true
        val epoch = generation
        val version = requestVersion
        val requested = quote
        val bounds =
            camera.bounds(width * 2, height * 2) // retain a screen of neighbours while dragging
        val pixels = camera.worldPixels
        val grid =
            flightTileInspectionZoom(
                selectedZoom,
                bounds,
                pixels,
                56.0 * resources.displayMetrics.density,
            )
        val satellite = layer != 1
        scanner.execute {
            val summary = store.catalog.summary()
            val snapshot =
                catalogSnapshot?.takeIf { it.revision == summary.revision }
                    ?: store.catalog.snapshot().also { catalogSnapshot = it }
            val cells =
                FlightTileMapModel.cells(bounds, grid, snapshot, requested?.requests.orEmpty())
            val images = arrayListOf<ImageTile>()
            val sourceZoom = ceil(log2(pixels / 256)).toInt().coerceIn(0, 19)
            // Existing coarse images first, then detail. No grid-dependent fallback,
            // no terrain tint applied to satellite, no full-corridor mosaic.
            val ranges = (0..22).associateWith { bounds.range(it) }
            var finerOnly = false
            for ((key, record) in snapshot.files) {
                if (Thread.currentThread().isInterrupted) return@execute
                if (key.satellite != satellite) continue
                val z = key.tile.zoom
                if (z !in 0..22) continue
                val (xs, ys) = ranges.getValue(z)
                if (key.tile.y !in ys) continue
                val n = 1 shl z
                val screenSize = pixels / n
                val x = xs.first + Math.floorMod(key.tile.x - xs.first, n)
                if (x !in xs) continue
                if (z > sourceZoom) {
                    // A legacy store may have z8 terrain but no z3-z7. Still show its
                    // first available children; a coarse tile elsewhere must not hide them.
                    fun hasAncestor(levels: IntRange) =
                        levels.any { level ->
                            val shift = z - level
                            snapshot.files.containsKey(
                                FlightOfflineTileKey(
                                    TerrainTileId(
                                        level,
                                        key.tile.x shr shift,
                                        key.tile.y shr shift,
                                    ),
                                    satellite,
                                )
                            )
                        }
                    if (hasAncestor(sourceZoom until z)) continue
                    if (screenSize < 24) {
                        if (!hasAncestor(0 until sourceZoom)) finerOnly = true
                        continue // sub-pixel detail cannot justify thousands of image decodes
                    }
                }
                val sample =
                    when {
                        screenSize >= 200 -> 1
                        screenSize >= 100 -> 2
                        screenSize >= 50 -> 4
                        screenSize >= 25 -> 8
                        else -> 16
                    }
                if (x in xs) images.add(ImageTile(key, record, x, sample))
                if (x + n in xs) images.add(ImageTile(key, record, x + n, sample))
            }
            images.sortBy { it.key.tile.zoom }
            val result =
                Frame(cells, images, bounds.gridZoom(grid), snapshot.complete, satellite, finerOnly)
            post {
                if (epoch != generation || !active) return@post
                scanRunning = false
                if (requested === quote) {
                    val previousGrid = frame?.gridZoom
                    frame = result
                    if (previousGrid != result.gridZoom) onGridChanged?.invoke(result.gridZoom)
                    selectedCellId?.let { id ->
                        val selected = result.cells.firstOrNull { it.id == id }
                        if (selected == null) selectedCellId = null
                        onCellUpdated?.invoke(selected)
                    }
                    invalidate()
                }
                if (version != requestVersion) schedule(0)
            }
        }
    }

    private fun tileRect(id: TerrainTileId, displayX: Int): RectF {
        val n = (1 shl id.zoom).toDouble()
        val side = camera.worldPixels / n
        val x = width / 2.0 + (displayX / n - camera.x) * camera.worldPixels
        val y = height / 2.0 + (id.y / n - camera.y) * camera.worldPixels
        return RectF(x.toFloat(), y.toFloat(), (x + side).toFloat(), (y + side).toFloat())
    }

    private fun color(cell: FlightTileMapCell) =
        when (cell.state) {
            0 -> Color.rgb(34, 128, 91)
            1 -> Color.rgb(91, 81, 120)
            2 -> Color.rgb(176, 103, 31)
            3 -> Color.rgb(41, 92, 141)
            else -> Color.rgb(33, 41, 49)
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        val current = frame
        var displayedImage = false
        if (current != null) {
            if (layer != 2 && current.satellite == (layer == 0)) {
                for (image in current.images) {
                    val rectangle = tileRect(image.key.tile, image.displayX)
                    if (!rectangle.intersects(0f, 0f, width.toFloat(), height.toFloat())) continue
                    val bitmap = cachedImage(image)
                    if (bitmap != null) {
                        canvas.drawBitmap(bitmap, null, rectangle, imagePaint)
                        displayedImage = true
                    }
                    if (cache.get(image.cacheKey) == null) queueImage(image)
                }
            }
            for (cell in current.cells) {
                val rect = tileRect(cell.id, cell.displayX)
                if (!rect.intersects(0f, 0f, width.toFloat(), height.toFloat())) continue
                if (layer == 2) {
                    fillPaint.color = color(cell)
                    canvas.drawRect(rect, fillPaint)
                }
                if (gridVisible || layer == 2) {
                    canvas.drawRect(rect, gridPaint)
                    if (layer == 2 && rect.width() > 48 * resources.displayMetrics.density) {
                        val label =
                            when (cell.state) {
                                0 -> "✓"
                                1 -> "…"
                                2 -> "!"
                                3 -> "·"
                                else -> ""
                            }
                        drawOutlined(
                            canvas,
                            label,
                            rect.centerX() - textPaint.measureText(label) / 2,
                            rect.centerY() + textPaint.textSize / 3,
                        )
                    }
                }
                if (cell.id == selectedCellId) {
                    val paint =
                        Paint(gridPaint).apply {
                            color = Color.rgb(154, 217, 255)
                            strokeWidth = 3 * resources.displayMetrics.density
                        }
                    canvas.drawRect(rect, paint)
                }
            }
        }
        drawRoute(canvas)
        drawPosition(canvas)
        drawScale(canvas)
        if (current == null || !current.ready) {
            drawMapMessage(canvas, context.getString(R.string.flight_files_indexing))
        } else if (layer != 2 && current.satellite != (layer == 0)) {
            drawMapMessage(canvas, context.getString(R.string.flight_tiles_image_loading))
        } else if (layer != 2 && current.finerOnly) {
            drawMapMessage(canvas, context.getString(R.string.flight_tiles_finer_only))
        } else if (
            layer != 2 &&
                current.images.none {
                    tileRect(it.key.tile, it.displayX)
                        .intersects(0f, 0f, width.toFloat(), height.toFloat())
                }
        ) {
            drawMapMessage(canvas, context.getString(R.string.flight_tiles_no_local_images))
        } else if (layer != 2 && !displayedImage) {
            val failed =
                current.images
                    .filter {
                        tileRect(it.key.tile, it.displayX)
                            .intersects(0f, 0f, width.toFloat(), height.toFloat())
                    }
                    .all { it.cacheKey in failedImages }
            drawMapMessage(
                canvas,
                context.getString(
                    if (failed) R.string.flight_tiles_image_error
                    else R.string.flight_tiles_image_loading
                ),
            )
        }
        canvas.restoreToCount(saved)
    }

    private fun drawScale(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val metersPerPixel = flightTileMetersPerPixel(camera)
        val meters = flightScaleStep(min(width * .3, 100.0 * density) * metersPerPixel)
        if (meters <= 0) return
        val x = 16 * density
        val y = 96 * density
        val end = x + (meters / metersPerPixel).toFloat()
        val label =
            if (meters >= 1000) context.getString(R.string.flight_tiles_scale_km, meters / 1000)
            else context.getString(R.string.flight_tiles_scale_m, meters.toInt())
        drawOutlined(canvas, label, x, y - 8 * density)
        for ((color, stroke) in listOf(Color.BLACK to 4f, Color.WHITE to 2f)) {
            val paint =
                Paint(gridPaint).apply {
                    this.color = color
                    strokeWidth = stroke * density
                }
            canvas.drawLine(x, y, end, y, paint)
            canvas.drawLine(x, y - 4 * density, x, y + 2 * density, paint)
            canvas.drawLine(end, y - 4 * density, end, y + 2 * density, paint)
        }
    }

    private fun drawPosition(canvas: Canvas) {
        val p = position ?: return
        val rawX = FlightTerrainTilePlanner.longitudeToTileX(p.second, 0)
        val x =
            (width / 2.0 + (rawX + round(camera.x - rawX) - camera.x) * camera.worldPixels)
                .toFloat()
        val y =
            (height / 2.0 +
                    (FlightTerrainTilePlanner.latitudeToTileY(p.first, 0) - camera.y) *
                        camera.worldPixels)
                .toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = 6 * resources.displayMetrics.density
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, radius + 2 * resources.displayMetrics.density, paint)
        paint.color = Color.rgb(55, 143, 210)
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun drawMapMessage(canvas: Canvas, message: String) {
        val saved = canvas.save()
        val margin = 24 * resources.displayMetrics.density
        val messagePaint =
            Paint(textPaint).apply { textSize = 13 * resources.displayMetrics.scaledDensity }
        val lines = arrayListOf<String>()
        var remaining = message
        while (remaining.isNotEmpty()) {
            val count =
                messagePaint
                    .breakText(remaining, true, (width - 2 * margin).coerceAtLeast(1f), null)
                    .coerceAtLeast(1)
            val boundary =
                if (count < remaining.length)
                    remaining.lastIndexOf(' ', count - 1).takeIf { it > 0 } ?: count
                else count
            lines += remaining.take(boundary)
            remaining = remaining.drop(boundary).trimStart()
        }
        val lineHeight = messagePaint.textSize * 1.4f
        val background = Paint().apply { color = Color.argb(232, 21, 34, 45) }
        val top = height / 2f - (lines.size * lineHeight) / 2
        canvas.drawRoundRect(
            RectF(
                margin / 2,
                top - margin / 2,
                width - margin / 2,
                top + lines.size * lineHeight + margin / 2,
            ),
            12f,
            12f,
            background,
        )
        lines.forEachIndexed { i, text ->
            canvas.drawText(
                text,
                (width - messagePaint.measureText(text)) / 2,
                top + (i + .8f) * lineHeight,
                messagePaint,
            )
        }
        canvas.restoreToCount(saved)
    }

    private fun drawRoute(canvas: Canvas) {
        if (route.size < 2) return
        val path = Path()
        val shift = round(camera.x - route.first().first)
        route.forEachIndexed { i, p ->
            val x = (width / 2.0 + (p.first + shift - camera.x) * camera.worldPixels).toFloat()
            val y = (height / 2.0 + (p.second - camera.y) * camera.worldPixels).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        routePaint.color = Color.BLACK
        routePaint.strokeWidth = 4f * resources.displayMetrics.density
        canvas.drawPath(path, routePaint)
        routePaint.color = Color.WHITE
        routePaint.strokeWidth = 1.5f * resources.displayMetrics.density
        canvas.drawPath(path, routePaint)
    }

    private fun drawOutlined(canvas: Canvas, text: String, x: Float, y: Float) {
        outlinePaint.textSize = textPaint.textSize
        canvas.drawText(text, x, y, outlinePaint)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun cachedImage(image: ImageTile): Bitmap? {
        cache.get(image.cacheKey)?.let {
            return it
        }
        for (sample in SAMPLE_SIZES) {
            cache.get(image.copy(sample = sample).cacheKey)?.let {
                return it
            }
        }
        return null
    }

    private fun queueImage(image: ImageTile) {
        if (
            !active ||
                decoder.isShutdown ||
                pendingImages.size >= 12 ||
                image.cacheKey in failedImages ||
                !pendingImages.add(image.cacheKey)
        )
            return
        val epoch = generation
        decoder.execute {
            val bitmap = runCatching { decode(image) }.getOrNull()
            post {
                if (epoch != generation || !active) {
                    bitmap?.recycle()
                    return@post
                }
                pendingImages.remove(image.cacheKey)
                if (bitmap == null) failedImages.add(image.cacheKey)
                else cache.put(image.cacheKey, bitmap)
                invalidate()
            }
        }
    }

    private fun decode(image: ImageTile): Bitmap? {
        val bitmap =
            BitmapFactory.decodeFile(
                store.file(image.key).path,
                BitmapFactory.Options().apply {
                    inSampleSize = image.sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
                ?: run {
                    store.catalog.inspected(image.key, image.record.revision, 0L)
                    return null
                }
        if (image.key.satellite) return bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val meters = TerrariumCodec.decodeArgb(pixels[i])
            pixels[i] = flightTileTerrainColor(meters)
        }
        return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
            .also { bitmap.recycle() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(
            event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL
        )
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            schedule(0)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle.attach()
    }

    override fun onDetachedFromWindow() {
        lifecycle.detach()
        cache.evictAll()
        super.onDetachedFromWindow()
    }

    companion object {
        private val SAMPLE_SIZES = intArrayOf(1, 2, 4, 8, 16)
    }
}
