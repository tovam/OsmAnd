package net.osmand.plus.plugins.flightmode

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.Location
import net.osmand.plus.OsmAndLocationProvider.GPSInfo
import net.osmand.plus.OsmandApplication
import net.osmand.shared.gpx.GpxFile

class FlightModeViewModel(application: Application) : AndroidViewModel(application) {

	private val app = application as OsmandApplication
	private val windowPlacementStore = FlightWindowPlacementStore(application)
	private val terrainRepository = FlightTerrainRepository(app)
	private val citySearch = FlightCitySearch(app)
	private var replayEngine: FlightReplayEngine? = null
	private var terrainJob: Job? = null
	private var citySearchJob: Job? = null
	private var requestedTerrainCenter: Pair<Double, Double>? = null

	var uiState by mutableStateOf(
		FlightUiState(
			windowPlacement = windowPlacementStore.load()
		)
	)
		private set

	fun showPage(page: FlightPage) {
		uiState = uiState.copy(page = page)
	}

	fun updateStop(index: Int, name: String) {
		val stops = uiState.plan.stops.toMutableList()
		if (index !in stops.indices) return
		if (stops[index].name == name) return
		stops[index] = FlightStop(name = name)
		updatePlan(uiState.plan.copy(stops = stops))
		searchCitiesForStop(index, name)
	}

	fun selectCity(index: Int, suggestion: FlightCitySuggestion) {
		val stops = uiState.plan.stops.toMutableList()
		if (index !in stops.indices) return
		citySearchJob?.cancel()
		stops[index] = FlightStop(
			name = suggestion.name,
			latitude = suggestion.latitude,
			longitude = suggestion.longitude
		)
		val plan = uiState.plan.copy(stops = stops)
		uiState = uiState.copy(
			plan = plan,
			profile = FlightProfilePlanner.build(plan),
			citySearchStopIndex = null,
			citySuggestions = emptyList(),
			citySearchLoading = false
		)
	}

	fun dismissCitySuggestions(index: Int) {
		if (uiState.citySearchStopIndex != index) return
		citySearchJob?.cancel()
		uiState = uiState.copy(
			citySearchStopIndex = null,
			citySuggestions = emptyList(),
			citySearchLoading = false
		)
	}

	fun addStop() {
		val stops = uiState.plan.stops.toMutableList()
		stops += FlightStop("")
		updatePlan(uiState.plan.copy(stops = stops))
	}

	fun removeStop(index: Int) {
		if (uiState.plan.stops.size <= 2 || index !in uiState.plan.stops.indices) return
		citySearchJob?.cancel()
		val stops = uiState.plan.stops.toMutableList().apply { removeAt(index) }
		val plan = uiState.plan.copy(stops = stops)
		uiState = uiState.copy(
			plan = plan,
			profile = FlightProfilePlanner.build(plan),
			citySearchStopIndex = null,
			citySuggestions = emptyList(),
			citySearchLoading = false
		)
	}

	fun updatePlan(plan: FlightPlan) {
		uiState = uiState.copy(plan = plan, profile = FlightProfilePlanner.build(plan))
	}

	private fun searchCitiesForStop(index: Int, name: String) {
		citySearchJob?.cancel()
		val query = name.trim()
		if (query.length < MINIMUM_CITY_QUERY_LENGTH) {
			uiState = uiState.copy(
				citySearchStopIndex = null,
				citySuggestions = emptyList(),
				citySearchLoading = false
			)
			return
		}

		uiState = uiState.copy(
			citySearchStopIndex = index,
			citySuggestions = emptyList(),
			citySearchLoading = true
		)
		citySearchJob = viewModelScope.launch {
			delay(CITY_SEARCH_DEBOUNCE_MILLIS)
			val runningJob = coroutineContext[Job]
			val suggestions = withContext(Dispatchers.IO) {
				citySearch.search(query) { runningJob?.isActive != true }
			}
			val currentStop = uiState.plan.stops.getOrNull(index)
			if (uiState.citySearchStopIndex == index && currentStop?.name == name) {
				uiState = uiState.copy(
					citySuggestions = suggestions,
					citySearchLoading = false
				)
			}
		}
	}

