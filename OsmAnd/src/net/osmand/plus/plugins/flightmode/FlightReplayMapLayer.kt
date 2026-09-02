package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import net.osmand.core.jni.MapMarker
import net.osmand.core.jni.MapMarkerBuilder
import net.osmand.core.jni.MapMarkersCollection
import net.osmand.core.jni.PointI
import net.osmand.core.jni.QListFloat
import net.osmand.core.jni.QVectorPointI
import net.osmand.core.jni.VectorLine
import net.osmand.core.jni.VectorLineBuilder
import net.osmand.core.jni.VectorLinesCollection
import net.osmand.data.LatLon
import net.osmand.data.RotatedTileBox
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.utils.SwigUtilities
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.plus.views.layers.geometry.GeometryWayDrawer
import net.osmand.util.MapUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renderer-native flight geometry.
 *
 * Positions and absolute altitudes are sent to OsmAnd's OpenGL renderer. Nothing is projected
 * back to Android Canvas, so the trace, samples and aircraft remain attached to the 3D world while
 * the camera pans, zooms or tilts.
 */
class FlightReplayMapLayer(context: Context) : OsmandMapLayer(context) {

	private data class LayerState(
		val trip: FlightTrip? = null,
		val sample: FlightSample? = null,
		val showPoints: Boolean = false,
		val photos: List<FlightPhotoAttachment> = emptyList()
	)

	@Volatile
	private var state = LayerState()
	private var routeGeometryDirty = true
	private var pointGeometryDirty = true
	private var photoGeometryDirty = true
	private var aircraftDirty = true

