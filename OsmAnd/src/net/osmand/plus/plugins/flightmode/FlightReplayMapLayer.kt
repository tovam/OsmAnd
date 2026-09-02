package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import net.osmand.data.RotatedTileBox
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.layers.base.OsmandMapLayer
import kotlin.math.ceil

/** Draws replay-only information without making native map objects clickable. */
class FlightReplayMapLayer(context: Context) : OsmandMapLayer(context) {

	private data class LayerState(
		val trip: FlightTrip? = null,
		val sample: FlightSample? = null,
		val showPoints: Boolean = false
	)

	@Volatile
	private var state = LayerState()

	private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(220, 93, 216, 255)
		style = Paint.Style.FILL
	}
	private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(150, 4, 10, 14)
		style = Paint.Style.FILL
	}
	private val planeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(205, 4, 10, 14)
		style = Paint.Style.FILL
	}
	private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(255, 139, 56)
		style = Paint.Style.FILL
	}
	private val planeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		style = Paint.Style.STROKE
		strokeJoin = Paint.Join.ROUND
		strokeWidth = 1.4f
	}

	fun update(trip: FlightTrip?, sample: FlightSample?, showPoints: Boolean) {
		val previous = state
		if (previous.trip === trip && previous.sample == sample && previous.showPoints == showPoints) return
		state = LayerState(trip, sample, showPoints)
		view?.refreshMap()
	}

	override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
		val current = state
		if (current.showPoints) drawRecordedPoints(canvas, tileBox, current.trip)
		current.sample?.let { drawPlane(canvas, tileBox, current.trip, it) }
	}

	private fun drawRecordedPoints(canvas: Canvas, tileBox: RotatedTileBox, trip: FlightTrip?) {
		val samples = trip?.samples.orEmpty()
		if (samples.isEmpty()) return
		val scale = density.coerceAtLeast(1f)
		val step = ceil(samples.size / MAXIMUM_DRAWN_POINTS.toDouble()).toInt().coerceAtLeast(1)
		for (index in samples.indices step step) {
			val sample = samples[index]
			if (!NativeUtilities.containsLatLon(getMapRenderer(), tileBox, sample.latitude, sample.longitude)) continue
			val pixel = NativeUtilities.getElevatedPixelFromLatLon(
				getMapRenderer(), tileBox, sample.latitude, sample.longitude
			)
			val x = pixel.x
			val y = pixel.y
			canvas.drawCircle(x, y, 3.1f * scale, pointHaloPaint)
			canvas.drawCircle(x, y, 1.65f * scale, pointPaint)
		}
	}

	private fun drawPlane(canvas: Canvas, tileBox: RotatedTileBox, trip: FlightTrip?, sample: FlightSample) {
		if (!NativeUtilities.containsLatLon(getMapRenderer(), tileBox, sample.latitude, sample.longitude)) return
		val pixel = NativeUtilities.getElevatedPixelFromLatLon(
			getMapRenderer(), tileBox, sample.latitude, sample.longitude
		)
		val x = pixel.x
		val y = pixel.y
		val scale = density.coerceAtLeast(1f)
		val path = Path().apply {
			moveTo(0f, -18f * scale)
			lineTo(3.4f * scale, -5f * scale)
			lineTo(15f * scale, 2f * scale)
			lineTo(15f * scale, 5f * scale)
			lineTo(3.2f * scale, 2.6f * scale)
			lineTo(3.8f * scale, 12f * scale)
			lineTo(8f * scale, 15f * scale)
			lineTo(8f * scale, 17f * scale)
			lineTo(0f, 14f * scale)
			lineTo(-8f * scale, 17f * scale)
			lineTo(-8f * scale, 15f * scale)
			lineTo(-3.8f * scale, 12f * scale)
			lineTo(-3.2f * scale, 2.6f * scale)
			lineTo(-15f * scale, 5f * scale)
			lineTo(-15f * scale, 2f * scale)
			lineTo(-3.4f * scale, -5f * scale)
			close()
		}
		canvas.save()
		canvas.translate(x, y)
		val projectedRotation = projectedTrackRotation(tileBox, trip, sample, x, y)
		val fallbackRotation = (sample.bearingDegrees ?: estimateBearing(trip, sample) ?: 0f) - tileBox.rotate
		canvas.rotate(projectedRotation ?: fallbackRotation)
		canvas.drawCircle(0f, 0f, 21f * scale, planeHaloPaint)
		canvas.drawPath(path, planePaint)
		planeOutlinePaint.strokeWidth = 1.4f * scale
		canvas.drawPath(path, planeOutlinePaint)
		canvas.restore()
	}

	private fun projectedTrackRotation(
		tileBox: RotatedTileBox,
		trip: FlightTrip?,
		sample: FlightSample,
		currentX: Float,
		currentY: Float
	): Float? {
		val samples = trip?.samples ?: return null
		if (samples.size < 2) return null
		val index = findSamplePosition(samples, sample)
		val next = (index + 1..samples.lastIndex)
			.firstOrNull { position ->
				samples[position].legIndex == samples[index].legIndex && !samePosition(samples[index], samples[position])
			}
			?.let(samples::get)
		if (next != null) {
			val nextPixel = NativeUtilities.getElevatedPixelFromLatLon(
				getMapRenderer(), tileBox, next.latitude, next.longitude
			)
			return screenDirectionDegrees(nextPixel.x - currentX, nextPixel.y - currentY)
		}
		val previous = (index - 1 downTo 0)
			.firstOrNull { position ->
				samples[position].legIndex == samples[index].legIndex && !samePosition(samples[index], samples[position])
			}
			?.let(samples::get)
			?: return null
		val previousPixel = NativeUtilities.getElevatedPixelFromLatLon(
			getMapRenderer(), tileBox, previous.latitude, previous.longitude
		)
		return screenDirectionDegrees(currentX - previousPixel.x, currentY - previousPixel.y)
	}

	private fun screenDirectionDegrees(deltaX: Float, deltaY: Float): Float? {
		if (kotlin.math.abs(deltaX) < 0.01f && kotlin.math.abs(deltaY) < 0.01f) return null
		// The plane path points upward at zero degrees. atan2 describes a vector
		// pointing right at zero degrees, hence the +90° conversion.
		return Math.toDegrees(kotlin.math.atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat() + 90f
	}

	private fun estimateBearing(trip: FlightTrip?, sample: FlightSample): Float? {
		val samples = trip?.samples ?: return null
		if (samples.size < 2) return null
		val index = findSamplePosition(samples, sample)
		val next = (index + 1..samples.lastIndex)
			.firstOrNull { position -> !samePosition(samples[index], samples[position]) }
			?.let(samples::get)
		val previous = (index - 1 downTo 0)
			.firstOrNull { position -> !samePosition(samples[index], samples[position]) }
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

	override fun drawInScreenPixels(): Boolean = true

	companion object {
		private const val MAXIMUM_DRAWN_POINTS = 1_200
	}
}
