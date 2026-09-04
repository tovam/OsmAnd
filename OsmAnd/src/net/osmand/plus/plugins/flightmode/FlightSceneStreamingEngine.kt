package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Every visual consumer contributes to one shared scene instead of owning a tile cache. */
enum class FlightSceneConsumer {
	MAP,
	WINDOW,
	WINDOW_MINI_MAP,
	BACKGROUND
}

enum class FlightSceneMotion {
	LIVE,
	PLAYING,
	MANUAL
}

enum class FlightSceneDemandReason {
	AIRCRAFT,
	CAMERA,
	CONFIGURATION,
	PAGE,
	RETRY
}

data class FlightSceneStreamingConfiguration(
	val radiusKm: Int,
	val satelliteQuality: FlightSatelliteQuality,
	val terrainFineZoom: Int,
	val terrainMiddleZoom: Int,
	val includeNativeMap: Boolean
)

/**
 * Declarative input to the streaming engine. UI code describes what may be observed;
 * it never starts downloads, chooses LODs or owns cancellation rules itself.
 */
data class FlightSceneDemand(
	val aircraft: FlightSample,
	val detailFocus: FlightTerrainDetailFocus?,
	val configuration: FlightSceneStreamingConfiguration,
	val consumers: Set<FlightSceneConsumer>,
	val motion: FlightSceneMotion
)

/** Central home for the invariants that must apply identically to every visual consumer. */
object FlightSceneStreamingPolicy {
	const val AIRCRAFT_RETARGET_DISTANCE_KM = 8.0
	const val DETAIL_FOCUS_MINIMUM_CHANGE_KM = 1.5
	const val DETAIL_FOCUS_FOLLOW_DISTANCE_KM = 8.0
	const val MAXIMUM_GAZE_FOCUS_DISTANCE_KM = 100.0
	const val NEARBY_RESOURCE_RETENTION_KM = 50.0
	const val MANUAL_MOVEMENT_SETTLE_MILLIS = 700L
	const val CAMERA_MOVEMENT_SETTLE_MILLIS = 1_500L

	fun configurationMatches(
		scene: FlightTerrainScene?,
		configuration: FlightSceneStreamingConfiguration
	): Boolean = scene != null &&
		scene.radiusKm == configuration.radiusKm &&
		scene.satelliteQuality == configuration.satelliteQuality &&
		scene.terrainFineZoom == configuration.terrainFineZoom &&
		scene.terrainMiddleZoom == configuration.terrainMiddleZoom &&
		(!configuration.includeNativeMap || scene.nativeMapRequested)

	fun configurationMatches(
		first: FlightSceneStreamingConfiguration?,
		second: FlightSceneStreamingConfiguration
	): Boolean = first == second

	fun focusChanged(first: FlightTerrainDetailFocus?, second: FlightTerrainDetailFocus?): Boolean {
		if (first == null || second == null) return first != second
		return FlightTerrainTilePlanner.distanceKm(
			first.latitude,
			first.longitude,
			second.latitude,
			second.longitude
		) >= DETAIL_FOCUS_MINIMUM_CHANGE_KM
	}

	fun aircraftDistanceKm(first: FlightSample, second: FlightSample): Double =
		FlightTerrainTilePlanner.distanceKm(
			first.latitude,
			first.longitude,
			second.latitude,
			second.longitude
		)
}

/**
 * Persistent desired-state reconciler for flight terrain and imagery.
 *
 * The repository remains the low-level tile/mesh provider. This engine is the only component
 * allowed to decide when a scene request starts, settles, supersedes another request or fails.
 * Its resident scene is retained across requests, so a new demand refines what is visible rather
 * than clearing it while replacement resources are pending.
 */
