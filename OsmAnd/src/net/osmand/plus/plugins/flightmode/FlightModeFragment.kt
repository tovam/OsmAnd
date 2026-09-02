package net.osmand.plus.plugins.flightmode

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import net.osmand.Location
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener
import net.osmand.plus.R
import net.osmand.plus.base.BaseFullScreenFragment
import net.osmand.plus.track.SelectTrackTabsFragment
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.InsetTargetsCollection
import net.osmand.plus.views.OsmandMapTileView

class FlightModeFragment : BaseFullScreenFragment(), OsmAndLocationListener {

	private lateinit var viewModel: FlightModeViewModel
	private var previousHudVisibility = View.VISIBLE
	private var previousMapState: MapState? = null
	private var lastMapRefreshMillis = 0L
	private var mapInteractionBlockerLayer: FlightMapInteractionBlockerLayer? = null
	private var replayMapLayer: FlightReplayMapLayer? = null
	private var mapTouchDownX = 0f
	private var mapTouchDownY = 0f
	private val mapTouchSlop by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat() }
	private val mapTouchListener = OsmandMapTileView.TouchListener { event ->
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				mapTouchDownX = event.x
				mapTouchDownY = event.y
			}
			MotionEvent.ACTION_POINTER_DOWN -> viewModel.setMapFollowing(false)
			MotionEvent.ACTION_MOVE -> {
				val deltaX = event.x - mapTouchDownX
				val deltaY = event.y - mapTouchDownY
				if (event.pointerCount > 1 || deltaX * deltaX + deltaY * deltaY >= mapTouchSlop * mapTouchSlop) {
					viewModel.setMapFollowing(false)
				}
			}
		}
	}

	private val openTripLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) viewModel.loadTrip(uri)
	}

	private val backPressedCallback = object : OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			when (viewModel.uiState.page) {
				FlightPage.WINDOW_SETUP -> {
					viewModel.saveWindowPlacement()
					viewModel.showPage(FlightPage.WINDOW)
				}
				FlightPage.WINDOW, FlightPage.SATELLITE, FlightPage.SENSORS, FlightPage.PHOTO -> viewModel.showPage(FlightPage.MAP)
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
					onClose = ::close,
					onPageChange = viewModel::showPage,
					onImportTrip = {
						openTripLauncher.launch(
							arrayOf(
								"application/gpx+xml",
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
					onSetPhotoSources = viewModel::setPhotoSources
				)
			}
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
		app.locationProvider.addLocationListener(this)
		activity.refreshMap()
	}

	override fun onPause() {
		app.locationProvider.removeLocationListener(this)
		viewModel.saveWindowPlacement()
		removeMapInteractionGuard()
		restoreMapState()
		val activity = requireMapActivity()
		activity.findViewById<View>(R.id.map_hud_container).visibility = previousHudVisibility
		activity.enableDrawer()
		activity.refreshMap()
		super.onPause()
	}

	override fun updateLocation(location: Location?) {
		if (location != null) {
			viewModel.updateLiveLocation(location, app.locationProvider.gpsInfo)
		}
	}

	override fun getInsetTargets(): InsetTargetsCollection = InsetTargetsCollection()

	private fun showReplayStateOnMap(trip: FlightTrip?, sample: FlightSample?, showPoints: Boolean) {
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
		mapView.addTouchListener(mapTouchListener)
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
		mapView.removeTouchListener(mapTouchListener)
		mapInteractionBlockerLayer?.let(mapView::removeLayer)
		mapInteractionBlockerLayer = null
		replayMapLayer?.let(mapView::removeLayer)
		replayMapLayer = null
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
