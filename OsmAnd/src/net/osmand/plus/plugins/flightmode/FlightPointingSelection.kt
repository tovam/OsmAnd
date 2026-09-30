package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class FlightPointingPhase {
    LOADING,
    PREPARING,
    READY,
    SELECTING,
    WAITING_GROUND,
    NO_INTERSECTION,
}

internal data class FlightPointingSelectionState(
    val point: FlightPickedPoint? = null,
    val phase: FlightPointingPhase = FlightPointingPhase.LOADING,
    val pendingClick: Boolean = false,
)

internal data class FlightPointingContext(
    val terrain: FlightTerrainPicker,
    val frame: FlightPickingFrame? = null,
    val photo: FlightPhotoProjection? = null,
)

/**
 * Coordinates and the visible camera are captured, so delayed clicks cannot drift as views move.
 */
internal sealed interface FlightPointingClick {
    data class Map(val latitude: Double, val longitude: Double) : FlightPointingClick

    data class Scene(val x: Double, val y: Double, val frame: FlightPickingFrame?) :
        FlightPointingClick

    data class Photo(val x: Double, val y: Double, val projection: FlightPhotoProjection?) :
        FlightPointingClick

    fun freeze(context: FlightPointingContext): FlightPointingClick =
        when (this) {
            is Map -> this
            is Scene -> if (frame == null) copy(frame = context.frame) else this
            is Photo -> if (projection == null) copy(projection = context.photo) else this
        }

    fun ray(context: FlightPointingContext): FlightPickingRay? =
        when (this) {
            is Map -> context.terrain.groundRay(latitude, longitude)
            is Scene -> frame?.rayFor(context.terrain, x, y)
            is Photo -> projection?.let { FlightPhotoPicker(it, context.terrain).rayAt(x, y) }
        }
}

/**
 * Only the latest click survives; unavailable geometry is retried automatically when it changes.
 */
internal class FlightPointingSelection(
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val mutableState = MutableStateFlow(FlightPointingSelectionState())
    val state = mutableState.asStateFlow()
    private var context: FlightPointingContext? = null
    private var latestClick: FlightPointingClick? = null
    private var request: Job? = null
    private var revision = 0L

    fun update(scope: CoroutineScope, next: FlightPointingContext?, preparing: Boolean) {
        val previous = context
        context = next
        if (next == null || !next.terrain.hasTerrain) {
            cancelRequest()
            publish(if (preparing) FlightPointingPhase.PREPARING else FlightPointingPhase.LOADING)
            return
        }
        val changed =
            previous == null ||
                previous.terrain.geometryKey != next.terrain.geometryKey ||
                previous.terrain.scene.missingTiles != next.terrain.scene.missingTiles ||
                previous.terrain.scene.centerLatitude != next.terrain.scene.centerLatitude ||
                previous.terrain.scene.centerLongitude != next.terrain.scene.centerLongitude ||
                previous.terrain.scene.radiusKm != next.terrain.scene.radiusKm
        if (changed) {
            cancelRequest()
            if (latestClick != null) resolve(scope) else publish(FlightPointingPhase.READY)
        } else if (
            mutableState.value.phase == FlightPointingPhase.NO_INTERSECTION &&
                (previous.photo != next.photo ||
                    (previous.frame != null &&
                        next.frame != null &&
                        (!previous.frame.matrix.contentEquals(next.frame.matrix) ||
                            !previous.frame.eye.contentEquals(next.frame.eye))))
        ) {
            latestClick = null
            publish(FlightPointingPhase.READY)
        }
    }

    fun select(scope: CoroutineScope, click: FlightPointingClick) {
        cancelRequest()
        latestClick = click
        val current = context
        if (current == null || !current.terrain.hasTerrain) {
            publish(
                mutableState.value.phase.takeIf { it == FlightPointingPhase.PREPARING }
                    ?: FlightPointingPhase.LOADING
            )
        } else resolve(scope)
    }

    private fun resolve(scope: CoroutineScope) {
        val current = context ?: return
        val click = latestClick?.freeze(current) ?: return
        latestClick = click
        val token = ++revision
        publish(FlightPointingPhase.SELECTING)
        request =
            scope.launch {
                val result =
                    runInterruptible(queryDispatcher) {
                        click.ray(current)?.let(current.terrain::pick)
                            ?: FlightTerrainPickResult.Miss
                    }
                // A replaced click, disabled mode or newer scene can never publish an obsolete
                // result.
                if (token != revision) return@launch
                when (result) {
                    is FlightTerrainPickResult.Hit -> {
                        latestClick = null
                        mutableState.value =
                            FlightPointingSelectionState(result.point, FlightPointingPhase.READY)
                    }
                    FlightTerrainPickResult.WaitingForGround ->
                        publish(FlightPointingPhase.WAITING_GROUND)
                    FlightTerrainPickResult.Miss -> publish(FlightPointingPhase.NO_INTERSECTION)
                }
            }
    }

    private fun publish(phase: FlightPointingPhase) {
        mutableState.value =
            mutableState.value.copy(phase = phase, pendingClick = latestClick != null)
    }

    private fun cancelRequest() {
        revision++
        request?.cancel()
        request = null
    }

    fun reset() {
        cancelRequest()
        context = null
        latestClick = null
        publish(FlightPointingPhase.LOADING)
    }
}