	fun startLive() {
		replayEngine = null
		uiState = uiState.copy(
			page = FlightPage.MAP,
			sessionMode = FlightSessionMode.LIVE,
			mapFollowing = true,
			trip = null,
			replayPlaying = false,
			replayProgress = 0f,
			tripLoadError = null,
			flightSpans = emptyList(),
			pendingFlightStartProgress = null
		)
	}

	fun loadTrip(uri: Uri) {
		loadTrip { FlightTripLoader.load(app, uri) }
	}

	fun loadTrip(gpxFile: GpxFile) {
		loadTrip { FlightTripLoader.load(gpxFile) }
	}

	private fun loadTrip(loader: () -> FlightTrip) {
		uiState = uiState.copy(loadingTrip = true, tripLoadError = null)
		viewModelScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { loader() }
			}
			result.onSuccess { trip ->
				replayEngine = FlightReplayEngine(trip)
				val firstSnapshot = replayEngine?.snapshotAt(0f)
				uiState = uiState.copy(
					page = FlightPage.MAP,
					sessionMode = FlightSessionMode.REPLAY,
					mapFollowing = true,
					trip = trip,
					profile = FlightProfilePlanner.fromTrip(trip),
					snapshot = firstSnapshot,
					replayProgress = 0f,
					replayPlaying = false,
					flightSpans = emptyList(),
					pendingFlightStartProgress = null,
					loadingTrip = false,
					tripLoadError = null
				)
				firstSnapshot?.sample?.let(::requestTerrain)
			}.onFailure { error ->
				uiState = uiState.copy(
					loadingTrip = false,
					tripLoadError = error.message ?: "Impossible de charger ce trajet"
				)
			}
		}
	}

	fun clearTripLoadError() {
		uiState = uiState.copy(tripLoadError = null)
	}

	fun seekReplay(progress: Float) {
		val safeProgress = progress.coerceIn(0f, 1f)
		val snapshot = replayEngine?.snapshotAt(safeProgress) ?: return
		uiState = uiState.copy(replayProgress = safeProgress, snapshot = snapshot)
		requestTerrain(snapshot.sample)
	}

	fun toggleReplayPlaying() {
		if (uiState.sessionMode != FlightSessionMode.REPLAY) return
		uiState = uiState.copy(replayPlaying = !uiState.replayPlaying)
	}

	fun setReplaySpeed(speed: Float) {
		uiState = uiState.copy(replaySpeed = speed.coerceIn(0.25f, 16f))
	}

	fun advanceReplay(realDeltaMillis: Long) {
		val trip = uiState.trip ?: return
		if (!uiState.replayPlaying) return
		val replayDuration = trip.durationMillis?.coerceAtLeast(1L)
			?: (trip.samples.size * 1_000L).coerceAtLeast(1L)
		val delta = realDeltaMillis.toFloat() * uiState.replaySpeed / replayDuration
		val next = uiState.replayProgress + delta
		if (next >= 1f) {
			seekReplay(1f)
			uiState = uiState.copy(replayPlaying = false)
		} else {
			seekReplay(next)
		}
	}

	fun updateLiveLocation(location: Location, gpsInfo: GPSInfo) {
		if (uiState.sessionMode != FlightSessionMode.LIVE) return
		val sample = FlightSample(
			index = (uiState.snapshot?.sample?.index ?: -1) + 1,
			legIndex = 0,
			timestampMillis = location.time,
			latitude = location.latitude,
			longitude = location.longitude,
			altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
			speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
			bearingDegrees = location.bearing.takeIf { location.hasBearing() },
			horizontalAccuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
			satellitesUsed = gpsInfo.usedSatellites,
			satellitesFound = gpsInfo.foundSatellites
		)
		uiState = uiState.copy(snapshot = FlightSnapshot(sample, progress = 0f))
		requestTerrain(sample)
	}

	fun preloadTerrain() {
		terrainJob?.cancel()
		terrainJob = viewModelScope.launch {
			try {
				val finalStatus = terrainRepository.preloadCorridor(uiState.plan) { status ->
					uiState = uiState.copy(terrainStatus = status)
				}
				uiState = uiState.copy(terrainStatus = finalStatus)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				uiState = uiState.copy(
					terrainStatus = uiState.terrainStatus.copy(
						phase = FlightTerrainPhase.ERROR,
						message = error.message ?: "Préchargement du relief impossible"
					)
				)
			}
		}
	}

	fun retryTerrain() {
		val sample = uiState.snapshot?.sample ?: previewFlightSample() ?: return
		requestedTerrainCenter = null
		requestTerrain(sample, force = true)
	}

	fun setTerrainRendererError(message: String) {
		uiState = uiState.copy(
			terrainStatus = uiState.terrainStatus.copy(
				phase = FlightTerrainPhase.ERROR,
				message = message
			)
		)
	}

	private fun requestTerrain(sample: FlightSample, force: Boolean = false) {
		val scene = uiState.terrainScene
		if (!force && scene != null && scene.radiusKm == uiState.plan.terrainCorridorKm) {
			val distance = FlightTerrainTilePlanner.distanceKm(
				scene.centerLatitude,
				scene.centerLongitude,
				sample.latitude,
				sample.longitude
			)
			if (distance <= (scene.radiusKm * TERRAIN_RELOAD_RADIUS_FRACTION).coerceAtLeast(MINIMUM_RELOAD_DISTANCE_KM)) {
				return
			}
		}
		val requested = requestedTerrainCenter
		if (!force && requested != null) {
			val distance = FlightTerrainTilePlanner.distanceKm(
				requested.first,
				requested.second,
				sample.latitude,
				sample.longitude
			)
			if (distance < MINIMUM_RELOAD_DISTANCE_KM) return
		}
		requestedTerrainCenter = sample.latitude to sample.longitude
		terrainJob?.cancel()
		terrainJob = viewModelScope.launch {
			try {
				val terrainScene = terrainRepository.loadScene(
					latitude = sample.latitude,
					longitude = sample.longitude,
					radiusKm = uiState.plan.terrainCorridorKm
				) { status ->
					uiState = uiState.copy(terrainStatus = status)
				}
				uiState = uiState.copy(
					terrainScene = terrainScene,
					terrainStatus = uiState.terrainStatus.copy(
						phase = FlightTerrainPhase.READY,
						message = when {
							terrainScene.missingTiles > 0 ->
								"Relief partiel : ${terrainScene.missingTiles} tuiles manquantes"
							terrainScene.satelliteTiles < terrainScene.loadedTiles ->
								"Relief prêt · satellite ${terrainScene.satelliteTiles}/${terrainScene.loadedTiles}"
							else -> "Relief + satellite prêts"
						}
					)
				)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				requestedTerrainCenter = null
				uiState = uiState.copy(
					terrainStatus = uiState.terrainStatus.copy(
						phase = FlightTerrainPhase.ERROR,
						message = error.message ?: "Chargement du relief impossible"
					)
				)
			}
		}
	}

	private fun previewFlightSample(): FlightSample? {
		val from = uiState.plan.stops.firstOrNull() ?: return null
		val to = uiState.plan.stops.getOrNull(1)
		val latitude = from.latitude ?: return null
		val longitude = from.longitude ?: return null
		return FlightSample(
			index = 0,
			legIndex = 0,
			timestampMillis = System.currentTimeMillis(),
			latitude = latitude,
			longitude = longitude,
			altitudeMeters = 10_000.0,
			speedMetersPerSecond = 230f,
			bearingDegrees = if (to?.latitude != null && to.longitude != null) 90f else null,
			horizontalAccuracyMeters = null
		)
	}

	fun setWindowAltitudeOverride(altitudeMeters: Float?) {
		uiState = uiState.copy(
			windowAltitudeOverrideMeters = altitudeMeters?.coerceIn(
				MINIMUM_WINDOW_ALTITUDE_METERS,
				MAXIMUM_WINDOW_ALTITUDE_METERS
			)
		)
	}

	fun moveWindow(forwardDeltaMeters: Float, verticalDeltaMeters: Float) {
		val current = uiState.windowPlacement
		setWindowPlacement(
			current.copy(
				forwardOffsetMeters = current.forwardOffsetMeters + forwardDeltaMeters,
				verticalOffsetMeters = current.verticalOffsetMeters + verticalDeltaMeters
			),
			persist = false
		)
	}

	fun saveWindowPlacement() {
		windowPlacementStore.save(uiState.windowPlacement)
	}

	fun setWindowSide(side: FlightCabinSide) {
		setWindowPlacement(uiState.windowPlacement.copy(side = side))
		recenterWindowLook()
	}

	fun moveWindowLook(yawDeltaDegrees: Float, pitchDeltaDegrees: Float) {
		val current = uiState.windowLook
		uiState = uiState.copy(
			windowLook = current.copy(
				yawDegrees = current.yawDegrees + yawDeltaDegrees,
				pitchDegrees = current.pitchDegrees + pitchDeltaDegrees
			).clamped()
		)
	}

	fun recenterWindowLook() {
		if (uiState.windowLook != FlightWindowLook()) {
			uiState = uiState.copy(windowLook = FlightWindowLook())
		}
	}

	fun setWindowZoom(zoom: Float) {
		setWindowPlacement(uiState.windowPlacement.copy(zoom = zoom), persist = false)
	}

	fun changeWindowZoom(factor: Float) {
		setWindowZoom(uiState.windowPlacement.zoom * factor.coerceIn(0.75f, 1.35f))
	}

	fun setCabinTransparent(transparent: Boolean) {
		setWindowPlacement(uiState.windowPlacement.copy(cabinTransparent = transparent))
	}

	private fun setWindowPlacement(placement: FlightWindowPlacement, persist: Boolean = true) {
		val safePlacement = placement.clamped()
		if (persist) windowPlacementStore.save(safePlacement)
		uiState = uiState.copy(windowPlacement = safePlacement)
	}

	fun setMapFollowing(following: Boolean) {
		if (uiState.mapFollowing != following) {
			uiState = uiState.copy(mapFollowing = following)
		}
	}

	fun setShowTrackPoints(show: Boolean) {
		uiState = uiState.copy(showTrackPoints = show)
	}

	fun markFlightStart() {
		if (uiState.sessionMode != FlightSessionMode.REPLAY) return
		uiState = uiState.copy(pendingFlightStartProgress = uiState.replayProgress)
	}

	fun markFlightEnd() {
		val start = uiState.pendingFlightStartProgress ?: return
		val span = FlightSpan(start, uiState.replayProgress).normalized()
		if (span.endProgress - span.startProgress < MINIMUM_FLIGHT_SPAN_PROGRESS) return
		uiState = uiState.copy(
			flightSpans = (uiState.flightSpans + span).sortedBy { it.startProgress },
			pendingFlightStartProgress = null
		)
	}

	fun cancelFlightStart() {
		uiState = uiState.copy(pendingFlightStartProgress = null)
	}

	fun removeFlightSpan(index: Int) {
		if (index !in uiState.flightSpans.indices) return
		uiState = uiState.copy(
			flightSpans = uiState.flightSpans.toMutableList().apply { removeAt(index) }
		)
	}

	fun setSatelliteOpacity(opacity: Float) {
		uiState = uiState.copy(satelliteOpacity = opacity.coerceIn(0f, 1f))
	}

	fun setTerrainOpacity(opacity: Float) {
		uiState = uiState.copy(terrainOpacity = opacity.coerceIn(0f, 1f))
	}

	fun setRecordingPolicy(policy: FlightRecordingPolicy) {
		uiState = uiState.copy(recordingPolicy = policy)
	}

	fun setPhotoSources(main: Boolean? = null, selfie: Boolean? = null, map: Boolean? = null, scene3d: Boolean? = null) {
		uiState = uiState.copy(
			photoMainCamera = main ?: uiState.photoMainCamera,
			photoSelfie = selfie ?: uiState.photoSelfie,
			photoMap = map ?: uiState.photoMap,
			photoScene3d = scene3d ?: uiState.photoScene3d
		)
	}

	override fun onCleared() {
		terrainJob?.cancel()
		citySearchJob?.cancel()
		super.onCleared()
	}

	companion object {
		private const val MINIMUM_CITY_QUERY_LENGTH = 2
		private const val CITY_SEARCH_DEBOUNCE_MILLIS = 180L
		private const val TERRAIN_RELOAD_RADIUS_FRACTION = 0.32
		private const val MINIMUM_RELOAD_DISTANCE_KM = 20.0
		private const val MINIMUM_WINDOW_ALTITUDE_METERS = -500f
		private const val MAXIMUM_WINDOW_ALTITUDE_METERS = 15_000f
		private const val MINIMUM_FLIGHT_SPAN_PROGRESS = 0.0005f
	}
}
