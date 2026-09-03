package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import net.osmand.plus.views.OsmandMapTileView

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

	private val touchSlopSquared = (ViewConfiguration.get(context).scaledTouchSlop * 1.5f).let { it * it }
	private val sourceLocation = IntArray(2)
	private val targetLocation = IntArray(2)
	private var downX = 0f
	private var downY = 0f
	private var explorationReported = false
	private var targetGestureActive = false
	private var gestureTargetView: View? = null

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
		targetView.getLocationOnScreen(targetLocation)
		val forwarded = MotionEvent.obtain(event)
		forwarded.offsetLocation(
			(sourceLocation[0] - targetLocation[0]).toFloat(),
			(sourceLocation[1] - targetLocation[1]).toFloat()
		)
		targetView.dispatchTouchEvent(forwarded)
		forwarded.recycle()
		// Changing follow mode causes a Compose update. Do it only after OsmAnd has
		// received the current event, never halfway through forwarding that event.
		if (reportExploration) onExplorationGesture()
		finishGestureIfNeeded(event)
		return true
	}

	private fun finishGestureIfNeeded(event: MotionEvent) {
		if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
			targetGestureActive = false
			gestureTargetView = null
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
			parent?.requestDisallowInterceptTouchEvent(false)
		}
		super.onDetachedFromWindow()
	}
}