	private var nativeTrip: FlightTrip? = null
	private var nativeHeights = FloatArray(0)
	private var routeLinesCollection: VectorLinesCollection? = null
	private var pointMarkersCollection: MapMarkersCollection? = null
	private var photoMarkersCollection: MapMarkersCollection? = null
	private var aircraftLinesCollection: VectorLinesCollection? = null
	private var aircraftMarkerCollection: MapMarkersCollection? = null
	private var aircraftMarker: MapMarker? = null
	private var aircraftMarkerSizePx = 0
	private var aircraftGroundAltitudeMeters = Float.NaN
	private var lastAircraftVectorUpdateMillis = 0L
	private val aircraftSurfaceIconKey = SwigUtilities.getOnSurfaceIconKey(1)
	private val aircraftSourceBitmap: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
		runCatching {
			context.assets.open(AIRCRAFT_BITMAP_ASSET).use { stream ->
				BitmapFactory.decodeStream(stream)
			}
		}.getOrNull()
	}

	fun update(
		trip: FlightTrip?,
		sample: FlightSample?,
		showPoints: Boolean,
		photos: List<FlightPhotoAttachment>
	) {
		val previous = state
		if (previous.trip === trip && previous.sample == sample && previous.showPoints == showPoints &&
			previous.photos == photos
		) return
		state = LayerState(trip, sample, showPoints, photos)
		if (previous.trip !== trip) {
			routeGeometryDirty = true
			pointGeometryDirty = true
			photoGeometryDirty = true
		}
		if (previous.showPoints != showPoints) pointGeometryDirty = true
		if (previous.photos != photos) photoGeometryDirty = true
		if (previous.sample != sample) aircraftDirty = true
		view?.refreshMap()
	}

	override fun initLayer(view: OsmandMapTileView) {
		super.initLayer(view)
		setPointsOrder(REPLAY_POINTS_Z_ORDER)
	}

	override fun onPrepareBufferImage(
		canvas: Canvas,
		tileBox: RotatedTileBox,
		settings: DrawSettings
	) {
		super.onPrepareBufferImage(canvas, tileBox, settings)
		val renderer = mapRenderer ?: return
		if (mapRendererChanged) {
			clearNativeCollections()
			routeGeometryDirty = true
			pointGeometryDirty = true
			photoGeometryDirty = true
			aircraftDirty = true
			mapRendererChanged = false
		}

		val current = state
		if (routeGeometryDirty) rebuildRoute(current.trip)
		if (pointGeometryDirty) rebuildRecordedPoints(current.trip, current.showPoints)
		if (photoGeometryDirty) rebuildPhotoMarkers(current.trip, current.photos)
		val groundAltitudeMeters = current.sample?.let { sample ->
			renderer.getLocationHeightInMeters(point31(sample))
				.takeIf { it > NativeUtilities.MIN_ALTITUDE_VALUE }
		} ?: 0f
		if (aircraftGroundChanged(groundAltitudeMeters)) aircraftDirty = true
		updateAircraftMarker(
			trip = current.trip,
			sample = current.sample,
			iconSizePx = aircraftIconSizePixels(tileBox)
		)
		val now = android.os.SystemClock.uptimeMillis()
		if (aircraftDirty && (current.sample == null || now - lastAircraftVectorUpdateMillis >= AIRCRAFT_VECTOR_UPDATE_INTERVAL_MILLIS)) {
			updateAircraftTether(current.trip, current.sample, groundAltitudeMeters)
			lastAircraftVectorUpdateMillis = now
		}

		routeLinesCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
		pointMarkersCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
		photoMarkersCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
		aircraftLinesCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
		aircraftMarkerCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
	}

	private fun rebuildRoute(trip: FlightTrip?) {
		clearRouteCollection()
		nativeTrip = trip
		nativeHeights = trip?.samples?.let(::resolveVisualHeights) ?: FloatArray(0)
		val samples = trip?.samples.orEmpty()
		if (samples.size < 2) {
			routeGeometryDirty = false
			return
		}

		val collection = VectorLinesCollection(true)
		val lineScale = GeometryWayDrawer.getVectorLineScale(application).toDouble()
		var lineId = 1
		for (range in contiguousLegRanges(samples)) {
			if (range.last - range.first < 1) continue
			val points = QVectorPointI()
			val heights = QListFloat()
			for (index in sampledIndices(range, MAXIMUM_ROUTE_POINTS)) {
				points.add(point31(samples[index]))
				heights.add(nativeHeights[index])
			}

			// Two concentric native strokes read as one continuous tube: the dark sleeve keeps
			// its volume legible against imagery, while the orange core carries the route.
			buildTubeStroke(
				collection = collection,
				lineId = lineId++,
				baseOrder = baseOrder,
				width = TUBE_SLEEVE_WIDTH_DP * lineScale,
				color = TUBE_SLEEVE_COLOR,
				points = points,
				heights = heights
			)
			buildTubeStroke(
				collection = collection,
				lineId = lineId++,
				baseOrder = baseOrder - 1,
				width = TUBE_CORE_WIDTH_DP * lineScale,
				color = TUBE_CORE_COLOR,
				points = points,
				heights = heights
			)
			lineId = buildCorridorFramework(collection, lineId, lineScale, samples, range)
		}
		if (collection.getLinesCount() > 0) routeLinesCollection = collection
		routeGeometryDirty = false
	}

	private fun buildTubeStroke(
		collection: VectorLinesCollection,
		lineId: Int,
		baseOrder: Int,
		width: Double,
		color: Int,
		points: QVectorPointI,
		heights: QListFloat
	) {
		VectorLineBuilder()
			.setBaseOrder(baseOrder)
			.setIsHidden(false)
			.setLineId(lineId)
			.setLineWidth(width)
			.setPoints(points)
			.setHeights(heights)
			.setElevationScaleFactor(1f)
			.setElevatedLineVisibility(true)
			.setSurfaceLineVisibility(false)
			.setOutlineWidth(0.0)
			.setNearOutlineColor(NativeUtilities.createFColorARGB(Color.TRANSPARENT))
			.setFarOutlineColor(NativeUtilities.createFColorARGB(Color.TRANSPARENT))
			.setFillColor(NativeUtilities.createFColorARGB(color))
			.setEndCapStyle(VectorLine.EndCapStyle.ROUND.swigValue())
			.setJointStyle(VectorLine.JointStyle.ROUND.swigValue())
			.setApproximationEnabled(false)
			.buildAndAddToCollection(collection)
	}

	private fun buildCorridorFramework(
		collection: VectorLinesCollection,
		firstLineId: Int,
		lineScale: Double,
		samples: List<FlightSample>,
		range: IntRange
	): Int {
		var lineId = firstLineId
		val railIndices = sampledIndices(range, MAXIMUM_CORRIDOR_RAIL_POINTS)
		for (rail in 0 until CORRIDOR_RAIL_COUNT) {
			val angle = rail * 2.0 * PI / CORRIDOR_RAIL_COUNT
			val points = QVectorPointI()
			val heights = QListFloat()
			railIndices.forEach { index ->
				val radius = corridorRadius(nativeHeights[index])
				val lateralMeters = cos(angle) * radius
				val verticalMeters = sin(angle) * radius
				val bearing = bearingAt(samples, index).toDouble()
				val direction = bearing + if (lateralMeters >= 0.0) 90.0 else -90.0
				val location = destination(
					LatLon(samples[index].latitude, samples[index].longitude),
					abs(lateralMeters),
					direction
				)
				points.add(point31(location))
				heights.add(max(VISUAL_CLEARANCE_METERS, nativeHeights[index] + verticalMeters.toFloat()))
			}
			buildTubeStroke(
				collection, lineId++, baseOrder - 2,
				CORRIDOR_RAIL_WIDTH_DP * lineScale, CORRIDOR_RAIL_COLOR, points, heights
			)
		}

		val ringIndices = sampledIndices(range, MAXIMUM_CORRIDOR_RINGS)
		ringIndices.forEach { index ->
			val points = QVectorPointI()
			val heights = QListFloat()
			val radius = corridorRadius(nativeHeights[index])
			val bearing = bearingAt(samples, index).toDouble()
			for (segment in 0..CORRIDOR_RING_SEGMENTS) {
				val angle = segment * 2.0 * PI / CORRIDOR_RING_SEGMENTS
				val lateralMeters = cos(angle) * radius
				val verticalMeters = sin(angle) * radius
				val direction = bearing + if (lateralMeters >= 0.0) 90.0 else -90.0
				val location = destination(
					LatLon(samples[index].latitude, samples[index].longitude),
					abs(lateralMeters),
					direction
				)
				points.add(point31(location))
				heights.add(max(VISUAL_CLEARANCE_METERS, nativeHeights[index] + verticalMeters.toFloat()))
			}
			buildTubeStroke(
				collection, lineId++, baseOrder - 3,
				CORRIDOR_RING_WIDTH_DP * lineScale, CORRIDOR_RING_COLOR, points, heights
			)
		}
		return lineId
	}

	private fun sampledIndices(range: IntRange, maximumCount: Int): List<Int> {
		val count = range.last - range.first + 1
		if (count <= maximumCount) return range.toList()
		val step = ceil((count - 1) / (maximumCount - 1).toDouble()).toInt().coerceAtLeast(1)
		return buildList {
			var index = range.first
			while (index <= range.last) {
				add(index)
				index += step
			}
			if (lastOrNull() != range.last) add(range.last)
		}
	}

	private fun corridorRadius(heightMeters: Float): Double =
		((heightMeters - VISUAL_CLEARANCE_METERS).coerceAtLeast(0f) * CORRIDOR_ALTITUDE_RADIUS_RATIO +
			CORRIDOR_MINIMUM_RADIUS_METERS).coerceAtMost(CORRIDOR_MAXIMUM_RADIUS_METERS.toFloat()).toDouble()

	private fun bearingAt(samples: List<FlightSample>, index: Int): Float {
		samples[index].bearingDegrees?.let { return it }
		val next = (index + 1..samples.lastIndex).firstOrNull { candidate ->
			samples[candidate].legIndex == samples[index].legIndex && !samePosition(samples[index], samples[candidate])
		}
		if (next != null) {
			FlightTrackMath.bearingBetween(samples[index], samples[next])?.let { return it }
		}
		val previous = (index - 1 downTo 0).firstOrNull { candidate ->
			samples[candidate].legIndex == samples[index].legIndex && !samePosition(samples[index], samples[candidate])
		}
		return previous?.let { FlightTrackMath.bearingBetween(samples[it], samples[index]) } ?: 0f
	}

	private fun rebuildRecordedPoints(trip: FlightTrip?, showPoints: Boolean) {
		clearPointCollection()
		val samples = trip?.samples.orEmpty()
		if (!showPoints || samples.isEmpty()) {
			pointGeometryDirty = false
			return
		}
		if (nativeTrip !== trip || nativeHeights.size != samples.size) {
			nativeTrip = trip
			nativeHeights = resolveVisualHeights(samples)
		}

		val collection = MapMarkersCollection()
		val pointImage = NativeUtilities.createSkImageFromBitmap(createPointBitmap())
		val step = ceil(samples.size / MAXIMUM_NATIVE_POINTS.toDouble()).toInt().coerceAtLeast(1)
		var markerId = 1
		for (index in samples.indices step step) {
			MapMarkerBuilder()
				.setMarkerId(markerId++)
				.setBaseOrder(pointsOrder)
				.setPosition(point31(samples[index]))
				.setHeight(nativeHeights[index])
				.setElevationScaleFactor(1f)
				.setIsHidden(false)
				.setIsAccuracyCircleSupported(false)
				.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal)
				.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical)
				.setPinIcon(pointImage)
				.buildAndAddToCollection(collection)
		}
		pointMarkersCollection = collection
		pointGeometryDirty = false
	}

	private fun rebuildPhotoMarkers(trip: FlightTrip?, photos: List<FlightPhotoAttachment>) {
		clearPhotoCollection()
		val samples = trip?.samples.orEmpty()
		if (samples.isEmpty() || photos.isEmpty()) {
			photoGeometryDirty = false
			return
		}
		if (nativeTrip !== trip || nativeHeights.size != samples.size) {
			nativeTrip = trip
			nativeHeights = resolveVisualHeights(samples)
		}
		val sampleByIndex = samples.associateBy(FlightSample::index)
		val icon = NativeUtilities.createSkImageFromBitmap(createPhotoBitmap())
		val collection = MapMarkersCollection()
		var markerId = PHOTO_MARKER_ID_START
		photos.forEach { photo ->
			val sample = photo.matchedSampleIndex?.let(sampleByIndex::get) ?: return@forEach
			MapMarkerBuilder()
				.setMarkerId(markerId++)
				.setBaseOrder(pointsOrder - 2)
				.setPosition(point31(sample))
				.setHeight(visualHeightForSample(trip, sample) + PHOTO_MARKER_CLEARANCE_METERS)
				.setElevationScaleFactor(1f)
				.setIsHidden(false)
				.setIsAccuracyCircleSupported(false)
				.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal)
				.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical)
				.setPinIcon(icon)
				.buildAndAddToCollection(collection)
		}
		if (markerId > PHOTO_MARKER_ID_START) photoMarkersCollection = collection
		photoGeometryDirty = false
	}

	private fun updateAircraftTether(
		trip: FlightTrip?,
		sample: FlightSample?,
		groundAltitudeMeters: Float
	) {
		clearAircraftLinesCollection()
		aircraftGroundAltitudeMeters = groundAltitudeMeters
		if (sample == null) {
			aircraftDirty = false
			return
		}

		val altitude = visualHeightForSample(trip, sample)
		val direction = (sample.bearingDegrees ?: estimateBearing(trip, sample) ?: 0f).toDouble()
		val center = LatLon(sample.latitude, sample.longitude)
		val collection = VectorLinesCollection(true)
		val lineScale = GeometryWayDrawer.getVectorLineScale(application).toDouble()
		if (altitude > groundAltitudeMeters + MINIMUM_TETHER_HEIGHT_METERS) {
			// A sub-metre horizontal separation keeps the native line non-degenerate. In world
			// coordinates this still reads as a vertical from the exact aircraft position.
			val ground = destination(center, TETHER_HORIZONTAL_OFFSET_METERS, direction + 90.0)
			val points = QVectorPointI().apply {
				add(point31(ground))
				add(point31(center))
			}
			val heights = QListFloat().apply {
				add(groundAltitudeMeters + TETHER_GROUND_CLEARANCE_METERS)
				add(altitude)
			}
			buildTubeStroke(
				collection, AIRCRAFT_LINE_ID_START, pointsOrder - 3,
				(TETHER_WIDTH_DP + TETHER_SLEEVE_EXTRA_WIDTH_DP) * lineScale,
				TETHER_SLEEVE_COLOR, points, heights
			)
			buildTubeStroke(
				collection, AIRCRAFT_LINE_ID_START + 1, pointsOrder - 4,
				TETHER_WIDTH_DP * lineScale, TETHER_COLOR, points, heights
			)
		}
		if (collection.getLinesCount() > 0) aircraftLinesCollection = collection
		aircraftDirty = false
	}

	private fun updateAircraftMarker(
		trip: FlightTrip?,
		sample: FlightSample?,
		iconSizePx: Int
	) {
		if (sample == null) {
			aircraftMarker?.setIsHidden(true)
			return
		}
		val altitude = visualHeightForSample(trip, sample)
		val direction = sample.bearingDegrees ?: estimateBearing(trip, sample) ?: 0f
		if (aircraftMarker == null || aircraftMarkerSizePx != iconSizePx) {
			clearAircraftMarkerCollection()
			val bitmap = createAircraftMarkerBitmap(iconSizePx) ?: return
			val collection = aircraftMarkerCollection ?: MapMarkersCollection().also {
				aircraftMarkerCollection = it
			}
			aircraftMarker = MapMarkerBuilder()
				.setMarkerId(AIRCRAFT_MARKER_ID)
				.setBaseOrder(pointsOrder - 8)
				.setPosition(point31(sample))
				.setHeight(altitude)
				.setElevationScaleFactor(1f)
				.setIsHidden(false)
				.setIsAccuracyCircleSupported(false)
				.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal)
				.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical)
				.addOnMapSurfaceIcon(
					aircraftSurfaceIconKey,
					NativeUtilities.createSkImageFromBitmap(bitmap)
				)
				.buildAndAddToCollection(collection)
			aircraftMarkerSizePx = iconSizePx
		}
		val marker = aircraftMarker ?: return
		marker.setPosition(point31(sample))
		marker.setHeight(altitude)
		marker.setOnMapSurfaceIconDirection(aircraftSurfaceIconKey, direction)
		marker.setIsHidden(false)
	}

	private fun aircraftIconSizePixels(tileBox: RotatedTileBox): Int {
		val currentTileBox = view?.currentRotatedTileBox?.takeIf { it.pixWidth > 1 && it.pixHeight > 1 } ?: tileBox
		val zoomProgress = ((currentTileBox.fullZoom - AIRCRAFT_MINIMUM_ZOOM) /
			(AIRCRAFT_MAXIMUM_ZOOM - AIRCRAFT_MINIMUM_ZOOM)).coerceIn(0.0, 1.0)
		val sizeDp = AIRCRAFT_MINIMUM_ICON_DP +
			(AIRCRAFT_MAXIMUM_ICON_DP - AIRCRAFT_MINIMUM_ICON_DP) * zoomProgress
		val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
		val rawSizePx = (sizeDp * density).roundToInt()
		val quantumPx = (AIRCRAFT_ICON_SIZE_QUANTUM_DP * density).roundToInt().coerceAtLeast(4)
		return (((rawSizePx + quantumPx / 2) / quantumPx) * quantumPx)
			.coerceAtLeast(AIRCRAFT_MINIMUM_BITMAP_PIXELS)
	}

	private fun aircraftGroundChanged(newGroundAltitudeMeters: Float): Boolean {
		val previous = aircraftGroundAltitudeMeters
		return !previous.isFinite() || abs(newGroundAltitudeMeters - previous) >= GROUND_REBUILD_DELTA_METERS
	}

	private fun destination(origin: LatLon, distanceMeters: Double, bearingDegrees: Double): LatLon =
		MapUtils.rhumbDestinationPoint(origin, distanceMeters, bearingDegrees)

	private fun resolveVisualHeights(samples: List<FlightSample>): FloatArray {
		val heights = FloatArray(samples.size) { Float.NaN }
		samples.forEachIndexed { index, sample ->
			sample.altitudeMeters?.takeIf(::isUsableAltitude)?.let {
				heights[index] = it.toFloat() + VISUAL_CLEARANCE_METERS
			}
		}
		for (range in contiguousLegRanges(samples)) {
			val known = range.filter { !heights[it].isNaN() }
			if (known.isEmpty()) {
				val denominator = (range.last - range.first).coerceAtLeast(1).toFloat()
				for (index in range) {
					val progress = (index - range.first) / denominator
					heights[index] = estimatedFlightAltitude(progress) + VISUAL_CLEARANCE_METERS
				}
				continue
			}

			val firstKnown = known.first()
			for (index in range.first until firstKnown) heights[index] = heights[firstKnown]
			for (knownIndex in 0 until known.lastIndex) {
				val from = known[knownIndex]
				val to = known[knownIndex + 1]
				val distance = (to - from).coerceAtLeast(1).toFloat()
				for (index in from + 1 until to) {
					val progress = (index - from) / distance
					heights[index] = heights[from] + (heights[to] - heights[from]) * progress
				}
			}
			val lastKnown = known.last()
			for (index in lastKnown + 1..range.last) heights[index] = heights[lastKnown]
		}
		return heights
	}

	private fun visualHeightForSample(trip: FlightTrip?, sample: FlightSample): Float {
		sample.altitudeMeters?.takeIf(::isUsableAltitude)?.let {
			return it.toFloat() + VISUAL_CLEARANCE_METERS
		}
		val samples = trip?.samples.orEmpty()
		if (samples.isNotEmpty()) {
			if (nativeTrip !== trip || nativeHeights.size != samples.size) {
				nativeTrip = trip
				nativeHeights = resolveVisualHeights(samples)
			}
			return nativeHeights.getOrElse(findSamplePosition(samples, sample)) {
				DEFAULT_UNKNOWN_ALTITUDE_METERS
			}
		}
		return DEFAULT_UNKNOWN_ALTITUDE_METERS
	}

	private fun contiguousLegRanges(samples: List<FlightSample>): List<IntRange> {
		if (samples.isEmpty()) return emptyList()
		val ranges = mutableListOf<IntRange>()
		var start = 0
		for (index in 1 until samples.size) {
			if (samples[index].legIndex != samples[index - 1].legIndex) {
				ranges.add(start until index)
				start = index
			}
		}
		ranges.add(start..samples.lastIndex)
		return ranges
	}

	private fun point31(sample: FlightSample): PointI = PointI(
		MapUtils.get31TileNumberX(sample.longitude),
		MapUtils.get31TileNumberY(sample.latitude)
	)

	private fun point31(location: LatLon): PointI = PointI(
		MapUtils.get31TileNumberX(location.longitude),
		MapUtils.get31TileNumberY(location.latitude)
	)

	private fun createPointBitmap(): Bitmap {
		val scale = context.resources.displayMetrics.density.coerceAtLeast(1f)
		val size = (POINT_BITMAP_DP * scale).toInt().coerceAtLeast(10)
		return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
			val canvas = Canvas(bitmap)
			val center = size / 2f
			val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 4, 10, 14) }
			val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POINT_COLOR }
			canvas.drawCircle(center, center, size * 0.46f, halo)
			canvas.drawCircle(center, center, size * 0.28f, fill)
		}
	}

	private fun createPhotoBitmap(): Bitmap {
		val scale = context.resources.displayMetrics.density.coerceAtLeast(1f)
		val size = (PHOTO_BITMAP_DP * scale).roundToInt().coerceAtLeast(24)
		return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
			val canvas = Canvas(bitmap)
			val center = size / 2f
			val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 8, 16, 21) }
			val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PHOTO_COLOR }
			val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
			canvas.drawCircle(center, center, size * 0.48f, background)
			canvas.drawCircle(center, center, size * 0.41f, accent)
			val body = RectF(size * 0.21f, size * 0.32f, size * 0.79f, size * 0.72f)
			canvas.drawRoundRect(body, size * 0.07f, size * 0.07f, background)
			canvas.drawRect(size * 0.32f, size * 0.25f, size * 0.52f, size * 0.36f, background)
			canvas.drawCircle(center, size * 0.52f, size * 0.13f, lens)
			canvas.drawCircle(center, size * 0.52f, size * 0.075f, background)
		}
	}

	private fun createAircraftMarkerBitmap(targetSizePx: Int): Bitmap? = aircraftSourceBitmap?.let { source ->
		if (source.width == targetSizePx && source.height == targetSizePx) source
		else Bitmap.createScaledBitmap(source, targetSizePx, targetSizePx, true)
	}

	private fun estimatedFlightAltitude(progress: Float): Float {
		val arc = sin(progress.coerceIn(0f, 1f) * PI).toFloat().coerceAtLeast(0f)
		return UNKNOWN_ENDPOINT_ALTITUDE_METERS + arc * UNKNOWN_CRUISE_ALTITUDE_METERS
	}

	private fun isUsableAltitude(value: Double): Boolean =
		!value.isNaN() && !value.isInfinite() && value in MIN_ALTITUDE_METERS..MAX_ALTITUDE_METERS

	private fun estimateBearing(trip: FlightTrip?, sample: FlightSample): Float? {
		val samples = trip?.samples ?: return null
		if (samples.size < 2) return null
		val index = findSamplePosition(samples, sample)
		val next = (index + 1..samples.lastIndex)
			.firstOrNull { position ->
				samples[position].legIndex == samples[index].legIndex &&
					!samePosition(samples[index], samples[position])
			}
			?.let(samples::get)
		val previous = (index - 1 downTo 0)
			.firstOrNull { position ->
				samples[position].legIndex == samples[index].legIndex &&
					!samePosition(samples[index], samples[position])
			}
			?.let(samples::get)
		val from = if (next != null) samples[index] else previous ?: return null
		val to = next ?: samples[index]
		return FlightTrackMath.bearingBetween(from, to)
	}

	private fun findSamplePosition(samples: List<FlightSample>, sample: FlightSample): Int {
		var index = sample.index.coerceIn(0, samples.lastIndex)
		if (samples[index].index != sample.index) {
			index = samples.indexOfFirst { it.index >= sample.index }.takeIf { it >= 0 } ?: index
		}
		return index
	}

	private fun samePosition(first: FlightSample, second: FlightSample): Boolean =
		kotlin.math.abs(first.latitude - second.latitude) < 1e-7 &&
			kotlin.math.abs(first.longitude - second.longitude) < 1e-7

	private fun clearRouteCollection() {
		val renderer = mapRenderer
		routeLinesCollection?.let { renderer?.removeSymbolsProvider(it) }
		routeLinesCollection = null
	}

	private fun clearPointCollection() {
		val renderer = mapRenderer
		pointMarkersCollection?.let { renderer?.removeSymbolsProvider(it) }
		pointMarkersCollection = null
	}

	private fun clearPhotoCollection() {
		val renderer = mapRenderer
		photoMarkersCollection?.let { renderer?.removeSymbolsProvider(it) }
		photoMarkersCollection = null
	}

	private fun clearAircraftCollection() {
		clearAircraftLinesCollection()
		clearAircraftMarkerCollection()
	}

	private fun clearAircraftMarkerCollection() {
		val renderer = mapRenderer
		aircraftMarkerCollection?.let { renderer?.removeSymbolsProvider(it) }
		aircraftMarkerCollection = null
		aircraftMarker = null
		aircraftMarkerSizePx = 0
	}

	private fun clearAircraftLinesCollection() {
		val renderer = mapRenderer
		aircraftLinesCollection?.let { renderer?.removeSymbolsProvider(it) }
		aircraftLinesCollection = null
	}

	private fun clearNativeCollections() {
		clearRouteCollection()
		clearPointCollection()
		clearPhotoCollection()
		clearAircraftCollection()
		nativeTrip = null
		nativeHeights = FloatArray(0)
		aircraftGroundAltitudeMeters = Float.NaN
		lastAircraftVectorUpdateMillis = 0L
	}

	override fun cleanupResources() {
		clearNativeCollections()
		super.cleanupResources()
	}

	override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) = Unit

	override fun drawInScreenPixels(): Boolean = false

	companion object {
		private const val MAXIMUM_NATIVE_POINTS = 1_200
		private const val MAXIMUM_ROUTE_POINTS = 4_000
		private const val MAXIMUM_CORRIDOR_RAIL_POINTS = 800
		private const val MAXIMUM_CORRIDOR_RINGS = 28
		private const val CORRIDOR_RAIL_COUNT = 6
		private const val CORRIDOR_RING_SEGMENTS = 12
		private const val TUBE_SLEEVE_WIDTH_DP = 10.5
		private const val TUBE_CORE_WIDTH_DP = 5.5
		private const val CORRIDOR_RAIL_WIDTH_DP = 1.4
		private const val CORRIDOR_RING_WIDTH_DP = 1.25
		private const val CORRIDOR_ALTITUDE_RADIUS_RATIO = 0.04f
		private const val CORRIDOR_MINIMUM_RADIUS_METERS = 24f
		private const val CORRIDOR_MAXIMUM_RADIUS_METERS = 520.0
		private const val POINT_BITMAP_DP = 10f
		private const val PHOTO_BITMAP_DP = 26f
		private const val PHOTO_MARKER_CLEARANCE_METERS = 36f
		private const val AIRCRAFT_BITMAP_ASSET = "flightmode/aircraft/flight_airliner_black.png"
		private const val AIRCRAFT_MINIMUM_ZOOM = 3.0
		private const val AIRCRAFT_MAXIMUM_ZOOM = 20.0
		private const val AIRCRAFT_MINIMUM_ICON_DP = 42.0
		private const val AIRCRAFT_MAXIMUM_ICON_DP = 110.0
		private const val AIRCRAFT_ICON_SIZE_QUANTUM_DP = 4.0
		private const val AIRCRAFT_MINIMUM_BITMAP_PIXELS = 48
		private const val AIRCRAFT_VECTOR_UPDATE_INTERVAL_MILLIS = 200L
		private const val TETHER_WIDTH_DP = 1.7
		private const val TETHER_SLEEVE_EXTRA_WIDTH_DP = 2.2
		private const val TETHER_HORIZONTAL_OFFSET_METERS = 0.10
		private const val TETHER_GROUND_CLEARANCE_METERS = 1.5f
		private const val MINIMUM_TETHER_HEIGHT_METERS = 8f
		private const val GROUND_REBUILD_DELTA_METERS = 1f
		private const val VISUAL_CLEARANCE_METERS = 12f
		private const val DEFAULT_UNKNOWN_ALTITUDE_METERS = 1_000f
		private const val UNKNOWN_ENDPOINT_ALTITUDE_METERS = 250f
		private const val UNKNOWN_CRUISE_ALTITUDE_METERS = 9_750f
		private const val MIN_ALTITUDE_METERS = -500.0
		private const val MAX_ALTITUDE_METERS = 30_000.0
		private const val REPLAY_POINTS_Z_ORDER = 998.5f
		private const val AIRCRAFT_LINE_ID_START = 2_000_000_000
		private const val AIRCRAFT_MARKER_ID = 1_999_999_999
		private const val PHOTO_MARKER_ID_START = 1_900_000_000
		private val TUBE_SLEEVE_COLOR = Color.argb(230, 6, 15, 20)
		private val TUBE_CORE_COLOR = Color.rgb(255, 145, 58)
		private val CORRIDOR_RAIL_COLOR = Color.argb(150, 255, 184, 100)
		private val CORRIDOR_RING_COLOR = Color.argb(112, 255, 202, 142)
		private val POINT_COLOR = Color.rgb(93, 216, 255)
		private val PHOTO_COLOR = Color.rgb(255, 204, 102)
		private val TETHER_COLOR = Color.rgb(255, 58, 58)
		private val TETHER_SLEEVE_COLOR = Color.argb(220, 72, 0, 0)
	}
}
