package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import net.osmand.core.jni.MapMarker
import net.osmand.core.jni.MapMarkerBuilder
import net.osmand.core.jni.MapMarkersCollection
import net.osmand.core.jni.PointI
import net.osmand.core.jni.QListFloat
import net.osmand.core.jni.QVectorPointI
import net.osmand.core.jni.SwigUtilities
import net.osmand.core.jni.VectorLine
import net.osmand.core.jni.VectorLineBuilder
import net.osmand.core.jni.VectorLinesCollection
import net.osmand.data.RotatedTileBox
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.plus.views.layers.geometry.GeometryWayDrawer
import net.osmand.util.MapUtils
import kotlin.math.PI
import kotlin.math.ceil
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
		val showPoints: Boolean = false
	)

	@Volatile
	private var state = LayerState()
	private var routeGeometryDirty = true
	private var pointGeometryDirty = true
	private var aircraftDirty = true

	private var nativeTrip: FlightTrip? = null
	private var nativeHeights = FloatArray(0)
	private var routeLinesCollection: VectorLinesCollection? = null
	private var pointMarkersCollection: MapMarkersCollection? = null
	private var aircraftMarkersCollection: MapMarkersCollection? = null
	private var aircraftMarker: MapMarker? = null
	private val aircraftIconKey = SwigUtilities.getOnSurfaceIconKey(AIRCRAFT_ICON_KEY)

	fun update(trip: FlightTrip?, sample: FlightSample?, showPoints: Boolean) {
		val previous = state
		if (previous.trip === trip && previous.sample == sample && previous.showPoints == showPoints) return
		state = LayerState(trip, sample, showPoints)
		if (previous.trip !== trip) {
			routeGeometryDirty = true
			pointGeometryDirty = true
		}
		if (previous.showPoints != showPoints) pointGeometryDirty = true
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
			aircraftDirty = true
			mapRendererChanged = false
		}

		val current = state
		if (routeGeometryDirty) rebuildRoute(current.trip)
		if (pointGeometryDirty) rebuildRecordedPoints(current.trip, current.showPoints)
		if (aircraftDirty) updateAircraft(current.trip, current.sample)

		routeLinesCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
		pointMarkersCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
		aircraftMarkersCollection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
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
			for (index in range) {
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

	private fun updateAircraft(trip: FlightTrip?, sample: FlightSample?) {
		if (sample == null) {
			aircraftMarker?.setIsHidden(true)
			aircraftDirty = false
			return
		}
		val position = point31(sample)
		val altitude = visualHeightForSample(trip, sample)
		val direction = sample.bearingDegrees ?: estimateBearing(trip, sample) ?: 0f
		val marker = aircraftMarker
		if (marker != null) {
			marker.setPosition(position)
			marker.setHeight(altitude)
			marker.setElevationScaleFactor(1f)
			marker.setOnMapSurfaceIconDirection(aircraftIconKey, direction)
			marker.setIsHidden(false)
			aircraftDirty = false
			return
		}

		val collection = MapMarkersCollection()
		val built = MapMarkerBuilder()
			.setMarkerId(AIRCRAFT_MARKER_ID)
			.setBaseOrder(pointsOrder - 1)
			.setPosition(position)
			.setHeight(altitude)
			.setElevationScaleFactor(1f)
			.setIsHidden(false)
			.setIsAccuracyCircleSupported(false)
			.addOnMapSurfaceIcon(aircraftIconKey, NativeUtilities.createSkImageFromBitmap(createAircraftBitmap()))
			.setUpdateAfterCreated(true)
			.buildAndAddToCollection(collection)
		built.setOnMapSurfaceIconDirection(aircraftIconKey, direction)
		aircraftMarkersCollection = collection
		aircraftMarker = built
		aircraftDirty = false
	}

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

	private fun createAircraftBitmap(): Bitmap {
		val scale = context.resources.displayMetrics.density.coerceAtLeast(1f)
		val size = (AIRCRAFT_BITMAP_DP * scale).toInt().coerceAtLeast(48)
		return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
			val canvas = Canvas(bitmap)
			val center = size / 2f
			val shapeScale = size / 44f
			val path = Path().apply {
				moveTo(center, center - 19f * shapeScale)
				lineTo(center + 3.6f * shapeScale, center - 5f * shapeScale)
				lineTo(center + 16f * shapeScale, center + 2f * shapeScale)
				lineTo(center + 16f * shapeScale, center + 5f * shapeScale)
				lineTo(center + 3.5f * shapeScale, center + 2.8f * shapeScale)
				lineTo(center + 4f * shapeScale, center + 13f * shapeScale)
				lineTo(center + 8f * shapeScale, center + 16f * shapeScale)
				lineTo(center + 8f * shapeScale, center + 18f * shapeScale)
				lineTo(center, center + 15f * shapeScale)
				lineTo(center - 8f * shapeScale, center + 18f * shapeScale)
				lineTo(center - 8f * shapeScale, center + 16f * shapeScale)
				lineTo(center - 4f * shapeScale, center + 13f * shapeScale)
				lineTo(center - 3.5f * shapeScale, center + 2.8f * shapeScale)
				lineTo(center - 16f * shapeScale, center + 5f * shapeScale)
				lineTo(center - 16f * shapeScale, center + 2f * shapeScale)
				lineTo(center - 3.6f * shapeScale, center - 5f * shapeScale)
				close()
			}
			val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 4, 10, 14) }
			val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AIRCRAFT_COLOR }
			val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = Color.WHITE
				style = Paint.Style.STROKE
				strokeJoin = Paint.Join.ROUND
				strokeWidth = 1.25f * scale
			}
			canvas.drawCircle(center, center, size * 0.48f, halo)
			canvas.drawPath(path, fill)
			canvas.drawPath(path, outline)
		}
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

	private fun clearAircraftCollection() {
		val renderer = mapRenderer
		aircraftMarkersCollection?.let { renderer?.removeSymbolsProvider(it) }
		aircraftMarkersCollection = null
		aircraftMarker = null
	}

	private fun clearNativeCollections() {
		clearRouteCollection()
		clearPointCollection()
		clearAircraftCollection()
		nativeTrip = null
		nativeHeights = FloatArray(0)
	}

	override fun cleanupResources() {
		clearNativeCollections()
		super.cleanupResources()
	}

	override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) = Unit

	override fun drawInScreenPixels(): Boolean = false

	companion object {
		private const val MAXIMUM_NATIVE_POINTS = 1_200
		private const val TUBE_SLEEVE_WIDTH_DP = 10.5
		private const val TUBE_CORE_WIDTH_DP = 5.5
		private const val POINT_BITMAP_DP = 10f
		private const val AIRCRAFT_BITMAP_DP = 48f
		private const val VISUAL_CLEARANCE_METERS = 12f
		private const val DEFAULT_UNKNOWN_ALTITUDE_METERS = 1_000f
		private const val UNKNOWN_ENDPOINT_ALTITUDE_METERS = 250f
		private const val UNKNOWN_CRUISE_ALTITUDE_METERS = 9_750f
		private const val MIN_ALTITUDE_METERS = -500.0
		private const val MAX_ALTITUDE_METERS = 30_000.0
		private const val REPLAY_POINTS_Z_ORDER = 998.5f
		private const val AIRCRAFT_ICON_KEY = 1
		private const val AIRCRAFT_MARKER_ID = 2_000_000_000
		private val TUBE_SLEEVE_COLOR = Color.argb(230, 6, 15, 20)
		private val TUBE_CORE_COLOR = Color.rgb(255, 145, 58)
		private val POINT_COLOR = Color.rgb(93, 216, 255)
		private val AIRCRAFT_COLOR = Color.rgb(255, 139, 56)
	}
}
