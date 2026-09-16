package net.osmand.plus.plugins.flightmode

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
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
	private var storageJob: Job? = null
	private var journeyListJob: Job? = null
	private var citySearchJob: Job? = null
	private var photoPersistenceJob: Job? = null
	private var pendingCaptureFile: File? = null
	private var pendingCaptureTimestampMillis: Long = 0L
	private var pendingCaptureSensors: FlightPhotoCapture? = null
	private var pendingDuplicateTrip: FlightTrip? = null
	private var uiVisible by mutableStateOf(false)
	private val uiVisibility = MutableStateFlow(false)
	private val livePredictor = FlightLivePredictor()
	private var preparationDownload: Job? = null
	private var preparationDownloadGeneration = 0L
	private var simulationOriginal: FlightJourney? = null
	private var simulationJob: Job? = null
	private var liveTimelineJob: Job? = null
	private var liveCursorMillis: Long? = null
	private val preparationSaveMutex = Mutex()
	private var pendingJournalNavigation: (suspend () -> Unit)? = null
	private val offlineOwner = Any()

	fun setOfflineSimulation(enabled: Boolean) {
		if (enabled && uiState.sessionMode == FlightSessionMode.LIVE && uiState.page in listOf(FlightPage.MAP,FlightPage.WINDOW)) return
		if (enabled == uiState.offlineSimulation) return
		if (enabled) {
			val sockets = FlightNetworkAccess.block(offlineOwner)
			pausePreparationDownload()
			viewModelScope.launch(Dispatchers.IO) { sockets.forEach { runCatching { it() } } }
		} else FlightNetworkAccess.release(offlineOwner)
		terrainStreamingEngine.reset()
		uiState = uiState.copy(offlineSimulation = enabled, terrainStatus = FlightTerrainStatus())
		if (uiState.page in listOf(FlightPage.MAP,FlightPage.WINDOW,FlightPage.WINDOW_SETUP)) {
			uiState.snapshot?.sample?.let { terrainStreamingEngine.retry(sceneDemand(it)) }
		}
	}

	fun preloadPreparation(quote: FlightOfflineQuote) {
		if (uiState.plan.stops.map { it.latitude to it.longitude } != quote.route ||
			(uiState.plan.preparation ?: FlightPreparation()).bands != quote.bands) return
		val previousDownload=preparationDownload
		previousDownload?.cancel()
		val generation=++preparationDownloadGeneration
		uiState=uiState.copy(offlinePreloadStatus=FlightTerrainStatus(phase=FlightTerrainPhase.DOWNLOADING))
		preparationDownload=viewModelScope.launch {
			try {
				previousDownload?.join()
				val original=saveLocalJournal()
				withContext(Dispatchers.IO) { journeyStore.update(original.id) { it.copy(offlineRequest=quote.assets) } }
				uiState=uiState.copy(offlinePreloadStatus=FlightTerrainStatus(phase=FlightTerrainPhase.DOWNLOADING))
				val result=terrainRepository.preloadPrepared(quote) { status ->
					if(uiState.journeyId==original.id && generation==preparationDownloadGeneration) uiState=uiState.copy(offlinePreloadStatus=status)
				}
				val saved=withContext(Dispatchers.IO) {
					journeyStore.updateVerifiedOfflineAssets(original.id, quote.assets, result.assets)
				}
				if(uiState.journeyId==original.id && generation==preparationDownloadGeneration) uiState=uiState.copy(offlineAssets=saved.offlineAssets,offlinePreloadStatus=result.status)
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) { if(generation==preparationDownloadGeneration) uiState=uiState.copy(offlinePreloadStatus=uiState.offlinePreloadStatus.copy(phase=FlightTerrainPhase.ERROR,message=e.message)) }
		}
	}
	fun pausePreparationDownload() {
		preparationDownloadGeneration++
		preparationDownload?.cancel()
		uiState=uiState.copy(offlinePreloadStatus=uiState.offlinePreloadStatus.copy(phase=FlightTerrainPhase.PAUSED,bytesPerSecond=0,
			message=app.getString(net.osmand.plus.R.string.flight_plan_paused)))
	}
	fun rehearsePreparation(page: FlightPage = FlightPage.MAP) {
		if (!FlightOfflinePreparation.canSimulate(uiState.plan)) {
			uiState = uiState.copy(simulationError = app.getString(net.osmand.plus.R.string.flight_test_route_required))
			return
		}
		simulationJob?.cancel()
		setOfflineSimulation(true)
		uiState = uiState.copy(simulationLoading = true, simulationError = null)
		simulationJob=viewModelScope.launch {
			try {
				if (simulationOriginal == null) {
					val now = System.currentTimeMillis()
					simulationOriginal = FlightJourney(uiState.journeyId ?: UUID.randomUUID().toString(), uiState.journeyName,
						uiState.journeyCreatedAtMillis ?: now, now, uiState.plan,
						uiState.trip?.takeUnless { uiState.previewingPlan } ?: recordedFlightTrip("", emptyList()),
						uiState.flightSpans, uiState.photos)
				}
				val plan = uiState.plan
				val trip=withContext(Dispatchers.Default) { FlightOfflinePreparation.simulation(plan) }
				if (FlightOfflinePreparation.simulationInput(uiState.plan) != FlightOfflinePreparation.simulationInput(plan) ||
					uiState.sessionMode != FlightSessionMode.PREPARE) return@launch
				replayEngine = FlightReplayEngine(trip)
				val progress=uiState.replayProgress
				uiState=uiState.copy(page=page, sessionMode=FlightSessionMode.PREPARE,
					trip=trip, profile=FlightProfilePlanner.fromTrip(trip), snapshot=replayEngine?.snapshotAt(progress),
					replayProgress=progress, replayPlaying=false, previewingPlan=true, simulationLoading=false)
				uiState.snapshot?.sample?.let(::requestTerrain)
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) { uiState=uiState.copy(simulationLoading=false,simulationError=e.message ?: "simulation_failed") }
		}
	}

	var uiState by mutableStateOf(
		FlightUiState(
			plan = FlightPlan(listOf(FlightStop(application.getString(net.osmand.plus.R.string.flight_plan_departure)),
				FlightStop(application.getString(net.osmand.plus.R.string.flight_plan_arrival)))),
			profile = FlightProfilePlanner.build(FlightPlan(emptyList())),
			windowPlacement = windowPlacementStore.load(),
			savedJourneys = emptyList(),
			journeyMessage = application.getSharedPreferences(FlightRecordingService.PREFS,0).getString("error",null)
		)
	)
		private set

	private val terrainStreamingEngine by lazy(LazyThreadSafetyMode.NONE) {
		FlightSceneStreamingEngine(
			scope = viewModelScope,
			repository = terrainRepository,
			initialScene = { uiState.terrainScene },
			publishScene = { scene -> uiState = uiState.copy(terrainScene = scene) },
			publishStatus = { status -> uiState = uiState.copy(terrainStatus = status) }
		).also { it.setForeground(uiVisible) }
	}

	init {
		refreshSavedJourneys()
		viewModelScope.launch {
			combine(FlightRecordingService.state, uiVisibility) { live, visible -> live to visible }.collect { (live, visible) ->
				uiState = uiState.copy(activeRecording = live)
				if (!visible) return@collect
				// A background recording never steals the selected journal or the home page.
				if (uiState.page in listOf(FlightPage.HOME, FlightPage.PLANS, FlightPage.JOURNEYS, FlightPage.PREPARE)) return@collect
				if (live.journeyId != null && uiState.journeyId == live.journeyId && !uiState.previewingPlan) {
					if (live.simulation && !uiState.liveState.running && live.running && live.simulationPlan != null)
						uiState=uiState.copy(plan=live.simulationPlan)
					live.latest?.let { livePredictor.accept(it, android.os.SystemClock.elapsedRealtime(),
						if (live.simulation) { if (live.simulationPaused) 0.0 else live.simulationRate.toDouble() } else 1.0,
						fixReceivedAt = live.lastFixElapsed.takeIf { it > 0L }) }
					val newPhotos=if(live.running && live.trip!==uiState.trip && live.trip!=null) uiState.photos.map { photo ->
						if(photo.matchedSamplePosition==null && photo.timestampMillis!=null &&
							live.trip.samples.firstOrNull()?.timestampMillis?.let { photo.timestampMillis>=it }==true &&
							live.trip.samples.lastOrNull()?.timestampMillis?.let { photo.timestampMillis<=it }==true)
							photo.copy(matchedSamplePosition=journeyStore.matchPhotoPosition(live.trip,photo.timestampMillis)) else photo
					} else uiState.photos
					val photosChanged=newPhotos!=uiState.photos
					uiState = uiState.copy(liveState = live, batteryHistory = live.battery,
						simulatedJourney = live.simulation,
						recordingPolicy=live.policy,
						photos=newPhotos,journeyDirty=uiState.journeyDirty || photosChanged,
						trip = live.trip ?: uiState.trip,
						sessionMode = if (live.running) FlightSessionMode.LIVE else FlightSessionMode.REPLAY,
						journeyMessage = live.error ?: uiState.journeyMessage)
					if(photosChanged)schedulePhotoPersistence()
					if (live.running) rebuildLiveTimeline(live)
					if (!live.running && live.trip?.samples?.isNotEmpty()==true) {
						replayEngine = FlightReplayEngine(live.trip)
						uiState=uiState.copy(snapshot=FlightSnapshot(live.trip.samples.last(),1f),replayProgress=1f,
							liveTimeline=null,browsingLiveTimeline=false,replayPlaying=false,
							profile=FlightProfilePlanner.fromTrip(live.trip),
							page=if(uiState.page==FlightPage.LIVE) FlightPage.MAP else uiState.page)
					}
				}
			}
		}
		viewModelScope.launch {
			snapshotFlow {
				uiVisible && uiState.sessionMode == FlightSessionMode.LIVE && !uiState.browsingLiveTimeline &&
					uiState.page in listOf(FlightPage.MAP, FlightPage.WINDOW)
			}.collectLatest { active ->
				if (!active) return@collectLatest
				var lastTerrain = 0L
				while (true) {
					delay(100)
					if (!uiVisible || uiState.sessionMode != FlightSessionMode.LIVE || uiState.browsingLiveTimeline) continue
					val now = android.os.SystemClock.elapsedRealtime()
					val sample = livePredictor.position(now) ?: continue
					val progress = uiState.liveTimeline?.let { FlightLiveTimeline.progress(it, sample.timestampMillis) } ?: 0f
					uiState = uiState.copy(snapshot = FlightSnapshot(sample, progress), replayProgress = progress)
					if (now - lastTerrain >= 1000) {
						requestTerrain(sample)
						lastTerrain = now
					}
				}
			}
		}
		viewModelScope.launch {
			snapshotFlow {
				if (uiVisible && uiState.sessionMode == FlightSessionMode.LIVE &&
					uiState.page !in listOf(FlightPage.HOME, FlightPage.PLANS, FlightPage.JOURNEYS, FlightPage.PREPARE)
				) uiState.journeyId else null
			}.collect { FlightUiActivity.set(this@FlightModeViewModel, it) }
		}
	}

	fun setUiVisible(visible: Boolean) {
		if (uiVisible == visible) return
		uiVisible = visible
		uiVisibility.value = visible
		terrainStreamingEngine.setForeground(visible)
		if (!visible) {
			FlightUiActivity.set(this, null)
			liveTimelineJob?.cancel()
			citySearchJob?.cancel()
			if (preparationDownload?.isActive == true) pausePreparationDownload()
		} else uiState.snapshot?.sample?.let(::requestTerrain)
	}

	private fun rebuildLiveTimeline(live: FlightLiveState) {
		if (!uiVisible) return
		val fix = live.latest ?: return
		if (liveTimelineJob?.isActive == true) return
		val plan = uiState.plan
		liveTimelineJob = viewModelScope.launch {
			val timeline = withContext(Dispatchers.Default) { FlightLiveTimeline.build(plan, live.trip, fix) }
			if (uiState.journeyId != live.journeyId || uiState.sessionMode != FlightSessionMode.LIVE) return@launch
			replayEngine = FlightReplayEngine(timeline)
			val time = liveCursorMillis.takeIf { uiState.browsingLiveTimeline } ?: fix.timestampMillis
			val progress = FlightLiveTimeline.progress(timeline, time)
			uiState = uiState.copy(liveTimeline=timeline, profile=FlightProfilePlanner.fromTrip(timeline),
				replayProgress=progress,
				snapshot=if(uiState.browsingLiveTimeline) replayEngine?.snapshotAt(progress)?.let {
					FlightLiveTimeline.withoutFutureMeasurements(it, fix.timestampMillis)
				} else uiState.snapshot)
		}
	}

	fun returnToLive() {
		if (uiState.sessionMode != FlightSessionMode.LIVE) return
		exitWindowPhotoEditing()
		liveCursorMillis = null
		val sample = livePredictor.position(android.os.SystemClock.elapsedRealtime()) ?: uiState.liveState.latest
		val progress = if (sample != null) uiState.liveTimeline?.let { FlightLiveTimeline.progress(it, sample.timestampMillis) } ?: 0f else 0f
		uiState = uiState.copy(browsingLiveTimeline=false, replayPlaying=false,
			snapshot=sample?.let { FlightSnapshot(it, progress) }, replayProgress=progress)
		sample?.let(::requestTerrain)
	}

	private fun schedulePreparationSimulation() {
		if (uiState.sessionMode != FlightSessionMode.PREPARE) return
		simulationJob?.cancel()
		val plan = uiState.plan
		if (!FlightOfflinePreparation.canSimulate(plan)) {
			replayEngine = null
			uiState = uiState.copy(trip=simulationOriginal?.trip, snapshot=null, previewingPlan=false,
				replayPlaying=false, replayProgress=0f, simulationLoading=false, profile=FlightProfilePlanner.build(plan))
			return
		}
		if (simulationOriginal == null) {
			val now=System.currentTimeMillis()
			simulationOriginal=FlightJourney(uiState.journeyId?:UUID.randomUUID().toString(),uiState.journeyName,
				uiState.journeyCreatedAtMillis?:now,now,plan,recordedFlightTrip("",emptyList()),emptyList(),emptyList())
		}
		simulationJob=viewModelScope.launch {
			try {
			delay(250)
			val trip=withContext(Dispatchers.Default) { FlightOfflinePreparation.simulation(plan) }
			if (FlightOfflinePreparation.simulationInput(uiState.plan) != FlightOfflinePreparation.simulationInput(plan) ||
				uiState.sessionMode != FlightSessionMode.PREPARE) return@launch
			replayEngine=FlightReplayEngine(trip)
			uiState=uiState.copy(trip=trip,profile=FlightProfilePlanner.fromTrip(trip),previewingPlan=true,simulationLoading=false,simulationError=null,
				snapshot=replayEngine?.snapshotAt(uiState.replayProgress))
			uiState.snapshot?.sample?.let(::requestTerrain)
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { uiState = uiState.copy(simulationLoading=false,simulationError=e.message ?: "simulation_failed") }
		}
	}

	/** One local write path for recorded journals and plans, including plans without GPS fixes. */
	private suspend fun saveLocalJournal(expectedId: String? = uiState.journeyId): FlightJourney = preparationSaveMutex.withLock {
		// A queued autosave must not write into a different journal opened in the meantime.
		if (uiState.journeyId != expectedId) throw CancellationException("Journal changed before save")
		uiState = uiState.copy(savingJourney = true)
		try {
			if (uiState.journeyId == null) uiState = uiState.copy(journeyId = UUID.randomUUID().toString())
			val source = uiState
			val now = System.currentTimeMillis()
			val name = FlightJourneyNaming.updated(source.journeyName, source.plan, source.plan)
			val journey = FlightJourney(
				id = requireNotNull(source.journeyId), name = name,
				createdAtMillis = source.journeyCreatedAtMillis ?: now, updatedAtMillis = now,
				plan = source.plan,
				trip = (if (source.previewingPlan) simulationOriginal?.trip else source.trip)
					?: recordedFlightTrip(name, emptyList()),
				flightSpans = source.flightSpans, photos = source.photos,
				offlineAssets = source.offlineAssets, batteryHistory = source.batteryHistory,
				simulation = source.simulatedJourney
			)
			val saved = withContext(Dispatchers.IO) { journeyStore.save(journey) }
			if (uiState.journeyId == source.journeyId) {
				val changed = !uiState.hasSameJournalContentAs(source,
					includeTrip = !source.previewingPlan && source.sessionMode != FlightSessionMode.LIVE,
					includeMeasurements = source.sessionMode != FlightSessionMode.LIVE)
				uiState = uiState.copy(
					journeyName = if (uiState.journeyName == source.journeyName) saved.name else uiState.journeyName,
					journeyCreatedAtMillis = saved.createdAtMillis,
					trip = if (uiState.previewingPlan || uiState.trip !== source.trip) uiState.trip else saved.trip,
					offlineAssets = saved.offlineAssets, journeyDirty = changed, journeySaveError = null,
					savedJourneys = (uiState.savedJourneys.filterNot { it.id == saved.id } +
						FlightJourneySummary(saved.id, saved.name, saved.updatedAtMillis, saved.trip.samples.size, saved.photos.size))
						.sortedByDescending { it.updatedAtMillis }
				)
			}
			saved
		} finally {
			uiState = uiState.copy(savingJourney = false)
		}
	}

	fun savePreparation(arm: Boolean) {
		if(uiState.savingPreparation)return
		val sourceId=uiState.journeyId
		uiState=uiState.copy(savingPreparation=true,scheduleError=null)
		viewModelScope.launch {
			try {
				var armedAt: Long? = null
				val result=saveFlightPreparation(arm, { saveLocalJournal(sourceId) }) { saved ->
					val scheduled=(saved.plan.preparation ?: FlightPreparation()).copy(automatic=true)
					armedAt=withContext(Dispatchers.IO) { FlightScheduleManager.arm(app,saved.copy(plan=saved.plan.copy(preparation=scheduled))) }
				}
				val saved=result.saved
				if (uiState.journeyId!=saved.id) return@launch
				uiState=uiState.copy(journeyMessage=app.getString(net.osmand.plus.R.string.flight_plan_saved))
				if (result.armed) {
					val scheduled = (saved.plan.preparation ?: FlightPreparation()).copy(automatic=true)
					uiState = uiState.copy(scheduledPreparation=scheduled,scheduledStartMillis=armedAt)
					updatePlan(uiState.plan.copy(preparation=(uiState.plan.preparation ?: FlightPreparation()).copy(automatic=true)))
				}
				result.scheduleError?.let { uiState=uiState.copy(scheduleError=it.message ?: "schedule_failed") }
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) { uiState=uiState.copy(journeyMessage=e.message,journeySaveError=e.message ?: "save_failed") }
			finally {
				if(uiState.journeyId==sourceId || sourceId==null) {
					uiState=uiState.copy(savingPreparation=false)
					if (uiState.journeyDirty && uiState.journeySaveError == null) schedulePhotoPersistence()
				}
			}
		}
	}

	fun cancelAutomaticDeparture() {
		val id = uiState.journeyId ?: return
		viewModelScope.launch {
			try {
				withContext(Dispatchers.IO) { FlightScheduleManager.cancel(app, id) }
				if (uiState.journeyId != id) return@launch
				uiState=uiState.copy(scheduledPreparation=null,scheduledStartMillis=null,scheduleError=null)
				updatePlan(uiState.plan.copy(preparation=(uiState.plan.preparation ?: FlightPreparation()).copy(automatic=false)))
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) { uiState=uiState.copy(scheduleError=e.message ?: "schedule_failed") }
		}
	}

	fun newPreparation(repeatRoute: Boolean) {
		runJournalNavigation {
			savePendingLocalChanges()
			pausePreparationDownload()
			FlightNetworkAccess.release(offlineOwner)
			val previous = uiState.plan
			val plan = if (repeatRoute) previous.copy(preparation = (previous.preparation ?: FlightPreparation()).copy(
				departureMillis = 0, arrivalMillis = 0, automatic = false))
			else FlightPlan(listOf(FlightStop(app.getString(net.osmand.plus.R.string.flight_plan_departure)),
				FlightStop(app.getString(net.osmand.plus.R.string.flight_plan_arrival))), preparation = FlightPreparation())
			simulationJob?.cancel()
			simulationOriginal = null
			replayEngine = null
			terrainStreamingEngine.reset()
			uiState = FlightUiState(page = FlightPage.PREPARE, plan = plan, profile = FlightProfilePlanner.build(plan),
				journeyId = UUID.randomUUID().toString(), journeyDirty = true,
				activeRecording = FlightRecordingService.state.value,
				windowPlacement = uiState.windowPlacement, savedJourneys = uiState.savedJourneys,
				offlineAssets = if (repeatRoute) uiState.offlineAssets else FlightOfflineAssets())
			schedulePreparationSimulation()
			schedulePhotoPersistence()
		}
	}

	fun localJourneyRemoved(id: String) {
		if (uiState.journeyId == id) {
			FlightNetworkAccess.release(offlineOwner)
			photoPersistenceJob?.cancel(); simulationJob?.cancel(); liveTimelineJob?.cancel()
			simulationOriginal=null; replayEngine=null; terrainStreamingEngine.reset()
			uiState=FlightUiState(page=FlightPage.HOME, windowPlacement=uiState.windowPlacement,
				activeRecording=FlightRecordingService.state.value,
				savedJourneys=uiState.savedJourneys.filterNot { it.id==id })
		} else uiState=uiState.copy(savedJourneys=uiState.savedJourneys.filterNot { it.id==id })
		refreshSavedJourneys()
	}

	fun showPage(page: FlightPage) {
		if (uiState.loadingTrip) return
		if (!FlightWorkspaceNavigation.allows(uiState.sessionMode, page)) return
		if (page in listOf(FlightPage.MAP, FlightPage.WINDOW) && uiState.sessionMode == FlightSessionMode.PREPARE &&
			(!uiState.offlineSimulation || uiState.snapshot == null)) {
			rehearsePreparation(page)
			return
		}
		if (page in listOf(FlightPage.HOME, FlightPage.PLANS, FlightPage.JOURNEYS, FlightPage.PREPARE)) {
			FlightUiActivity.set(this, null)
			liveTimelineJob?.cancel()
			simulationJob?.cancel()
			uiState = uiState.copy(simulationLoading=false)
			if (uiState.offlineSimulation) {
				FlightNetworkAccess.release(offlineOwner)
				uiState = uiState.copy(offlineSimulation=false)
			}
			terrainStreamingEngine.reset()
		}
		if(page==FlightPage.PREPARE) {
			uiState=uiState.copy(replayPlaying=false,terrainStatus=FlightTerrainStatus())
		}
		val leavingWindow = page != FlightPage.WINDOW && page != FlightPage.WINDOW_SETUP
		if (leavingWindow) {
			exitWindowPhotoEditing()
			uiState = uiState.copy(page = page, terrainDetailFocus = null)
			(uiState.snapshot?.sample ?: previewFlightSample())?.let { sample ->
				requestTerrain(sample, FlightSceneDemandReason.PAGE)
			}
		} else {
			uiState = uiState.copy(page = page)
			if (page == FlightPage.WINDOW) scheduleTerrainDetailFocus()
			(uiState.snapshot?.sample ?: previewFlightSample())?.let { requestTerrain(it, FlightSceneDemandReason.PAGE) }
		}
		if (page in listOf(FlightPage.HOME, FlightPage.PLANS, FlightPage.JOURNEYS)) {
			uiState = uiState.copy(replayPlaying = false)
			refreshSavedJourneys()
		}
		if (page == FlightPage.JOURNAL) refreshStorageUsage()
		if (uiState.journeyDirty) schedulePhotoPersistence()
		if (uiState.sessionMode == FlightSessionMode.LIVE && page in listOf(FlightPage.MAP, FlightPage.WINDOW)) {
			rebuildLiveTimeline(uiState.liveState)
		}
	}

	private fun refreshSavedJourneys() {
		if (journeyListJob?.isActive == true) return
		val initial = uiState.savedJourneys
		uiState = uiState.copy(savedJourneysLoading = true)
		journeyListJob = viewModelScope.launch {
			try {
				val summaries = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { journeyStore.list { partial ->
					viewModelScope.launch { uiState = uiState.copy(savedJourneys =
						FlightJournalSummaries.mergeListing(initial, uiState.savedJourneys, partial, complete = false)) }
				} }
				uiState = uiState.copy(savedJourneys =
					FlightJournalSummaries.mergeListing(initial, uiState.savedJourneys, summaries, complete = true), savedJourneysLoading = false)
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { uiState = uiState.copy(journeyMessage = e.message, savedJourneysLoading = false) }
		}
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
		updatePlan(plan)
		uiState = uiState.copy(
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
		updatePlan(plan)
		uiState = uiState.copy(
			citySearchStopIndex = null,
			citySuggestions = emptyList(),
			citySearchLoading = false
		)
	}

	fun updatePlan(plan: FlightPlan) {
		if(plan==uiState.plan)return
		val simulationChanged = FlightOfflinePreparation.simulationInput(plan) != FlightOfflinePreparation.simulationInput(uiState.plan)
		val coverageChanged=plan.stops!=uiState.plan.stops || plan.preparation?.bands!=uiState.plan.preparation?.bands
		if(coverageChanged) { preparationDownloadGeneration++; preparationDownload?.cancel() }
		uiState = uiState.copy(
			plan = plan,
			journeyName = FlightJourneyNaming.updated(uiState.journeyName, uiState.plan, plan),
			offlinePreloadStatus=if(coverageChanged)FlightTerrainStatus()else uiState.offlinePreloadStatus,
			profile = if (uiState.trip != null) uiState.profile else FlightProfilePlanner.build(plan),
			journeyDirty = true
		)
		if (simulationChanged) schedulePreparationSimulation()
		schedulePhotoPersistence()
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
		if (liveStartJob?.isActive == true) return
		FlightNetworkAccess.release(offlineOwner)
		uiState = uiState.copy(offlineSimulation=false)
		simulationJob?.cancel()
		if(uiState.previewingPlan) {
			uiState=uiState.copy(trip=simulationOriginal?.trip,previewingPlan=false,snapshot=null)
			simulationOriginal=null
		}
		val active=FlightRecordingService.state.value
		if(active.running && !active.simulation && active.journeyId!=null) { openJourney(active.journeyId); return }
		liveStartJob = viewModelScope.launch {
			try {
				val sourceId = uiState.journeyId
				val phase = withContext(Dispatchers.IO) {
					sourceId?.let { FlightRecordingStore(app, it).readState().phase } ?: FlightTrackingPhase.WAITING
				}
				if (uiState.journeyId != sourceId) return@launch
				if (FlightWorkPolicy.needsNewRecordingJournal(uiState.simulatedJourney,
					!uiState.trip?.samples.isNullOrEmpty(), phase)) {
					// Reusing a completed journey means repeating its plan, never overwriting measurements/photos.
					saveLocalJournal()
					uiState=uiState.copy(journeyId=null,journeyCreatedAtMillis=null,trip=null,photos=emptyList(),
						batteryHistory=emptyList(),flightSpans=emptyList(),previewingPlan=false,simulatedJourney=false,
						journeyName=if(uiState.simulatedJourney) FlightJourneyNaming.route(uiState.plan) else uiState.journeyName)
				}
				val prepared=saveLocalJournal()
				livePredictor.reset()
				replayEngine=null
				uiState=uiState.copy(page=FlightPage.MAP,sessionMode=FlightSessionMode.LIVE,
					replayPlaying=false,windowPhotoOverlay=FlightWindowPhotoOverlay(),snapshot=null,
					liveState=FlightLiveState(journeyId=prepared.id))
				FlightRecordingService.start(app,prepared.id)
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) {
				val message = e.message ?: e.javaClass.simpleName
				uiState=uiState.copy(journeyMessage=message,
					liveState=uiState.liveState.copy(journeyId=uiState.journeyId, running=false, error=message))
			}
		}
	}

	private var liveStartJob: Job? = null

	fun startLiveSimulation() {
		if (uiState.simulationLoading) return
		if (FlightRecordingService.state.value.running) {
			uiState=uiState.copy(journeyMessage=app.getString(net.osmand.plus.R.string.flight_live_already_running))
			return
		}
		val source = uiState
		if (!FlightOfflinePreparation.canSimulate(source.plan) && (source.trip?.samples?.size ?: 0) < 2) return
		uiState=uiState.copy(simulationLoading=true,simulationError=null)
		viewModelScope.launch {
			try {
				savePendingLocalChanges()
				val now=System.currentTimeMillis()
				val copy=FlightJourney("simulation_${UUID.randomUUID()}",
					app.getString(net.osmand.plus.R.string.flight_immersion_name,source.journeyName),now,now,
					source.plan.copy(preparation=(source.plan.preparation ?: FlightPreparation()).copy(automatic=false)),
					if(source.previewingPlan) recordedFlightTrip("",emptyList()) else source.trip ?: recordedFlightTrip("",emptyList()),
					emptyList(),emptyList(),source.offlineAssets,simulation=true)
				val saved=withContext(Dispatchers.IO) { journeyStore.save(copy) }
				setOfflineSimulation(true)
				applyJourney(saved)
				simulationJob?.cancel(); simulationOriginal=null; replayEngine=null; livePredictor.reset()
				uiState=uiState.copy(page=FlightPage.MAP,sessionMode=FlightSessionMode.LIVE,previewingPlan=false,
					simulatedJourney=true,simulationLoading=false,snapshot=null,trip=recordedFlightTrip(saved.name,emptyList()),
					liveState=FlightLiveState(journeyId=saved.id,simulation=true))
				FlightRecordingService.simulate(app,saved.id)
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) { uiState=uiState.copy(simulationLoading=false,simulationError=e.message,
				sessionMode=FlightSessionMode.PREPARE,page=FlightPage.PREPARE) }
		}
	}

	fun loadSource(uri: Uri) {
		pendingDuplicateTrip = null
		runJournalNavigation {
			savePendingLocalChanges()
			val source = withContext(Dispatchers.IO) {
					if (journeyStore.isJourneyArchive(uri)) {
						LoadedSource.Journey(journeyStore.importArchive(uri))
					} else {
						LoadedSource.Trip(FlightTripLoader.load(app, uri))
					}
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
		runJournalNavigation {
			savePendingLocalChanges()
			val trip = withContext(Dispatchers.IO) { loader() }
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
		uiState = uiState.copy(simulatedJourney = false)
		// Importing a recording for inspection must not fill its offline gaps before the test starts.
		setOfflineSimulation(true)
		applyReplayTrip(
			trip = trip,
			journeyId = null,
			journeyName = trip.name,
			journeyCreatedAtMillis = System.currentTimeMillis(),
			flightSpans = emptyList(),
			photos = emptyList(),
			offlineAssets = FlightOfflineAssets(),
			dirty = true,
			message = app.getString(net.osmand.plus.R.string.flight_local_imported)
		)
		schedulePhotoPersistence()
	}

	private fun applyJourney(journey: FlightJourney) {
		simulationJob?.cancel(); liveTimelineJob?.cancel(); liveCursorMillis=null
		simulationOriginal=null
		if(journey.id!=uiState.journeyId)livePredictor.reset()
		pendingDuplicateTrip = null
		uiState = uiState.copy(plan = journey.plan,batteryHistory=journey.batteryHistory,previewingPlan=false,savingPreparation=false,
			simulatedJourney=journey.simulation,
			simulationLoading=false,simulationError=null,scheduleError=null,scheduledPreparation=null,scheduledStartMillis=null,
			liveTimeline=null,browsingLiveTimeline=false,replayPlaying=false,
			liveState=FlightRecordingService.state.value.takeIf { it.journeyId==journey.id }?:FlightLiveState())
		viewModelScope.launch {
			try {
				val scheduled=withContext(Dispatchers.IO) { FlightScheduleManager.scheduled(app,journey.id) }
				val at=withContext(Dispatchers.IO) { FlightScheduleManager.scheduledStart(app,journey.id) }
				if (uiState.journeyId==journey.id) uiState=uiState.copy(scheduledPreparation=scheduled,scheduledStartMillis=at)
			} catch(e:CancellationException) { throw e }
			catch(e:Exception) { if(uiState.journeyId==journey.id) uiState=uiState.copy(scheduleError=e.message ?: "schedule_failed") }
		}
		if (journey.trip.samples.isEmpty()) {
			simulationOriginal=journey
			val name=FlightJourneyNaming.updated(journey.name,journey.plan,journey.plan)
			uiState=uiState.copy(page=FlightPage.PREPARE,sessionMode=FlightSessionMode.PREPARE,
				journeyId=journey.id,journeyName=name,journeyCreatedAtMillis=journey.createdAtMillis,
				trip=journey.trip,photos=journey.photos,offlineAssets=journey.offlineAssets,snapshot=null,
				pendingPhotos=emptyList(),selectedPhotoId=journey.photos.firstOrNull()?.id,
				windowPhotoOverlay=FlightWindowPhotoOverlay(),flightSpans=journey.flightSpans,pendingFlightStartProgress=null,
				replayProgress=0f,replayTimelineWindowFraction=1f,offlinePreloadStatus=FlightTerrainStatus(),
				terrainDetailFocus=null,journeyMessage=null,tripLoadError=null,
				loadingTrip=false,profile=FlightProfilePlanner.build(journey.plan),journeyDirty=name!=journey.name,journeySaveError=null)
			attachLoadedLiveJourney()
			if(uiState.sessionMode==FlightSessionMode.PREPARE) {
				if(uiState.offlineSimulation) rehearsePreparation() else schedulePreparationSimulation()
			}
			return
		}
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
		attachLoadedLiveJourney()
	}

	private fun attachLoadedLiveJourney() {
		val live=FlightRecordingService.state.value
		if(live.running && live.journeyId==uiState.journeyId) {
			if (!live.simulation) FlightNetworkAccess.release(offlineOwner)
			uiState=uiState.copy(offlineSimulation=live.simulation, simulatedJourney=live.simulation,
				plan=live.simulationPlan ?: uiState.plan)
			live.latest?.let { livePredictor.accept(it,android.os.SystemClock.elapsedRealtime(),
				if (live.simulation) { if (live.simulationPaused) 0.0 else live.simulationRate.toDouble() } else 1.0,
				fixReceivedAt = live.lastFixElapsed.takeIf { it > 0L }) }
			uiState=uiState.copy(page=FlightPage.MAP,sessionMode=FlightSessionMode.LIVE,liveState=live,
				trip=live.trip?:uiState.trip,batteryHistory=live.battery,snapshot=live.latest?.let { FlightSnapshot(it,0f) })
			rebuildLiveTimeline(live)
		}
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
		simulationJob?.cancel()
		liveTimelineJob?.cancel()
		simulationOriginal = null
		liveCursorMillis = null
		terrainStreamingEngine.reset()
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
			previewingPlan = false,
			liveTimeline = null,
			browsingLiveTimeline = false,
			mapFollowing = true,
			trip = resolvedTrip,
			profile = FlightProfilePlanner.fromTrip(resolvedTrip),
			snapshot = firstSnapshot,
			windowPhotoOverlay = FlightWindowPhotoOverlay(),
			terrainDetailFocus = null,
			replayProgress = 0f,
			replayTimelineWindowFraction = 1f,
			replayPlaying = false,
			flightSpans = flightSpans,
			pendingFlightStartProgress = null,
			journeyId = journeyId,
			journeyName = journeyName,
			journeyCreatedAtMillis = journeyCreatedAtMillis,
			journeyDirty = dirty,
			journeySaveError = null,
			photos = sortedPhotos,
			offlineAssets = offlineAssets,
			offlinePreloadStatus = FlightTerrainStatus(),
			pendingPhotos = emptyList(),
			selectedPhotoId = sortedPhotos.firstOrNull()?.id,
			journeyMessage = message,
			loadingTrip = false,
			tripLoadError = null,
			duplicateJourneyWarning = null,
			savedJourneys = uiState.savedJourneys
		)
		refreshSavedJourneys()
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
		val rawSnapshot = replayEngine?.snapshotAt(safeProgress) ?: return
		val snapshot = if (uiState.sessionMode == FlightSessionMode.LIVE) {
			FlightLiveTimeline.withoutFutureMeasurements(rawSnapshot, uiState.liveState.latest?.timestampMillis ?: 0L)
		} else rawSnapshot
		if(uiState.sessionMode==FlightSessionMode.LIVE) liveCursorMillis=snapshot.sample.timestampMillis
		exitWindowPhotoEditing()
		uiState = uiState.copy(
			replayProgress = safeProgress,
			browsingLiveTimeline = uiState.sessionMode == FlightSessionMode.LIVE,
			snapshot = snapshot,
			terrainDetailFocus = null
		)
		requestTerrain(snapshot.sample)
		scheduleTerrainDetailFocus()
	}

	fun setReplayTimelineWindowFraction(fraction: Float) {
		val minimumFraction = minimumFlightTimelineWindowFraction(uiState.liveTimeline ?: uiState.trip)
		uiState = uiState.copy(
			replayTimelineWindowFraction = fraction
				.takeIf { it.isFinite() }
				?.coerceIn(minimumFraction, 1f)
				?: uiState.replayTimelineWindowFraction
		)
	}

	fun toggleReplayPlaying() {
		if (replayEngine == null) return
		if (uiState.sessionMode == FlightSessionMode.LIVE && !uiState.browsingLiveTimeline) seekReplay(uiState.replayProgress)
		uiState = uiState.copy(replayPlaying = !uiState.replayPlaying)
	}

	fun setReplaySpeed(speed: Float) {
		uiState = uiState.copy(replaySpeed = speed.coerceIn(0.25f, 16f))
	}

	fun advanceReplay(realDeltaMillis: Long) {
		if (!uiVisible) return
		val trip = uiState.liveTimeline ?: uiState.trip ?: return
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

	private fun scheduleAutomaticOfflinePreload() {
		// A configurable large package requires the user's explicit estimate/download confirmation.
		if (uiState.plan.preparation!=null || uiState.offlineSimulation) return
		terrainStreamingEngine.scheduleBackgroundWork {
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
		// Reloading tiles alone cannot recover a failed shader/context. Recreate the AndroidView.
		if (uiState.terrainRendererRecovery.error != null) {
			uiState = uiState.copy(terrainRendererRecovery = uiState.terrainRendererRecovery.retry())
		}
		val sample = uiState.snapshot?.sample
			?: (if (uiState.sessionMode == FlightSessionMode.LIVE) null else previewFlightSample())
			?: return
		terrainStreamingEngine.retry(sceneDemand(sample))
	}

	fun setTerrainRendererError(message: String) {
		uiState = uiState.copy(terrainRendererRecovery = uiState.terrainRendererRecovery.failed(message))
	}

	fun setTerrainRenderStats(stats: FlightTerrainRenderStats) {
		if (uiState.terrainRenderStats != stats) {
			uiState = uiState.copy(terrainRenderStats = stats)
		}
	}

	private fun requestTerrain(
		sample: FlightSample,
		reason: FlightSceneDemandReason = FlightSceneDemandReason.AIRCRAFT
	) {
		// Editing dates/bands is not a 3D consumer. Keep resident data, but do not compete with typing.
		if (uiState.page !in listOf(FlightPage.MAP,FlightPage.WINDOW,FlightPage.WINDOW_SETUP)) return
		terrainStreamingEngine.submit(sceneDemand(sample), reason)
	}

	private fun sceneDemand(sample: FlightSample): FlightSceneDemand {
		val plan = uiState.plan
		return FlightSceneDemand(
			aircraft = sample,
			detailFocus = uiState.terrainDetailFocus,
			configuration = FlightSceneStreamingConfiguration(
				radiusKm = plan.terrainCorridorKm,
				satelliteQuality = plan.satelliteQuality,
				terrainFineZoom = plan.terrainFineZoom,
				terrainMiddleZoom = plan.terrainMiddleZoom,
				// The cache page is a stable offline viewer; the live 3D scene belongs to Hublot.
				includeNativeMap = false
			),
			consumers = when (uiState.page) {
				FlightPage.MAP -> setOf(FlightSceneConsumer.MAP, FlightSceneConsumer.BACKGROUND)
				FlightPage.WINDOW -> setOf(
					FlightSceneConsumer.WINDOW,
					FlightSceneConsumer.WINDOW_MINI_MAP,
					FlightSceneConsumer.BACKGROUND
				)
				FlightPage.WINDOW_SETUP -> setOf(FlightSceneConsumer.WINDOW, FlightSceneConsumer.BACKGROUND)
				else -> setOf(FlightSceneConsumer.BACKGROUND)
			},
			motion = when {
				uiState.sessionMode == FlightSessionMode.LIVE -> FlightSceneMotion.LIVE
				uiState.replayPlaying -> FlightSceneMotion.PLAYING
				else -> FlightSceneMotion.MANUAL
			}
		)
	}

	/**
	 * Direction changes are cheap and immediate; detailed imagery is deliberately not.
	 * Keep the existing textures while the user moves, then retarget only after the
	 * optical axis has remained untouched for the streaming engine's settling interval.
	 */
	private fun scheduleTerrainDetailFocus() {
		if (uiState.page != FlightPage.WINDOW) return
		val sample = uiState.snapshot?.sample ?: previewFlightSample() ?: return
		val focus = FlightViewGeometry.groundDetailFocus(
			sample = sample,
			placement = uiState.windowPlacement,
			look = uiState.windowLook,
			altitudeOverrideMeters = uiState.windowAltitudeOverrideMeters,
			maximumDistanceKm = FlightSceneStreamingPolicy.MAXIMUM_GAZE_FOCUS_DISTANCE_KM
		)
		if (!FlightSceneStreamingPolicy.focusChanged(uiState.terrainDetailFocus, focus)) return
		uiState = uiState.copy(terrainDetailFocus = focus)
		requestTerrain(sample, FlightSceneDemandReason.CAMERA)
	}

	/** Once a live focus is stable, let it follow the aircraft without touch jitter. */
	private fun followTerrainDetailFocus(sample: FlightSample) {
		if (uiState.page != FlightPage.WINDOW) return
		val current = uiState.terrainDetailFocus
		if (current == null) {
			scheduleTerrainDetailFocus()
			return
		}
		val next = FlightViewGeometry.groundDetailFocus(
			sample = sample,
			placement = uiState.windowPlacement,
			look = uiState.windowLook,
			altitudeOverrideMeters = uiState.windowAltitudeOverrideMeters,
			maximumDistanceKm = FlightSceneStreamingPolicy.MAXIMUM_GAZE_FOCUS_DISTANCE_KM
		) ?: return
		if (FlightTerrainTilePlanner.distanceKm(
				current.latitude,
				current.longitude,
				next.latitude,
				next.longitude
			) < FlightSceneStreamingPolicy.DETAIL_FOCUS_FOLLOW_DISTANCE_KM
		) return
		uiState = uiState.copy(terrainDetailFocus = next)
		requestTerrain(sample, FlightSceneDemandReason.AIRCRAFT)
	}

	private fun previewFlightSample(): FlightSample? {
		// A planned departure is not a measured live position (nor a measured 10 km altitude).
		if (uiState.sessionMode == FlightSessionMode.LIVE) return null
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
		scheduleTerrainDetailFocus()
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
		scheduleTerrainDetailFocus()
	}

	fun recenterWindowLook() {
		if (uiState.windowLook != FlightWindowLook()) {
			uiState = uiState.copy(windowLook = FlightWindowLook())
			scheduleTerrainDetailFocus()
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
		scheduleTerrainDetailFocus()
	}

	fun setMapFollowing(following: Boolean) {
		if (uiState.mapFollowing != following) {
			uiState = uiState.copy(mapFollowing = following, mapCenterLocked = uiState.mapCenterLocked && following)
		}
	}

	fun setMapCenterLocked(locked: Boolean) {
		uiState = uiState.copy(mapCenterLocked=locked, mapFollowing=locked || uiState.mapFollowing)
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
		schedulePhotoPersistence()
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
		schedulePhotoPersistence()
	}

	fun setSatelliteQuality(quality: FlightSatelliteQuality) {
		if (uiState.plan.satelliteQuality == quality) return
		updatePlan(uiState.plan.copy(satelliteQuality = quality))
		(uiState.snapshot?.sample ?: previewFlightSample())?.let {
			requestTerrain(it, FlightSceneDemandReason.CONFIGURATION)
		}
	}

	fun setTerrainFineZoom(zoom: Int) {
		val fineZoom = zoom.coerceIn(
			FlightPlan.MIN_TERRAIN_DETAIL_ZOOM,
			FlightPlan.MAX_TERRAIN_DETAIL_ZOOM
		)
		val middleZoom = uiState.plan.terrainMiddleZoom.coerceAtMost(fineZoom)
		if (uiState.plan.terrainFineZoom == fineZoom && uiState.plan.terrainMiddleZoom == middleZoom) return
		updatePlan(uiState.plan.copy(terrainFineZoom = fineZoom, terrainMiddleZoom = middleZoom))
		(uiState.snapshot?.sample ?: previewFlightSample())?.let {
			requestTerrain(it, FlightSceneDemandReason.CONFIGURATION)
		}
	}

	fun setTerrainMiddleZoom(zoom: Int) {
		val middleZoom = zoom.coerceIn(
			FlightPlan.MIN_TERRAIN_DETAIL_ZOOM,
			FlightPlan.MAX_TERRAIN_DETAIL_ZOOM
		)
		val fineZoom = uiState.plan.terrainFineZoom.coerceAtLeast(middleZoom)
		if (uiState.plan.terrainFineZoom == fineZoom && uiState.plan.terrainMiddleZoom == middleZoom) return
		updatePlan(uiState.plan.copy(terrainFineZoom = fineZoom, terrainMiddleZoom = middleZoom))
		(uiState.snapshot?.sample ?: previewFlightSample())?.let {
			requestTerrain(it, FlightSceneDemandReason.CONFIGURATION)
		}
	}

	fun setSatelliteQualityOverlay(show: Boolean) {
		if (uiState.showSatelliteQualityOverlay != show) {
			uiState = uiState.copy(showSatelliteQualityOverlay = show)
		}
	}

	fun setRecordingPolicy(policy: FlightRecordingPolicy) {
		uiState = uiState.copy(recordingPolicy = policy)
		if(FlightRecordingService.state.value.running) app.startService(android.content.Intent(app,FlightRecordingService::class.java)
			.setAction(FlightRecordingService.POLICY).putExtra("distance",policy.cruisePointDistanceMeters)
			.putExtra("interval",policy.maximumStraightIntervalSeconds).putExtra("turn",policy.turnAcceleration)
			.putExtra("deviation",policy.routeDeviationAcceleration))
	}

	fun updateJourneyName(name: String) {
		if (uiState.journeyName != name) {
			uiState = uiState.copy(journeyName = name, journeyDirty = true, journeyMessage = null)
			schedulePhotoPersistence()
		}
	}

	fun saveJourney() {
		photoPersistenceJob?.cancel()
		photoPersistenceJob = null
		persistJourney(showConfirmation = true)
	}

	private fun persistJourney(showConfirmation: Boolean) {
		val planned = uiState.sessionMode == FlightSessionMode.PREPARE
		val trip = uiState.trip
		if (!planned && uiState.journeyId == null && trip?.samples.isNullOrEmpty()) {
			if (showConfirmation) uiState = uiState.copy(journeyMessage = "Aucun point à enregistrer")
			return
		}
		val sourceId = uiState.journeyId
		viewModelScope.launch {
			try {
				val saved = saveLocalJournal(sourceId)
				if (uiState.journeyId != saved.id) return@launch
				if (showConfirmation) uiState = uiState.copy(journeyMessage = app.getString(net.osmand.plus.R.string.flight_sync_local_saved))
				if (uiState.page == FlightPage.JOURNAL) refreshStorageUsage()
				if (uiState.journeyDirty) schedulePhotoPersistence()
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				uiState = uiState.copy(
					journeySaveError = error.message ?: "save_failed",
					journeyMessage = error.message ?: "Enregistrement automatique impossible"
				)
			}
		}
	}

	private fun schedulePhotoPersistence() {
		if (uiState.journeyId == null && uiState.trip == null) return
		photoPersistenceJob?.cancel()
		photoPersistenceJob = viewModelScope.launch {
			delay(PHOTO_PERSISTENCE_DEBOUNCE_MILLIS)
			persistJourney(showConfirmation = false)
		}
	}

	fun openJourney(id: String) {
		if (uiState.loadingTrip) return
		// Reopening the selected journal must keep the timeline, retouches and pending photos.
		val alreadyOpen = uiState.journeyId == id && uiState.trip != null
		val open: suspend () -> Unit = {
			openLocalFlight(
				alreadyOpen = alreadyOpen,
				savePending = { savePendingLocalChanges() },
				load = { withContext(Dispatchers.IO) { journeyStore.load(id) } },
				apply = ::applyJourney,
				resume = { showPage(FlightWorkspaceNavigation.resumePage(uiState)) }
			)
		}
		if (alreadyOpen) viewModelScope.launch { open() } else runJournalNavigation(open)
	}

	/** All replace/close paths use the same disk-only save barrier. */
	private fun runJournalNavigation(action: suspend () -> Unit) {
		if (uiState.loadingTrip) return
		if (uiState.pendingPhotos.isNotEmpty()) {
			pendingJournalNavigation = action
			uiState = uiState.copy(confirmJourneyNavigation = true)
			return
		}
		pendingDuplicateTrip = null
		uiState = uiState.copy(loadingTrip = true, tripLoadError = null,
			duplicateJourneyWarning = null, replayPlaying = false, journeyMessage = null)
		viewModelScope.launch {
			try {
				action()
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				showTripLoadFailure(error)
			} finally {
				uiState = uiState.copy(loadingTrip = false)
			}
		}
	}

	private suspend fun savePendingLocalChanges() {
		photoPersistenceJob?.cancelAndJoin()
		photoPersistenceJob = null
		// Also waits for an autosave already writing to disk. Snapshots are captured under the lock.
		preparationSaveMutex.withLock { }
		if (!uiState.journeyDirty) return
		try {
			// Scheduling/GPS permissions belong to explicit preparation actions, not local opening.
			saveLocalJournal()
			if (uiState.journeyDirty) throw java.io.IOException(app.getString(net.osmand.plus.R.string.flight_local_retry_navigation))
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			uiState = uiState.copy(journeySaveError = error.message ?: "save_failed")
			throw java.io.IOException(app.getString(net.osmand.plus.R.string.flight_local_navigation_failed), error)
		}
	}

	fun confirmJournalNavigation() {
		val action = pendingJournalNavigation ?: return
		pendingJournalNavigation = null
		uiState = uiState.copy(confirmJourneyNavigation = false)
		validatePendingPhotos()
		runJournalNavigation(action)
	}

	fun cancelJournalNavigation() {
		pendingJournalNavigation = null
		uiState = uiState.copy(confirmJourneyNavigation = false)
	}

	fun closeWorkspace(close: () -> Unit) {
		runJournalNavigation {
			savePendingLocalChanges()
			close()
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
		if(uiState.previewingPlan) return
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
			offlineAssets = uiState.offlineAssets,
			batteryHistory = uiState.batteryHistory
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
		pendingCaptureSensors = null
		pendingCaptureFile?.let { previous -> if (previous.isFile) previous.delete() }
		return journeyStore.createCaptureFile().also { file ->
			pendingCaptureFile = file
			pendingCaptureTimestampMillis = System.currentTimeMillis()
		}
	}

	fun recordPhotoShutter(capture: FlightPhotoCapture) {
		if (pendingCaptureFile == null) return
		val associated = if (uiState.liveState.simulation) capture.copy(
			shutterMillis=capture.fix?.timestampMillis ?: uiState.liveState.latest?.timestampMillis ?: capture.shutterMillis) else capture
		pendingCaptureSensors = associated
		pendingCaptureTimestampMillis = associated.shutterMillis
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
		).let { photo -> pendingCaptureSensors?.let { capture -> photo.copy(
			timestampMillis=capture.shutterMillis, timestampSource=FlightPhotoTimestampSource.LIVE_CAPTURE,
			matchedSamplePosition=journeyStore.matchPhotoPosition(uiState.trip,capture.shutterMillis), capture=capture
		) } ?: photo }
		pendingCaptureSensors = null
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
		val progress = uiState.progressForRecordedPhoto(photo.matchedSamplePosition)
		if (progress != null) seekReplay(progress)
		else if (uiState.sessionMode == FlightSessionMode.LIVE) seekReplay(uiState.replayProgress)
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
				photo.copy(
					matchedSamplePosition = position,
					windowAlignment = retainedAlignmentForPosition(photo, position)
				),
				if (dateRecovered) {
					"Date retrouvée dans la photo · association à la position GPS interpolée"
				} else {
					"Photo réassociée à la position GPS interpolée dans le temps"
				}
			)
			uiState.progressForRecordedPhoto(position)?.let(::seekReplay)
		}
	}

	fun associatePhotoAtCurrentReplay(id: String) {
		val photo = findPhoto(id) ?: return
		val position = uiState.recordedPhotoPositionAtCursor()
			?.let(FlightSampleInterpolator::quantizePosition)
		if (position == null) {
			uiState = uiState.copy(selectedPhotoId = id, journeyMessage = "Aucun point courant auquel associer cette photo")
			return
		}
		replacePhoto(
			photo.copy(
				matchedSamplePosition = position,
				windowAlignment = retainedAlignmentForPosition(photo, position)
			),
			String.format(Locale.US, "Photo associée au point virtuel %.2f", position + 1.0)
		)
	}

	fun clearPhotoAssociation(id: String) {
		val photo = findPhoto(id) ?: return
		replacePhoto(
			photo.copy(matchedSamplePosition = null, windowAlignment = null),
			"Association de la photo supprimée"
		)
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

	fun setPhotoImageAdjustments(id: String, adjustments: FlightPhotoImageAdjustments) {
		val photo = findPhoto(id) ?: return
		val safe = adjustments.clamped()
		if (photo.imageAdjustments == safe) return
		replacePhoto(photo.copy(imageAdjustments = safe), message = null)
	}

	fun setPhotoCalibration(id: String, calibration: FlightPhotoCalibration) {
		val photo = findPhoto(id) ?: return
		replacePhoto(photo.copy(calibration = calibration), message = null)
	}

	fun preparePhotoCalibration(id: String) {
		val photo = findPhoto(id) ?: return
		val sample = FlightSampleInterpolator.sampleAt(uiState.trip, photo.matchedSamplePosition) ?: return
		terrainStreamingEngine.submit(sceneDemand(sample).copy(
			consumers = setOf(FlightSceneConsumer.WINDOW, FlightSceneConsumer.BACKGROUND),
			motion = FlightSceneMotion.MANUAL
		), FlightSceneDemandReason.PAGE)
	}

	fun openPhotoOnMap(id: String) {
		selectPhoto(id)
		if (findPhoto(id)?.matchedSamplePosition != null) {
			exitWindowPhotoEditing()
			uiState = uiState.copy(
				page = FlightPage.MAP,
				mapFollowing = true,
				terrainDetailFocus = null
			)
			uiState.snapshot?.sample?.let { sample ->
				requestTerrain(sample, FlightSceneDemandReason.PAGE)
			}
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
			// A photo always starts as a world-space object, even without EXIF.
			storeActiveWindowPhotoAlignment()
			if (detectedFov == null) detectAndApplyPhotoPerspective(id, savedAlignment == null)
			scheduleTerrainDetailFocus()
		}
	}

	private fun detectAndApplyPhotoPerspective(id: String, mayInitializeCamera: Boolean) {
		viewModelScope.launch {
			val photo = findPhoto(id) ?: return@launch
			val initialPlacement = uiState.windowPlacement
			val initialLook = uiState.windowLook
			val initialAltitude = uiState.windowAltitudeOverrideMeters
			val detectedFov = withContext(Dispatchers.IO) {
				journeyStore.detectPhotoVerticalFieldOfViewDegrees(photo)
			} ?: return@launch
			val latestPhoto = findPhoto(id) ?: return@launch
			val currentAlignment = latestPhoto.windowAlignment
			// Initial viewport measurement is not a user edit.
			val comparableAlignment = currentAlignment?.copy(
				spatialPose = currentAlignment.spatialPose?.copy(
					referenceAspectRatio = photo.windowAlignment?.spatialPose?.referenceAspectRatio
				)
			)
			if (latestPhoto.cameraVerticalFieldOfViewDegrees == null) {
				replacePhoto(
					latestPhoto.copy(cameraVerticalFieldOfViewDegrees = detectedFov),
					message = null
				)
			}
			// Metadata arriving late must not overwrite a user's calibration or view.
			if (mayInitializeCamera && uiState.windowPhotoOverlay.photoId == id &&
				comparableAlignment == photo.windowAlignment &&
				latestPhoto.rotationDegrees == photo.rotationDegrees &&
				uiState.windowPlacement == initialPlacement && uiState.windowLook == initialLook &&
				uiState.windowAltitudeOverrideMeters == initialAltitude
			) {
				val placement = uiState.windowPlacement.copy(
					zoom = FlightPhotoPerspective.windowZoomForVerticalFieldOfView(detectedFov)
				).clamped()
				uiState = uiState.copy(windowPlacement = placement)
				storeActiveWindowPhotoAlignment(updateViewPose = true)
			}
		}
	}

	fun setWindowPhotoOpacity(opacity: Float) {
		uiState = uiState.copy(
			windowPhotoOverlay = uiState.windowPhotoOverlay.copy(opacity = opacity).clamped()
		)
		storeActiveWindowPhotoAlignment(updateViewPose = false)
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
		storeActiveWindowPhotoAlignment(updateViewPose = false)
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
		scheduleTerrainDetailFocus()
	}

	fun resetWindowPhotoTransform() {
		val current = uiState.windowPhotoOverlay
		uiState = uiState.copy(
			windowPhotoOverlay = current.copy(scale = 1f, offsetXFraction = 0f, offsetYFraction = 0f)
		)
		storeActiveWindowPhotoAlignment(updateViewPose = false)
		current.photoId?.let(::findPhoto)?.takeIf { it.rotationDegrees != 0f }?.let { photo ->
			replacePhoto(photo.copy(rotationDegrees = 0f), "Position et rotation de la photo réinitialisées")
		}
	}

	fun clearWindowPhotoOverlay() {
		exitWindowPhotoEditing()
	}

	private fun exitWindowPhotoEditing() {
		if (uiState.windowPhotoOverlay.photoId == null) return
		storeActiveWindowPhotoAlignment()
		uiState = uiState.copy(windowPhotoOverlay = FlightWindowPhotoOverlay())
	}

	private fun findPhoto(id: String): FlightPhotoAttachment? =
		(uiState.photos + uiState.pendingPhotos).firstOrNull { it.id == id }

	private fun retainedAlignmentForPosition(
		photo: FlightPhotoAttachment,
		newPosition: Double
	): FlightPhotoWindowAlignment? = photo.windowAlignment?.takeIf {
		photo.matchedSamplePosition?.let { previous -> abs(previous - newPosition) < 0.005 } == true
	}

	private fun storeActiveWindowPhotoAlignment(updateViewPose: Boolean = false) {
		val overlay = uiState.windowPhotoOverlay
		val photo = overlay.photoId?.let(::findPhoto) ?: return
		val referencePosition = photo.matchedSamplePosition
		val previous = photo.windowAlignment?.clamped()
		val preserveViewPose = !updateViewPose && previous != null
		val alignment = FlightPhotoWindowAlignment(
			opacity = overlay.opacity,
			scale = overlay.scale,
			offsetXFraction = overlay.offsetXFraction,
			offsetYFraction = overlay.offsetYFraction,
			windowPlacement = if (preserveViewPose) previous.windowPlacement else uiState.windowPlacement,
			windowLook = if (preserveViewPose) previous.windowLook else uiState.windowLook,
			altitudeOverrideMeters = if (preserveViewPose) {
				previous.altitudeOverrideMeters
			} else uiState.windowAltitudeOverrideMeters,
			spatialPose = if (preserveViewPose) {
				previous.spatialPose ?: FlightViewGeometry.photoSpatialPose(
					uiState.trip, referencePosition, previous.windowPlacement,
					previous.windowLook, previous.altitudeOverrideMeters
				)
			} else {
				FlightViewGeometry.photoSpatialPose(
					trip = uiState.trip,
					samplePosition = referencePosition,
					placement = uiState.windowPlacement,
					look = uiState.windowLook,
					altitudeOverrideMeters = uiState.windowAltitudeOverrideMeters
				)?.copy(referenceAspectRatio = previous?.spatialPose?.referenceAspectRatio)
			}
		).clamped()
		if (photo.windowAlignment != alignment) {
			replacePhoto(photo.copy(windowAlignment = alignment), message = null)
		}
	}

	fun initializeWindowPhotoViewport(photoId: String, aspectRatio: Float) {
		if (!aspectRatio.isFinite() || aspectRatio <= 0f) return
		val photo = findPhoto(photoId) ?: return
		val alignment = photo.windowAlignment ?: return
		val pose = alignment.spatialPose ?: return
		if (pose.referenceAspectRatio != null) return
		replacePhoto(photo.copy(windowAlignment = alignment.copy(
			spatialPose = pose.copy(referenceAspectRatio = aspectRatio)
		)), message = null)
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

	override fun onCleared() {
		FlightUiActivity.set(this, null)
		FlightNetworkAccess.release(offlineOwner)
		terrainStreamingEngine.close()
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
		private const val MINIMUM_WINDOW_ALTITUDE_METERS = -500f
		private const val MAXIMUM_WINDOW_ALTITUDE_METERS = 15_000f
		private const val MINIMUM_FLIGHT_SPAN_PROGRESS = 0.0005f
	}
}
