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
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class FlightModeViewModel(application: Application) : AndroidViewModel(application) {

	private val app = application as OsmandApplication
	private val windowPlacementStore = FlightWindowPlacementStore(application)
	private val terrainRepository = FlightTerrainRepository(app)
	private val citySearch = FlightCitySearch(app)
	private val journeyStore = FlightJourneyStore(application)
	private var replayEngine: FlightReplayEngine? = null
	private var terrainJob: Job? = null
	private var offlinePreloadJob: Job? = null
	private var storageJob: Job? = null
	private var citySearchJob: Job? = null
	private var photoPersistenceJob: Job? = null
	private var requestedTerrainCenter: Pair<Double, Double>? = null
	private val liveSamples = mutableListOf<FlightSample>()
	private var liveDistanceMeters = 0.0
	private var liveSequence = 0
	private var pendingCaptureFile: File? = null
	private var pendingCaptureTimestampMillis: Long = 0L
	private var pendingDuplicateTrip: FlightTrip? = null
	private var latestEnvironmentReading = FlightEnvironmentReading()

	var uiState by mutableStateOf(
		FlightUiState(
			windowPlacement = windowPlacementStore.load(),
			savedJourneys = journeyStore.list()
		)
	)
		private set

	init {
		refreshStorageUsage()
	}

	fun showPage(page: FlightPage) {
		uiState = uiState.copy(page = page)
		if (page == FlightPage.JOURNEYS) refreshStorageUsage()
	}

	fun refreshStorageUsage() {
		storageJob?.cancel()
		val journeyId = uiState.journeyId
		val photos = uiState.photos
		val offlineAssets = uiState.offlineAssets
		uiState = uiState.copy(storageUsageLoading = true)
		storageJob = viewModelScope.launch {
			val usage = withContext(Dispatchers.IO) {
				journeyStore.storageUsage(journeyId, photos, offlineAssets)
			}
			uiState = uiState.copy(storageUsage = usage, storageUsageLoading = false)
		}
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
		uiState = uiState.copy(
			plan = plan,
			profile = if (uiState.trip != null) uiState.profile else FlightProfilePlanner.build(plan),
			journeyDirty = uiState.journeyDirty || uiState.trip != null
		)
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
		offlinePreloadJob?.cancel()
		pendingDuplicateTrip = null
		replayEngine = null
		liveSamples.clear()
		liveDistanceMeters = 0.0
		liveSequence = 0
		latestEnvironmentReading = FlightEnvironmentReading()
		val journeyName = uiState.plan.stops.map { it.name.trim() }.filter { it.isNotEmpty() }
			.joinToString(" → ").ifBlank { "Nouveau journal de vol" }
		uiState = uiState.copy(
			page = FlightPage.MAP,
			sessionMode = FlightSessionMode.LIVE,
			mapFollowing = true,
			trip = null,
			replayPlaying = false,
			replayProgress = 0f,
			tripLoadError = null,
			duplicateJourneyWarning = null,
			windowPhotoOverlay = FlightWindowPhotoOverlay(),
			flightSpans = emptyList(),
			pendingFlightStartProgress = null,
			journeyId = null,
			journeyName = journeyName,
			journeyCreatedAtMillis = System.currentTimeMillis(),
			journeyDirty = true,
			photos = emptyList(),
			offlineAssets = FlightOfflineAssets(),
			offlinePreloadStatus = FlightTerrainStatus(),
			pendingPhotos = emptyList(),
			selectedPhotoId = null,
			journeyMessage = null
		)
	}

	fun loadSource(uri: Uri) {
		pendingDuplicateTrip = null
		uiState = uiState.copy(
			loadingTrip = true,
			tripLoadError = null,
			journeyMessage = null,
			duplicateJourneyWarning = null
		)
		viewModelScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) {
					if (journeyStore.isJourneyArchive(uri)) {
						LoadedSource.Journey(journeyStore.importArchive(uri))
					} else {
						LoadedSource.Trip(FlightTripLoader.load(app, uri))
					}
				}
			}
			val source = result.getOrElse { error ->
				showTripLoadFailure(error)
				return@launch
			}
			when (source) {
				is LoadedSource.Journey -> applyJourney(source.journey)
				is LoadedSource.Trip -> checkImportedTrip(source.trip)
			}
		}
	}

	fun loadTrip(uri: Uri) = loadSource(uri)

	fun loadTrip(gpxFile: GpxFile) {
		loadTrip { FlightTripLoader.load(gpxFile) }
	}

	private fun loadTrip(loader: () -> FlightTrip) {
		pendingDuplicateTrip = null
		uiState = uiState.copy(
			loadingTrip = true,
			tripLoadError = null,
			journeyMessage = null,
			duplicateJourneyWarning = null
		)
		viewModelScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { loader() }
			}
			val trip = result.getOrElse { error ->
				showTripLoadFailure(error)
				return@launch
			}
			checkImportedTrip(trip)
		}
	}

	private suspend fun checkImportedTrip(trip: FlightTrip) {
		val matchingJourney = withContext(Dispatchers.IO) { journeyStore.findMatchingJourney(trip) }
		if (matchingJourney == null) {
			applyImportedTrip(trip)
			return
		}
		pendingDuplicateTrip = trip
		uiState = uiState.copy(
			loadingTrip = false,
			duplicateJourneyWarning = matchingJourney,
			tripLoadError = null
		)
	}

	fun openDuplicateJourney() {
		val journey = uiState.duplicateJourneyWarning ?: return
		pendingDuplicateTrip = null
		uiState = uiState.copy(duplicateJourneyWarning = null)
		openJourney(journey.id)
	}

	fun continueDuplicateImport() {
		val trip = pendingDuplicateTrip ?: return
		pendingDuplicateTrip = null
		uiState = uiState.copy(duplicateJourneyWarning = null)
		applyImportedTrip(trip)
	}

	fun dismissDuplicateImport() {
		pendingDuplicateTrip = null
		uiState = uiState.copy(duplicateJourneyWarning = null)
	}

	private fun applyImportedTrip(trip: FlightTrip) {
		pendingDuplicateTrip = null
		applyReplayTrip(
			trip = trip,
			journeyId = null,
			journeyName = trip.name,
			journeyCreatedAtMillis = System.currentTimeMillis(),
			flightSpans = emptyList(),
			photos = emptyList(),
			offlineAssets = FlightOfflineAssets(),
			dirty = true,
			message = "GPX importé · enregistre-le comme Journal de vol"
		)
	}

	private fun applyJourney(journey: FlightJourney) {
		pendingDuplicateTrip = null
		uiState = uiState.copy(plan = journey.plan)
		applyReplayTrip(
			trip = journey.trip,
			journeyId = journey.id,
			journeyName = journey.name,
			journeyCreatedAtMillis = journey.createdAtMillis,
			flightSpans = journey.flightSpans,
			photos = journey.photos,
			offlineAssets = journey.offlineAssets,
			dirty = false,
			message = "Journal de vol chargé"
		)
	}

	private fun applyReplayTrip(
		trip: FlightTrip,
		journeyId: String?,
		journeyName: String,
		journeyCreatedAtMillis: Long,
		flightSpans: List<FlightSpan>,
		photos: List<FlightPhotoAttachment>,
		offlineAssets: FlightOfflineAssets,
		dirty: Boolean,
		message: String?
	) {
		// A GPX heading is optional. Resolve it once here for every replay source
		// (plain GPX, OsmAnd track or saved Flight Journal) so every screen uses
		// the direction from the current point to the next point in the same leg.
		val resolvedTrip = trip.copy(samples = FlightTrackMath.fillMissingBearings(trip.samples))
		val sortedPhotos = photos.sortedWith(PHOTO_TIME_COMPARATOR)
		replayEngine = FlightReplayEngine(resolvedTrip)
		val firstSnapshot = replayEngine?.snapshotAt(0f)
		uiState = uiState.copy(
			page = FlightPage.MAP,
			sessionMode = FlightSessionMode.REPLAY,
			mapFollowing = true,
			trip = resolvedTrip,
			profile = FlightProfilePlanner.fromTrip(resolvedTrip),
			snapshot = firstSnapshot,
			windowPhotoOverlay = FlightWindowPhotoOverlay(),
			replayProgress = 0f,
			replayPlaying = false,
			flightSpans = flightSpans,
			pendingFlightStartProgress = null,
			journeyId = journeyId,
			journeyName = journeyName,
			journeyCreatedAtMillis = journeyCreatedAtMillis,
			journeyDirty = dirty,
			photos = sortedPhotos,
			offlineAssets = offlineAssets,
			offlinePreloadStatus = FlightTerrainStatus(),
			pendingPhotos = emptyList(),
			selectedPhotoId = sortedPhotos.firstOrNull()?.id,
			journeyMessage = message,
			loadingTrip = false,
			tripLoadError = null,
			duplicateJourneyWarning = null,
			savedJourneys = journeyStore.list()
		)
		firstSnapshot?.sample?.let(::requestTerrain)
		if (hasOfflineCorridorSource()) scheduleAutomaticOfflinePreload()
	}

	private fun showTripLoadFailure(error: Throwable) {
		uiState = uiState.copy(
			loadingTrip = false,
			tripLoadError = error.message ?: "Impossible de charger ce trajet"
		)
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
		val previous = uiState.snapshot?.sample
		val timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
		val rawSample = FlightSample(
			index = liveSequence++,
			legIndex = 0,
			timestampMillis = timestamp,
			latitude = location.latitude,
			longitude = location.longitude,
			altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
			speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
			bearingDegrees = location.bearing.takeIf { location.hasBearing() },
			horizontalAccuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
			satellitesUsed = gpsInfo.usedSatellites,
			satellitesFound = gpsInfo.foundSatellites,
			soundDb = latestEnvironmentReading.soundDb,
			soundSpectrum = latestEnvironmentReading.soundSpectrum,
			vibrationHz = latestEnvironmentReading.vibrationHz
		)
		val sample = if (rawSample.bearingDegrees == null && previous != null) {
			rawSample.copy(bearingDegrees = FlightTrackMath.bearingBetween(previous, rawSample))
		} else rawSample
		if (shouldRecordLiveSample(sample)) appendLiveSample(sample)
		uiState = uiState.copy(snapshot = FlightSnapshot(sample, progress = 0f))
		requestTerrain(sample)
		if (uiState.offlinePreloadStatus.phase == FlightTerrainPhase.IDLE &&
			offlinePreloadJob?.isActive != true && hasOfflineCorridorSource()
		) {
			scheduleAutomaticOfflinePreload()
		}
	}

	fun updateEnvironment(reading: FlightEnvironmentReading) {
		if (uiState.sessionMode != FlightSessionMode.LIVE) return
		latestEnvironmentReading = reading
		val snapshot = uiState.snapshot ?: return
		uiState = uiState.copy(
			snapshot = snapshot.copy(
				sample = snapshot.sample.copy(
					soundDb = reading.soundDb,
					soundSpectrum = reading.soundSpectrum,
					vibrationHz = reading.vibrationHz
				)
			)
		)
	}

	private fun shouldRecordLiveSample(sample: FlightSample): Boolean {
		val previous = liveSamples.lastOrNull() ?: return true
		val deltaSeconds = ((sample.timestampMillis - previous.timestampMillis).coerceAtLeast(0L) / 1_000f)
		val bearingDelta = if (sample.bearingDegrees != null && previous.bearingDegrees != null) {
			angularDifference(sample.bearingDegrees, previous.bearingDegrees)
		} else 0f
		val turnRate = if (deltaSeconds > 0f) bearingDelta / deltaSeconds else 0f
		val interval = uiState.recordingPolicy.intervalSeconds(
			speedMetersPerSecond = sample.speedMetersPerSecond ?: 0f,
			turnRateDegreesPerSecond = turnRate
		)
		return deltaSeconds >= interval
	}

	private fun appendLiveSample(sample: FlightSample) {
		val recorded = sample.copy(index = liveSamples.size)
		liveSamples.lastOrNull()?.let { previous ->
			val result = FloatArray(1)
			Location.distanceBetween(previous.latitude, previous.longitude, recorded.latitude, recorded.longitude, result)
			liveDistanceMeters += result[0]
		}
		liveSamples += recorded
		publishLiveTrip(recorded)
	}

	private fun publishLiveTrip(lastSample: FlightSample) {
		val first = liveSamples.first()
		val trip = FlightTrip(
			name = uiState.journeyName.ifBlank { "Journal de vol" },
			samples = liveSamples.toList(),
			legs = listOf(
				FlightLeg(
					index = 0,
					name = "",
					startSampleIndex = 0,
					endSampleIndex = liveSamples.lastIndex,
					distanceMeters = liveDistanceMeters,
					startTimeMillis = first.timestampMillis,
					endTimeMillis = lastSample.timestampMillis
				)
			),
			hasUsableTimestamps = liveSamples.size > 1 && lastSample.timestampMillis > first.timestampMillis,
			totalDistanceMeters = liveDistanceMeters,
			sourceDescription = "Enregistrement Suivi de vol"
		)
		uiState = uiState.copy(
			trip = trip,
			profile = FlightProfilePlanner.fromTrip(trip),
			journeyDirty = true
		)
	}

	private fun angularDifference(first: Float, second: Float): Float {
		var difference = abs(first - second) % 360f
		if (difference > 180f) difference = 360f - difference
		return difference
	}

	private fun scheduleAutomaticOfflinePreload() {
		offlinePreloadJob?.cancel()
		val sceneJob = terrainJob
			offlinePreloadJob = viewModelScope.launch {
				// Let relief and the visible Standard base win first. The complete corridor may
				// then download while detailed rings continue at their smaller worker count.
				while (sceneJob?.isActive == true) {
					val status = uiState.terrainStatus
					val reliefReady = status.requestedTiles > 0 &&
						status.availableTiles + status.failedTiles >= status.requestedTiles
					val standardBaseAttempted = status.availableTiles > 0 &&
						status.satelliteTiles + status.satelliteFailedTiles >= status.availableTiles
					if (reliefReady && standardBaseAttempted) break
					delay(250)
				}
			delay(500)
			runOfflinePreload()
		}
	}

	private fun hasOfflineCorridorSource(): Boolean =
		uiState.trip?.samples?.size?.let { it >= 2 } == true ||
			uiState.plan.stops.count { it.latitude != null && it.longitude != null } >= 2

	private suspend fun runOfflinePreload() {
		try {
			val plan = uiState.plan
			val trip = uiState.trip
			val finalStatus = terrainRepository.preloadCorridor(plan, trip) { status ->
				uiState = uiState.copy(offlinePreloadStatus = status)
			}
			val assets = if (trip != null) withContext(Dispatchers.IO) {
				journeyStore.discoverOfflineAssets(plan, trip, uiState.offlineAssets)
			} else uiState.offlineAssets
			val assetsChanged = assets != uiState.offlineAssets
			uiState = uiState.copy(
				offlinePreloadStatus = finalStatus,
				offlineAssets = assets,
				journeyDirty = uiState.journeyDirty || assetsChanged
			)
			refreshStorageUsage()
			if (assetsChanged && uiState.journeyId != null) saveJourney()
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			uiState = uiState.copy(
				offlinePreloadStatus = uiState.offlinePreloadStatus.copy(
					phase = FlightTerrainPhase.ERROR,
					message = error.message ?: "Préchargement du relief impossible"
				)
			)
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

	fun setTerrainRenderStats(stats: FlightTerrainRenderStats) {
		if (uiState.terrainRenderStats != stats) {
			uiState = uiState.copy(terrainRenderStats = stats)
		}
	}

	private fun requestTerrain(sample: FlightSample, force: Boolean = false) {
		// The dedicated cache page is now a stable offline tile viewer. The live 3D
		// renderer belongs to Hublot, so it never needs a second native-map render pass.
		val includeNativeMap = false
		val scene = uiState.terrainScene
		if (!force && scene != null && scene.radiusKm == uiState.plan.terrainCorridorKm &&
			scene.satelliteQuality == uiState.plan.satelliteQuality &&
			(!includeNativeMap || scene.nativeMapRequested)
		) {
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
					radiusKm = uiState.plan.terrainCorridorKm,
					satelliteQuality = uiState.plan.satelliteQuality,
					includeNativeMap = includeNativeMap,
					previousScene = scene,
					onScene = { partialScene ->
						// Publish only tile deltas. Geometry buffers and cached textures remain
						// resident while missing detail is filled from the aircraft outward.
						uiState = uiState.copy(terrainScene = partialScene)
					}
				) { status ->
					uiState = uiState.copy(terrainStatus = status)
				}
				uiState = uiState.copy(
					terrainScene = terrainScene,
					terrainStatus = uiState.terrainStatus.copy(
						phase = FlightTerrainPhase.READY,
						bytesPerSecond = 0L,
						textureQueue = 0,
						message = when {
							terrainScene.missingTiles > 0 && includeNativeMap ->
								"Relief partiel : ${terrainScene.missingTiles} tuiles manquantes · carte OsmAnd ${terrainScene.nativeMapTiles}/${terrainScene.loadedTiles}"
							terrainScene.missingTiles > 0 ->
								"Relief partiel : ${terrainScene.missingTiles} tuiles manquantes"
							includeNativeMap && terrainScene.nativeMapTiles < terrainScene.loadedTiles ->
								"Relief prêt · carte OsmAnd ${terrainScene.nativeMapTiles}/${terrainScene.loadedTiles}"
							includeNativeMap && terrainScene.satelliteTiles < terrainScene.loadedTiles ->
								"Relief + carte OsmAnd prêts · satellite ${terrainScene.satelliteTiles}/${terrainScene.loadedTiles}"
							terrainScene.satelliteTiles < terrainScene.loadedTiles ->
								"Relief prêt · satellite ${terrainScene.satelliteTiles}/${terrainScene.loadedTiles}"
							includeNativeMap -> "Relief + satellite + carte OsmAnd prêts"
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
			bearingDegrees = if (to?.latitude != null && to.longitude != null) {
				FlightTrackMath.bearingBetween(latitude, longitude, to.latitude, to.longitude)
			} else null,
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
		storeActiveWindowPhotoAlignment()
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
		storeActiveWindowPhotoAlignment()
	}

	fun recenterWindowLook() {
		if (uiState.windowLook != FlightWindowLook()) {
			uiState = uiState.copy(windowLook = FlightWindowLook())
			storeActiveWindowPhotoAlignment()
		}
	}

	fun setWindowZoom(zoom: Float) {
		setWindowPlacement(uiState.windowPlacement.copy(zoom = zoom), persist = false)
	}

	fun changeWindowZoom(factor: Float) {
		setWindowZoom(
			uiState.windowPlacement.zoom * dampedFlightPinchFactor(factor).coerceIn(0.75f, 1.35f)
		)
	}

	fun setCabinTransparent(transparent: Boolean) {
		setWindowPlacement(uiState.windowPlacement.copy(cabinTransparent = transparent))
	}

	fun setCabinHidden(hidden: Boolean) {
		setWindowPlacement(uiState.windowPlacement.copy(cabinHidden = hidden))
	}

	private fun setWindowPlacement(placement: FlightWindowPlacement, persist: Boolean = true) {
		val safePlacement = placement.clamped()
		if (persist) windowPlacementStore.save(safePlacement)
		uiState = uiState.copy(windowPlacement = safePlacement)
		storeActiveWindowPhotoAlignment()
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
			pendingFlightStartProgress = null,
			journeyDirty = true
		)
	}

	fun cancelFlightStart() {
		uiState = uiState.copy(pendingFlightStartProgress = null)
	}

	fun removeFlightSpan(index: Int) {
		if (index !in uiState.flightSpans.indices) return
		uiState = uiState.copy(
			flightSpans = uiState.flightSpans.toMutableList().apply { removeAt(index) },
			journeyDirty = true
		)
	}

	fun setSatelliteQuality(quality: FlightSatelliteQuality) {
		if (uiState.plan.satelliteQuality == quality) return
		updatePlan(uiState.plan.copy(satelliteQuality = quality))
		requestedTerrainCenter = null
		(uiState.snapshot?.sample ?: previewFlightSample())?.let { requestTerrain(it, force = true) }
	}

	fun setRecordingPolicy(policy: FlightRecordingPolicy) {
		uiState = uiState.copy(recordingPolicy = policy)
	}

	fun updateJourneyName(name: String) {
		if (uiState.journeyName != name) {
			uiState = uiState.copy(journeyName = name, journeyDirty = true, journeyMessage = null)
		}
	}

	fun saveJourney() {
		photoPersistenceJob?.cancel()
		photoPersistenceJob = null
		persistJourney(showConfirmation = true)
	}

	private fun persistJourney(showConfirmation: Boolean) {
		flushLatestLiveSample()
		val trip = uiState.trip
		if (trip == null || trip.samples.isEmpty()) {
			if (showConfirmation) uiState = uiState.copy(journeyMessage = "Aucun point à enregistrer")
			return
		}
		val now = System.currentTimeMillis()
		val journey = FlightJourney(
			id = uiState.journeyId ?: UUID.randomUUID().toString(),
			name = uiState.journeyName.trim().ifBlank { trip.name.ifBlank { "Journal de vol" } },
			createdAtMillis = uiState.journeyCreatedAtMillis ?: now,
			updatedAtMillis = now,
			plan = uiState.plan,
			trip = trip,
			flightSpans = uiState.flightSpans,
			photos = uiState.photos,
			offlineAssets = uiState.offlineAssets
		)
		viewModelScope.launch {
			val result = runCatching { withContext(Dispatchers.IO) { journeyStore.save(journey) } }
			result.onSuccess { saved ->
				uiState = uiState.copy(
					journeyId = saved.id,
					journeyName = saved.name,
					journeyCreatedAtMillis = saved.createdAtMillis,
					offlineAssets = saved.offlineAssets,
					journeyDirty = false,
					savedJourneys = journeyStore.list(),
					journeyMessage = if (showConfirmation) "Journal de vol enregistré" else uiState.journeyMessage
				)
				refreshStorageUsage()
			}.onFailure { error ->
				uiState = uiState.copy(
					journeyMessage = error.message ?: "Enregistrement automatique impossible"
				)
			}
		}
	}

	private fun schedulePhotoPersistence() {
		if (uiState.trip?.samples.isNullOrEmpty() || uiState.photos.isEmpty()) return
		photoPersistenceJob?.cancel()
		photoPersistenceJob = viewModelScope.launch {
			delay(PHOTO_PERSISTENCE_DEBOUNCE_MILLIS)
			persistJourney(showConfirmation = false)
		}
	}

	fun openJourney(id: String) {
		pendingDuplicateTrip = null
		uiState = uiState.copy(
			loadingTrip = true,
			journeyMessage = null,
			duplicateJourneyWarning = null
		)
		viewModelScope.launch {
			val result = runCatching { withContext(Dispatchers.IO) { journeyStore.load(id) } }
			result.onSuccess(::applyJourney).onFailure(::showTripLoadFailure)
		}
	}

	fun suggestedExportName(): String {
		val base = uiState.journeyName.trim().ifBlank { "voyage-aerien" }
			.replace(Regex("[^A-Za-z0-9._-]"), "-")
			.trim('-')
			.take(80)
			.ifBlank { "voyage-aerien" }
		return "$base.${FlightJourneyStore.ARCHIVE_EXTENSION}"
	}

	fun exportJourney(uri: Uri) {
		flushLatestLiveSample()
		val trip = uiState.trip
		if (trip == null || trip.samples.isEmpty()) {
			uiState = uiState.copy(journeyMessage = "Aucun voyage à exporter")
			return
		}
		val now = System.currentTimeMillis()
		val journey = FlightJourney(
			id = uiState.journeyId ?: UUID.randomUUID().toString(),
			name = uiState.journeyName.trim().ifBlank { trip.name },
			createdAtMillis = uiState.journeyCreatedAtMillis ?: now,
			updatedAtMillis = now,
			plan = uiState.plan,
			trip = trip,
			flightSpans = uiState.flightSpans,
			photos = uiState.photos,
			offlineAssets = uiState.offlineAssets
		)
		viewModelScope.launch {
			val result = runCatching { withContext(Dispatchers.IO) { journeyStore.exportArchive(journey, uri) } }
			uiState = uiState.copy(
				journeyMessage = result.fold(
					onSuccess = { "Archive exportée : GPX + capteurs + photos + données hors ligne" },
					onFailure = { it.message ?: "Export impossible" }
				)
			)
		}
	}

	fun stageReplayPhotos(uris: List<Uri>) {
		if (uris.isEmpty()) return
		viewModelScope.launch {
			val photos = withContext(Dispatchers.IO) { journeyStore.importPhotos(uris, uiState.trip) }
			uiState = uiState.copy(
				pendingPhotos = (uiState.pendingPhotos + photos).sortedWith(PHOTO_TIME_COMPARATOR),
				selectedPhotoId = photos.firstOrNull()?.id ?: uiState.selectedPhotoId,
				journeyMessage = if (photos.isEmpty()) "Aucune photo lisible" else "${photos.size} photo(s) à valider"
			)
		}
	}

	fun validatePendingPhotos() {
		if (uiState.pendingPhotos.isEmpty()) return
		val accepted = uiState.pendingPhotos.sortedWith(PHOTO_TIME_COMPARATOR)
		uiState = uiState.copy(
			photos = (uiState.photos + accepted).sortedWith(PHOTO_TIME_COMPARATOR),
			pendingPhotos = emptyList(),
			selectedPhotoId = accepted.first().id,
			journeyDirty = true,
			journeyMessage = "Photos associées par leur heure"
		)
		refreshStorageUsage()
		schedulePhotoPersistence()
	}

	fun discardPendingPhotos() {
		val discarded = uiState.pendingPhotos
		if (discarded.isEmpty()) return
		uiState = uiState.copy(pendingPhotos = emptyList(), journeyMessage = null)
		viewModelScope.launch(Dispatchers.IO) { journeyStore.discardPhotos(discarded) }
	}

	fun preparePhotoCapture(): File {
		pendingCaptureFile?.let { previous -> if (previous.isFile) previous.delete() }
		return journeyStore.createCaptureFile().also { file ->
			pendingCaptureFile = file
			pendingCaptureTimestampMillis = System.currentTimeMillis()
		}
	}

	fun finishPhotoCapture(success: Boolean) {
		val file = pendingCaptureFile ?: return
		pendingCaptureFile = null
		if (!success || !file.isFile || file.length() == 0L) {
			if (file.isFile) file.delete()
			return
		}
		val photo = journeyStore.capturedPhoto(
			file = file,
			fallbackTimestampMillis = pendingCaptureTimestampMillis,
			trip = uiState.trip,
			includeMainCamera = uiState.photoMainCamera,
			includeSelfie = uiState.photoSelfie,
			includeMap = uiState.photoMap,
			includeScene3d = uiState.photoScene3d
		)
		uiState = uiState.copy(
			photos = (uiState.photos + photo).sortedWith(PHOTO_TIME_COMPARATOR),
			selectedPhotoId = photo.id,
			journeyDirty = true,
			journeyMessage = "Photo enregistrée à ${photo.timestampMillis ?: pendingCaptureTimestampMillis}"
		)
		schedulePhotoPersistence()
	}

	fun selectPhoto(id: String) {
		val photo = (uiState.photos + uiState.pendingPhotos).firstOrNull { it.id == id } ?: return
		uiState = uiState.copy(selectedPhotoId = id)
		val progress = FlightSampleInterpolator.progressAt(uiState.trip, photo.matchedSamplePosition)
		if (progress != null) seekReplay(progress)
	}

	fun togglePhotoSelection(id: String) {
		if (uiState.selectedPhotoId == id) {
			uiState = uiState.copy(selectedPhotoId = null)
		} else {
			selectPhoto(id)
		}
	}

	fun associatePhotoAutomatically(id: String) {
		val originalPhoto = findPhoto(id) ?: return
		val originalTrip = uiState.trip
		viewModelScope.launch {
			val redetectedPhoto = if (originalPhoto.timestampMillis == null) {
				withContext(Dispatchers.IO) {
					journeyStore.redetectMissingPhotoTimestamp(originalPhoto, originalTrip)
				}
			} else originalPhoto
			val currentPhoto = findPhoto(id) ?: return@launch
			val photo = if (currentPhoto.timestampMillis == null && redetectedPhoto.timestampMillis != null) {
				currentPhoto.copy(
					timestampMillis = redetectedPhoto.timestampMillis,
					timestampSource = redetectedPhoto.timestampSource
				)
			} else currentPhoto
			val trip = uiState.trip
			val timestamp = photo.timestampMillis
			val position = journeyStore.matchPhotoPosition(trip, timestamp)
			if (position == null) {
				val message = when {
					timestamp == null ->
						"Aucune heure trouvée dans l’EXIF, le nom « ${photo.fileName} » ou les dates du fichier · place le curseur puis choisis Associer ici"
					trip?.hasUsableTimestamps != true ->
						"La trace n’a pas d’heure exploitable · place le curseur puis choisis Associer ici"
					else ->
						"La date détectée est hors de ce trajet · place le curseur puis choisis Associer ici"
				}
				if (photo != currentPhoto) {
					replacePhoto(photo, message)
				} else {
					uiState = uiState.copy(selectedPhotoId = id, journeyMessage = message)
				}
				return@launch
			}
			val dateRecovered = currentPhoto.timestampMillis == null && photo.timestampMillis != null
			replacePhoto(
				photo.copy(matchedSamplePosition = position),
				if (dateRecovered) {
					"Date retrouvée dans la photo · association à la position GPS interpolée"
				} else {
					"Photo réassociée à la position GPS interpolée dans le temps"
				}
			)
			FlightSampleInterpolator.progressAt(trip, position)?.let(::seekReplay)
		}
	}

	fun associatePhotoAtCurrentReplay(id: String) {
		val photo = findPhoto(id) ?: return
		val trip = uiState.trip
		val position = FlightSampleInterpolator.positionAtProgress(trip, uiState.replayProgress)
			?.let(FlightSampleInterpolator::quantizePosition)
		if (position == null) {
			uiState = uiState.copy(selectedPhotoId = id, journeyMessage = "Aucun point courant auquel associer cette photo")
			return
		}
		replacePhoto(
			photo.copy(matchedSamplePosition = position),
			String.format(Locale.US, "Photo associée au point virtuel %.2f", position + 1.0)
		)
	}

	fun clearPhotoAssociation(id: String) {
		val photo = findPhoto(id) ?: return
		replacePhoto(photo.copy(matchedSamplePosition = null), "Association de la photo supprimée")
	}

	fun rotatePhoto(id: String, deltaDegrees: Float) {
		if (!deltaDegrees.isFinite() || abs(deltaDegrees) < 0.01f) return
		val photo = findPhoto(id) ?: return
		val rotation = (photo.rotationDegrees + deltaDegrees).let { value ->
			val normalized = value % 360f
			if (normalized < 0f) normalized + 360f else normalized
		}
		replacePhoto(photo.copy(rotationDegrees = rotation), "Rotation de la photo enregistrée")
	}

	fun openPhotoOnMap(id: String) {
		selectPhoto(id)
		if (findPhoto(id)?.matchedSamplePosition != null) {
			uiState = uiState.copy(page = FlightPage.MAP, mapFollowing = true)
		}
	}

	fun openPhotoInWindow(id: String) {
		val photo = findPhoto(id) ?: return
		selectPhoto(id)
		if (photo.matchedSamplePosition != null) {
			val savedAlignment = photo.windowAlignment?.clamped()
			val detectedFov = photo.cameraVerticalFieldOfViewDegrees
			val initialPlacement = savedAlignment?.windowPlacement ?: detectedFov?.let { verticalFov ->
				uiState.windowPlacement.copy(
					zoom = FlightPhotoPerspective.windowZoomForVerticalFieldOfView(verticalFov)
				).clamped()
			} ?: uiState.windowPlacement
			uiState = uiState.copy(
				page = FlightPage.WINDOW,
				windowPhotoOverlay = savedAlignment?.let { alignment ->
					FlightWindowPhotoOverlay(
						photoId = id,
						opacity = alignment.opacity,
						scale = alignment.scale,
						offsetXFraction = alignment.offsetXFraction,
						offsetYFraction = alignment.offsetYFraction,
						gestureTarget = FlightWindowGestureTarget.LINKED
					).clamped()
				} ?: FlightWindowPhotoOverlay(
					photoId = id,
					gestureTarget = FlightWindowGestureTarget.PHOTO
				),
				windowPlacement = initialPlacement,
				windowLook = savedAlignment?.windowLook ?: uiState.windowLook,
				windowAltitudeOverrideMeters = if (savedAlignment != null) {
					savedAlignment.altitudeOverrideMeters
				} else {
					uiState.windowAltitudeOverrideMeters
				}
			)
			if (detectedFov == null) detectAndApplyPhotoPerspective(id)
		}
	}

	private fun detectAndApplyPhotoPerspective(id: String) {
		viewModelScope.launch {
			val photo = findPhoto(id) ?: return@launch
			val detectedFov = withContext(Dispatchers.IO) {
				journeyStore.detectPhotoVerticalFieldOfViewDegrees(photo)
			} ?: return@launch
			val latestPhoto = findPhoto(id) ?: return@launch
			if (latestPhoto.cameraVerticalFieldOfViewDegrees == null) {
				replacePhoto(
					latestPhoto.copy(cameraVerticalFieldOfViewDegrees = detectedFov),
					message = null
				)
			}
			if (uiState.windowPhotoOverlay.photoId == id && latestPhoto.windowAlignment == null) {
				val placement = uiState.windowPlacement.copy(
					zoom = FlightPhotoPerspective.windowZoomForVerticalFieldOfView(detectedFov)
				).clamped()
				uiState = uiState.copy(windowPlacement = placement)
				storeActiveWindowPhotoAlignment()
			}
		}
	}

	fun setWindowPhotoOpacity(opacity: Float) {
		uiState = uiState.copy(
			windowPhotoOverlay = uiState.windowPhotoOverlay.copy(opacity = opacity).clamped()
		)
		storeActiveWindowPhotoAlignment()
	}

	fun setWindowGestureTarget(target: FlightWindowGestureTarget) {
		if (target != FlightWindowGestureTarget.VIEW && uiState.windowPhotoOverlay.photoId == null) return
		uiState = uiState.copy(
			windowPhotoOverlay = uiState.windowPhotoOverlay.copy(gestureTarget = target)
		)
	}

	fun transformWindowPhoto(panXFraction: Float, panYFraction: Float, zoomFactor: Float) {
		val current = uiState.windowPhotoOverlay
		if (current.photoId == null) return
		uiState = uiState.copy(
			windowPhotoOverlay = current.copy(
				scale = current.scale * dampedFlightPinchFactor(zoomFactor).coerceIn(0.70f, 1.45f),
				offsetXFraction = current.offsetXFraction + panXFraction,
				offsetYFraction = current.offsetYFraction + panYFraction
			).clamped()
		)
		storeActiveWindowPhotoAlignment()
	}

	fun transformLinkedWindowView(
		panXFraction: Float,
		panYFraction: Float,
		zoomFactor: Float,
		viewAspectRatio: Float
	) {
		val current = uiState.windowPhotoOverlay
		if (current.photoId == null) return
		val transformed = linkedFlightWindowTransform(
			placement = uiState.windowPlacement,
			look = uiState.windowLook,
			photoOverlay = current,
			panXFraction = panXFraction,
			panYFraction = panYFraction,
			rawZoomFactor = zoomFactor,
			viewAspectRatio = viewAspectRatio
		)
		uiState = uiState.copy(
			windowPlacement = transformed.placement,
			windowLook = transformed.look,
			windowPhotoOverlay = transformed.photoOverlay
		)
		storeActiveWindowPhotoAlignment()
	}

	fun resetWindowPhotoTransform() {
		val current = uiState.windowPhotoOverlay
		uiState = uiState.copy(
			windowPhotoOverlay = current.copy(scale = 1f, offsetXFraction = 0f, offsetYFraction = 0f)
		)
		storeActiveWindowPhotoAlignment()
		current.photoId?.let(::findPhoto)?.takeIf { it.rotationDegrees != 0f }?.let { photo ->
			replacePhoto(photo.copy(rotationDegrees = 0f), "Position et rotation de la photo réinitialisées")
		}
	}

	fun clearWindowPhotoOverlay() {
		uiState = uiState.copy(windowPhotoOverlay = FlightWindowPhotoOverlay())
	}

	private fun findPhoto(id: String): FlightPhotoAttachment? =
		(uiState.photos + uiState.pendingPhotos).firstOrNull { it.id == id }

	private fun storeActiveWindowPhotoAlignment() {
		val overlay = uiState.windowPhotoOverlay
		val photo = overlay.photoId?.let(::findPhoto) ?: return
		val alignment = FlightPhotoWindowAlignment(
			opacity = overlay.opacity,
			scale = overlay.scale,
			offsetXFraction = overlay.offsetXFraction,
			offsetYFraction = overlay.offsetYFraction,
			windowPlacement = uiState.windowPlacement,
			windowLook = uiState.windowLook,
			altitudeOverrideMeters = uiState.windowAltitudeOverrideMeters
		).clamped()
		if (photo.windowAlignment != alignment) {
			replacePhoto(photo.copy(windowAlignment = alignment), message = null)
		}
	}

	private fun replacePhoto(photo: FlightPhotoAttachment, message: String?) {
		val attached = uiState.photos.any { it.id == photo.id }
		uiState = uiState.copy(
			photos = uiState.photos.map { if (it.id == photo.id) photo else it }.sortedWith(PHOTO_TIME_COMPARATOR),
			pendingPhotos = uiState.pendingPhotos.map { if (it.id == photo.id) photo else it }
				.sortedWith(PHOTO_TIME_COMPARATOR),
			selectedPhotoId = photo.id,
			journeyDirty = uiState.journeyDirty || attached,
			journeyMessage = message ?: uiState.journeyMessage
		)
		if (attached) schedulePhotoPersistence()
	}

	fun setPhotoSources(main: Boolean? = null, selfie: Boolean? = null, map: Boolean? = null, scene3d: Boolean? = null) {
		uiState = uiState.copy(
			photoMainCamera = main ?: uiState.photoMainCamera,
			photoSelfie = selfie ?: uiState.photoSelfie,
			photoMap = map ?: uiState.photoMap,
			photoScene3d = scene3d ?: uiState.photoScene3d
		)
	}

	private fun flushLatestLiveSample() {
		if (uiState.sessionMode != FlightSessionMode.LIVE) return
		val latest = uiState.snapshot?.sample ?: return
		val recorded = liveSamples.lastOrNull()
		if (recorded?.timestampMillis == latest.timestampMillis) {
			val replacement = latest.copy(index = liveSamples.lastIndex)
			liveSamples[liveSamples.lastIndex] = replacement
			publishLiveTrip(replacement)
		} else {
			appendLiveSample(latest)
		}
	}

	override fun onCleared() {
		terrainJob?.cancel()
		offlinePreloadJob?.cancel()
		storageJob?.cancel()
		citySearchJob?.cancel()
		photoPersistenceJob?.cancel()
		super.onCleared()
	}

	private sealed interface LoadedSource {
		data class Trip(val trip: FlightTrip) : LoadedSource
		data class Journey(val journey: FlightJourney) : LoadedSource
	}

	companion object {
		private val PHOTO_TIME_COMPARATOR = compareBy<FlightPhotoAttachment>(
			{ it.timestampMillis == null },
			{ it.timestampMillis ?: Long.MAX_VALUE },
			{ it.fileName.lowercase() }
		)
		private const val MINIMUM_CITY_QUERY_LENGTH = 2
		private const val CITY_SEARCH_DEBOUNCE_MILLIS = 180L
		private const val PHOTO_PERSISTENCE_DEBOUNCE_MILLIS = 450L
		private const val TERRAIN_RELOAD_RADIUS_FRACTION = 0.32
		private const val MINIMUM_RELOAD_DISTANCE_KM = 20.0
		private const val MINIMUM_WINDOW_ALTITUDE_METERS = -500f
		private const val MAXIMUM_WINDOW_ALTITUDE_METERS = 15_000f
		private const val MINIMUM_FLIGHT_SPAN_PROGRESS = 0.0005f
	}
}
