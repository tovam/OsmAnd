package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import net.osmand.plus.views.OsmandMapTileView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The flight HUD lives in a full-screen ComposeView above OsmAnd's map. This view
 * forwards only the uncovered map surface gestures to the real map view; Compose
 * controls layered above it keep receiving their own touches normally.
 */
class FlightMapGestureProxyView(
	context: Context,
	private var target: OsmandMapTileView,
	private var onExplorationGesture: () -> Unit
) : View(context) {

	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
	private val touchSlopSquared = touchSlop * touchSlop
	private val sourceLocation = IntArray(2)
	private val targetLocation = IntArray(2)
	private var downX = 0f
	private var downY = 0f
	private var explorationReported = false
	private var targetGestureActive = false
	private var gestureTargetView: View? = null
	private var tiltCandidate = false
	private var tiltOverrideActive = false
	private var tiltStartX1 = 0f
	private var tiltStartY1 = 0f
	private var tiltStartX2 = 0f
	private var tiltStartY2 = 0f
	private var tiltStartDistance = 0f
	private var tiltStartAngle = 0f
	private var tiltStartElevation = 0f
	private var lastTiltRefreshMillis = 0L

	init {
		isClickable = true
		isFocusable = false
	}

	fun update(target: OsmandMapTileView, onExplorationGesture: () -> Unit) {
		this.target = target
		this.onExplorationGesture = onExplorationGesture
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		var reportExploration = false
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				gestureTargetView = target.view
				targetGestureActive = true
				tiltCandidate = false
				tiltOverrideActive = false
				downX = event.x
				downY = event.y
				explorationReported = false
				parent?.requestDisallowInterceptTouchEvent(true)
			}
			MotionEvent.ACTION_MOVE -> {
				val deltaX = event.x - downX
				val deltaY = event.y - downY
				if (!explorationReported && (event.pointerCount > 1 || deltaX * deltaX + deltaY * deltaY >= touchSlopSquared)) {
					explorationReported = true
					reportExploration = true
				}
			}
			MotionEvent.ACTION_POINTER_DOWN -> {
				if (event.pointerCount == 2) {
					beginTiltCandidate(event)
				} else {
					tiltCandidate = false
				}
				if (!explorationReported) {
					explorationReported = true
					reportExploration = true
				}
			}
		}

		getLocationOnScreen(sourceLocation)
		// Keep one Android View for the complete pointer stream. Forwarding straight to
		// OsmandMapTileView bypasses View.dispatchTouchEvent(), which breaks capture of
		// multi-pointer zoom/rotation/tilt on the OpenGL map surface.
		val targetView = gestureTargetView ?: target.view
		if (targetView == null) {
			finishGestureIfNeeded(event)
			return true
		}
		if (handleTiltOverride(event, targetView)) {
			if (reportExploration) onExplorationGesture()
			finishGestureIfNeeded(event)
			return true
		}

		dispatchToMap(targetView, event)
		// Changing follow mode causes a Compose update. Do it only after OsmAnd has
		// received the current event, never halfway through forwarding that event.
		if (reportExploration) onExplorationGesture()
		finishGestureIfNeeded(event)
		return true
	}

	private fun beginTiltCandidate(event: MotionEvent) {
		tiltStartX1 = event.getX(0)
		tiltStartY1 = event.getY(0)
		tiltStartX2 = event.getX(1)
		tiltStartY2 = event.getY(1)
		tiltStartDistance = hypot(tiltStartX2 - tiltStartX1, tiltStartY2 - tiltStartY1)
		tiltStartAngle = atan2(tiltStartY2 - tiltStartY1, tiltStartX2 - tiltStartX1)
		tiltStartElevation = target.elevationAngle
		lastTiltRefreshMillis = 0L
		tiltCandidate = tiltStartDistance > 0f
	}

	private fun handleTiltOverride(event: MotionEvent, targetView: View): Boolean {
		if (tiltOverrideActive) {
			if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount >= 2) {
				applyTilt(event)
			}
			return true
		}
		if (!tiltCandidate || event.actionMasked != MotionEvent.ACTION_MOVE || event.pointerCount != 2) {
			return false
		}

		val dx1 = event.getX(0) - tiltStartX1
		val dy1 = event.getY(0) - tiltStartY1
		val dx2 = event.getX(1) - tiltStartX2
		val dy2 = event.getY(1) - tiltStartY2
		val centerDeltaX = (dx1 + dx2) / 2f
		val centerDeltaY = (dy1 + dy2) / 2f
		val currentDistance = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
		val distanceChange = abs(currentDistance / tiltStartDistance - 1f)
		val currentAngle = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
		val angleChange = abs(normalizeRadians(currentAngle - tiltStartAngle))
		// The centroid distinguishes a two-finger vertical translation from a pinch or
		// rotation without requiring both imperfect fingers to follow identical paths.
		// The previous per-finger test rejected ordinary Pixel-sized hand jitter.
		val mostlyVertical = abs(centerDeltaX) <=
			abs(centerDeltaY) * MAX_HORIZONTAL_TO_VERTICAL_RATIO + touchSlop * 0.5f
		val stableSpacing = distanceChange <= MAX_TILT_DISTANCE_CHANGE && angleChange <= MAX_TILT_ANGLE_CHANGE_RADIANS

		if (abs(centerDeltaY) < touchSlop || !mostlyVertical || !stableSpacing) {
			return false
		}

		// OsmAnd's stock recognizer deliberately requires an almost perfectly horizontal
		// initial finger line. Once our more tolerant tilt is unambiguous, cancel that
		// native stream so it cannot turn the same movement into a late pinch/rotation.
		dispatchToMap(targetView, event, MotionEvent.ACTION_CANCEL)
		tiltOverrideActive = true
		applyTilt(event)
		return true
	}

	private fun applyTilt(event: MotionEvent) {
		val centerDeltaY = ((event.getY(0) - tiltStartY1) + (event.getY(1) - tiltStartY2)) / 2f
		target.setElevationAngle(tiltStartElevation + centerDeltaY / PIXELS_PER_TILT_DEGREE)
		val now = SystemClock.uptimeMillis()
		if (now - lastTiltRefreshMillis >= TILT_REFRESH_INTERVAL_MILLIS) {
			lastTiltRefreshMillis = now
			target.refreshMap()
		}
	}

	private fun dispatchToMap(targetView: View, event: MotionEvent, forcedAction: Int? = null) {
		targetView.getLocationOnScreen(targetLocation)
		// A one-pointer CANCEL is intentional: OsmAnd's MultiTouchSupport only clears
		// its internal zoom/tilt mode after CANCEL when fewer than two pointers remain.
		val forwarded = if (forcedAction == MotionEvent.ACTION_CANCEL) {
			MotionEvent.obtain(
				event.downTime,
				event.eventTime,
				MotionEvent.ACTION_CANCEL,
				event.x,
				event.y,
				event.metaState
			)
		} else {
			MotionEvent.obtain(event).also { copy ->
				if (forcedAction != null) copy.action = forcedAction
			}
		}
		forwarded.offsetLocation(
			(sourceLocation[0] - targetLocation[0]).toFloat(),
			(sourceLocation[1] - targetLocation[1]).toFloat()
		)
		targetView.dispatchTouchEvent(forwarded)
		forwarded.recycle()
	}

	private fun normalizeRadians(angle: Float): Float {
		var normalized = angle
		while (normalized > PI_RADIANS) normalized -= FULL_TURN_RADIANS
		while (normalized < -PI_RADIANS) normalized += FULL_TURN_RADIANS
		return normalized
	}

	private fun finishGestureIfNeeded(event: MotionEvent) {
		if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
			if (tiltOverrideActive) target.refreshMap()
			targetGestureActive = false
			gestureTargetView = null
			tiltCandidate = false
			tiltOverrideActive = false
			parent?.requestDisallowInterceptTouchEvent(false)
		}
	}

	override fun onDetachedFromWindow() {
		if (targetGestureActive) {
			val now = SystemClock.uptimeMillis()
			val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
			(gestureTargetView ?: target.view)?.dispatchTouchEvent(cancel)
			cancel.recycle()
			targetGestureActive = false
			gestureTargetView = null
			tiltCandidate = false
			tiltOverrideActive = false
			parent?.requestDisallowInterceptTouchEvent(false)
		}
		super.onDetachedFromWindow()
	}

	private companion object {
		const val MAX_HORIZONTAL_TO_VERTICAL_RATIO = 0.85f
		const val MAX_TILT_DISTANCE_CHANGE = 0.24f
		const val MAX_TILT_ANGLE_CHANGE_RADIANS = 0.35f
		const val PIXELS_PER_TILT_DEGREE = 8f
		const val TILT_REFRESH_INTERVAL_MILLIS = 40L
		const val PI_RADIANS = 3.1415927f
		const val FULL_TURN_RADIANS = PI_RADIANS * 2f
	}
}
