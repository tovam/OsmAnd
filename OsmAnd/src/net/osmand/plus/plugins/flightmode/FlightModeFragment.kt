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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import net.osmand.Location
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener
import net.osmand.plus.R
import net.osmand.plus.base.BaseFullScreenFragment
import net.osmand.plus.track.SelectTrackTabsFragment
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.InsetTargetsCollection

class FlightModeFragment : BaseFullScreenFragment(), OsmAndLocationListener {

	private lateinit var viewModel: FlightModeViewModel
	private var previousHudVisibility = View.VISIBLE
	private var previousMapState: MapState? = null
	private var lastMapRefreshMillis = 0L
	private var mapInteractionBlockerLayer: FlightMapInteractionBlockerLayer? = null
	private var replayMapLayer: FlightReplayMapLayer? = null
	private var environmentRecorder: FlightEnvironmentRecorder? = null
	private var locationUpdatesRegistered = false
	private var externalPhotoCaptureInProgress = false
	private val microphonePermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { granted ->
		if (viewModel.uiState.sessionMode == FlightSessionMode.LIVE) {
			restartEnvironmentRecorder(recordMicrophone = granted)
		}
	}
	private val openTripLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) viewModel.loadSource(uri)
	}
	private val openPhotosLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		viewModel.stageReplayPhotos(uris)
	}
	private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
		externalPhotoCaptureInProgress = false
		viewModel.finishPhotoCapture(success)
	}
	private val exportJourneyLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/zip")
	) { uri ->
		if (uri != null) viewModel.exportJourney(uri)
	}

	private val backPressedCallback = object : OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			when (viewModel.uiState.page) {
				FlightPage.WINDOW_SETUP -> {
					viewModel.saveWindowPlacement()
					viewModel.showPage(FlightPage.WINDOW)
				}
				FlightPage.WINDOW, FlightPage.SATELLITE, FlightPage.SENSORS, FlightPage.PHOTO,
				FlightPage.JOURNEYS -> viewModel.showPage(FlightPage.MAP)
				FlightPage.MAP, FlightPage.PREPARE -> close()
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel = ViewModelProvider(this)[FlightModeViewModel::class.java]
		requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				FlightModeScreen(
					state = viewModel.uiState,
					mapView = app.osmandMap.mapView,
					onClose = ::close,
					onPageChange = viewModel::showPage,
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
					onStartLive = {
						viewModel.startLive()
						app.locationProvider.lastKnownLocation?.let {
							viewModel.updateLiveLocation(it, app.locationProvider.gpsInfo)
						}
						startEnvironmentRecorder(requestPermission = true)
					},
					onUpdateStop = viewModel::updateStop,
					onSelectCity = viewModel::selectCity,
					onDismissCitySuggestions = viewModel::dismissCitySuggestions,
					onAddStop = viewModel::addStop,
					onRemoveStop = viewModel::removeStop,
					onUpdatePlan = viewModel::updatePlan,
					onPreloadTerrain = viewModel::preloadTerrain,
					onSeekReplay = viewModel::seekReplay,
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
					onSetMapFollowing = viewModel::setMapFollowing,
					onShowTrackPoints = viewModel::setShowTrackPoints,
					onMarkFlightStart = viewModel::markFlightStart,
					onMarkFlightEnd = viewModel::markFlightEnd,
					onCancelFlightStart = viewModel::cancelFlightStart,
					onRemoveFlightSpan = viewModel::removeFlightSpan,
					onSetSatelliteOpacity = viewModel::setSatelliteOpacity,
					onSetTerrainOpacity = viewModel::setTerrainOpacity,
					onSetRecordingPolicy = viewModel::setRecordingPolicy,
					onSetPhotoSources = viewModel::setPhotoSources,
					onPhotoAction = ::handlePhotoAction,
					onValidatePhotos = viewModel::validatePendingPhotos,
					onDiscardPhotos = viewModel::discardPendingPhotos,
					onSelectPhoto = viewModel::selectPhoto,
					onUpdateJourneyName = viewModel::updateJourneyName,
					onSaveJourney = viewModel::saveJourney,
					onExportJourney = { exportJourneyLauncher.launch(viewModel.suggestedExportName()) },
					onOpenJourney = viewModel::openJourney
				)
			}
		}
	}

	private fun handlePhotoAction() {
		if (viewModel.uiState.sessionMode == FlightSessionMode.LIVE) {
			val file = viewModel.preparePhotoCapture()
			externalPhotoCaptureInProgress = true
			takePhotoLauncher.launch(AndroidUtils.getUriForFile(requireContext(), file))
		} else {
			openPhotosLauncher.launch(arrayOf("image/*"))
		}
	}

	override fun onResume() {
		super.onResume()
		val activity = requireMapActivity()
		activity.disableDrawer()
		val hud = activity.findViewById<View>(R.id.map_hud_container)
		previousHudVisibility = hud.visibility
		hud.visibility = View.GONE
		captureMapState()
		installMapInteractionGuard()
		startLocationUpdates()
		if (viewModel.uiState.sessionMode == FlightSessionMode.LIVE) {
			startEnvironmentRecorder(requestPermission = false)
		}
		activity.refreshMap()
	}

	override fun onPause() {
		if (!externalPhotoCaptureInProgress) {
			stopLocationUpdates()
			environmentRecorder?.stop()
		}
		viewModel.saveWindowPlacement()
		cancelNativeMapGesture()
		removeMapInteractionGuard()
		restoreMapState()
		val activity = requireMapActivity()
		activity.findViewById<View>(R.id.map_hud_container).visibility = previousHudVisibility
		activity.enableDrawer()
		activity.refreshMap()
		super.onPause()
	}

	override fun onDestroyView() {
		// onPause normally performs this cleanup. Repeating it here is deliberate:
		// a fragment transaction or activity recreation must never leave a flight
		// layer or an unfinished gesture attached to OsmAnd's shared map view.
		cancelNativeMapGesture()
		removeMapInteractionGuard()
		restoreMapState()
		super.onDestroyView()
	}

	override fun onDestroy() {
		stopLocationUpdates()
		environmentRecorder?.stop()
		super.onDestroy()
	}

	override fun updateLocation(location: Location?) {
		if (location != null) {
			viewModel.updateLiveLocation(location, app.locationProvider.gpsInfo)
		}
	}

	override fun getInsetTargets(): InsetTargetsCollection = InsetTargetsCollection()

	private fun showReplayStateOnMap(trip: FlightTrip?, sample: FlightSample?, showPoints: Boolean) {
		if (viewModel.uiState.sessionMode != FlightSessionMode.LIVE) environmentRecorder?.stop()
		replayMapLayer?.update(trip, sample, showPoints)
		if (sample == null || viewModel.uiState.page != FlightPage.MAP || !viewModel.uiState.mapFollowing) return
		val mapView = app.osmandMap.mapView
		mapView.setLatLon(sample.latitude, sample.longitude)
		mapView.setElevationAngle(55f)
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
				layer.update(state.trip, state.snapshot?.sample, state.showTrackPoints)
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

	private fun close() {
		parentFragmentManager.popBackStack()
	}

	private fun startEnvironmentRecorder(requestPermission: Boolean) {
		val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
			PackageManager.PERMISSION_GRANTED
		restartEnvironmentRecorder(recordMicrophone = granted)
		if (requestPermission && !granted) microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
	}

	private fun restartEnvironmentRecorder(recordMicrophone: Boolean) {
		val recorder = environmentRecorder ?: FlightEnvironmentRecorder(requireContext(), viewModel::updateEnvironment)
			.also { environmentRecorder = it }
		recorder.stop()
		recorder.start(recordMicrophone)
	}

	private fun startLocationUpdates() {
		if (!locationUpdatesRegistered) {
			app.locationProvider.addLocationListener(this)
			locationUpdatesRegistered = true
		}
	}

	private fun stopLocationUpdates() {
		if (locationUpdatesRegistered) {
			app.locationProvider.removeLocationListener(this)
			locationUpdatesRegistered = false
		}
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
