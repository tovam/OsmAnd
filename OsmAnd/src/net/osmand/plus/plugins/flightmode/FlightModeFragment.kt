package net.osmand.plus.plugins.flightmode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import net.osmand.plus.R
import net.osmand.plus.Version
import net.osmand.plus.base.BaseFullScreenFragment
import net.osmand.plus.track.SelectTrackTabsFragment
import net.osmand.plus.track.helpers.SelectedGpxFile
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.InsetTargetsCollection
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.plus.views.corenative.NativeCoreContext

class FlightModeFragment : BaseFullScreenFragment() {

	private lateinit var viewModel: FlightModeViewModel
	private var previousHudVisibility = View.VISIBLE
	private var previousMapState: MapState? = null
	private var lastMapRefreshMillis = 0L
	private var mapInteractionBlockerLayer: FlightMapInteractionBlockerLayer? = null
	private var replayMapLayer: FlightReplayMapLayer? = null
	private var previousGpxObjectsDelegate: OsmandMapLayer.CustomMapObjects<SelectedGpxFile>? = null
	private var gpxLayerSuppressed = false
	private var showFlightCamera by mutableStateOf(false)
	private val cameraPermissionLauncher=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		showFlightCamera=granted
	}
	private var previous3DMapsEnabled: Boolean? = null
	private var flightMapViewInitialized = false
	private var flightRendererSetupRequested = false
	private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
		if (granted[Manifest.permission.ACCESS_FINE_LOCATION]==true) viewModel.startLive()
	}
	private val microphonePermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { granted ->
		if (viewModel.uiState.sessionMode == FlightSessionMode.LIVE) {
			FlightRecordingService.microphone(requireContext(),granted,viewModel.uiState.journeyId)
		}
	}
	private val openTripLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) viewModel.loadSource(uri)
	}
	private val openPhotosLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		viewModel.stageReplayPhotos(uris)
	}
	private val exportJourneyLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/zip")
	) { uri ->
		if (uri != null) viewModel.exportJourney(uri)
	}

	private val backPressedCallback = object : OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			if (viewModel.uiState.loadingTrip) return
			if (viewModel.uiState.duplicateJourneyWarning != null) {
				viewModel.dismissDuplicateImport()
				return
			}
			if (viewModel.uiState.page == FlightPage.WINDOW_SETUP) viewModel.saveWindowPlacement()
			val back = FlightWorkspaceNavigation.backPage(viewModel.uiState.page, viewModel.uiState.sessionMode)
			if (back == null) close() else viewModel.showPage(back)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel = ViewModelProvider(this)[FlightModeViewModel::class.java]
		requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		return ComposeView(requireContext()).apply {
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				FlightModeScreen(
					state = viewModel.uiState,
					mapView = app.osmandMap.mapView,
					onClose = ::close,
					onPageChange = viewModel::showPage,
					onDisarmPreparation = viewModel::cancelAutomaticDeparture,
					onOfflineSimulation = viewModel::setOfflineSimulation,
					onImportTrip = {
						openTripLauncher.launch(
							arrayOf(
								"application/gpx+xml",
								"application/zip",
								"application/xml",
								"text/xml",
								"application/octet-stream"
							)
						)
					},
					onSelectInternalTrack = ::openInternalTrack,
					onStartLive = ::startLiveWithPermission,
					onUpdateStop = viewModel::updateStop,
					onSelectCity = viewModel::selectCity,
					onDismissCitySuggestions = viewModel::dismissCitySuggestions,
					onAddStop = viewModel::addStop,
					onRemoveStop = viewModel::removeStop,
					onUpdatePlan = viewModel::updatePlan,
					onSeekReplay = viewModel::seekReplay,
					onSetReplayTimelineWindowFraction = viewModel::setReplayTimelineWindowFraction,
					onToggleReplay = viewModel::toggleReplayPlaying,
					onAdvanceReplay = viewModel::advanceReplay,
					onMapState = ::showReplayStateOnMap,
					onSetWindowAltitudeOverride = viewModel::setWindowAltitudeOverride,
					onMoveWindow = viewModel::moveWindow,
					onSaveWindowPlacement = viewModel::saveWindowPlacement,
					onSetWindowSide = viewModel::setWindowSide,
					onMoveWindowLook = viewModel::moveWindowLook,
					onRecenterWindowLook = viewModel::recenterWindowLook,
					onSetWindowZoom = viewModel::setWindowZoom,
					onChangeWindowZoom = viewModel::changeWindowZoom,
					onSetCabinTransparent = viewModel::setCabinTransparent,
					onSetCabinHidden = viewModel::setCabinHidden,
					onRetryTerrain = viewModel::retryTerrain,
					onTerrainRendererError = viewModel::setTerrainRendererError,
					onTerrainRenderStats = viewModel::setTerrainRenderStats,
					onSetMapFollowing = viewModel::setMapFollowing,
					onSetMapCenterLocked = viewModel::setMapCenterLocked,
					onReturnLive = viewModel::returnToLive,
					onShowTrackPoints = viewModel::setShowTrackPoints,
					onMarkFlightStart = viewModel::markFlightStart,
					onMarkFlightEnd = viewModel::markFlightEnd,
					onCancelFlightStart = viewModel::cancelFlightStart,
					onRemoveFlightSpan = viewModel::removeFlightSpan,
					onSetSatelliteQuality = viewModel::setSatelliteQuality,
					onSetTerrainFineZoom = viewModel::setTerrainFineZoom,
					onSetTerrainMiddleZoom = viewModel::setTerrainMiddleZoom,
					onSetSatelliteQualityOverlay = viewModel::setSatelliteQualityOverlay,
					onSetRecordingPolicy = viewModel::setRecordingPolicy,
					onSetPhotoSources = viewModel::setPhotoSources,
					onPhotoAction = ::handlePhotoAction,
					onValidatePhotos = viewModel::validatePendingPhotos,
					onDiscardPhotos = viewModel::discardPendingPhotos,
					onSelectPhoto = viewModel::togglePhotoSelection,
					onAssociatePhotoAutomatically = viewModel::associatePhotoAutomatically,
					onAssociatePhotoAtCurrentReplay = viewModel::associatePhotoAtCurrentReplay,
					onClearPhotoAssociation = viewModel::clearPhotoAssociation,
					onRotatePhoto = viewModel::rotatePhoto,
					onSetPhotoImageAdjustments = viewModel::setPhotoImageAdjustments,
					onSetPhotoCalibration = viewModel::setPhotoCalibration,
					onPreparePhotoCalibration = viewModel::preparePhotoCalibration,
					onOpenPhotoOnMap = viewModel::openPhotoOnMap,
					onOpenPhotoInWindow = viewModel::openPhotoInWindow,
					onSetWindowPhotoOpacity = viewModel::setWindowPhotoOpacity,
					onSetWindowGestureTarget = viewModel::setWindowGestureTarget,
					onTransformWindowPhoto = viewModel::transformWindowPhoto,
					onTransformLinkedWindowView = viewModel::transformLinkedWindowView,
					onInitializeWindowPhotoViewport = viewModel::initializeWindowPhotoViewport,
					onResetWindowPhotoTransform = viewModel::resetWindowPhotoTransform,
					onClearWindowPhotoOverlay = viewModel::clearWindowPhotoOverlay,
					onUpdateJourneyName = viewModel::updateJourneyName,
					onSaveJourney = viewModel::saveJourney,
					onExportJourney = { exportJourneyLauncher.launch(viewModel.suggestedExportName()) },
					onOpenJourney = viewModel::openJourney,
					onConfirmJournalNavigation = viewModel::confirmJournalNavigation,
					onCancelJournalNavigation = viewModel::cancelJournalNavigation,
					onClearTripLoadError = viewModel::clearTripLoadError,
					onOpenDuplicateJourney = viewModel::openDuplicateJourney,
					onContinueDuplicateImport = viewModel::continueDuplicateImport,
					onDismissDuplicateImport = viewModel::dismissDuplicateImport,
					onSavePreparation = viewModel::savePreparation,
					onNewPreparation = viewModel::newPreparation,
					onPreloadPreparation = viewModel::preloadPreparation,
					onCancelPreparationDownload = viewModel::pausePreparationDownload,
					onRehearsePreparation = { viewModel.rehearsePreparation() },
					onSimulateLive = viewModel::startLiveSimulation,
					onPreparationPermissions = ::showPreparationPermissions,
					onStopLive = { FlightRecordingService.stop(requireContext(),viewModel.uiState.journeyId) },
					onToggleLiveMicrophone = ::toggleLiveMicrophone,
					onLocalJourneyRemoved = viewModel::localJourneyRemoved
				)
				if(showFlightCamera) FlightCameraScreen(viewLifecycleOwner,viewModel.uiState.liveState.latest,
					onClose={showFlightCamera=false},onPrepareFile=viewModel::preparePhotoCapture,onCaptured=viewModel::finishPhotoCapture,
					onShutter=viewModel::recordPhotoShutter)
			}
		}
	}

	private fun handlePhotoAction() {
		if (viewModel.uiState.sessionMode == FlightSessionMode.LIVE) {
			if(ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
				showFlightCamera=true
			else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
		} else {
			openPhotosLauncher.launch(arrayOf("image/*"))
		}
	}

	private fun toggleLiveMicrophone() {
		if (viewModel.uiState.liveState.microphone) FlightRecordingService.microphone(requireContext(),false,viewModel.uiState.journeyId)
		else if(ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
			FlightRecordingService.microphone(requireContext(),true,viewModel.uiState.journeyId)
		else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
	}

	override fun onResume() {
		super.onResume()
		viewModel.setUiVisible(true)
		val activity = requireMapActivity()
		activity.disableDrawer()
		val hud = activity.findViewById<View>(R.id.map_hud_container)
		previousHudVisibility = hud.visibility
		hud.visibility = View.GONE
		captureMapState()
		ensureFlightMapRenderer()
		disableNativeFlightRelief()
		suppressSurfaceGpxTracks()
		installMapInteractionGuard()
		activity.refreshMap()
	}

	override fun onPause() {
		viewModel.setUiVisible(false)
		viewModel.saveWindowPlacement()
		cancelNativeMapGesture()
		removeMapInteractionGuard()
		restoreSurfaceGpxTracks()
		restoreMapState()
		restoreNativeReliefSetting()
		val activity = requireMapActivity()
		activity.findViewById<View>(R.id.map_hud_container).visibility = previousHudVisibility
		activity.enableDrawer()
		activity.refreshMap()
		super.onPause()
	}

	override fun onDestroyView() {
		viewModel.setUiVisible(false)
		// onPause normally performs this cleanup. Repeating it here is deliberate:
		// a fragment transaction or activity recreation must never leave a flight
		// layer or an unfinished gesture attached to OsmAnd's shared map view.
		cancelNativeMapGesture()
		removeMapInteractionGuard()
		restoreSurfaceGpxTracks()
		restoreMapState()
		restoreNativeReliefSetting()
		super.onDestroyView()
	}

	private fun startLiveWithPermission() {
		if (ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
			viewModel.startLive()
		else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
	}

	private fun showPreparationPermissions() {
		val context=requireContext()
		val statusText=android.text.SpannableStringBuilder()
		FlightScheduleManager.permissionStatuses(context).forEach { status ->
			val start=statusText.length
			statusText.append(getString(if(status.granted) R.string.flight_plan_permission_ok else R.string.flight_plan_permission_missing,
				getString(status.label))).append("\n")
			statusText.setSpan(android.text.style.ForegroundColorSpan(if(status.granted) android.graphics.Color.rgb(30,165,95)
				else android.graphics.Color.rgb(210,120,30)),start,statusText.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		androidx.appcompat.app.AlertDialog.Builder(context).setTitle(R.string.flight_plan_permissions)
			.setMessage(android.text.SpannableStringBuilder(getString(R.string.flight_plan_permission_explanation)+"\n\n").append(statusText))
			.setPositiveButton(R.string.flight_plan_open_settings) { _,_ ->
				val alarm=context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
				val action=if(android.os.Build.VERSION.SDK_INT>=31 && !alarm.canScheduleExactAlarms())
					android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM else android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
				startActivity(android.content.Intent(action,android.net.Uri.parse("package:${context.packageName}")))
			}.setNegativeButton(R.string.shared_string_cancel,null).show()
	}

	override fun getInsetTargets(): InsetTargetsCollection = InsetTargetsCollection()

	private fun showReplayStateOnMap(
		trip: FlightTrip?,
		sample: FlightSample?,
		showPoints: Boolean,
		photos: List<FlightPhotoAttachment>
	) {
		// Photo editing must not rebuild the hidden native flight layer on every finger movement.
		if (!isResumed || viewModel.uiState.page != FlightPage.MAP) return
		replayMapLayer?.update(trip, sample, showPoints, photos)
		replayMapLayer?.updateHypothesis(if(viewModel.uiState.sessionMode==FlightSessionMode.LIVE) viewModel.uiState.plan else null,
			viewModel.uiState.liveState.latest)
		if (sample == null || viewModel.uiState.page != FlightPage.MAP || !viewModel.uiState.mapFollowing) return
		val mapView = app.osmandMap.mapView
		if (viewModel.uiState.mapCenterLocked) {
			mapView.setTarget31(net.osmand.util.MapUtils.get31TileNumberX(sample.longitude),
				net.osmand.util.MapUtils.get31TileNumberY(sample.latitude))
		} else mapView.setLatLon(sample.latitude, sample.longitude)
		if (!flightMapViewInitialized) {
			mapView.setElevationAngle(55f)
			flightMapViewInitialized = true
		}
		val now = System.currentTimeMillis()
		if (now - lastMapRefreshMillis >= MAP_REFRESH_INTERVAL_MILLIS) {
			lastMapRefreshMillis = now
			mapView.refreshMap()
		}
	}

	private fun openInternalTrack() {
		SelectTrackTabsFragment.showInstance(
			parentFragmentManager,
			SelectTrackTabsFragment.GpxFileSelectionListener { gpxFile -> viewModel.loadTrip(gpxFile) }
		)
	}

	private fun installMapInteractionGuard() {
		val mapView = app.osmandMap.mapView
		if (mapInteractionBlockerLayer == null) {
			FlightMapInteractionBlockerLayer(requireContext()).also { layer ->
				mapView.addLayer(layer, MAP_INTERACTION_BLOCKER_Z_ORDER)
				mapInteractionBlockerLayer = layer
			}
		}
		if (replayMapLayer == null) {
			FlightReplayMapLayer(requireContext()).also { layer ->
				mapView.addLayer(layer, REPLAY_MAP_LAYER_Z_ORDER)
				replayMapLayer = layer
				val state = viewModel.uiState
				layer.update(state.trip, state.snapshot?.sample, state.showTrackPoints, state.photos)
			}
		}
	}

	private fun removeMapInteractionGuard() {
		val mapView = app.osmandMap.mapView
		mapInteractionBlockerLayer?.let(mapView::removeLayer)
		mapInteractionBlockerLayer = null
		replayMapLayer?.let(mapView::removeLayer)
		replayMapLayer = null
	}

	/** The normal GPX layer is surface-bound; the flight layer replaces it with raised geometry. */
	private fun suppressSurfaceGpxTracks() {
		if (gpxLayerSuppressed) return
		val gpxLayer = app.osmandMap.mapLayers.gpxLayer
		previousGpxObjectsDelegate = gpxLayer.customObjectsDelegate
		gpxLayer.customObjectsDelegate = OsmandMapLayer.CustomMapObjects<SelectedGpxFile>().apply {
			setCustomMapObjects(emptyList())
		}
		gpxLayer.setInvalidated(true)
		gpxLayerSuppressed = true
	}

	private fun restoreSurfaceGpxTracks() {
		if (!gpxLayerSuppressed) return
		val gpxLayer = app.osmandMap.mapLayers.gpxLayer
		gpxLayer.customObjectsDelegate = previousGpxObjectsDelegate
		gpxLayer.setInvalidated(true)
		previousGpxObjectsDelegate = null
		gpxLayerSuppressed = false
	}

	private fun cancelNativeMapGesture() {
		val now = SystemClock.uptimeMillis()
		val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
		try {
			app.osmandMap.mapView.onTouchEvent(cancel)
		} finally {
			cancel.recycle()
		}
	}

	private fun captureMapState() {
		if (previousMapState != null) return
		val mapView = app.osmandMap.mapView
		previousMapState = MapState(
			latitude = mapView.latitude,
			longitude = mapView.longitude,
			zoom = mapView.zoom,
			zoomFloatPart = mapView.zoomFloatPart,
			rotation = mapView.rotate,
			elevationAngle = mapView.elevationAngle
		)
	}

	private fun restoreMapState() {
		val state = previousMapState ?: return
		val mapView = app.osmandMap.mapView
		mapView.setLatLon(state.latitude, state.longitude)
		mapView.setZoomWithFloatPart(state.zoom, state.zoomFloatPart)
		mapView.setRotate(state.rotation, true)
		mapView.setElevationAngle(state.elevationAngle)
		previousMapState = null
	}

	/**
	 * The native OsmAnd relief is deliberately hidden throughout the flight fragment.
	 * Its green tiles otherwise bleed through the regular map and coastal water. The
	 * dedicated renderer applies the visible relief layer only in Satellite et 3D;
	 * Hublot keeps the terrain geometry without those relief colours. Restore the
	 * user's global OsmAnd setting on exit.
	 */
	private fun disableNativeFlightRelief() {
		if (previous3DMapsEnabled != null) return
		previous3DMapsEnabled = app.settings.ENABLE_3D_MAPS.get()
		if (previous3DMapsEnabled == true) {
			app.settings.ENABLE_3D_MAPS.set(false)
			NativeCoreContext.getMapRendererContext()?.recreateHeightmapProvider()
		}
	}

	private fun restoreNativeReliefSetting() {
		val previous = previous3DMapsEnabled ?: return
		if (app.settings.ENABLE_3D_MAPS.get() != previous) {
			app.settings.ENABLE_3D_MAPS.set(previous)
			NativeCoreContext.getMapRendererContext()?.recreateHeightmapProvider()
		}
		previous3DMapsEnabled = null
		flightMapViewInitialized = false
	}

	/**
	 * The flight map relies on the native renderer for camera tilt and elevated replay geometry.
	 * Early OsmAnd Smart APKs used the legacy flavor, which persisted USE_OPENGL_RENDER=false;
	 * Android keeps that preference when a later OpenGL-capable APK is installed as an update.
	 * Re-enable and attach the native renderer when entering flight mode instead of silently
	 * leaving the gesture proxy connected to a renderer that cannot display elevation angles.
	 */
	private fun ensureFlightMapRenderer() {
		val mapView = app.osmandMap.mapView
		if (app.useOpenGlRenderer() && mapView.hasMapRenderer() || flightRendererSetupRequested) return
		if (!Version.isOpenGlAvailable(app)) return

		flightRendererSetupRequested = true
		app.settings.USE_OPENGL_RENDER.set(true)
		val attachRenderer = {
			app.osmandMap.setupRenderingView()
			app.osmandMap.mapView.mapActivity?.refreshMapComplete()
			flightRendererSetupRequested = false
		}
		if (NativeCoreContext.isInit()) {
			attachRenderer()
		} else {
			app.appInitializer.initOpenglAsync { attachRenderer() }
		}
	}

	private fun close() {
		viewModel.closeWorkspace { if (isAdded) parentFragmentManager.popBackStack() }
	}

	private data class MapState(
		val latitude: Double,
		val longitude: Double,
		val zoom: Int,
		val zoomFloatPart: Float,
		val rotation: Float,
		val elevationAngle: Float
	)

	companion object {
		private const val TAG = "FlightModeFragment"
		private const val MAP_REFRESH_INTERVAL_MILLIS = 250L
		private const val MAP_INTERACTION_BLOCKER_Z_ORDER = 1_000f
		private const val REPLAY_MAP_LAYER_Z_ORDER = 999f

		fun showInstance(manager: FragmentManager) {
			if (AndroidUtils.isFragmentCanBeAdded(manager, TAG)) {
				manager.beginTransaction()
					.replace(R.id.fragmentContainer, FlightModeFragment(), TAG)
					.addToBackStack(TAG)
					.commitAllowingStateLoss()
			}
		}
	}
}
