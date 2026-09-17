package net.osmand.plus.plugins.flightmode

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.helpers.MapDisplayPositionManager

/**
 * Temporarily constrains the activity's single native map to a screen-space rectangle.
 *
 * The map wrapper is a FrameLayout, so its padding changes the actual renderer child
 * bounds.  The map target remains in the centre of that child; this class deliberately
 * does not move the geographic centre, zoom, rotation, or elevation angle.
 */
class FlightSplitMapViewport(
	private val mapActivity: MapActivity,
	private val mapWrapper: View,
	private val mapDisplayPositionManager: MapDisplayPositionManager
) {

	private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

	private var savedPadding: Padding? = null
	private var savedMapRatio: PointF? = null
	private var savedMapRatioWasCustom = false
	private var pendingBounds: Rect? = null
	private var appliedBounds: Rect? = null
	private var pendingApply: Runnable? = null
	private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
	private var disposed = false

	/**
	 * Requests a new visible map rectangle in screen coordinates. The request is applied
	 * after the current layout pass so renderer dimensions and touch coordinates settle
	 * together. Repeated requests before that pass coalesce to the last rectangle.
	 */
	fun updateBounds(screenBounds: Rect) {
		if (disposed || screenBounds.isEmpty || screenBounds == appliedBounds || screenBounds == pendingBounds) return
		captureOriginalStateIfNeeded()
		pendingBounds = Rect(screenBounds)
		scheduleApply()
	}

	/** Restores the padding and display ratio captured by the first update request. */
	fun restore() {
		if (disposed) return
		cancelPendingCallbacks()
		pendingBounds = null
		appliedBounds = null

		val padding = savedPadding
		val ratio = savedMapRatio
		if (padding == null || ratio == null) return

		mapActivity.setMapViewPaddings(padding.left, padding.top, padding.right, padding.bottom)
		if (savedMapRatioWasCustom) {
			mapDisplayPositionManager.setCustomMapRatio(ratio.x, ratio.y)
		} else {
			mapDisplayPositionManager.restoreMapRatio()
		}
		mapActivity.refreshMap()
		savedPadding = null
		savedMapRatio = null
		savedMapRatioWasCustom = false
	}

	/** Restores state and prevents any already queued layout callback from applying. */
	fun dispose() {
		if (disposed) return
		restore()
		disposed = true
		cancelPendingCallbacks()
	}

	private fun captureOriginalStateIfNeeded() {
		if (savedPadding != null) return
		savedPadding = Padding(
			mapWrapper.paddingLeft,
			mapWrapper.paddingTop,
			mapWrapper.paddingRight,
			mapWrapper.paddingBottom
		)
		val ratio = mapDisplayPositionManager.mapRatio
		savedMapRatio = PointF(ratio.x, ratio.y)
		savedMapRatioWasCustom = mapDisplayPositionManager.hasCustomMapRatio()
	}

	private fun scheduleApply() {
		pendingApply?.let(mapWrapper::removeCallbacks)
		val runnable = Runnable {
			pendingApply = null
			if (disposed || pendingBounds == null) return@Runnable
			if (!mapWrapper.isLaidOut || mapWrapper.width <= 0 || mapWrapper.height <= 0) {
				waitForPreDraw()
			} else {
				applyPendingBounds()
			}
		}
		pendingApply = runnable
		mapWrapper.post(runnable)
	}

	private fun waitForPreDraw() {
		if (disposed || preDrawListener != null) return
		val observer = mapWrapper.viewTreeObserver
		if (!observer.isAlive) return
		lateinit var listener: ViewTreeObserver.OnPreDrawListener
		listener = ViewTreeObserver.OnPreDrawListener {
			mapWrapper.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
			preDrawListener = null
			if (!disposed && pendingBounds != null && mapWrapper.isLaidOut &&
				mapWrapper.width > 0 && mapWrapper.height > 0) {
				applyPendingBounds()
			}
			true
		}
		preDrawListener = listener
		observer.addOnPreDrawListener(listener)
	}

	private fun applyPendingBounds() {
		val requested = pendingBounds ?: return
		val location = IntArray(2)
		mapWrapper.getLocationOnScreen(location)
		val wrapper = Rect(
			location[0],
			location[1],
			location[0] + mapWrapper.width,
			location[1] + mapWrapper.height
		)
		val padding = flightViewportPadding(
			FlightViewportRect(requested.left, requested.top, requested.right, requested.bottom),
			FlightViewportRect(wrapper.left, wrapper.top, wrapper.right, wrapper.bottom)) ?: return
		mapActivity.setMapViewPaddings(padding.left, padding.top, padding.right, padding.bottom)
		// Padding makes the renderer child equal to the requested rectangle. Keep the
		// native map target centred in that child without changing its geo anchor.
		mapDisplayPositionManager.setCustomMapRatio(0.5f, 0.5f)
		appliedBounds = Rect(requested)
		pendingBounds = null
		mapActivity.refreshMap()
	}

	private fun cancelPendingCallbacks() {
		pendingApply?.let {
			mapWrapper.removeCallbacks(it)
			pendingApply = null
		}
		preDrawListener?.let {
			mapWrapper.viewTreeObserver.takeIf { observer -> observer.isAlive }
				?.removeOnPreDrawListener(it)
			preDrawListener = null
		}
	}
}
