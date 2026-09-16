package net.osmand.plus.plugins.flightmode

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Stop immediately from the lifecycle callback, not from a recomposition that may be suspended. */
@Composable
internal fun FlightVisibilityEffect(vararg keys: Any?, changed: (Boolean) -> Unit) {
    val view = LocalView.current
    val action by rememberUpdatedState(changed)
    DisposableEffect(view, *keys) {
        val lifecycle = checkNotNull(view.findViewTreeLifecycleOwner()).lifecycle
        var active: Boolean? = null
        fun update() {
            val next = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (next != active) {
                active = next
                action(next)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            if (active == true) action(false)
        }
    }
}

/** Unlike plain LaunchedEffect, delay-based loops cannot keep running behind another app. */
@Composable
internal fun FlightResumedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(block)
    val holder = remember { arrayOfNulls<Job>(1) }
    FlightVisibilityEffect(*keys) { visible ->
        holder[0]?.cancel()
        holder[0] = if (visible) scope.launch(block = action) else null
    }
}

/** AndroidView instances may stay attached when their activity is stopped. */
internal class FlightViewVisibility(
    private val view: View,
    private val changed: (Boolean) -> Unit,
) {
    private var lifecycle: Lifecycle? = null
    private var active = false
    private val observer = LifecycleEventObserver { _, _ -> update() }

    fun attach() {
        lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        lifecycle?.addObserver(observer)
        update()
    }

    fun detach() {
        lifecycle?.removeObserver(observer)
        lifecycle = null
        update()
    }

    private fun update() {
        val next = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (next != active) {
            active = next
            changed(next)
        }
    }
}
