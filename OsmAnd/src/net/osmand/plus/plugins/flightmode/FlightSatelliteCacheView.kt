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
            strokeWidth = 1f
            color = Color.argb(160, 255, 255, 255)
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9f * resources.displayMetrics.scaledDensity
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
                        ?.let { onCellSelected?.invoke(it) }
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

    fun fitContent() {
        FlightTileMapCamera.fitProjected(route, width, height)?.let {
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
        val grid = selectedZoom
        val pixels = camera.worldPixels
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
                    frame = result
                    onGridChanged?.invoke(result.gridZoom)
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
            1 -> Color.rgb(85, 88, 100)
            2 -> Color.rgb(176, 103, 31)
            3 -> Color.rgb(41, 92, 141)
            else -> Color.rgb(33, 41, 49)
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        val current = frame
        if (current != null) {
            if (layer != 2 && current.satellite == (layer == 0)) {
                for (image in current.images) {
                    val rectangle = tileRect(image.key.tile, image.displayX)
                    if (!rectangle.intersects(0f, 0f, width.toFloat(), height.toFloat())) continue
                    val bitmap = cachedImage(image)
                    if (bitmap != null) {
                        canvas.drawBitmap(bitmap, null, rectangle, imagePaint)
                        if (rectangle.width() >= textPaint.textSize * 6)
                            drawOutlined(
                                canvas,
                                "z${image.key.tile.zoom}",
                                maxOf(4f, rectangle.left + 4),
                                maxOf(textPaint.textSize, rectangle.top + textPaint.textSize),
                            )
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
                canvas.drawRect(rect, gridPaint)
                drawCellText(canvas, cell, rect)
            }
        }
        drawRoute(canvas)
        if (current == null || !current.ready) {
            drawOutlined(
                canvas,
                context.getString(R.string.flight_files_indexing),
                8f,
                height - textPaint.textSize,
            )
        } else if (layer != 2 && current.finerOnly) {
            drawOutlined(
                canvas,
                context.getString(R.string.flight_tiles_finer_only),
                8f,
                height - textPaint.textSize,
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

    private fun drawCellText(canvas: Canvas, cell: FlightTileMapCell, rect: RectF) {
        val line = textPaint.textSize * 1.2f
        if (rect.width() < textPaint.textSize * 6 || rect.height() < line * 4.5f) return
        fun levels(values: List<FlightTileLevelPortion>) =
            values.joinToString(" ") { it.compact() }.ifEmpty { "—" }
        val rows =
            listOf(
                "S ✓ ${levels(cell.satellite)}",
                "S → ${levels(cell.requestedSatellite)}",
                "R ✓ ${levels(cell.terrain)}",
                "R → ${levels(cell.requestedTerrain)}",
            )
        val saved = canvas.save()
        canvas.clipRect(rect)
        var y = maxOf(rect.top, 0f) + line
        for (row in rows) {
            var text = row
            while (text.isNotEmpty() && y < rect.bottom) {
                val count = textPaint.breakText(text, true, rect.width() - 8, null).coerceAtLeast(1)
                drawOutlined(canvas, text.take(count), rect.left + 4, y)
                text = text.drop(count)
                y += line
            }
        }
        canvas.restoreToCount(saved)
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
            pixels[i] =
                when {
                    meters < 0 -> Color.rgb(28, 80, 132)
                    meters < 400 -> Color.rgb(70, 115, 65)
                    meters < 1500 -> Color.rgb(136, 120, 70)
                    meters < 2700 -> Color.rgb(142, 139, 128)
                    else -> Color.rgb(225, 227, 230)
                }
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