class FlightSceneStreamingEngine(
	private val scope: CoroutineScope,
	private val repository: FlightTerrainRepository,
	private val initialScene: () -> FlightTerrainScene?,
	private val publishScene: (FlightTerrainScene) -> Unit,
	private val publishStatus: (FlightTerrainStatus) -> Unit
) {
	private var desiredDemand: FlightSceneDemand? = null
	private var activeDemand: FlightSceneDemand? = null
	private var residentScene: FlightTerrainScene? = null
	private var activeJob: Job? = null
	private var settleJob: Job? = null
	private var backgroundJob: Job? = null
	private var backgroundWork: BackgroundWork? = null
	private var settleReason: FlightSceneDemandReason? = null
	private var latestStatus = FlightTerrainStatus()
	private var generation = 0L
	private var backgroundGeneration = 0L
	private var backgroundExecutionGeneration = 0L
	private var closed = false

	val isBusy: Boolean
		get() = activeJob?.isActive == true || settleJob?.isActive == true || backgroundJob?.isActive == true

	val hasBackgroundWork: Boolean
		get() = backgroundWork != null || backgroundJob?.isActive == true

	fun submit(demand: FlightSceneDemand, reason: FlightSceneDemandReason = FlightSceneDemandReason.AIRCRAFT) {
		if (closed) return
		val previousDesired = desiredDemand
		desiredDemand = demand
		val scene = residentScene ?: initialScene()
		val runningDemand = activeDemand.takeIf { activeJob?.isActive == true }
		val referenceDemand = runningDemand ?: scene?.let { resident ->
			FlightSceneDemand(
				aircraft = demand.aircraft.copy(
					latitude = resident.centerLatitude,
					longitude = resident.centerLongitude
				),
				detailFocus = resident.detailFocus,
				configuration = FlightSceneStreamingConfiguration(
					radiusKm = resident.radiusKm,
					satelliteQuality = resident.satelliteQuality,
					terrainFineZoom = resident.terrainFineZoom,
					terrainMiddleZoom = resident.terrainMiddleZoom,
					includeNativeMap = resident.nativeMapRequested
				),
				consumers = demand.consumers,
				motion = demand.motion
			)
		}
		val configurationReady = FlightSceneStreamingPolicy.configurationMatches(scene, demand.configuration)
		val configurationRunning = FlightSceneStreamingPolicy.configurationMatches(
			runningDemand?.configuration,
			demand.configuration
		)
		val configurationNeedsWork = !configurationReady && !configurationRunning
		val aircraftNeedsWork = referenceDemand == null ||
			FlightSceneStreamingPolicy.aircraftDistanceKm(
				referenceDemand.aircraft,
				demand.aircraft
			) >= FlightSceneStreamingPolicy.AIRCRAFT_RETARGET_DISTANCE_KM
		val focusNeedsWork = referenceDemand == null ||
			FlightSceneStreamingPolicy.focusChanged(referenceDemand.detailFocus, demand.detailFocus)
		val forced = reason == FlightSceneDemandReason.CONFIGURATION || reason == FlightSceneDemandReason.RETRY

		if (!forced && !configurationNeedsWork && !aircraftNeedsWork && !focusNeedsWork) return
		if (reason == FlightSceneDemandReason.PAGE && !configurationNeedsWork && !aircraftNeedsWork) return

		when {
			forced || scene == null || configurationNeedsWork -> startDesiredDemand()
			aircraftNeedsWork && (demand.motion == FlightSceneMotion.LIVE ||
				demand.motion == FlightSceneMotion.PLAYING) -> startDesiredDemand()
			aircraftNeedsWork -> scheduleDesiredDemand(
				delayMillis = FlightSceneStreamingPolicy.MANUAL_MOVEMENT_SETTLE_MILLIS,
				reason = FlightSceneDemandReason.AIRCRAFT,
				targetChanged = previousDesired == null ||
					previousDesired.aircraft.latitude != demand.aircraft.latitude ||
					previousDesired.aircraft.longitude != demand.aircraft.longitude
			)
			focusNeedsWork -> scheduleDesiredDemand(
				delayMillis = FlightSceneStreamingPolicy.CAMERA_MOVEMENT_SETTLE_MILLIS,
				reason = FlightSceneDemandReason.CAMERA,
				targetChanged = FlightSceneStreamingPolicy.focusChanged(
					previousDesired?.detailFocus,
					demand.detailFocus
				)
			)
		}
	}

	fun retry(demand: FlightSceneDemand) {
		submit(demand, FlightSceneDemandReason.RETRY)
	}

	/**
	 * Registers low-priority corridor work. It runs only while the visible scene is stable,
	 * is pre-empted by any real aircraft/camera demand, then resumes automatically.
	 */
	fun scheduleBackgroundWork(delayMillis: Long = BACKGROUND_START_DELAY_MILLIS, block: suspend () -> Unit) {
		if (closed) return
		backgroundExecutionGeneration++
		backgroundJob?.cancel()
		backgroundJob = null
		backgroundWork = BackgroundWork(++backgroundGeneration, delayMillis, block)
		startBackgroundWorkIfIdle()
	}

	fun reset() {
		generation++
		backgroundGeneration++
		backgroundExecutionGeneration++
		activeJob?.cancel()
		settleJob?.cancel()
		backgroundJob?.cancel()
		activeJob = null
		settleJob = null
		backgroundJob = null
		backgroundWork = null
		settleReason = null
		desiredDemand = null
		activeDemand = null
		residentScene = initialScene()
		latestStatus = FlightTerrainStatus()
	}

	fun close() {
		closed = true
		reset()
	}

	private fun scheduleDesiredDemand(
		delayMillis: Long,
		reason: FlightSceneDemandReason,
		targetChanged: Boolean
	) {
		if (settleJob?.isActive == true && settleReason == reason && !targetChanged) return
		preemptBackgroundWork()
		settleJob?.cancel()
		settleReason = reason
		settleJob = scope.launch {
			delay(delayMillis)
			settleJob = null
			settleReason = null
			startDesiredDemand()
		}
	}

	private fun startDesiredDemand() {
		val demand = desiredDemand ?: return
		preemptBackgroundWork()
		settleJob?.cancel()
		settleJob = null
		settleReason = null
		val previousScene = residentScene ?: initialScene()
		val requestGeneration = ++generation
		activeDemand = demand
		activeJob?.cancel()
		val job = scope.launch(start = CoroutineStart.LAZY) {
			try {
				val scene = repository.loadScene(
					latitude = demand.aircraft.latitude,
					longitude = demand.aircraft.longitude,
					radiusKm = demand.configuration.radiusKm,
					satelliteQuality = demand.configuration.satelliteQuality,
					terrainFineZoom = demand.configuration.terrainFineZoom,
					terrainMiddleZoom = demand.configuration.terrainMiddleZoom,
					detailFocus = demand.detailFocus,
					includeNativeMap = demand.configuration.includeNativeMap,
					previousScene = previousScene,
					onScene = { partialScene ->
						if (requestGeneration == generation) {
							residentScene = partialScene
							publishScene(partialScene)
						}
					}
				) { status ->
					if (requestGeneration == generation) {
						latestStatus = status
						publishStatus(status)
					}
				}
				if (requestGeneration != generation) return@launch
				residentScene = scene
				publishScene(scene)
				latestStatus = latestStatus.copy(
					phase = FlightTerrainPhase.READY,
					bytesPerSecond = 0L,
					textureQueue = 0,
					message = readyMessage(scene, demand.configuration.includeNativeMap)
				)
				publishStatus(latestStatus)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				if (requestGeneration != generation) return@launch
				latestStatus = latestStatus.copy(
					phase = FlightTerrainPhase.ERROR,
					message = error.message ?: "Chargement du relief impossible"
				)
				publishStatus(latestStatus)
			} finally {
				if (requestGeneration == generation) {
					activeJob = null
					activeDemand = null
					startBackgroundWorkIfIdle()
				}
			}
		}
		activeJob = job
		job.start()
	}

	private fun preemptBackgroundWork() {
		backgroundExecutionGeneration++
		backgroundJob?.cancel()
		backgroundJob = null
	}

	private fun startBackgroundWorkIfIdle() {
		if (closed || activeJob?.isActive == true || settleJob?.isActive == true ||
			backgroundJob?.isActive == true
		) return
		val work = backgroundWork ?: return
		val executionGeneration = ++backgroundExecutionGeneration
		val job = scope.launch(start = CoroutineStart.LAZY) {
			try {
				delay(work.delayMillis)
				if (activeJob?.isActive == true || settleJob?.isActive == true) return@launch
				work.block()
				if (backgroundWork?.generation == work.generation) backgroundWork = null
			} catch (error: CancellationException) {
				throw error
			} finally {
				if (backgroundExecutionGeneration == executionGeneration) backgroundJob = null
			}
		}
		backgroundJob = job
		job.start()
	}

	private fun readyMessage(scene: FlightTerrainScene, includeNativeMap: Boolean): String = when {
		scene.missingTiles > 0 && includeNativeMap ->
			"Relief partiel : ${scene.missingTiles} tuiles manquantes · carte OsmAnd ${scene.nativeMapTiles}/${scene.loadedTiles}"
		scene.missingTiles > 0 -> "Relief partiel : ${scene.missingTiles} tuiles manquantes"
		includeNativeMap && scene.nativeMapTiles < scene.loadedTiles ->
			"Relief prêt · carte OsmAnd ${scene.nativeMapTiles}/${scene.loadedTiles}"
		includeNativeMap && scene.satelliteTiles < scene.loadedTiles ->
			"Relief + carte OsmAnd prêts · satellite ${scene.satelliteTiles}/${scene.loadedTiles}"
		scene.satelliteTiles < scene.loadedTiles ->
			"Relief prêt · satellite ${scene.satelliteTiles}/${scene.loadedTiles}"
		includeNativeMap -> "Relief + satellite + carte OsmAnd prêts"
		else -> "Relief + satellite prêts"
	}

	private data class BackgroundWork(
		val generation: Long,
		val delayMillis: Long,
		val block: suspend () -> Unit
	)

	private companion object {
		const val BACKGROUND_START_DELAY_MILLIS = 500L
	}
}
