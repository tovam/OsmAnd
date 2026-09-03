package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.R
import net.osmand.plus.media.MediaMetadataUtils
import net.osmand.plus.views.OsmandMapTileView
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

private val FlightBackground = Color(0xFF0A0F13)
private val FlightPanel = Color(0xF211181E)
private val FlightPanelStrong = Color(0xFF11181E)
private val FlightHudPanel = Color(0xB80A0F13)
private val FlightLine = Color(0xFF2A3842)
private val FlightMuted = Color(0xFFA5B0B8)
private val FlightText = Color(0xFFF4F7F8)
private val FlightOrange = Color(0xFFFF8B38)
private val FlightBlue = Color(0xFF5DD8FF)
private val FlightGreen = Color(0xFF7BE0A3)
private val FlightWarning = Color(0xFFFFCC66)
private const val MAXIMUM_PHOTO_PREVIEW_PIXELS = 1_600

private val PHOTO_TIME_COMPARATOR = compareBy<FlightPhotoAttachment>(
	{ it.timestampMillis == null },
	{ it.timestampMillis ?: Long.MAX_VALUE },
	{ it.fileName.lowercase() }
)

private data class PhotoPreviewState(val loading: Boolean = true, val bitmap: Bitmap? = null)

private enum class WindowPanel { FLIGHT, VIEW }

@Composable
fun FlightModeScreen(
	state: FlightUiState,
	mapView: OsmandMapTileView? = null,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onImportTrip: () -> Unit,
	onSelectInternalTrack: () -> Unit,
	onStartLive: () -> Unit,
	onUpdateStop: (Int, String) -> Unit,
	onSelectCity: (Int, FlightCitySuggestion) -> Unit,
	onDismissCitySuggestions: (Int) -> Unit,
	onAddStop: () -> Unit,
	onRemoveStop: (Int) -> Unit,
	onUpdatePlan: (FlightPlan) -> Unit,
	onSeekReplay: (Float) -> Unit,
	onToggleReplay: () -> Unit,
	onAdvanceReplay: (Long) -> Unit,
	onMapState: (FlightTrip?, FlightSample?, Boolean, List<FlightPhotoAttachment>) -> Unit,
	onSetWindowAltitudeOverride: (Float?) -> Unit,
	onMoveWindow: (Float, Float) -> Unit,
	onSaveWindowPlacement: () -> Unit,
	onSetWindowSide: (FlightCabinSide) -> Unit,
	onMoveWindowLook: (Float, Float) -> Unit,
	onRecenterWindowLook: () -> Unit,
	onSetWindowZoom: (Float) -> Unit,
	onChangeWindowZoom: (Float) -> Unit,
	onSetCabinTransparent: (Boolean) -> Unit,
	onSetCabinHidden: (Boolean) -> Unit,
	onRetryTerrain: () -> Unit,
	onTerrainRendererError: (String) -> Unit,
	onTerrainRenderStats: (FlightTerrainRenderStats) -> Unit,
	onSetMapFollowing: (Boolean) -> Unit,
	onShowTrackPoints: (Boolean) -> Unit,
	onMarkFlightStart: () -> Unit,
	onMarkFlightEnd: () -> Unit,
	onCancelFlightStart: () -> Unit,
	onRemoveFlightSpan: (Int) -> Unit,
	onSetSatelliteQuality: (FlightSatelliteQuality) -> Unit,
	onSetRecordingPolicy: (FlightRecordingPolicy) -> Unit,
	onSetPhotoSources: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
	onPhotoAction: () -> Unit,
	onValidatePhotos: () -> Unit,
	onDiscardPhotos: () -> Unit,
	onSelectPhoto: (String) -> Unit,
	onAssociatePhotoAutomatically: (String) -> Unit,
	onAssociatePhotoAtCurrentReplay: (String) -> Unit,
	onClearPhotoAssociation: (String) -> Unit,
	onRotatePhoto: (String, Float) -> Unit,
	onOpenPhotoOnMap: (String) -> Unit,
	onOpenPhotoInWindow: (String) -> Unit,
	onSetWindowPhotoOpacity: (Float) -> Unit,
	onSetWindowGestureTarget: (FlightWindowGestureTarget) -> Unit,
	onTransformWindowPhoto: (Float, Float, Float) -> Unit,
	onResetWindowPhotoTransform: () -> Unit,
	onClearWindowPhotoOverlay: () -> Unit,
	onUpdateJourneyName: (String) -> Unit,
	onSaveJourney: () -> Unit,
	onExportJourney: () -> Unit,
	onOpenJourney: (String) -> Unit,
	onOpenDuplicateJourney: () -> Unit,
	onContinueDuplicateImport: () -> Unit,
	onDismissDuplicateImport: () -> Unit
) {
	MaterialTheme(
		colorScheme = darkColorScheme(
			primary = FlightOrange,
			secondary = FlightBlue,
			background = FlightBackground,
			surface = FlightPanelStrong,
			onBackground = FlightText,
			onSurface = FlightText
		)
	) {
		val safeDrawingInsets = WindowInsets.safeDrawing
		LaunchedEffect(state.replayPlaying, state.replaySpeed) {
			while (state.replayPlaying) {
				delay(100)
				onAdvanceReplay(100)
			}
		}
		val mapSample = state.snapshot?.sample
		LaunchedEffect(
			state.trip,
			mapSample,
			state.showTrackPoints,
			state.photos,
			state.pendingPhotos,
			state.page,
			state.mapFollowing
		) {
			onMapState(
				state.trip,
				mapSample,
				state.showTrackPoints,
				(state.photos + state.pendingPhotos).sortedWith(PHOTO_TIME_COMPARATOR)
			)
		}
		LaunchedEffect(state.page, state.terrainScene, state.terrainStatus.phase) {
			val terrainPage = state.page == FlightPage.WINDOW
			val idle = state.terrainStatus.phase == FlightTerrainPhase.IDLE ||
				state.terrainStatus.phase == FlightTerrainPhase.READY
			val missingScene = terrainPage && state.terrainScene == null && idle
			if (missingScene) {
				onRetryTerrain()
			}
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(if (state.page == FlightPage.MAP) Color.Transparent else FlightBackground)
				.drawBehind {
					if (state.page == FlightPage.MAP) {
						val topInset = safeDrawingInsets.getTop(this).toFloat()
						val bottomInset = safeDrawingInsets.getBottom(this).toFloat()
						if (topInset > 0f) {
							drawRect(FlightPanelStrong, size = Size(size.width, topInset))
						}
						if (bottomInset > 0f) {
							drawRect(
								FlightPanelStrong,
								topLeft = Offset(0f, size.height - bottomInset),
								size = Size(size.width, bottomInset)
							)
						}
					}
				}
				.windowInsetsPadding(safeDrawingInsets)
		) {
			when (state.page) {
				FlightPage.PREPARE -> PrepareScreen(
					state = state,
					onClose = onClose,
					onImportTrip = onImportTrip,
					onSelectInternalTrack = onSelectInternalTrack,
					onStartLive = onStartLive,
					onUpdateStop = onUpdateStop,
					onSelectCity = onSelectCity,
					onDismissCitySuggestions = onDismissCitySuggestions,
					onAddStop = onAddStop,
					onRemoveStop = onRemoveStop,
					onUpdatePlan = onUpdatePlan,
					onOpenJourney = onOpenJourney
				)
				FlightPage.MAP -> MapScreen(
					state = state,
					mapView = mapView,
					onClose = onClose,
					onPageChange = onPageChange,
					onSeekReplay = onSeekReplay,
					onToggleReplay = onToggleReplay,
					onSetMapFollowing = onSetMapFollowing,
					onMarkFlightStart = onMarkFlightStart,
					onMarkFlightEnd = onMarkFlightEnd,
					onCancelFlightStart = onCancelFlightStart,
					onRemoveFlightSpan = onRemoveFlightSpan
				)
				FlightPage.WINDOW -> WindowScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onSetAltitudeOverride = onSetWindowAltitudeOverride,
					onSeekReplay = onSeekReplay,
					onToggleReplay = onToggleReplay,
					onSetSide = onSetWindowSide,
					onMoveLook = onMoveWindowLook,
					onRecenterLook = onRecenterWindowLook,
					onChangeZoom = onChangeWindowZoom,
					onSetZoom = onSetWindowZoom,
					onSetCabinTransparent = onSetCabinTransparent,
					onSetCabinHidden = onSetCabinHidden,
					onSetSatelliteQuality = onSetSatelliteQuality,
					onSetShadowsEnabled = { enabled -> onUpdatePlan(state.plan.copy(shadowsEnabled = enabled)) },
					onSetShadowIntensity = { intensity ->
						onUpdatePlan(state.plan.copy(shadowIntensity = intensity.coerceIn(0f, 1f)))
					},
					onSetPhotoOpacity = onSetWindowPhotoOpacity,
					onSetGestureTarget = onSetWindowGestureTarget,
					onTransformPhoto = onTransformWindowPhoto,
					onRotatePhoto = onRotatePhoto,
					onResetPhotoTransform = onResetWindowPhotoTransform,
					onClearPhoto = onClearWindowPhotoOverlay,
					onRetryTerrain = onRetryTerrain,
					onTerrainRendererError = onTerrainRendererError,
					onTerrainRenderStats = onTerrainRenderStats
				)
				FlightPage.WINDOW_SETUP -> WindowSetupScreen(
					state = state,
					onBack = { onPageChange(FlightPage.WINDOW) },
					onMoveWindow = onMoveWindow,
					onSaveWindowPlacement = onSaveWindowPlacement,
					onSetSide = onSetWindowSide
				)
				FlightPage.SATELLITE -> SatelliteScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange
				)
				FlightPage.SENSORS -> SensorsScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onShowTrackPoints = onShowTrackPoints,
					onSetPolicy = onSetRecordingPolicy
				)
				FlightPage.PHOTO -> PhotoScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onSetSources = onSetPhotoSources,
					onPhotoAction = onPhotoAction,
					onValidatePhotos = onValidatePhotos,
					onDiscardPhotos = onDiscardPhotos,
					onSelectPhoto = onSelectPhoto,
					onAssociatePhotoAutomatically = onAssociatePhotoAutomatically,
					onAssociatePhotoAtCurrentReplay = onAssociatePhotoAtCurrentReplay,
					onClearPhotoAssociation = onClearPhotoAssociation,
					onRotatePhoto = onRotatePhoto,
					onOpenPhotoOnMap = onOpenPhotoOnMap,
					onOpenPhotoInWindow = onOpenPhotoInWindow
				)
				FlightPage.JOURNEYS -> JourneysScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onImport = onImportTrip,
					onSelectInternalTrack = onSelectInternalTrack,
					onUpdateName = onUpdateJourneyName,
					onSave = onSaveJourney,
					onExport = onExportJourney,
					onOpen = onOpenJourney
				)
			}

			if (state.loadingTrip) {
				Box(
					modifier = Modifier.fillMaxSize().background(Color(0xD900000000)),
					contentAlignment = Alignment.Center
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						CircularProgressIndicator(color = FlightOrange, strokeWidth = 3.dp)
						Spacer(Modifier.height(14.dp))
						Text(stringResource(R.string.flight_mode_importing), color = FlightText)
					}
				}
			}

			state.duplicateJourneyWarning?.let { journey ->
				DuplicateJourneyWarning(
					journey = journey,
					onOpenExisting = onOpenDuplicateJourney,
					onContinueImport = onContinueDuplicateImport,
					onCancel = onDismissDuplicateImport
				)
			}
		}
	}
}

@Composable
private fun DuplicateJourneyWarning(
	journey: FlightJourneySummary,
	onOpenExisting: () -> Unit,
	onContinueImport: () -> Unit,
	onCancel: () -> Unit
) {
	Box(
		modifier = Modifier.fillMaxSize()
			.background(Color(0xF20A0F13))
			.clickable(onClick = {}),
		contentAlignment = Alignment.Center
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
				.background(FlightPanelStrong)
				.border(2.dp, Color(0xFFFF5A52))
		) {
			Text(
				text = stringResource(R.string.flight_mode_duplicate_gpx_title),
				color = Color.White,
				fontSize = 18.sp,
				fontWeight = FontWeight.Black,
				modifier = Modifier.fillMaxWidth().background(Color(0xFFB4231C)).padding(14.dp)
			)
			Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
				Text(
					text = stringResource(R.string.flight_mode_duplicate_gpx_message),
					color = FlightText,
					fontSize = 13.sp,
					fontWeight = FontWeight.SemiBold
				)
				Spacer(Modifier.height(9.dp))
				Text(journey.name, color = FlightOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
				Text(
					text = stringResource(
						R.string.flight_mode_duplicate_gpx_details,
						journey.sampleCount,
						journey.photoCount,
						formatDateTime(journey.updatedAtMillis)
					),
					color = FlightMuted,
					fontSize = 10.sp
				)
				Spacer(Modifier.height(12.dp))
				FlatButton(
					text = stringResource(R.string.flight_mode_duplicate_open_existing),
					modifier = Modifier.fillMaxWidth(),
					accent = true,
					onClick = onOpenExisting
				)
				Spacer(Modifier.height(7.dp))
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
					FlatButton(
						text = stringResource(R.string.flight_mode_cancel),
						modifier = Modifier.weight(1f),
						accent = false,
						onClick = onCancel
					)
					FlatButton(
						text = stringResource(R.string.flight_mode_duplicate_import_anyway),
						modifier = Modifier.weight(1f),
						accent = false,
						onClick = onContinueImport
					)
				}
			}
		}
	}
}

@Composable
private fun PrepareScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onImportTrip: () -> Unit,
	onSelectInternalTrack: () -> Unit,
	onStartLive: () -> Unit,
	onUpdateStop: (Int, String) -> Unit,
	onSelectCity: (Int, FlightCitySuggestion) -> Unit,
	onDismissCitySuggestions: (Int) -> Unit,
	onAddStop: () -> Unit,
	onRemoveStop: (Int) -> Unit,
	onUpdatePlan: (FlightPlan) -> Unit,
	onOpenJourney: (String) -> Unit
) {
	val focusManager = LocalFocusManager.current
	val focusRequesters = remember(state.plan.stops.size) {
		List(state.plan.stops.size) { FocusRequester() }
	}
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_prepare), FlightSessionMode.PREPARE, onClose)
		LazyColumn(Modifier.weight(1f)) {
			if (state.savedJourneys.isNotEmpty()) {
				item { SectionTitle(stringResource(R.string.flight_mode_saved_journeys, state.savedJourneys.size)) }
				itemsIndexed(state.savedJourneys) { _, journey ->
					SavedJourneyRow(journey = journey, onOpen = onOpenJourney)
				}
				item { Spacer(Modifier.height(8.dp)) }
			}
			item { SectionTitle(stringResource(R.string.flight_mode_route)) }
			itemsIndexed(state.plan.stops) { index, stop ->
				val submitCity: () -> Unit = {
					val suggestion = state.citySuggestions.firstOrNull()
						.takeIf { state.citySearchStopIndex == index }
					if (suggestion != null) {
						onSelectCity(index, suggestion)
					} else {
						onDismissCitySuggestions(index)
					}
					if (index < state.plan.stops.lastIndex) {
						focusRequesters[index + 1].requestFocus()
					} else {
						focusManager.clearFocus()
					}
				}
				FlightStopRow(
					index = index,
					count = state.plan.stops.size,
					name = stop.name,
					focusRequester = focusRequesters[index],
					searchActive = state.citySearchStopIndex == index,
					searchLoading = state.citySearchStopIndex == index && state.citySearchLoading,
					suggestions = if (state.citySearchStopIndex == index) state.citySuggestions else emptyList(),
					onNameChange = { onUpdateStop(index, it) },
					onSubmit = submitCity,
					onSuggestionSelected = { suggestion ->
						onSelectCity(index, suggestion)
						if (index < state.plan.stops.lastIndex) {
							focusRequesters[index + 1].requestFocus()
						} else {
							focusManager.clearFocus()
						}
					},
					onDismissSuggestions = { onDismissCitySuggestions(index) },
					onRemove = { onRemoveStop(index) }
				)
			}
			item {
				FlatTextAction(
					text = stringResource(R.string.flight_mode_add_stop),
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
					onClick = onAddStop
				)
			}
			item { SectionTitle(stringResource(R.string.flight_mode_expected_profile)) }
			item {
				FlightProfileView(
					profile = state.profile,
					progress = null,
					modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp)
				)
				Text(
					text = stringResource(R.string.flight_mode_profile_stopover_hint),
					color = FlightMuted,
					fontSize = 12.sp,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
				)
			}
			item { SectionTitle(stringResource(R.string.flight_mode_preload)) }
			item {
				DenseSettingRow(
					title = stringResource(R.string.flight_mode_terrain_corridor, state.plan.terrainCorridorKm),
					value = "600 km de large"
				)
				DenseSettingRow(
					title = stringResource(R.string.flight_mode_satellite_radius, state.plan.detailedSatelliteRadiusKm),
					value = "autour de l’avion"
				)
				SwitchSettingRow(
					title = stringResource(R.string.flight_mode_shadows),
					checked = state.plan.shadowsEnabled,
					onChecked = { onUpdatePlan(state.plan.copy(shadowsEnabled = it)) }
				)
				SwitchSettingRow(
					title = stringResource(R.string.flight_mode_resume),
					checked = state.plan.resumeAfterRestart,
					onChecked = { onUpdatePlan(state.plan.copy(resumeAfterRestart = it)) }
				)
				TerrainPreloadStatus(state.offlinePreloadStatus)
				Text(
					text = stringResource(R.string.flight_mode_terrain_attribution),
					color = FlightMuted,
					fontSize = 10.sp,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
				)
			}
			state.tripLoadError?.let { error ->
				item {
					Text(error, color = FlightWarning, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
				}
			}
			item { Spacer(Modifier.height(12.dp)) }
		}
		Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine).padding(10.dp)) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				FlatButton(
					text = stringResource(R.string.flight_mode_load_osmand_track),
					modifier = Modifier.weight(1f),
					accent = false,
					onClick = onSelectInternalTrack
				)
				FlatButton(
					text = stringResource(R.string.flight_mode_load_gpx_file),
					modifier = Modifier.weight(1f),
					accent = false,
					onClick = onImportTrip
				)
			}
			Spacer(Modifier.height(8.dp))
			FlatButton(
				text = stringResource(R.string.flight_mode_start_live),
				modifier = Modifier.fillMaxWidth(),
				accent = true,
				onClick = onStartLive
			)
		}
	}
}

@Composable
private fun MapScreen(
	state: FlightUiState,
	mapView: OsmandMapTileView?,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onSeekReplay: (Float) -> Unit,
	onToggleReplay: () -> Unit,
	onSetMapFollowing: (Boolean) -> Unit,
	onMarkFlightStart: () -> Unit,
	onMarkFlightEnd: () -> Unit,
	onCancelFlightStart: () -> Unit,
	onRemoveFlightSpan: (Int) -> Unit
) {
	val sample = state.snapshot?.sample
	val density = LocalDensity.current
	val targetScalePixels = with(density) { 96.dp.toPx() }
	var mapScale by remember(mapView) { mutableStateOf<FlightMapScale?>(null) }
	var mapRotation by remember(mapView) { mutableStateOf(mapView?.rotate ?: 0f) }
	LaunchedEffect(mapView, targetScalePixels) {
		while (true) {
			mapView?.let { view ->
				val tileBox = view.currentRotatedTileBox
				val centerX = tileBox.pixWidth / 2
				val centerY = tileBox.pixHeight / 2
				val rawMeters = tileBox.getDistance(
					centerX,
					centerY,
					centerX + targetScalePixels.roundToInt(),
					centerY
				)
				mapScale = calculateFlightMapScale(rawMeters)
				mapRotation = view.rotate
			}
			delay(250)
		}
	}
	Box(Modifier.fillMaxSize()) {
		if (mapView != null) {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { context ->
					FlightMapGestureProxyView(context, mapView) { onSetMapFollowing(false) }
				},
				update = { proxy ->
					proxy.update(mapView) { onSetMapFollowing(false) }
				}
			)
		}
		Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
			FlightTopBar(routeTitle(state), state.sessionMode, onClose, overlay = true)
			InstrumentStrip(sample, overlay = true)
		}

		Row(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(top = 122.dp, end = 2.dp)
				.height(44.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(2.dp)
		) {
			FlightMapRoundButton(
				icon = R.drawable.ic_action_compass_north,
				tint = if (abs(mapRotation) < 0.5f) FlightMuted else FlightBlue,
				contentDescription = stringResource(R.string.flight_mode_north_up),
				label = "%03d°".format(Math.floorMod(mapRotation.roundToInt(), 360)),
				onClick = { mapView?.resetRotation() }
			)
			FlightMapRoundButton(
				icon = R.drawable.ic_action_center_on_track,
				tint = if (state.mapFollowing) FlightGreen else FlightOrange,
				contentDescription = if (state.mapFollowing) {
					stringResource(R.string.flight_mode_map_free)
				} else {
					stringResource(R.string.flight_mode_map_following)
				},
				onClick = { onSetMapFollowing(!state.mapFollowing) }
			)
		}

		mapScale?.let { scale ->
			FlightMapScaleBar(
				scale = scale,
				modifier = Modifier.align(Alignment.TopStart).padding(top = 128.dp, start = 11.dp)
			)
		}

		Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
			if (state.snapshot?.dataGap == true) {
				Text(
					stringResource(R.string.flight_mode_gap),
					color = FlightWarning,
					fontSize = 12.sp,
					modifier = Modifier.fillMaxWidth().background(Color(0xE31A1510)).padding(9.dp),
					textAlign = TextAlign.Center
				)
			}
			FlightProfileView(
				profile = state.profile,
				progress = state.replayProgress.takeIf { state.sessionMode == FlightSessionMode.REPLAY },
				flightSpans = state.flightSpans,
				pendingStartProgress = state.pendingFlightStartProgress,
				modifier = Modifier.fillMaxWidth().height(122.dp).background(FlightHudPanel).padding(horizontal = 8.dp, vertical = 4.dp)
			)
			if (state.sessionMode == FlightSessionMode.REPLAY) {
				FlightRangeEditor(
					state = state,
					onMarkStart = onMarkFlightStart,
					onMarkEnd = onMarkFlightEnd,
					onCancelStart = onCancelFlightStart,
					onRemoveSpan = onRemoveFlightSpan
				)
				ReplayBar(state, onSeekReplay, onToggleReplay)
			}
			FlightBottomNavigation(FlightPage.MAP, onPageChange, overlay = true)
		}
	}
}

private data class FlightMapScale(val meters: Double, val widthFraction: Float)

private fun calculateFlightMapScale(rawMeters: Double): FlightMapScale? {
	if (!rawMeters.isFinite() || rawMeters <= 0.0) return null
	val power = 10.0.pow(floor(log10(rawMeters)))
	val normalized = rawMeters / power
	val step = when {
		normalized >= 5.0 -> 5.0
		normalized >= 2.0 -> 2.0
		else -> 1.0
	}
	val meters = step * power
	return FlightMapScale(meters, (meters / rawMeters).toFloat().coerceIn(0.15f, 1f))
}

@Composable
private fun FlightMapScaleBar(scale: FlightMapScale, modifier: Modifier = Modifier) {
	Column(
		modifier.background(FlightHudPanel, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
		horizontalAlignment = Alignment.Start
	) {
		Text(
			text = if (scale.meters >= 1_000.0) {
				if (scale.meters >= 10_000.0) "%.0f km".format(Locale.ROOT, scale.meters / 1_000.0)
				else "%.1f km".format(Locale.ROOT, scale.meters / 1_000.0)
			} else "%.0f m".format(Locale.ROOT, scale.meters),
			color = FlightText,
			fontSize = 9.sp,
			fontFamily = FontFamily.Monospace
		)
		Canvas(Modifier.width(96.dp).height(7.dp)) {
			val barWidth = size.width * scale.widthFraction
			val y = size.height - 1.dp.toPx()
			drawLine(FlightText, Offset(0f, y), Offset(barWidth, y), 1.5.dp.toPx())
			drawLine(FlightText, Offset(0f, y - 5.dp.toPx()), Offset(0f, y), 1.5.dp.toPx())
			drawLine(FlightText, Offset(barWidth, y - 5.dp.toPx()), Offset(barWidth, y), 1.5.dp.toPx())
		}
	}
}

@Composable
private fun FlightMapRoundButton(
	icon: Int,
	tint: Color,
	contentDescription: String,
	label: String? = null,
	onClick: () -> Unit
) {
	Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
		Box(
			Modifier.size(34.dp).background(FlightHudPanel, CircleShape).border(1.dp, tint, CircleShape),
			contentAlignment = Alignment.Center
		) {
			androidx.compose.material3.Icon(
				painter = painterResource(icon),
				contentDescription = contentDescription,
				tint = tint,
				modifier = if (label == null) {
					Modifier.size(20.dp)
				} else {
					Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(15.dp)
				}
			)
			if (label != null) {
				Text(
					label,
					color = tint,
					fontSize = 6.sp,
					fontFamily = FontFamily.Monospace,
					modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
				)
			}
		}
	}
}

@Composable
private fun FlightRangeEditor(
	state: FlightUiState,
	onMarkStart: () -> Unit,
	onMarkEnd: () -> Unit,
	onCancelStart: () -> Unit,
	onRemoveSpan: (Int) -> Unit
) {
	Row(
		modifier = Modifier.fillMaxWidth().height(38.dp).background(FlightPanelStrong).border(1.dp, FlightLine)
			.padding(horizontal = 7.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp)
	) {
		Text(
			text = if (state.flightSpans.isEmpty()) {
				stringResource(R.string.flight_mode_no_flight_defined).uppercase()
			} else if (state.flightSpans.size == 1) {
				stringResource(R.string.flight_mode_one_flight_defined).uppercase()
			} else {
				stringResource(R.string.flight_mode_many_flights_defined, state.flightSpans.size).uppercase()
			},
			color = if (state.flightSpans.isEmpty()) FlightMuted else FlightGreen,
			fontSize = 8.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.weight(1f),
			maxLines = 1
		)
		val firstVisibleSpan = (state.flightSpans.size - 3).coerceAtLeast(0)
		for (index in firstVisibleSpan..state.flightSpans.lastIndex) {
			Box(
				Modifier.height(28.dp).border(1.dp, FlightLine).clickable { onRemoveSpan(index) }
					.padding(horizontal = 7.dp),
				contentAlignment = Alignment.Center
			) {
				Text(stringResource(R.string.flight_mode_flight_number_remove, index + 1), color = FlightBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
			}
		}
		if (state.pendingFlightStartProgress == null) {
			CompactAction(stringResource(R.string.flight_mode_mark_start, (state.replayProgress * 100).roundToInt()).uppercase(), FlightOrange, onMarkStart)
		} else {
			CompactAction(stringResource(R.string.flight_mode_cancel).uppercase(), FlightMuted, onCancelStart)
			CompactAction(stringResource(R.string.flight_mode_mark_end, (state.replayProgress * 100).roundToInt()).uppercase(), FlightOrange, onMarkEnd)
		}
	}
}

@Composable
private fun CompactAction(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Box(
		modifier.height(28.dp).border(1.dp, color).clickable(onClick = onClick).padding(horizontal = 7.dp),
		contentAlignment = Alignment.Center
	) {
		Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
	}
}

@Composable
private fun WindowScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onSetAltitudeOverride: (Float?) -> Unit,
	onSeekReplay: (Float) -> Unit,
	onToggleReplay: () -> Unit,
	onSetSide: (FlightCabinSide) -> Unit,
	onMoveLook: (Float, Float) -> Unit,
	onRecenterLook: () -> Unit,
	onChangeZoom: (Float) -> Unit,
	onSetZoom: (Float) -> Unit,
	onSetCabinTransparent: (Boolean) -> Unit,
	onSetCabinHidden: (Boolean) -> Unit,
	onSetSatelliteQuality: (FlightSatelliteQuality) -> Unit,
	onSetShadowsEnabled: (Boolean) -> Unit,
	onSetShadowIntensity: (Float) -> Unit,
	onSetPhotoOpacity: (Float) -> Unit,
	onSetGestureTarget: (FlightWindowGestureTarget) -> Unit,
	onTransformPhoto: (Float, Float, Float) -> Unit,
	onRotatePhoto: (String, Float) -> Unit,
	onResetPhotoTransform: () -> Unit,
	onClearPhoto: () -> Unit,
	onRetryTerrain: () -> Unit,
	onTerrainRendererError: (String) -> Unit,
	onTerrainRenderStats: (FlightTerrainRenderStats) -> Unit
) {
	var panel by remember(state.sessionMode) {
		mutableStateOf(if (state.sessionMode == FlightSessionMode.REPLAY) WindowPanel.FLIGHT else WindowPanel.VIEW)
	}
	val overlayPhoto = state.windowPhotoOverlay.photoId?.let { photoId ->
		(state.photos + state.pendingPhotos).firstOrNull { it.id == photoId }
	}
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_window), state.sessionMode, onClose)
		Box(Modifier.weight(1f).fillMaxWidth()) {
			FlightWindowScene(
				placement = state.windowPlacement,
				look = state.windowLook,
				trip = state.trip,
				sample = state.snapshot?.sample,
				scene = state.terrainScene,
				terrainStatus = state.terrainStatus,
				terrainRenderStats = state.terrainRenderStats,
				altitudeOverrideMeters = state.windowAltitudeOverrideMeters,
				shadingEnabled = state.plan.shadowsEnabled,
				shadowIntensity = state.plan.shadowIntensity,
				satelliteOpacity = state.satelliteOpacity,
				satelliteQuality = state.plan.satelliteQuality,
				photo = overlayPhoto,
				photoOverlay = state.windowPhotoOverlay,
				onSetSide = onSetSide,
				onMoveLook = onMoveLook,
				onRecenterLook = onRecenterLook,
				onChangeZoom = onChangeZoom,
				onTransformPhoto = onTransformPhoto,
				onSetPhotoOpacity = onSetPhotoOpacity,
				onSetGestureTarget = onSetGestureTarget,
				onResetPhotoTransform = onResetPhotoTransform,
				onRotatePhoto = onRotatePhoto,
				onClearPhoto = onClearPhoto,
				onSetShadowsEnabled = onSetShadowsEnabled,
				onRetryTerrain = onRetryTerrain,
				onRendererError = onTerrainRendererError,
				onRenderStats = onTerrainRenderStats,
				modifier = Modifier.fillMaxSize()
			)
		}
		WindowPanelSelector(panel = panel, onSelect = { panel = it })
		when (panel) {
			WindowPanel.FLIGHT -> {
				if (state.sessionMode == FlightSessionMode.REPLAY) {
					FlightProfileView(
						profile = state.profile,
						progress = state.replayProgress,
						flightSpans = state.flightSpans,
						modifier = Modifier.fillMaxWidth().height(66.dp).background(FlightPanelStrong)
							.padding(horizontal = 7.dp, vertical = 3.dp)
					)
					ReplayBar(state, onSeekReplay, onToggleReplay)
				} else {
					CompactInstrumentStrip(state.snapshot?.sample)
				}
			}
			WindowPanel.VIEW -> {
				LazyColumn(Modifier.fillMaxWidth().height(176.dp)) {
					item { SatelliteQualitySelector(state.plan.satelliteQuality, onSetSatelliteQuality) }
					item {
						WindowSunControls(
							enabled = state.plan.shadowsEnabled,
							intensity = state.plan.shadowIntensity,
							onSetEnabled = onSetShadowsEnabled,
							onSetIntensity = onSetShadowIntensity
						)
					}
					item {
						AltitudeOverrideControls(
							reportedAltitudeMeters = state.snapshot?.sample?.altitudeMeters?.toFloat(),
							overrideAltitudeMeters = state.windowAltitudeOverrideMeters,
							onSetOverride = onSetAltitudeOverride
						)
					}
					item {
						WindowViewControls(
							placement = state.windowPlacement,
							onOpenSetup = { onPageChange(FlightPage.WINDOW_SETUP) },
							onSetZoom = onSetZoom,
							onSetCabinTransparent = onSetCabinTransparent,
							onSetCabinHidden = onSetCabinHidden
						)
					}
				}
			}
		}
		FlightBottomNavigation(FlightPage.WINDOW, onPageChange)
	}
}

@Composable
private fun WindowPanelSelector(panel: WindowPanel, onSelect: (WindowPanel) -> Unit) {
	Row(Modifier.fillMaxWidth().height(32.dp).background(FlightPanelStrong).border(1.dp, FlightLine)) {
		listOf(
			WindowPanel.FLIGHT to stringResource(R.string.flight_mode_route_replay).uppercase(),
			WindowPanel.VIEW to stringResource(R.string.flight_mode_display).uppercase()
		).forEach { (item, label) ->
			Box(
				Modifier.weight(1f).fillMaxHeight().clickable { onSelect(item) },
				contentAlignment = Alignment.Center
			) {
				if (panel == item) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(FlightOrange))
				Text(label, color = if (panel == item) FlightText else FlightMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
			}
		}
	}
}

@Composable
private fun CompactInstrumentStrip(sample: FlightSample?) {
	Row(
		Modifier.fillMaxWidth().height(48.dp).background(FlightPanelStrong).border(1.dp, FlightLine),
		verticalAlignment = Alignment.CenterVertically
	) {
		CompactMetric(stringResource(R.string.flight_mode_altitude_short).uppercase(), sample?.altitudeMeters?.let { "%.0f m".format(it) } ?: "—", Modifier.weight(1f))
		CompactMetric(stringResource(R.string.flight_mode_speed_short).uppercase(), sample?.speedMetersPerSecond?.let { "%.0f km/h".format(it * 3.6f) } ?: "—", Modifier.weight(1f))
		CompactMetric(stringResource(R.string.flight_mode_gps).uppercase(), sample?.satellitesUsed?.let { "$it/${sample.satellitesFound ?: 0}" } ?: "—", Modifier.weight(1f))
		CompactMetric(stringResource(R.string.flight_mode_time_short).uppercase(), sample?.timestampMillis?.takeIf { it > 0L }?.let(::formatClock) ?: "—", Modifier.weight(1f))
	}
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier) {
	Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.Center) {
		Text(label, color = FlightMuted, fontSize = 7.sp, maxLines = 1)
		Text(value, color = FlightText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
	}
}

@Composable
private fun WindowSetupScreen(
	state: FlightUiState,
	onBack: () -> Unit,
	onMoveWindow: (Float, Float) -> Unit,
	onSaveWindowPlacement: () -> Unit,
	onSetSide: (FlightCabinSide) -> Unit
) {
	val placement = state.windowPlacement
	val finishSetup = {
		onSaveWindowPlacement()
		onBack()
	}
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightBackTopBar(stringResource(R.string.flight_mode_window_setup), finishSetup)
		Text(
			stringResource(R.string.flight_mode_window_setup_help),
			color = FlightMuted,
			fontSize = 12.sp,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
			textAlign = TextAlign.Center
		)
		CabinSideSelector(placement.side, onSetSide)
		WindowPlacementDiagram(
			placement = placement,
			onMoveWindow = onMoveWindow,
			onDragFinished = onSaveWindowPlacement,
			modifier = Modifier.weight(1f).fillMaxWidth()
		)
		Text(
			windowPlacementText(placement),
			color = FlightBlue,
			fontSize = 13.sp,
			fontFamily = FontFamily.Monospace,
			modifier = Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)
				.padding(horizontal = 12.dp, vertical = 10.dp),
			textAlign = TextAlign.Center
		)
		FlatButton(
			text = stringResource(R.string.flight_mode_done),
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			accent = true,
			onClick = finishSetup
		)
	}
}

@Composable
private fun SatelliteScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit
) {
	var cacheInfo by remember { mutableStateOf(FlightSatelliteCacheInfo()) }
	val refreshKey = "${state.offlinePreloadStatus.phase}:${state.offlinePreloadStatus.availableTiles}:" +
		"${state.offlinePreloadStatus.satelliteTiles}:${state.offlineAssets.terrainTileCount}:" +
		state.offlineAssets.standardSatelliteTileCount
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_cached_tiles), state.sessionMode, onClose)
		Box(Modifier.weight(1f).fillMaxWidth()) {
			AndroidView(
				modifier = Modifier.fillMaxSize(),
				factory = { context -> FlightSatelliteCacheView(context) },
				update = { view ->
					view.onCacheInfoChanged = { cacheInfo = it }
					view.setRefreshKey(refreshKey)
				}
			)
			Column(
				Modifier.align(Alignment.TopCenter).fillMaxWidth().background(FlightHudPanel)
					.border(1.dp, FlightLine).padding(horizontal = 10.dp, vertical = 5.dp)
			) {
				Text(
					text = when {
						cacheInfo.loading -> stringResource(R.string.flight_mode_satellite_loading)
						cacheInfo.zoom != null -> stringResource(
							R.string.flight_mode_offline_tile_count,
							cacheInfo.satelliteTileCount,
							cacheInfo.terrainTileCount,
							cacheInfo.zoom ?: 0
						)
						else -> stringResource(R.string.flight_mode_satellite_empty)
					},
					color = if (cacheInfo.tileCount > 0) FlightGreen else FlightMuted,
					fontSize = 11.sp,
					fontWeight = FontWeight.SemiBold
				)
				Text(stringResource(R.string.flight_mode_offline_tiles_help), color = FlightMuted, fontSize = 9.sp)
			}
			Text(
				text = stringResource(R.string.flight_mode_satellite_attribution_short),
				color = FlightMuted,
				fontSize = 8.sp,
				modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(FlightHudPanel)
					.padding(horizontal = 8.dp, vertical = 4.dp)
			)
		}
		Row(
			Modifier.fillMaxWidth().height(31.dp).background(FlightPanelStrong).padding(horizontal = 9.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				terrainStatusText(state.offlinePreloadStatus),
				color = if (state.offlinePreloadStatus.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightMuted,
				fontSize = 8.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f)
			)
		}
		FlightBottomNavigation(FlightPage.SATELLITE, onPageChange)
	}
}

@Composable
private fun SatelliteQualitySelector(
	quality: FlightSatelliteQuality,
	onSetQuality: (FlightSatelliteQuality) -> Unit
) {
	Row(
		Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 9.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp)
	) {
		Text(
			stringResource(R.string.flight_mode_satellite_quality).uppercase(),
			color = FlightMuted,
			fontSize = 8.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.width(61.dp)
		)
		FlightSatelliteQuality.values().forEach { item ->
			val selected = item == quality
			Box(
				Modifier.weight(1f).height(25.dp)
					.background(if (selected) FlightBlue.copy(alpha = 0.16f) else Color.Transparent)
					.border(1.dp, if (selected) FlightBlue else FlightLine)
					.clickable { onSetQuality(item) },
				contentAlignment = Alignment.Center
			) {
				Text(
					when (item) {
						FlightSatelliteQuality.STANDARD -> stringResource(R.string.flight_mode_satellite_quality_standard)
						FlightSatelliteQuality.HIGH -> stringResource(R.string.flight_mode_satellite_quality_high)
						FlightSatelliteQuality.ULTRA -> stringResource(R.string.flight_mode_satellite_quality_ultra)
						FlightSatelliteQuality.ULTRA_PLUS -> stringResource(R.string.flight_mode_satellite_quality_ultra_plus)
					},
					color = if (selected) FlightBlue else FlightText,
					fontSize = 8.sp,
					fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
				)
			}
		}
	}
}

@Composable
private fun SensorsScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onShowTrackPoints: (Boolean) -> Unit,
	onSetPolicy: (FlightRecordingPolicy) -> Unit
) {
	val sample = state.snapshot?.sample
	val currentSpeed = sample?.speedMetersPerSecond
	val interval = currentSpeed?.let(state.recordingPolicy::intervalSeconds)
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_sensors), state.sessionMode, onClose)
		LazyColumn(Modifier.weight(1f)) {
			item { SectionTitle("POSITION") }
			item {
				SensorReadout(sample)
			}
			item { SectionTitle("ENVIRONNEMENT") }
			item {
				EnvironmentSensorRow(
					title = stringResource(R.string.flight_mode_sound),
					value = sample?.soundDb?.let { "%.1f dB".format(it) }
				)
				SpectrumStrip(sample?.soundSpectrum)
				EnvironmentSensorRow(
					title = stringResource(R.string.flight_mode_vibration),
					value = sample?.vibrationHz?.let { "%.1f Hz".format(it) }
				)
			}
			item {
				SectionTitle(
					if (state.sessionMode == FlightSessionMode.LIVE) stringResource(R.string.flight_mode_recording)
					else stringResource(R.string.flight_mode_recording_finished)
				)
			}
			item {
				if (state.sessionMode == FlightSessionMode.LIVE) {
					Text(
						text = if (interval != null && currentSpeed != null) {
							stringResource(R.string.flight_mode_recording_now, interval, interval * currentSpeed)
						} else {
							stringResource(R.string.flight_mode_recording_waiting)
						},
						color = FlightGreen,
						fontSize = 10.sp,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
					)
					PolicySlider(
						title = stringResource(R.string.flight_mode_cruise_spacing),
						valueText = "${state.recordingPolicy.cruisePointDistanceMeters.toInt()} m",
						value = state.recordingPolicy.cruisePointDistanceMeters,
						range = 250f..4_000f,
						onValue = { onSetPolicy(state.recordingPolicy.copy(cruisePointDistanceMeters = it)) }
					)
					PolicySlider(
						title = stringResource(R.string.flight_mode_turn_boost, state.recordingPolicy.turnAcceleration),
						valueText = "≥ 1°/s",
						value = state.recordingPolicy.turnAcceleration,
						range = 1f..5f,
						onValue = { onSetPolicy(state.recordingPolicy.copy(turnAcceleration = it)) }
					)
					PolicySlider(
						title = stringResource(R.string.flight_mode_deviation_boost, state.recordingPolicy.routeDeviationAcceleration),
						valueText = "> 5 km",
						value = state.recordingPolicy.routeDeviationAcceleration,
						range = 1f..5f,
						onValue = { onSetPolicy(state.recordingPolicy.copy(routeDeviationAcceleration = it)) }
					)
				} else {
					Text(
						stringResource(R.string.flight_mode_recording_read_only),
						color = FlightMuted,
						fontSize = 11.sp,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
					)
				}
				CompactToggleRow(
					title = stringResource(R.string.flight_mode_show_points),
					checked = state.showTrackPoints,
					onChecked = onShowTrackPoints
				)
			}
		}
		FlightBottomNavigation(FlightPage.SENSORS, onPageChange)
	}
}

@Composable
private fun PhotoScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onSetSources: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
	onPhotoAction: () -> Unit,
	onValidatePhotos: () -> Unit,
	onDiscardPhotos: () -> Unit,
	onSelectPhoto: (String) -> Unit,
	onAssociatePhotoAutomatically: (String) -> Unit,
	onAssociatePhotoAtCurrentReplay: (String) -> Unit,
	onClearPhotoAssociation: (String) -> Unit,
	onRotatePhoto: (String, Float) -> Unit,
	onOpenPhotoOnMap: (String) -> Unit,
	onOpenPhotoInWindow: (String) -> Unit
) {
	var fullScreenPhotoId by remember { mutableStateOf<String?>(null) }
	val fullScreenPhoto = fullScreenPhotoId?.let { id ->
		(state.photos + state.pendingPhotos).firstOrNull { it.id == id }
	}
	Box(Modifier.fillMaxSize().background(FlightBackground)) {
		Column(Modifier.fillMaxSize()) {
			FlightTopBar(stringResource(R.string.flight_mode_photo), state.sessionMode, onClose)
			LazyColumn(Modifier.weight(1f)) {
			if (state.sessionMode == FlightSessionMode.LIVE) {
				item { SectionTitle(stringResource(R.string.flight_mode_photo_composition).uppercase()) }
				item { SourceToggle(stringResource(R.string.flight_mode_main_camera), state.photoMainCamera) { onSetSources(it, null, null, null) } }
				item { SourceToggle(stringResource(R.string.flight_mode_selfie), state.photoSelfie) { onSetSources(null, it, null, null) } }
				item { SourceToggle(stringResource(R.string.flight_mode_current_map), state.photoMap) { onSetSources(null, null, it, null) } }
				item { SourceToggle(stringResource(R.string.flight_mode_current_3d), state.photoScene3d) { onSetSources(null, null, null, it) } }
				item {
					Text(
						stringResource(R.string.flight_mode_live_photo_help),
						color = FlightMuted,
						fontSize = 11.sp,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
					)
				}
			} else {
				item { SectionTitle(stringResource(R.string.flight_mode_replay_photos).uppercase()) }
				item {
					Text(
						stringResource(R.string.flight_mode_replay_photo_help),
						color = FlightMuted,
						fontSize = 11.sp,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
					)
				}
			}
			item {
				FlatButton(
					text = if (state.sessionMode == FlightSessionMode.LIVE) {
						stringResource(R.string.flight_mode_take_photo)
					} else {
						stringResource(R.string.flight_mode_add_gallery_photos)
					},
					modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
					accent = true,
					onClick = onPhotoAction
				)
			}
			if (state.pendingPhotos.isNotEmpty()) {
				item { SectionTitle(stringResource(R.string.flight_mode_photos_to_confirm, state.pendingPhotos.size)) }
				itemsIndexed(state.pendingPhotos.sortedWith(PHOTO_TIME_COMPARATOR)) { _, photo ->
					FlightPhotoEntry(
						photo = photo,
						selected = photo.id == state.selectedPhotoId,
						trip = state.trip,
						sessionMode = state.sessionMode,
						onSelect = onSelectPhoto,
						onAssociateAutomatically = onAssociatePhotoAutomatically,
						onAssociateHere = onAssociatePhotoAtCurrentReplay,
						onClearAssociation = onClearPhotoAssociation,
						onOpenMap = onOpenPhotoOnMap,
						onOpenWindow = onOpenPhotoInWindow,
						onOpenPhoto = { fullScreenPhotoId = it },
						onRotatePhoto = onRotatePhoto
					)
				}
				item {
					Row(
						Modifier.fillMaxWidth().padding(10.dp),
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						FlatButton(stringResource(R.string.flight_mode_cancel), Modifier.weight(1f), false, onDiscardPhotos)
						FlatButton(stringResource(R.string.flight_mode_confirm_photos), Modifier.weight(1f), true, onValidatePhotos)
					}
				}
			}
			if (state.photos.isNotEmpty()) {
				item { SectionTitle(stringResource(R.string.flight_mode_attached_photos, state.photos.size)) }
				itemsIndexed(state.photos.sortedWith(PHOTO_TIME_COMPARATOR)) { _, photo ->
					FlightPhotoEntry(
						photo = photo,
						selected = photo.id == state.selectedPhotoId,
						trip = state.trip,
						sessionMode = state.sessionMode,
						onSelect = onSelectPhoto,
						onAssociateAutomatically = onAssociatePhotoAutomatically,
						onAssociateHere = onAssociatePhotoAtCurrentReplay,
						onClearAssociation = onClearPhotoAssociation,
						onOpenMap = onOpenPhotoOnMap,
						onOpenWindow = onOpenPhotoInWindow,
						onOpenPhoto = { fullScreenPhotoId = it },
						onRotatePhoto = onRotatePhoto
					)
				}
			}
			state.journeyMessage?.let { message ->
				item { Text(message, color = FlightGreen, fontSize = 11.sp, modifier = Modifier.padding(12.dp)) }
			}
			}
			FlightBottomNavigation(FlightPage.PHOTO, onPageChange)
		}
		fullScreenPhoto?.let { photo ->
			FlightPhotoFullscreen(
				photo = photo,
				onClose = { fullScreenPhotoId = null },
				onRotate = { deltaDegrees -> onRotatePhoto(photo.id, deltaDegrees) }
			)
		}
	}
}

@Composable
private fun FlightPhotoEntry(
	photo: FlightPhotoAttachment,
	selected: Boolean,
	trip: FlightTrip?,
	sessionMode: FlightSessionMode,
	onSelect: (String) -> Unit,
	onAssociateAutomatically: (String) -> Unit,
	onAssociateHere: (String) -> Unit,
	onClearAssociation: (String) -> Unit,
	onOpenMap: (String) -> Unit,
	onOpenWindow: (String) -> Unit,
	onOpenPhoto: (String) -> Unit,
	onRotatePhoto: (String, Float) -> Unit
) {
	val sample = FlightSampleInterpolator.sampleAt(trip, photo.matchedSamplePosition)
	Column {
		FlightPhotoRow(photo, selected, sample, onSelect)
		if (selected) {
			FlightPhotoPreview(photo, onOpenPhoto)
			FlightPhotoMetadata(photo, sample, trip)
			Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
				CompactAction(
					stringResource(R.string.flight_mode_photo_open_fullscreen).uppercase(),
					FlightBlue,
					{ onOpenPhoto(photo.id) },
					Modifier.weight(1f)
				)
			}
			if (sessionMode == FlightSessionMode.REPLAY) {
				PhotoAssociationControls(
					photo = photo,
					hasMatch = sample != null,
					onAssociateAutomatically = onAssociateAutomatically,
					onAssociateHere = onAssociateHere,
					onClearAssociation = onClearAssociation,
					onOpenMap = onOpenMap,
					onOpenWindow = onOpenWindow
				)
			}
		}
	}
}

@Composable
private fun PhotoAssociationControls(
	photo: FlightPhotoAttachment,
	hasMatch: Boolean,
	onAssociateAutomatically: (String) -> Unit,
	onAssociateHere: (String) -> Unit,
	onClearAssociation: (String) -> Unit,
	onOpenMap: (String) -> Unit,
	onOpenWindow: (String) -> Unit
) {
	Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
			CompactAction(
				when {
					photo.timestampMillis != null -> stringResource(
						R.string.flight_mode_photo_match_at,
						formatClock(photo.timestampMillis)
					).uppercase()
					hasMatch -> stringResource(R.string.flight_mode_photo_redetect_and_rematch).uppercase()
					else -> stringResource(R.string.flight_mode_photo_match_automatically).uppercase()
				},
				FlightBlue,
				{ onAssociateAutomatically(photo.id) },
				Modifier.weight(1f)
			)
			CompactAction(
				stringResource(R.string.flight_mode_photo_match_here).uppercase(),
				FlightOrange,
				{ onAssociateHere(photo.id) },
				Modifier.weight(1f)
			)
		}
		if (hasMatch) {
			Spacer(Modifier.height(5.dp))
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
				CompactAction(
					stringResource(R.string.flight_mode_photo_open_map).uppercase(),
					FlightGreen,
					{ onOpenMap(photo.id) },
					Modifier.weight(1f)
				)
				CompactAction(
					stringResource(R.string.flight_mode_photo_open_window).uppercase(),
					FlightBlue,
					{ onOpenWindow(photo.id) },
					Modifier.weight(1f)
				)
				CompactAction(
					stringResource(R.string.flight_mode_photo_unmatch).uppercase(),
					FlightMuted,
					{ onClearAssociation(photo.id) },
					Modifier.weight(1f)
				)
			}
		}
	}
}

@Composable
private fun FlightPhotoRow(
	photo: FlightPhotoAttachment,
	selected: Boolean,
	matchedSample: FlightSample?,
	onSelect: (String) -> Unit
) {
	val matchedTimestamp = matchedSample?.timestampMillis?.takeIf { it > 0L }
	val dateLabel = when {
		photo.timestampMillis != null -> formatDateTime(photo.timestampMillis)
		matchedTimestamp != null -> stringResource(
			R.string.flight_mode_photo_date_inferred_short,
			formatDateTime(matchedTimestamp)
		)
		matchedSample != null -> stringResource(R.string.flight_mode_photo_matched_without_time)
		else -> stringResource(R.string.flight_mode_photo_without_date)
	}
	Row(
		Modifier.fillMaxWidth().height(46.dp)
			.background(if (selected) Color(0x2217BDE3) else Color.Transparent)
			.clickable { onSelect(photo.id) }
			.padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(photo.fileName, color = FlightText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		Column(horizontalAlignment = Alignment.End) {
			Text(dateLabel, color = FlightMuted, fontSize = 9.sp)
			Text(
				photo.matchedSamplePosition?.let {
					stringResource(R.string.flight_mode_photo_matched_point, formatVirtualPoint(it))
				}
					?: stringResource(R.string.flight_mode_photo_not_matched),
				color = if (photo.matchedSamplePosition != null) FlightGreen else FlightWarning,
				fontSize = 8.sp
			)
		}
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 12.dp).background(FlightLine))
}

@Composable
private fun FlightPhotoPreview(photo: FlightPhotoAttachment, onOpen: (String) -> Unit) {
	val preview by produceState(initialValue = PhotoPreviewState(), key1 = photo.localPath) {
		val loaded = withContext(Dispatchers.IO) { decodePhotoPreview(File(photo.localPath)) }
		value = PhotoPreviewState(loading = false, bitmap = loaded)
	}
	val bitmap = preview.bitmap
	Box(
		modifier = Modifier.fillMaxWidth().height(176.dp).padding(horizontal = 10.dp, vertical = 7.dp)
			.clip(RoundedCornerShape(4.dp)).background(Color.Black).border(1.dp, FlightLine)
			.clickable { onOpen(photo.id) },
		contentAlignment = Alignment.Center
	) {
		if (bitmap != null) {
			Image(
				bitmap = bitmap.asImageBitmap(),
				contentDescription = photo.fileName,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize().graphicsLayer(rotationZ = photo.rotationDegrees.toFloat())
			)
		} else {
			Text(
				stringResource(
					if (preview.loading) R.string.flight_mode_photo_preview_loading
					else R.string.flight_mode_photo_preview_unavailable
				),
				color = if (preview.loading) FlightMuted else FlightWarning,
				fontSize = 10.sp
			)
		}
	}
}

@Composable
private fun FlightPhotoFullscreen(
	photo: FlightPhotoAttachment,
	onClose: () -> Unit,
	onRotate: (Float) -> Unit
) {
	val preview by produceState(initialValue = PhotoPreviewState(), key1 = photo.localPath) {
		val loaded = withContext(Dispatchers.IO) { decodePhotoPreview(File(photo.localPath)) }
		value = PhotoPreviewState(loading = false, bitmap = loaded)
	}
	Box(
		Modifier.fillMaxSize().background(Color(0xFA000000)).clickable(enabled = true, onClick = {}),
		contentAlignment = Alignment.Center
	) {
		preview.bitmap?.let { bitmap ->
			Image(
				bitmap = bitmap.asImageBitmap(),
				contentDescription = photo.fileName,
				contentScale = ContentScale.Fit,
				modifier = Modifier.fillMaxSize().padding(top = 50.dp, bottom = 54.dp)
					.pointerInput(photo.id) {
						detectTransformGestures { _, _, _, rotationDegrees ->
							if (abs(rotationDegrees) >= 0.01f) onRotate(rotationDegrees)
						}
					}
					.graphicsLayer(rotationZ = photo.rotationDegrees.toFloat())
			)
		} ?: Text(
			stringResource(
				if (preview.loading) R.string.flight_mode_photo_preview_loading
				else R.string.flight_mode_photo_preview_unavailable
			),
			color = if (preview.loading) FlightMuted else FlightWarning
		)
		Row(
			Modifier.align(Alignment.TopCenter).fillMaxWidth().height(48.dp).background(FlightHudPanel)
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(photo.fileName, color = FlightText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
			CompactAction(stringResource(R.string.flight_mode_close).uppercase(), FlightText, onClose)
		}
		Row(
			Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp).background(FlightHudPanel)
				.padding(horizontal = 10.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center
		) {
			Text(
				stringResource(R.string.flight_mode_photo_rotate_gesture, photo.rotationDegrees),
				color = FlightBlue,
				fontSize = 10.sp,
				fontWeight = FontWeight.Bold
			)
		}
	}
}

@Composable
private fun FlightPhotoMetadata(photo: FlightPhotoAttachment, sample: FlightSample?, trip: FlightTrip?) {
	val fileSize = remember(photo.localPath) { runCatching { File(photo.localPath).length() }.getOrDefault(0L) }
	val matchedTimestamp = sample?.timestampMillis?.takeIf { it > 0L }
	Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
		Text(
			stringResource(R.string.flight_mode_photo_details).uppercase(),
			color = FlightBlue,
			fontSize = 8.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.padding(vertical = 4.dp)
		)
		PhotoMetadataLine(stringResource(R.string.flight_mode_photo_file), photo.fileName)
		PhotoMetadataLine(
			stringResource(R.string.flight_mode_photo_date),
			when {
				photo.timestampMillis != null -> formatDateTime(photo.timestampMillis)
				matchedTimestamp != null -> stringResource(
					R.string.flight_mode_photo_date_inferred,
					formatDateTime(matchedTimestamp)
				)
				sample != null -> stringResource(R.string.flight_mode_photo_matched_without_time)
				else -> stringResource(R.string.flight_mode_photo_without_date)
			}
		)
		if (photo.timestampMillis == null && matchedTimestamp != null) {
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_photo_date_source),
				stringResource(R.string.flight_mode_photo_date_source_association)
			)
		} else photo.timestampSource?.let { source ->
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_photo_date_source),
				stringResource(
					when (source) {
						FlightPhotoTimestampSource.EXIF -> R.string.flight_mode_photo_date_source_exif
						FlightPhotoTimestampSource.MEDIA_CAPTURE -> R.string.flight_mode_photo_date_source_media
						FlightPhotoTimestampSource.FILE_NAME -> R.string.flight_mode_photo_date_source_filename
						FlightPhotoTimestampSource.FILE_MODIFIED -> R.string.flight_mode_photo_date_source_modified
						FlightPhotoTimestampSource.FILE_ADDED -> R.string.flight_mode_photo_date_source_added
						FlightPhotoTimestampSource.LIVE_CAPTURE -> R.string.flight_mode_photo_date_source_live
					}
				)
			)
		}
		PhotoMetadataLine(stringResource(R.string.flight_mode_photo_file_size), formatStorageBytes(fileSize))
		if (sample == null) {
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_photo_position),
				stringResource(R.string.flight_mode_photo_not_matched),
				warning = true
			)
		} else {
			val progress = FlightSampleInterpolator.progressAt(trip, photo.matchedSamplePosition)
				?.times(100f)?.roundToInt() ?: 0
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_photo_timeline),
				stringResource(
					R.string.flight_mode_photo_timeline_value,
					formatVirtualPoint(photo.matchedSamplePosition ?: sample.index.toDouble()),
					progress
				)
			)
			if (photo.timestampMillis != null && sample.timestampMillis > 0L) {
				PhotoMetadataLine(
					stringResource(R.string.flight_mode_photo_time_offset),
					formatDuration(abs(photo.timestampMillis - sample.timestampMillis))
				)
			}
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_photo_position),
				String.format(Locale.US, "%.6f, %.6f", sample.latitude, sample.longitude)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_altitude),
				sample.altitudeMeters?.let { "%.0f m".format(it) } ?: stringResource(R.string.flight_mode_missing)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_speed),
				sample.speedMetersPerSecond?.let { "%.0f km/h".format(it * 3.6f) } ?: stringResource(R.string.flight_mode_missing)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_heading),
				sample.bearingDegrees?.let { "%03.0f°".format(it) } ?: stringResource(R.string.flight_mode_missing)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_accuracy),
				sample.horizontalAccuracyMeters?.let { "±%.0f m".format(it) } ?: stringResource(R.string.flight_mode_missing)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_satellites),
				if (sample.satellitesUsed != null) "${sample.satellitesUsed}/${sample.satellitesFound ?: 0}"
				else stringResource(R.string.flight_mode_missing)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_sound),
				sample.soundDb?.let { "%.1f dB".format(it) } ?: stringResource(R.string.flight_mode_missing)
			)
			PhotoMetadataLine(
				stringResource(R.string.flight_mode_vibration),
				sample.vibrationHz?.let { "%.1f Hz".format(it) } ?: stringResource(R.string.flight_mode_missing)
			)
		}
	}
}

@Composable
private fun PhotoMetadataLine(label: String, value: String, warning: Boolean = false) {
	Row(
		Modifier.fillMaxWidth().height(27.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(label.uppercase(), color = FlightMuted, fontSize = 8.sp, modifier = Modifier.width(92.dp), maxLines = 1)
		Text(
			value,
			color = if (warning) FlightWarning else FlightText,
			fontSize = 9.sp,
			fontFamily = FontFamily.Monospace,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
		)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).background(FlightLine.copy(alpha = 0.55f)))
}

private fun decodePhotoPreview(file: File): Bitmap? {
	val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
	BitmapFactory.decodeFile(file.absolutePath, bounds)
	if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
	var sampleSize = 1
	while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAXIMUM_PHOTO_PREVIEW_PIXELS) sampleSize *= 2
	val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
		?: return null
	val rotation = when (MediaMetadataUtils.getExifOrientation(file)) {
		3 -> 180f
		6 -> 90f
		8 -> 270f
		else -> 0f
	}
	if (rotation == 0f) return decoded
	return runCatching {
		Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
	}.getOrNull()?.also { rotated ->
		if (rotated !== decoded) decoded.recycle()
	} ?: decoded
}

@Composable
private fun JourneysScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onImport: () -> Unit,
	onSelectInternalTrack: () -> Unit,
	onUpdateName: (String) -> Unit,
	onSave: () -> Unit,
	onExport: () -> Unit,
	onOpen: (String) -> Unit
) {
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_journeys), state.sessionMode, onClose)
		LazyColumn(Modifier.weight(1f)) {
			item { SectionTitle(stringResource(R.string.flight_mode_current_journey)) }
			item {
				BasicTextField(
					value = state.journeyName,
					onValueChange = onUpdateName,
					singleLine = true,
					textStyle = TextStyle(color = FlightText, fontSize = 16.sp),
					cursorBrush = Brush.verticalGradient(listOf(FlightOrange, FlightOrange)),
					modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp, vertical = 12.dp)
				)
			}
			item {
				Text(
					stringResource(
						R.string.flight_mode_journey_stats,
						state.trip?.samples?.size ?: 0,
						state.photos.size,
						state.flightSpans.size
					),
					color = FlightMuted,
					fontSize = 10.sp,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
				)
			}
			if (state.offlineAssets.terrainTileCount > 0 || state.offlineAssets.standardSatelliteTileCount > 0) {
				item {
					Text(
						stringResource(
							R.string.flight_mode_journey_offline_assets,
							state.offlineAssets.terrainTileCount,
							state.offlineAssets.standardSatelliteTileCount
						),
						color = FlightBlue,
						fontSize = 9.sp,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
					)
				}
			}
			item {
				Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FlatButton(
						if (state.journeyDirty) stringResource(R.string.flight_mode_save_journey) else stringResource(R.string.flight_mode_journey_saved),
						Modifier.weight(1f),
						state.journeyDirty,
						onSave
					)
					FlatButton(stringResource(R.string.flight_mode_export_journey), Modifier.weight(1f), false, onExport)
				}
			}
			item {
				Row(
					Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					FlatButton(
						stringResource(R.string.flight_mode_load_osmand_track),
						Modifier.weight(1f),
						false,
						onSelectInternalTrack
					)
					FlatButton(
						stringResource(R.string.flight_mode_import_journey_or_gpx),
						Modifier.weight(1f),
						false,
						onImport
					)
				}
			}
			state.journeyMessage?.let { message ->
				item { Text(message, color = FlightGreen, fontSize = 11.sp, modifier = Modifier.padding(12.dp)) }
			}
			item { SectionTitle(stringResource(R.string.flight_mode_storage)) }
			item {
				if (state.storageUsageLoading && state.storageUsage == null) {
					Row(
						Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(9.dp)
					) {
						CircularProgressIndicator(color = FlightOrange, strokeWidth = 2.dp, modifier = Modifier.size(17.dp))
						Text(stringResource(R.string.flight_mode_storage_calculating), color = FlightMuted, fontSize = 10.sp)
					}
					} else {
						val usage = state.storageUsage
						if (usage != null) FlightStorageUsageTable(usage)
					}
			}
			item { SectionTitle(stringResource(R.string.flight_mode_saved_journeys, state.savedJourneys.size)) }
			if (state.savedJourneys.isEmpty()) {
				item { Text(stringResource(R.string.flight_mode_no_saved_journey), color = FlightMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
			} else {
				itemsIndexed(state.savedJourneys) { _, journey ->
					SavedJourneyRow(journey = journey, onOpen = onOpen)
				}
			}
		}
		FlightBottomNavigation(FlightPage.JOURNEYS, onPageChange)
	}
}

@Composable
private fun FlightStorageUsageTable(usage: FlightStorageUsage) {
	Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)) {
		StorageHeader(stringResource(R.string.flight_mode_storage_current), formatStorageBytes(usage.currentJourneyBytes))
		StorageRow(stringResource(R.string.flight_mode_storage_track_data), usage.currentJournalBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_current_photos), usage.currentPhotosBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_current_terrain), usage.currentTerrainBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_current_satellite), usage.currentSatelliteStandardBytes)
		StorageHeader(stringResource(R.string.flight_mode_storage_shared), formatStorageBytes(usage.totalBytes))
		StorageRow(stringResource(R.string.flight_mode_storage_all_journals), usage.allJournalBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_all_photos), usage.allPhotosBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_terrain), usage.terrainBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_satellite_sources), usage.satelliteSourceBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_satellite_render), usage.satelliteRenderBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_osmand_map_render), usage.nativeMapRenderBytes)
		StorageRow(stringResource(R.string.flight_mode_storage_graphics), usage.graphicsBytes)
		if (usage.otherBytes > 0L) StorageRow(stringResource(R.string.flight_mode_storage_other), usage.otherBytes)
		Row(
			Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF152029)).padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(stringResource(R.string.flight_mode_storage_total).uppercase(), color = FlightText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
			Text(formatStorageBytes(usage.totalBytes), color = FlightGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
		}
	}
}

@Composable
private fun StorageHeader(label: String, value: String) {
	Row(
		Modifier.fillMaxWidth().height(29.dp).background(Color(0xFF0D1419)).padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(label.uppercase(), color = FlightOrange, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
		Text(value, color = FlightText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
	}
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
	Row(
		Modifier.fillMaxWidth().height(27.dp).padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(label, color = FlightMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
		Text(formatStorageBytes(bytes), color = FlightText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
	}
}

private fun formatStorageBytes(bytes: Long): String {
	if (bytes < 1_024L) return "$bytes o"
	val kib = bytes / 1_024.0
	if (kib < 1_024.0) return "%.1f Ko".format(Locale.ROOT, kib)
	val mib = kib / 1_024.0
	if (mib < 1_024.0) return "%.1f Mo".format(Locale.ROOT, mib)
	return "%.2f Go".format(Locale.ROOT, mib / 1_024.0)
}

@Composable
private fun SavedJourneyRow(journey: FlightJourneySummary, onOpen: (String) -> Unit) {
	Column(Modifier.fillMaxWidth().clickable { onOpen(journey.id) }) {
		Row(
			Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Column(Modifier.weight(1f)) {
				Text(
					journey.name,
					color = FlightText,
					fontSize = 13.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(formatDateTime(journey.updatedAtMillis), color = FlightMuted, fontSize = 9.sp)
			}
			Text(
				"${journey.sampleCount} pts · ${journey.photoCount} photos",
				color = FlightBlue,
				fontSize = 9.sp
			)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 12.dp).background(FlightLine))
	}
}

@Composable
private fun FlightTopBar(title: String, mode: FlightSessionMode, onClose: () -> Unit, overlay: Boolean = false) {
	Row(
		modifier = Modifier.fillMaxWidth().height(54.dp)
			.background(if (overlay) FlightHudPanel else FlightPanelStrong)
			.border(1.dp, FlightLine),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(
			modifier = Modifier.size(54.dp).clickable(onClick = onClose),
			contentAlignment = Alignment.Center
		) {
			androidx.compose.material3.Icon(
				painter = painterResource(R.drawable.ic_action_close),
				contentDescription = stringResource(R.string.flight_mode_close),
				tint = FlightText,
				modifier = Modifier.size(22.dp)
			)
		}
		Text(
			text = title,
			color = FlightText,
			fontSize = 17.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
		)
		Text(
			text = when (mode) {
				FlightSessionMode.LIVE -> stringResource(R.string.flight_mode_live).uppercase()
				FlightSessionMode.REPLAY -> stringResource(R.string.flight_mode_replay).uppercase()
				FlightSessionMode.PREPARE -> "PRÉVOL"
			},
			color = when (mode) {
				FlightSessionMode.LIVE -> FlightGreen
				FlightSessionMode.REPLAY -> FlightBlue
				FlightSessionMode.PREPARE -> FlightOrange
			},
			fontSize = 10.sp,
			fontWeight = FontWeight.Bold,
			letterSpacing = 1.sp,
			modifier = Modifier.padding(horizontal = 13.dp)
		)
	}
}

@Composable
private fun InstrumentStrip(sample: FlightSample?, overlay: Boolean = false) {
	Row(
		modifier = Modifier.fillMaxWidth().height(66.dp)
			.background(if (overlay) FlightHudPanel else FlightPanel)
			.border(1.dp, FlightLine),
		verticalAlignment = Alignment.CenterVertically
	) {
		InstrumentCell(stringResource(R.string.flight_mode_altitude), sample?.altitudeMeters?.let { "%.0f".format(it) } ?: "—", "m", Modifier.weight(1f))
		VerticalDivider()
		InstrumentCell(stringResource(R.string.flight_mode_speed), sample?.speedMetersPerSecond?.let { "%.0f".format(it * 3.6f) } ?: "—", "km/h", Modifier.weight(1f))
		VerticalDivider()
		InstrumentCell(
			stringResource(R.string.flight_mode_gps),
			if (sample?.satellitesUsed != null) "${sample.satellitesUsed}/${sample.satellitesFound ?: 0}" else "—",
			"sat",
			Modifier.weight(1f)
		)
		VerticalDivider()
		InstrumentCell(stringResource(R.string.flight_mode_accuracy), sample?.horizontalAccuracyMeters?.let { "%.0f".format(it) } ?: "—", "m", Modifier.weight(1f))
	}
}

@Composable
private fun InstrumentCell(label: String, value: String, unit: String, modifier: Modifier) {
	Column(modifier.padding(horizontal = 9.dp), verticalArrangement = Arrangement.Center) {
		Text(label.uppercase(), color = FlightMuted, fontSize = 9.sp, letterSpacing = 0.6.sp)
		Row(verticalAlignment = Alignment.Bottom) {
			Text(value, color = FlightText, fontSize = 23.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
			Spacer(Modifier.width(3.dp))
			Text(unit, color = FlightMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
		}
	}
}

@Composable
private fun VerticalDivider() {
	Box(Modifier.width(1.dp).fillMaxHeight(0.68f).background(FlightLine))
}

@Composable
private fun ReplayBar(state: FlightUiState, onSeek: (Float) -> Unit, onToggle: () -> Unit) {
	val sample = state.snapshot?.sample
	Row(
		modifier = Modifier.fillMaxWidth().height(38.dp).background(FlightPanelStrong).border(1.dp, FlightLine).padding(horizontal = 7.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(
			Modifier.size(28.dp).background(FlightOrange).clickable(onClick = onToggle),
			contentAlignment = Alignment.Center
		) {
			Text(if (state.replayPlaying) "Ⅱ" else "▶", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
		}
		Slider(
			value = state.replayProgress,
			onValueChange = onSeek,
			modifier = Modifier.weight(1f).height(30.dp).padding(horizontal = 7.dp)
		)
		Column(horizontalAlignment = Alignment.End) {
			Text(sample?.timestampMillis?.takeIf { it > 0 }?.let(::formatClock) ?: "point ${sample?.index?.plus(1) ?: 0}", color = FlightText, fontSize = 9.sp)
			Text("${(state.replayProgress * 100).toInt()} %", color = FlightBlue, fontSize = 8.sp)
		}
	}
}

@Composable
private fun FlightBottomNavigation(selected: FlightPage, onSelected: (FlightPage) -> Unit, overlay: Boolean = false) {
	val pages = listOf(
		FlightPage.MAP to stringResource(R.string.flight_mode_map),
		FlightPage.WINDOW to stringResource(R.string.flight_mode_window),
		FlightPage.SATELLITE to stringResource(R.string.flight_mode_cached_tiles_short),
		FlightPage.SENSORS to stringResource(R.string.flight_mode_sensors),
		FlightPage.PHOTO to stringResource(R.string.flight_mode_photo),
		FlightPage.JOURNEYS to stringResource(R.string.flight_mode_journeys)
	)
	Row(
		Modifier.fillMaxWidth().height(48.dp)
			.background(if (overlay) FlightHudPanel else FlightPanelStrong)
			.border(1.dp, FlightLine)
	) {
		pages.forEach { (page, label) ->
			Box(
				modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelected(page) },
				contentAlignment = Alignment.Center
			) {
				if (selected == page) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(FlightOrange))
				Text(
					label,
					color = if (selected == page) FlightText else FlightMuted,
					fontSize = 8.sp,
					fontWeight = if (selected == page) FontWeight.Bold else FontWeight.Normal,
					maxLines = 1
				)
			}
		}
	}
}

@Composable
private fun FlightStopRow(
	index: Int,
	count: Int,
	name: String,
	focusRequester: FocusRequester,
	searchActive: Boolean,
	searchLoading: Boolean,
	suggestions: List<FlightCitySuggestion>,
	onNameChange: (String) -> Unit,
	onSubmit: () -> Unit,
	onSuggestionSelected: (FlightCitySuggestion) -> Unit,
	onDismissSuggestions: () -> Unit,
	onRemove: () -> Unit
) {
	var focused by remember(index) { mutableStateOf(false) }
	val showSuggestions = focused && searchActive && name.trim().length >= 2
	val placeholder = stringResource(R.string.flight_mode_city_placeholder)
	Row(
		modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(Modifier.width(28.dp).fillMaxHeight()) {
			if (index > 0) Box(Modifier.align(Alignment.TopCenter).width(2.dp).height(22.dp).background(FlightOrange))
			if (index < count - 1) Box(Modifier.align(Alignment.BottomCenter).width(2.dp).height(22.dp).background(FlightOrange))
			Box(
				Modifier.align(Alignment.Center).size(if (index == 0 || index == count - 1) 12.dp else 9.dp)
					.background(if (index == 0 || index == count - 1) FlightOrange else FlightBackground, RoundedCornerShape(50))
					.border(2.dp, FlightOrange, RoundedCornerShape(50))
			)
		}
		Box(Modifier.weight(1f)) {
			BasicTextField(
				value = name,
				onValueChange = onNameChange,
				singleLine = true,
				textStyle = TextStyle(color = FlightText, fontSize = 16.sp),
				cursorBrush = Brush.verticalGradient(listOf(FlightOrange, FlightOrange)),
				keyboardOptions = KeyboardOptions(
					imeAction = if (index < count - 1) ImeAction.Next else ImeAction.Done
				),
				keyboardActions = KeyboardActions(
					onNext = { onSubmit() },
					onDone = { onSubmit() }
				),
				decorationBox = { innerTextField ->
					if (name.isBlank()) {
						Text(placeholder, color = FlightMuted, fontSize = 16.sp)
					}
					innerTextField()
				},
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(focusRequester)
					.onFocusChanged {
						if (focused && !it.isFocused) onDismissSuggestions()
						focused = it.isFocused
					}
					.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 12.dp)
			)
			DropdownMenu(
				expanded = showSuggestions,
				onDismissRequest = onDismissSuggestions,
				modifier = Modifier.widthIn(min = 240.dp, max = 330.dp).background(FlightPanelStrong),
				properties = PopupProperties(focusable = false)
			) {
				when {
					searchLoading -> {
						Row(
							Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							CircularProgressIndicator(color = FlightOrange, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
							Text(stringResource(R.string.flight_mode_city_searching), color = FlightMuted, fontSize = 12.sp)
						}
					}
					suggestions.isEmpty() -> {
						Text(
							stringResource(R.string.flight_mode_city_no_result),
							color = FlightMuted,
							fontSize = 12.sp,
							modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
						)
					}
					else -> suggestions.forEach { suggestion ->
						Column(
							Modifier.fillMaxWidth().clickable { onSuggestionSelected(suggestion) }
								.padding(horizontal = 14.dp, vertical = 9.dp)
						) {
							Text(suggestion.name, color = FlightText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
							Text(
								citySuggestionSubtitle(suggestion),
								color = FlightMuted,
								fontSize = 11.sp,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
					}
				}
			}
		}
		if (count > 2 && index > 0 && index < count - 1) {
			Text("×", color = FlightMuted, fontSize = 22.sp, modifier = Modifier.size(36.dp).clickable(onClick = onRemove), textAlign = TextAlign.Center)
		}
	}
	Box(Modifier.fillMaxWidth().height(1.dp)) {
		Box(Modifier.fillMaxSize().padding(start = 52.dp).background(FlightLine))
		if (index < count - 1) {
			Box(
				Modifier.padding(start = 29.dp).width(2.dp).fillMaxHeight().background(FlightOrange)
			)
		}
	}
}

@Composable
private fun citySuggestionSubtitle(suggestion: FlightCitySuggestion): String {
	val kind = when (suggestion.subType) {
		"city" -> stringResource(R.string.flight_mode_city_type_city)
		"town" -> stringResource(R.string.flight_mode_city_type_town)
		"village" -> stringResource(R.string.flight_mode_city_type_village)
		else -> stringResource(R.string.flight_mode_city_type_place)
	}
	return listOfNotNull(kind, suggestion.regionName).joinToString(" · ")
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text.uppercase(),
		color = FlightOrange,
		fontSize = 10.sp,
		fontWeight = FontWeight.Bold,
		letterSpacing = 1.1.sp,
		modifier = Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine).padding(horizontal = 16.dp, vertical = 8.dp)
	)
}

@Composable
private fun DenseSettingRow(title: String, value: String) {
	Row(
		Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).border(width = 0.dp, color = Color.Transparent),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(title, color = FlightText, fontSize = 14.sp, modifier = Modifier.weight(1f))
		Text(value, color = FlightMuted, fontSize = 12.sp)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp).background(FlightLine))
}

@Composable
private fun SwitchSettingRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
	Row(
		Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(title, color = FlightText, fontSize = 14.sp, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = onChecked)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp).background(FlightLine))
}

@Composable
private fun TerrainPreloadStatus(status: FlightTerrainStatus) {
	val working = status.phase == FlightTerrainPhase.PLANNING ||
		status.phase == FlightTerrainPhase.DOWNLOADING ||
		status.phase == FlightTerrainPhase.BUILDING
	Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.flight_mode_terrain_source), color = FlightText, fontSize = 13.sp)
				Text(terrainStatusText(status), color = if (status.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightMuted, fontSize = 11.sp)
			}
		}
		if (working && status.requestedTiles > 0) {
			LinearProgressIndicator(
				progress = { status.progress },
				modifier = Modifier.fillMaxWidth().height(3.dp),
				color = FlightOrange,
				trackColor = FlightLine
			)
		}
		Text(
			stringResource(R.string.flight_mode_preload_explanation),
			color = FlightMuted,
			fontSize = 9.sp,
			modifier = Modifier.padding(top = 5.dp)
		)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp).background(FlightLine))
}

@Composable
private fun FlatButton(text: String, modifier: Modifier, accent: Boolean, onClick: () -> Unit) {
	Box(
		modifier = modifier.height(45.dp)
			.background(if (accent) FlightOrange else Color.Transparent)
			.border(1.dp, if (accent) FlightOrange else FlightLine)
			.clickable(onClick = onClick),
		contentAlignment = Alignment.Center
	) {
		Text(text, color = if (accent) Color.Black else FlightText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
	}
}

@Composable
private fun FlatTextAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
	Text(
		text = "+  $text",
		color = FlightBlue,
		fontSize = 13.sp,
		fontWeight = FontWeight.Medium,
		modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp)
	)
}

@Composable
private fun FlightProfileView(
	profile: FlightProfile,
	progress: Float?,
	flightSpans: List<FlightSpan> = emptyList(),
	pendingStartProgress: Float? = null,
	modifier: Modifier = Modifier
) {
	val density = LocalDensity.current
	Canvas(modifier) {
		if (profile.points.isEmpty()) return@Canvas
		val left = 8.dp.toPx()
		val right = size.width - 8.dp.toPx()
		val top = 9.dp.toPx()
		val bottom = size.height - if (profile.recorded) 8.dp.toPx() else 25.dp.toPx()
		val chartHeight = bottom - top
		val maxAltitude = max(1f, profile.points.maxOf { it.altitudeMeters })
		flightSpans.forEach { rawSpan ->
			val span = rawSpan.normalized()
			val startX = left + (right - left) * span.startProgress
			val endX = left + (right - left) * span.endProgress
			drawRect(
				color = FlightOrange.copy(alpha = 0.14f),
				topLeft = Offset(startX, top),
				size = Size((endX - startX).coerceAtLeast(1f), chartHeight)
			)
			drawLine(FlightOrange.copy(alpha = 0.8f), Offset(startX, top), Offset(startX, bottom), 1.dp.toPx())
			drawLine(FlightOrange.copy(alpha = 0.8f), Offset(endX, top), Offset(endX, bottom), 1.dp.toPx())
		}
		if (pendingStartProgress != null && progress != null) {
			val start = minOf(pendingStartProgress, progress).coerceIn(0f, 1f)
			val end = maxOf(pendingStartProgress, progress).coerceIn(0f, 1f)
			drawRect(
				color = FlightWarning.copy(alpha = 0.10f),
				topLeft = Offset(left + (right - left) * start, top),
				size = Size((right - left) * (end - start), chartHeight)
			)
		}

		for (i in 0..3) {
			val y = top + chartHeight * i / 3f
			drawLine(FlightLine.copy(alpha = 0.7f), Offset(left, y), Offset(right, y), 1.dp.toPx())
		}
		profile.legs.forEach { leg ->
			val path = Path()
			leg.points.forEachIndexed { pointIndex, point ->
				val x = left + (right - left) * point.progress
				val y = bottom - chartHeight * (point.altitudeMeters / maxAltitude)
				if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
			}
			val lineColor = if (profile.recorded) FlightBlue else if (leg.index % 2 == 0) FlightOrange else FlightBlue
			drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
			if (!profile.recorded && leg.index < profile.legs.lastIndex) {
				val stopX = left + (right - left) * leg.endProgress
				drawLine(FlightWarning.copy(alpha = 0.65f), Offset(stopX, top), Offset(stopX, bottom), 1.dp.toPx())
				drawCircle(FlightWarning, radius = 3.dp.toPx(), center = Offset(stopX, bottom))
			}
			}
		progress?.let {
			val x = left + (right - left) * it.coerceIn(0f, 1f)
			drawLine(FlightGreen, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
		}

		if (profile.recorded) return@Canvas
		val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.rgb(190, 201, 208)
			textSize = with(density) { 10.sp.toPx() }
		}
		drawIntoCanvas { canvas ->
			val labels = buildList {
				profile.legs.firstOrNull()?.let { add(it.startProgress to it.from.name) }
				profile.legs.forEach { add(it.endProgress to it.to.name) }
			}.filter { it.second.isNotBlank() }
			labels.forEachIndexed { index, (position, label) ->
				val measured = labelPaint.measureText(label)
				val desiredX = left + (right - left) * position - when (index) {
					0 -> 0f
					labels.lastIndex -> measured
					else -> measured / 2f
				}
				canvas.nativeCanvas.drawText(label, desiredX.coerceIn(left, right - measured), size.height - 5.dp.toPx(), labelPaint)
			}
		}
	}
}

@Composable
private fun FlightWindowScene(
	placement: FlightWindowPlacement,
	look: FlightWindowLook,
	trip: FlightTrip?,
	sample: FlightSample?,
	scene: FlightTerrainScene?,
	terrainStatus: FlightTerrainStatus,
	terrainRenderStats: FlightTerrainRenderStats,
	altitudeOverrideMeters: Float?,
	shadingEnabled: Boolean,
	shadowIntensity: Float,
	satelliteOpacity: Float,
	satelliteQuality: FlightSatelliteQuality,
	photo: FlightPhotoAttachment?,
	photoOverlay: FlightWindowPhotoOverlay,
	onSetSide: (FlightCabinSide) -> Unit,
	onMoveLook: (Float, Float) -> Unit,
	onRecenterLook: () -> Unit,
	onChangeZoom: (Float) -> Unit,
	onTransformPhoto: (Float, Float, Float) -> Unit,
	onSetPhotoOpacity: (Float) -> Unit,
	onSetGestureTarget: (FlightWindowGestureTarget) -> Unit,
	onResetPhotoTransform: () -> Unit,
	onRotatePhoto: (String, Float) -> Unit,
	onClearPhoto: () -> Unit,
	onSetShadowsEnabled: (Boolean) -> Unit,
	onRetryTerrain: () -> Unit,
	onRendererError: (String) -> Unit,
	onRenderStats: (FlightTerrainRenderStats) -> Unit,
	modifier: Modifier = Modifier
) {
	val latestPlacement by rememberUpdatedState(placement)
	val latestMoveLook by rememberUpdatedState(onMoveLook)
	val latestChangeZoom by rememberUpdatedState(onChangeZoom)
	val latestPhotoOverlay by rememberUpdatedState(photoOverlay)
	val latestTransformPhoto by rememberUpdatedState(onTransformPhoto)
	val latestRotatePhoto by rememberUpdatedState(onRotatePhoto)
	var sceneAspectRatio by remember { mutableStateOf(1f) }
	val activePhoto = photo?.takeIf { photoOverlay.photoId == it.id }
	val latestActivePhotoId by rememberUpdatedState(activePhoto?.id)
	val photoOverlayVisible = activePhoto != null
	Box(
		modifier = modifier.background(FlightBackground)
			.onSizeChanged { size ->
				sceneAspectRatio = size.width.toFloat() / size.height.coerceAtLeast(1)
			}
			.pointerInput(Unit) {
				detectTransformGestures { _, pan, zoom, rotationDegrees ->
					val manipulatePhoto = latestPhotoOverlay.photoId != null &&
						latestPhotoOverlay.gestureTarget == FlightWindowGestureTarget.PHOTO
					if (manipulatePhoto) {
						latestTransformPhoto(
							pan.x / size.width.coerceAtLeast(1),
							pan.y / size.height.coerceAtLeast(1),
							zoom
						)
						if (abs(rotationDegrees) >= 0.01f) {
							latestActivePhotoId?.let { latestRotatePhoto(it, rotationDegrees) }
						}
					} else if (pan != Offset.Zero) {
						val horizontalFov = latestPlacement.horizontalFieldOfViewDegrees(
							size.width.toFloat() / size.height.coerceAtLeast(1)
						)
						val verticalFov = latestPlacement.verticalFieldOfViewDegrees()
						latestMoveLook(
							-pan.x / size.width.coerceAtLeast(1) * horizontalFov,
							pan.y / size.height.coerceAtLeast(1) * verticalFov
						)
					}
					if (!manipulatePhoto && abs(zoom - 1f) > 0.002f) latestChangeZoom(zoom)
				}
			}
	) {
		FlightTerrainSurface(
			scene = scene,
			sample = sample,
			windowPlacement = placement,
			windowLook = look,
			altitudeOverrideMeters = altitudeOverrideMeters,
			shadingEnabled = shadingEnabled,
			shadowIntensity = shadowIntensity,
			satelliteOpacity = satelliteOpacity,
			// Hublot keeps the Terrarium geometry, while the dedicated cached-tile page
			// owns the coloured 2D relief visualization.
			terrainOpacity = 0f,
			nativeMapOpacity = 0f,
			onRendererError = onRendererError,
			onRenderStats = onRenderStats,
			modifier = Modifier.fillMaxSize()
		)
		activePhoto?.let { FlightWindowPhotoOverlayImage(it, photoOverlay, Modifier.fillMaxSize()) }
		if (!placement.cabinHidden) {
			FlightCabinWindowOverlay(placement, look, Modifier.fillMaxSize())
		}
		FlightCompassOverlay(placement, look, sample, Modifier.fillMaxSize())
		FlightAircraftForwardOverlay(placement, look, sample, Modifier.fillMaxSize())
		WindowQuickControls(
			placement = placement,
			look = look,
			shadowsEnabled = shadingEnabled,
			onSetSide = onSetSide,
			onRecenter = onRecenterLook,
			onSetShadowsEnabled = onSetShadowsEnabled,
			modifier = Modifier.align(Alignment.TopCenter).padding(top = 7.dp)
		)
		if (sample != null) {
			AndroidView(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(top = 6.dp, end = 7.dp)
					.fillMaxWidth(0.22f)
					.aspectRatio(1f),
				factory = { context -> FlightWindowOverviewView(context) },
				update = { overview ->
					overview.update(
						trip = trip,
						sample = sample,
						viewAzimuthDegrees = placement.viewAzimuthDegrees(sample.bearingDegrees ?: 0f, look),
						viewConeDegrees = placement.horizontalFieldOfViewDegrees(sceneAspectRatio),
						quality = scene?.satelliteQuality ?: satelliteQuality,
						baseZoom = scene?.zoom ?: terrainStatus.zoom?.takeIf { it > 0 },
						cacheKey = "$satelliteQuality:${scene?.generation ?: terrainStatus.zoom}:${terrainStatus.satelliteTiles}"
					)
				}
			)
		}
		activePhoto?.let { overlayPhoto ->
			WindowPhotoOverlayControls(
				photo = overlayPhoto,
				overlay = photoOverlay,
				onSetOpacity = onSetPhotoOpacity,
				onSetGestureTarget = onSetGestureTarget,
				onResetTransform = onResetPhotoTransform,
				onClose = onClearPhoto,
				modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
			)
		}
		TerrainStatusOverlay(
			status = terrainStatus,
			scene = scene,
			renderStats = terrainRenderStats,
			onRetry = onRetryTerrain,
			modifier = Modifier.align(Alignment.BottomCenter)
				.padding(bottom = if (photoOverlayVisible) 82.dp else 18.dp)
		)
		if ((scene?.satelliteTiles ?: 0) > 0) {
			Text(
				text = stringResource(R.string.flight_mode_satellite_attribution_short),
				color = Color(0xFFC7D0D6),
				fontSize = 8.sp,
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(bottom = if (photoOverlayVisible) 78.dp else 0.dp)
					.background(Color(0x990A0F13))
					.padding(horizontal = 5.dp, vertical = 3.dp)
			)
		}
	}
}

@Composable
private fun WindowQuickControls(
	placement: FlightWindowPlacement,
	look: FlightWindowLook,
	shadowsEnabled: Boolean,
	onSetSide: (FlightCabinSide) -> Unit,
	onRecenter: () -> Unit,
	onSetShadowsEnabled: (Boolean) -> Unit,
	modifier: Modifier = Modifier
) {
	Row(
		modifier.background(Color(0xD90A0F13)).border(1.dp, FlightLine).padding(3.dp),
		horizontalArrangement = Arrangement.spacedBy(3.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		listOf(
			FlightCabinSide.LEFT to stringResource(R.string.flight_mode_side_left_short).uppercase(),
			FlightCabinSide.RIGHT to stringResource(R.string.flight_mode_side_right_short).uppercase()
		).forEach { (side, label) ->
			Box(
				Modifier.height(29.dp).width(48.dp)
					.background(if (placement.side == side) FlightOrange else Color.Transparent)
					.border(1.dp, if (placement.side == side) FlightOrange else FlightLine)
					.clickable { onSetSide(side) },
				contentAlignment = Alignment.Center
			) {
				Text(label, color = if (placement.side == side) Color.Black else FlightText, fontSize = 8.sp, fontWeight = FontWeight.Bold)
			}
		}
		Box(
			Modifier.height(29.dp).border(1.dp, if (look == FlightWindowLook()) FlightLine else FlightBlue)
				.clickable(onClick = onRecenter).padding(horizontal = 8.dp),
			contentAlignment = Alignment.Center
		) {
			Text(stringResource(R.string.flight_mode_recenter_short).uppercase(), color = if (look == FlightWindowLook()) FlightMuted else FlightBlue, fontSize = 8.sp, fontWeight = FontWeight.Bold)
		}
		Box(
			Modifier.size(29.dp)
				.border(1.dp, if (shadowsEnabled) FlightOrange else FlightLine)
				.clickable { onSetShadowsEnabled(!shadowsEnabled) },
			contentAlignment = Alignment.Center
		) {
			Text("☀", color = if (shadowsEnabled) FlightOrange else FlightMuted, fontSize = 14.sp)
		}
	}
}

@Composable
private fun FlightWindowPhotoOverlayImage(
	photo: FlightPhotoAttachment,
	overlay: FlightWindowPhotoOverlay,
	modifier: Modifier = Modifier
) {
	val preview by produceState(initialValue = PhotoPreviewState(), key1 = photo.localPath) {
		val loaded = withContext(Dispatchers.IO) { decodePhotoPreview(File(photo.localPath)) }
		value = PhotoPreviewState(loading = false, bitmap = loaded)
	}
	Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
		preview.bitmap?.let { bitmap ->
			Image(
				bitmap = bitmap.asImageBitmap(),
				contentDescription = photo.fileName,
				contentScale = ContentScale.Fit,
				modifier = Modifier.fillMaxSize().graphicsLayer {
					alpha = overlay.opacity
					scaleX = overlay.scale
					scaleY = overlay.scale
					translationX = overlay.offsetXFraction * size.width
					translationY = overlay.offsetYFraction * size.height
					rotationZ = photo.rotationDegrees.toFloat()
				}
			)
		}
	}
}

@Composable
private fun WindowPhotoOverlayControls(
	photo: FlightPhotoAttachment,
	overlay: FlightWindowPhotoOverlay,
	onSetOpacity: (Float) -> Unit,
	onSetGestureTarget: (FlightWindowGestureTarget) -> Unit,
	onResetTransform: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier
) {
	Column(
		modifier.fillMaxWidth(0.96f).background(Color(0xE611181E)).border(1.dp, FlightLine)
			.padding(horizontal = 6.dp, vertical = 3.dp)
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				photo.fileName,
				color = FlightText,
				fontSize = 8.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f)
			)
			Text(
				stringResource(R.string.flight_mode_photo_overlay_close).uppercase(),
				color = FlightMuted,
				fontSize = 8.sp,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 5.dp, vertical = 3.dp)
			)
		}
		Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.flight_mode_photo_opacity).uppercase(), color = FlightBlue, fontSize = 7.sp, modifier = Modifier.width(48.dp))
			Slider(value = overlay.opacity, onValueChange = onSetOpacity, modifier = Modifier.weight(1f).height(24.dp))
			Text("${(overlay.opacity * 100).roundToInt()} %", color = FlightText, fontSize = 8.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			listOf(
				FlightWindowGestureTarget.VIEW to stringResource(R.string.flight_mode_gesture_view),
				FlightWindowGestureTarget.PHOTO to stringResource(R.string.flight_mode_gesture_photo)
			).forEach { (target, label) ->
				val selected = overlay.gestureTarget == target
				Box(
					Modifier.weight(1f).height(24.dp)
						.background(if (selected) FlightBlue.copy(alpha = 0.16f) else Color.Transparent)
						.border(1.dp, if (selected) FlightBlue else FlightLine)
						.clickable { onSetGestureTarget(target) },
					contentAlignment = Alignment.Center
				) {
					Text(label.uppercase(), color = if (selected) FlightBlue else FlightMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
				}
			}
			Box(
				Modifier.height(24.dp).border(1.dp, FlightLine).clickable(onClick = onResetTransform)
					.padding(horizontal = 7.dp),
				contentAlignment = Alignment.Center
			) {
				Text(stringResource(R.string.flight_mode_reset).uppercase(), color = FlightMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
			}
		}
	}
}

@Composable
private fun FlightCabinWindowOverlay(
	placement: FlightWindowPlacement,
	look: FlightWindowLook,
	modifier: Modifier = Modifier
) {
	Canvas(modifier) {
		val geometry = placement.geometry()
		val distance = geometry.eyeToWindowDistanceMeters.coerceAtLeast(0.2f)
		val fieldOfViewDegrees = placement.verticalFieldOfViewDegrees()
		val halfFieldOfView = Math.toRadians((fieldOfViewDegrees / 2f).toDouble()).toFloat()
		val physicalRadius = FlightWindowPlacement.WINDOW_DIAMETER_METERS / 2f
		val projectedDiameter = (size.height * (physicalRadius / distance) / tan(halfFieldOfView))
			.coerceIn(size.height * 0.16f, size.height * 0.82f)
		val horizontalIncidence = geometry.horizontalIncidence.coerceIn(0.30f, 1f)
		val verticalIncidence = geometry.verticalIncidence.coerceIn(0.45f, 1f)
		val radiusX = projectedDiameter * horizontalIncidence / 2f
		val radiusY = projectedDiameter * verticalIncidence / 2f
		val shear = (placement.forwardOffsetMeters * placement.verticalOffsetMeters /
			(FlightWindowPlacement.WALL_DISTANCE_METERS * FlightWindowPlacement.WALL_DISTANCE_METERS))
			.coerceIn(-0.45f, 0.45f) * radiusX
		val aspect = size.width / size.height.coerceAtLeast(1f)
		val horizontalFieldOfView = 2f * atan(tan(halfFieldOfView) * aspect)
		val center = Offset(
			size.width / 2f - Math.toRadians(look.yawDegrees.toDouble()).toFloat() /
				horizontalFieldOfView.coerceAtLeast(0.01f) * size.width,
			size.height / 2f + Math.toRadians(look.pitchDegrees.toDouble()).toFloat() /
				(halfFieldOfView * 2f).coerceAtLeast(0.01f) * size.height
		)
		val windowVisible = center.x + radiusX >= 0f && center.x - radiusX <= size.width &&
			center.y + radiusY >= 0f && center.y - radiusY <= size.height
		val windowPath = Path()
		if (windowVisible) {
			for (step in 0..48) {
				val angle = step / 48f * (Math.PI * 2.0)
				val cosine = cos(angle).toFloat()
				val sine = sin(angle).toFloat()
				val point = Offset(
					center.x + radiusX * cosine + shear * sine,
					center.y + radiusY * sine
				)
				if (step == 0) windowPath.moveTo(point.x, point.y) else windowPath.lineTo(point.x, point.y)
			}
			windowPath.close()
		}
		val cabinMask = Path().apply {
			fillType = PathFillType.EvenOdd
			addRect(Rect(0f, 0f, size.width, size.height))
			if (windowVisible) addPath(windowPath)
		}
		drawPath(
			path = cabinMask,
			color = Color(0xFF151C22).copy(alpha = if (placement.cabinTransparent) 0.50f else 0.97f)
		)
		if (windowVisible) {
			drawPath(windowPath, Color(0xFF505B63), style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round))
			drawPath(windowPath, Color(0xFFAAB4BA), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
			drawPath(windowPath, Color(0x66000000), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
		}
	}
}

@Composable
private fun FlightCompassOverlay(
	placement: FlightWindowPlacement,
	look: FlightWindowLook,
	sample: FlightSample?,
	modifier: Modifier = Modifier
) {
	Canvas(modifier) {
		val radius = 39.dp.toPx()
		val center = Offset(50.dp.toPx(), 49.dp.toPx())
		val bearing = sample?.bearingDegrees
		val viewAzimuth = placement.viewAzimuthDegrees(bearing ?: 0f, look)
		drawCircle(Color.Black.copy(alpha = 0.25f), radius, center)
		drawCircle(Color.Black.copy(alpha = 0.30f), radius, center, style = Stroke(width = 1.dp.toPx()))
		val labels = listOf(
			0f to "N", 45f to "NE", 90f to "E", 135f to "SE",
			180f to "S", 225f to "SO", 270f to "O", 315f to "NO"
		)
		val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.argb(77, 0, 0, 0)
			textSize = 8.dp.toPx()
			textAlign = Paint.Align.CENTER
			isFakeBoldText = true
		}
		drawIntoCanvas { composeCanvas ->
			labels.forEach { (azimuth, label) ->
				val relativeAzimuth = normalizeDegrees(azimuth - viewAzimuth)
				val angle = Math.toRadians((relativeAzimuth - 90f).toDouble())
				val x = center.x + cos(angle).toFloat() * radius * 0.72f
				val y = center.y + sin(angle).toFloat() * radius * 0.72f + labelPaint.textSize * 0.34f
				composeCanvas.nativeCanvas.drawText(label, x, y, labelPaint)
			}
		}
		fun relativeDirectionEnd(worldAzimuth: Float, length: Float): Offset {
			val relativeAzimuth = normalizeDegrees(worldAzimuth - viewAzimuth)
			val angle = Math.toRadians((relativeAzimuth - 90f).toDouble())
			return Offset(center.x + cos(angle).toFloat() * length, center.y + sin(angle).toFloat() * length)
		}
		drawLine(Color.Black.copy(alpha = 0.25f), center, Offset(center.x, center.y - radius * 0.58f), 2.dp.toPx())
		bearing?.let {
			drawLine(Color.Black.copy(alpha = 0.30f), center, relativeDirectionEnd(it, radius * 0.88f), 3.dp.toPx())
		}
		drawIntoCanvas { composeCanvas ->
			labelPaint.textSize = 7.dp.toPx()
			composeCanvas.nativeCanvas.drawText(
				bearing?.let { "CAP %03d°".format(Math.floorMod(it.roundToInt(), 360)) } ?: "CAP —",
				center.x,
				center.y + radius + 10.dp.toPx(),
				labelPaint
			)
		}
	}
}

/** Projects the aircraft's forward axis into the same perspective as the terrain view. */
@Composable
private fun FlightAircraftForwardOverlay(
	placement: FlightWindowPlacement,
	look: FlightWindowLook,
	sample: FlightSample?,
	modifier: Modifier = Modifier
) {
	val bearing = sample?.bearingDegrees ?: return
	Canvas(modifier) {
		val verticalHalfFov = Math.toRadians((placement.verticalFieldOfViewDegrees() / 2f).toDouble()).toFloat()
		val aspect = size.width / size.height.coerceAtLeast(1f)
		val horizontalHalfFov = atan(tan(verticalHalfFov) * aspect)
		val viewAzimuth = placement.viewAzimuthDegrees(bearing, look)
		val relativeYaw = FlightWindowLook.normalizeYaw(bearing - viewAzimuth)
		val relativeYawRadians = Math.toRadians(relativeYaw.toDouble()).toFloat()
		val viewElevation = placement.geometry().elevationRadians + Math.toRadians(look.pitchDegrees.toDouble()).toFloat()
		val relativeElevation = -viewElevation
		val pointsForward = abs(relativeYaw) < 89.5f
		val projectedX = if (pointsForward) {
			size.width * 0.5f + tan(relativeYawRadians) / tan(horizontalHalfFov).coerceAtLeast(0.001f) * size.width * 0.5f
		} else if (relativeYaw >= 0f) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
		val projectedY = size.height * 0.5f -
			tan(relativeElevation) / tan(verticalHalfFov).coerceAtLeast(0.001f) * size.height * 0.5f
		val margin = 18.dp.toPx()
		val visible = pointsForward && projectedX in margin..(size.width - margin) &&
			projectedY in margin..(size.height - margin)
		val markerX = if (visible) projectedX else if (relativeYaw >= 0f) size.width - margin else margin
		val markerY = if (visible) projectedY else projectedY.coerceIn(size.height * 0.28f, size.height * 0.72f)
		val cue = Color(0xB8DDF7FF)
		val halo = Color.Black.copy(alpha = 0.34f)
		val radius = 9.dp.toPx()
		drawCircle(halo, radius + 3.dp.toPx(), Offset(markerX, markerY))
		if (visible) {
			drawCircle(cue, radius, Offset(markerX, markerY), style = Stroke(width = 1.4.dp.toPx()))
			drawLine(cue, Offset(markerX - radius * 0.70f, markerY + radius * 0.35f), Offset(markerX, markerY - radius * 0.55f), 1.8.dp.toPx())
			drawLine(cue, Offset(markerX, markerY - radius * 0.55f), Offset(markerX + radius * 0.70f, markerY + radius * 0.35f), 1.8.dp.toPx())
		} else {
			val direction = if (relativeYaw >= 0f) 1f else -1f
			drawPath(
				Path().apply {
					moveTo(markerX + direction * radius * 0.70f, markerY)
					lineTo(markerX - direction * radius * 0.45f, markerY - radius * 0.65f)
					lineTo(markerX - direction * radius * 0.45f, markerY + radius * 0.65f)
					close()
				},
				cue
			)
		}
		val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.argb(190, 221, 247, 255)
			textSize = 8.dp.toPx()
			textAlign = Paint.Align.CENTER
			isFakeBoldText = true
			setShadowLayer(2.dp.toPx(), 0f, 1.dp.toPx(), android.graphics.Color.BLACK)
		}
		drawIntoCanvas { canvas ->
			canvas.nativeCanvas.drawText(
				"AVANT · %03d°".format(Math.floorMod(bearing.roundToInt(), 360)),
				markerX.coerceIn(42.dp.toPx(), size.width - 42.dp.toPx()),
				(markerY - radius - 4.dp.toPx()).coerceAtLeast(labelPaint.textSize),
				labelPaint
			)
		}
	}
}

@Composable
private fun TerrainStatusOverlay(
	status: FlightTerrainStatus,
	scene: FlightTerrainScene?,
	renderStats: FlightTerrainRenderStats,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier
) {
	var expanded by remember { mutableStateOf(false) }
	val working = status.phase == FlightTerrainPhase.PLANNING ||
		status.phase == FlightTerrainPhase.DOWNLOADING ||
		status.phase == FlightTerrainPhase.BUILDING
	if (!working && status.phase != FlightTerrainPhase.ERROR && scene == null) return
	val showDetails = working || status.phase == FlightTerrainPhase.ERROR || expanded
	Row(
		modifier = modifier
			.background(Color(0xD911181E))
			.border(1.dp, if (status.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightLine)
			.clickable {
				if (status.phase == FlightTerrainPhase.ERROR) onRetry() else expanded = !expanded
			}
			.padding(horizontal = 8.dp, vertical = if (showDetails) 6.dp else 3.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp)
	) {
		if (working) CircularProgressIndicator(color = FlightOrange, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
		Text(
			text = when {
				status.phase == FlightTerrainPhase.ERROR -> "${terrainStatusText(status)} · toucher pour réessayer"
				showDetails -> terrainRuntimeStatusText(status, renderStats)
				else -> terrainRuntimeSummary(status, renderStats)
			},
			color = if (status.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightText,
			fontSize = if (showDetails) 9.sp else 8.sp,
			fontFamily = FontFamily.Monospace,
			lineHeight = 11.sp
		)
	}
}

@Composable
private fun AltitudeOverrideControls(
	reportedAltitudeMeters: Float?,
	overrideAltitudeMeters: Float?,
	onSetOverride: (Float?) -> Unit
) {
	val manual = overrideAltitudeMeters != null
	Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Column(Modifier.weight(1f)) {
				Text(
					stringResource(R.string.flight_mode_window_altitude).uppercase(),
					color = FlightOrange,
					fontSize = 9.sp,
					fontWeight = FontWeight.Bold
				)
				Text(
					text = if (manual) {
						stringResource(R.string.flight_mode_altitude_manual, overrideAltitudeMeters ?: 0f)
					} else if (reportedAltitudeMeters != null) {
						stringResource(R.string.flight_mode_altitude_gps, reportedAltitudeMeters)
					} else {
						stringResource(R.string.flight_mode_altitude_gps_missing)
					},
					color = if (manual) FlightBlue else FlightText,
					fontSize = 10.sp,
					fontFamily = FontFamily.Monospace
				)
			}
			Switch(
				checked = manual,
				onCheckedChange = { enabled ->
					if (enabled) {
						val initial = ((reportedAltitudeMeters ?: 10_000f) / 100f).roundToInt() * 100f
						onSetOverride(initial.coerceIn(-500f, 15_000f))
					} else {
						onSetOverride(null)
					}
				}
			)
		}
		if (overrideAltitudeMeters != null) {
			Row(
				Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text("−500 m", color = FlightMuted, fontSize = 9.sp, modifier = Modifier.width(44.dp))
				Slider(
					value = overrideAltitudeMeters,
					onValueChange = onSetOverride,
					valueRange = -500f..15_000f,
					steps = 154,
					modifier = Modifier.weight(1f)
				)
				Text("15 km", color = FlightMuted, fontSize = 9.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
			}
		}
	}
}

@Composable
private fun WindowSunControls(
	enabled: Boolean,
	intensity: Float,
	onSetEnabled: (Boolean) -> Unit,
	onSetIntensity: (Float) -> Unit
) {
	Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)) {
		Row(
			Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				stringResource(R.string.flight_mode_sun_shadows),
				color = if (enabled) FlightOrange else FlightMuted,
				fontSize = 9.sp,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.weight(1f)
			)
			Switch(checked = enabled, onCheckedChange = onSetEnabled)
		}
		if (enabled) {
			Row(
				Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(stringResource(R.string.flight_mode_shadow_intensity), color = FlightMuted, fontSize = 8.sp, modifier = Modifier.width(56.dp))
				Slider(value = intensity.coerceIn(0f, 1f), onValueChange = onSetIntensity, modifier = Modifier.weight(1f).height(26.dp))
				Text("${(intensity * 100).roundToInt()} %", color = FlightText, fontSize = 9.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
			}
		}
	}
}

@Composable
private fun WindowViewControls(
	placement: FlightWindowPlacement,
	onOpenSetup: () -> Unit,
	onSetZoom: (Float) -> Unit,
	onSetCabinTransparent: (Boolean) -> Unit,
	onSetCabinHidden: (Boolean) -> Unit
) {
	Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)) {
		Row(
			Modifier.fillMaxWidth().clickable(onClick = onOpenSetup).padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.flight_mode_window_position).uppercase(), color = FlightOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
				Text(windowPlacementText(placement), color = FlightText, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
			}
			Text(stringResource(R.string.flight_mode_adjust).uppercase(), color = FlightBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 12.dp).background(FlightLine))
		Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.flight_mode_zoom).uppercase(), color = FlightMuted, fontSize = 9.sp, modifier = Modifier.width(48.dp))
			Slider(
				value = placement.zoom,
				onValueChange = onSetZoom,
				valueRange = FlightWindowPlacement.MIN_ZOOM..FlightWindowPlacement.MAX_ZOOM,
				modifier = Modifier.weight(1f)
			)
			Text("×%.1f".format(placement.zoom), color = FlightText, fontSize = 11.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
		}
		Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.flight_mode_transparent_cabin), color = FlightText, fontSize = 10.sp, modifier = Modifier.weight(1f))
			Switch(checked = placement.cabinTransparent, onCheckedChange = onSetCabinTransparent)
		}
		Row(
			Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(stringResource(R.string.flight_mode_hide_cabin), color = FlightText, fontSize = 11.sp, modifier = Modifier.weight(1f))
			Switch(checked = placement.cabinHidden, onCheckedChange = onSetCabinHidden)
		}
	}
}

@Composable
private fun FlightBackTopBar(title: String, onBack: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().height(54.dp).background(FlightPanelStrong).border(1.dp, FlightLine),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(Modifier.size(54.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
			androidx.compose.material3.Icon(
				painter = painterResource(R.drawable.ic_arrow_back),
				contentDescription = stringResource(R.string.flight_mode_back),
				tint = FlightText,
				modifier = Modifier.size(22.dp)
			)
		}
		Text(title, color = FlightText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
	}
}

@Composable
private fun CabinSideSelector(side: FlightCabinSide, onSetSide: (FlightCabinSide) -> Unit) {
	Row(
		Modifier.fillMaxWidth().height(50.dp).background(FlightPanelStrong).border(1.dp, FlightLine).padding(6.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp)
	) {
		CabinSideOption(
			label = stringResource(R.string.flight_mode_left_side),
			selected = side == FlightCabinSide.LEFT,
			modifier = Modifier.weight(1f),
			onClick = { onSetSide(FlightCabinSide.LEFT) }
		)
		CabinSideOption(
			label = stringResource(R.string.flight_mode_right_side),
			selected = side == FlightCabinSide.RIGHT,
			modifier = Modifier.weight(1f),
			onClick = { onSetSide(FlightCabinSide.RIGHT) }
		)
	}
}

@Composable
private fun CabinSideOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
	Box(
		modifier.fillMaxHeight().background(if (selected) FlightOrange else Color.Transparent)
			.border(1.dp, if (selected) FlightOrange else FlightLine).clickable(onClick = onClick),
		contentAlignment = Alignment.Center
	) {
		Text(label, color = if (selected) Color.Black else FlightText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
	}
}

@Composable
private fun WindowPlacementDiagram(
	placement: FlightWindowPlacement,
	onMoveWindow: (Float, Float) -> Unit,
	onDragFinished: () -> Unit,
	modifier: Modifier = Modifier
) {
	val currentPlacement by rememberUpdatedState(placement)
	val density = LocalDensity.current
	val windowTouchRadius = with(density) { 44.dp.toPx() }
	Box(
		modifier.background(Color(0xFF10171C)).pointerInput(placement.side) {
			var draggingWindow = false
			detectDragGestures(
				onDragStart = { start ->
					val center = windowPlacementDiagramCenter(size.width.toFloat(), size.height.toFloat(), currentPlacement)
					draggingWindow = (start - center).getDistance() <= windowTouchRadius
				},
				onDragEnd = {
					if (draggingWindow) onDragFinished()
					draggingWindow = false
				},
				onDragCancel = {
					if (draggingWindow) onDragFinished()
					draggingWindow = false
				}
			) { change, dragAmount ->
				if (draggingWindow) {
					change.consume()
					val direction = if (currentPlacement.side == FlightCabinSide.LEFT) 1f else -1f
					onMoveWindow(
						dragAmount.x / size.width.coerceAtLeast(1) * 2f * direction,
						-dragAmount.y / size.height.coerceAtLeast(1) * 1.1f
					)
				}
			}
		}
	) {
		Canvas(Modifier.fillMaxSize()) {
			val direction = if (placement.side == FlightCabinSide.LEFT) 1f else -1f
			val eye = Offset(size.width * 0.47f, size.height * 0.42f)
			val windowCenter = windowPlacementDiagramCenter(size.width, size.height, placement)
			val floorY = size.height * 0.82f
			drawRoundRect(
				color = Color(0xFF1A252C),
				topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
				size = Size(size.width - 4.dp.toPx(), floorY - 2.dp.toPx()),
				cornerRadius = CornerRadius(44.dp.toPx(), 44.dp.toPx())
			)
			for (index in 1 until 6) {
				val x = size.width * index / 6f
				drawLine(FlightLine.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
			}
			for (index in 1 until 6) {
				val y = size.height * index / 6f
				drawLine(FlightLine.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
			}
			drawCircle(Color(0xFF4C5962), 29.dp.toPx(), windowCenter)
			drawCircle(Color(0xFFB5C1C7), 24.dp.toPx(), windowCenter)
			drawCircle(Color(0xFF4B8FAB), 19.dp.toPx(), windowCenter)
			drawLine(Color(0xFF74828B), Offset(0f, floorY), Offset(size.width, floorY), 2.dp.toPx())
			val seatBackX = eye.x - direction * 48.dp.toPx()
			drawLine(Color(0xFF66737C), Offset(seatBackX, eye.y + 22.dp.toPx()), Offset(seatBackX, floorY - 22.dp.toPx()), 12.dp.toPx(), StrokeCap.Round)
			drawLine(Color(0xFF66737C), Offset(seatBackX, floorY - 26.dp.toPx()), Offset(eye.x + direction * 30.dp.toPx(), floorY - 26.dp.toPx()), 12.dp.toPx(), StrokeCap.Round)
			val nose = Path().apply {
				moveTo(eye.x + direction * 14.dp.toPx(), eye.y - 7.dp.toPx())
				lineTo(eye.x + direction * 29.dp.toPx(), eye.y + 1.dp.toPx())
				lineTo(eye.x + direction * 14.dp.toPx(), eye.y + 8.dp.toPx())
				close()
			}
			drawPath(nose, Color(0xFFD8C1A5))
			drawCircle(Color(0xFFD8C1A5), 22.dp.toPx(), Offset(eye.x - direction * 5.dp.toPx(), eye.y))
			drawLine(Color(0xFFD8C1A5), Offset(eye.x - direction * 6.dp.toPx(), eye.y + 24.dp.toPx()), Offset(eye.x - direction * 12.dp.toPx(), floorY - 38.dp.toPx()), 13.dp.toPx(), StrokeCap.Round)
			drawLine(FlightBlue.copy(alpha = 0.8f), eye, windowCenter, 1.5.dp.toPx())
			drawCircle(FlightBlue, 3.5.dp.toPx(), eye)
			drawCircle(FlightOrange, 30.dp.toPx(), windowCenter, style = Stroke(3.dp.toPx()))
		}
		Text(
			if (placement.side == FlightCabinSide.LEFT) stringResource(R.string.flight_mode_forward_right)
			else stringResource(R.string.flight_mode_forward_left),
			color = FlightOrange,
			fontSize = 10.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.align(if (placement.side == FlightCabinSide.LEFT) Alignment.TopEnd else Alignment.TopStart)
				.padding(12.dp)
		)
		Text(
			stringResource(R.string.flight_mode_fixed_eye),
			color = FlightBlue,
			fontSize = 9.sp,
			modifier = Modifier.align(Alignment.Center).padding(top = 70.dp)
		)
		Text(
			stringResource(R.string.flight_mode_fixed_window_size),
			color = FlightMuted,
			fontSize = 9.sp,
			modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
		)
	}
}

private fun windowPlacementDiagramCenter(width: Float, height: Float, placement: FlightWindowPlacement): Offset {
	val direction = if (placement.side == FlightCabinSide.LEFT) 1f else -1f
	val eye = Offset(width * 0.47f, height * 0.42f)
	val forwardScale = width * 0.36f / FlightWindowPlacement.MAX_FORWARD_OFFSET_METERS
	val verticalScale = height * 0.31f / FlightWindowPlacement.MAX_VERTICAL_OFFSET_METERS
	return Offset(
		eye.x + placement.forwardOffsetMeters * forwardScale * direction,
		eye.y - placement.verticalOffsetMeters * verticalScale
	)
}

@Composable
private fun SensorReadout(sample: FlightSample?) {
	Column {
		DenseSensorLine(
			stringResource(R.string.flight_mode_satellites_short),
			if (sample?.satellitesUsed != null) "${sample.satellitesUsed}/${sample.satellitesFound ?: 0}" else "—"
		)
		DenseSensorLine(stringResource(R.string.flight_mode_accuracy), sample?.horizontalAccuracyMeters?.let { "±%.0f m".format(it) } ?: "—")
		DenseSensorLine("HDOP", sample?.hdop?.let { "%.1f".format(it) } ?: "—")
		DenseSensorLine(stringResource(R.string.flight_mode_latitude), sample?.let { "%.6f°".format(it.latitude) } ?: "—")
		DenseSensorLine(stringResource(R.string.flight_mode_longitude), sample?.let { "%.6f°".format(it.longitude) } ?: "—")
		DenseSensorLine(stringResource(R.string.flight_mode_altitude), sample?.altitudeMeters?.let { "%.0f m".format(it) } ?: "—")
		DenseSensorLine(stringResource(R.string.flight_mode_speed), sample?.speedMetersPerSecond?.let { "%.0f km/h".format(it * 3.6f) } ?: "—")
		DenseSensorLine(stringResource(R.string.flight_mode_heading), sample?.bearingDegrees?.let { "%03.0f°".format(it) } ?: "—")
		DenseSensorLine(stringResource(R.string.flight_mode_timestamp), sample?.timestampMillis?.takeIf { it > 0L }?.let(::formatClock) ?: "—")
	}
}

@Composable
private fun DenseSensorLine(label: String, value: String) {
	Row(
		Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(label.uppercase(), color = FlightMuted, fontSize = 8.sp, modifier = Modifier.weight(1f), maxLines = 1)
		Text(
			text = value,
			color = if (value == "—") FlightMuted else FlightText,
			fontSize = 11.sp,
			fontFamily = FontFamily.Monospace,
			maxLines = 1,
			textAlign = TextAlign.End
		)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 10.dp).background(FlightLine.copy(alpha = 0.55f)))
}

@Composable
private fun EnvironmentSensorRow(title: String, value: String?) {
	Row(
		Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(Modifier.size(7.dp).background(if (value == null) FlightLine else FlightGreen, RoundedCornerShape(50)))
		Text(title, color = FlightText, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
		Text(value ?: stringResource(R.string.flight_mode_missing), color = if (value == null) FlightMuted else FlightText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 10.dp).background(FlightLine))
}

@Composable
private fun SpectrumStrip(levels: List<Float>?) {
	if (levels.isNullOrEmpty()) {
		Box(
			Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
			contentAlignment = Alignment.CenterStart
		) {
			Text(
				stringResource(R.string.flight_mode_spectrum_missing),
				color = FlightMuted,
				fontSize = 9.sp
			)
		}
		return
	}
	Canvas(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 10.dp, vertical = 5.dp)) {
		levels.forEachIndexed { index, rawLevel ->
			val barWidth = size.width / levels.size
			val level = rawLevel.coerceIn(0f, 1f)
			drawRect(
				color = if (index < levels.size * 0.68f) FlightBlue else FlightOrange,
				topLeft = Offset(index * barWidth + 1f, size.height * (1f - level)),
				size = Size((barWidth - 2f).coerceAtLeast(1f), size.height * level)
			)
		}
	}
}

@Composable
private fun PolicySlider(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
	Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
		Row {
			Text(title, color = FlightText, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
			Text(valueText, color = FlightBlue, fontSize = 9.sp)
		}
		Slider(value = value, onValueChange = onValue, valueRange = range, modifier = Modifier.fillMaxWidth().height(27.dp))
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 10.dp).background(FlightLine))
}

@Composable
private fun CompactToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
	Row(
		Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 10.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(title, color = FlightText, fontSize = 10.sp, modifier = Modifier.weight(1f))
		Box(
			Modifier.height(25.dp).width(42.dp).background(if (checked) FlightGreen else Color.Transparent)
				.border(1.dp, if (checked) FlightGreen else FlightLine),
			contentAlignment = Alignment.Center
		) {
			Text(
				if (checked) stringResource(R.string.flight_mode_yes_short).uppercase()
				else stringResource(R.string.flight_mode_no_short).uppercase(),
				color = if (checked) Color.Black else FlightMuted,
				fontSize = 8.sp,
				fontWeight = FontWeight.Bold
			)
		}
	}
}

@Composable
private fun SourceToggle(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
	SwitchSettingRow(title, checked, onChecked)
}

private fun routeTitle(state: FlightUiState): String {
	return state.journeyName.takeIf { it.isNotBlank() }
		?: state.trip?.name
		?: state.plan.stops.joinToString(" → ") { it.name }
}

@Composable
private fun windowPlacementText(placement: FlightWindowPlacement): String {
	val side = if (placement.side == FlightCabinSide.LEFT) {
		stringResource(R.string.flight_mode_side_left_short)
	} else {
		stringResource(R.string.flight_mode_side_right_short)
	}
	val longitudinal = when {
		abs(placement.forwardOffsetMeters) < 0.005f -> stringResource(R.string.flight_mode_window_aligned)
		placement.forwardOffsetMeters > 0f -> stringResource(R.string.flight_mode_window_ahead, placement.forwardOffsetMeters * 100f)
		else -> stringResource(R.string.flight_mode_window_behind, -placement.forwardOffsetMeters * 100f)
	}
	val vertical = when {
		abs(placement.verticalOffsetMeters) < 0.005f -> stringResource(R.string.flight_mode_window_eye_level)
		placement.verticalOffsetMeters > 0f -> stringResource(R.string.flight_mode_window_above, placement.verticalOffsetMeters * 100f)
		else -> stringResource(R.string.flight_mode_window_below, -placement.verticalOffsetMeters * 100f)
	}
	return "$side · $longitudinal · $vertical"
}

private fun terrainStatusText(status: FlightTerrainStatus): String = when (status.phase) {
	FlightTerrainPhase.IDLE -> "Pas encore préchargé"
	FlightTerrainPhase.PLANNING -> "Calcul des tuiles nécessaires…"
	FlightTerrainPhase.DOWNLOADING -> buildString {
		status.message?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
		append("${status.availableTiles}/${status.requestedTiles} tuiles")
		if (status.satelliteTiles > 0) append(" · satellite ${status.satelliteTiles}")
		if (status.satelliteFailedTiles > 0) append(" · ${status.satelliteFailedTiles} satellite manquantes")
		status.zoom?.let { append(" · z$it") }
		if (status.bytesDownloaded > 0L) append(" · ${formatDataSize(status.bytesDownloaded)}")
	}
	FlightTerrainPhase.BUILDING -> status.message ?: "Construction du maillage GPU…"
	FlightTerrainPhase.READY -> status.message ?: "Relief disponible hors ligne"
	FlightTerrainPhase.ERROR -> status.message ?: "Relief indisponible"
}

private fun terrainRuntimeSummary(
	status: FlightTerrainStatus,
	renderStats: FlightTerrainRenderStats
): String = buildString {
	status.zoom?.let { append("z$it · ") }
	append(terrainTierSummary(status))
	val gpuBytes = renderStats.geometryBytes + renderStats.textureBytes
	if (gpuBytes > 0L) append(" · GPU ${formatDataSize(gpuBytes)}")
	if (renderStats.queuedTextureUploads > 0) append(" · attente ${renderStats.queuedTextureUploads}")
}

private fun terrainRuntimeStatusText(
	status: FlightTerrainStatus,
	renderStats: FlightTerrainRenderStats
): String = buildString {
	status.message?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
	append("z${status.zoom ?: "?"} · relief ${status.availableTiles}/${status.requestedTiles}")
	append(" · ").append(terrainTierDetails(status)).append('\n')
	append("relief RAM ${status.decodedTerrainCacheTiles}/${formatDataSize(status.decodedTerrainCacheBytes)}")
	append(" (hits ${status.memoryCacheHits})")
	append(" · géométrie CPU ${status.geometryCacheTiles}/${formatDataSize(status.geometryCacheBytes)}")
	append(" · hits disque ${status.diskCacheHits}")
	append(" · réseau ${status.networkRequests}")
	if (status.bytesPerSecond > 0L) append(" · ${formatDataSize(status.bytesPerSecond)}/s")
	if (status.bytesDownloaded > 0L) append(" · Σ${formatDataSize(status.bytesDownloaded)}")
	append('\n')
	append("maillage GPU visible ${renderStats.visibleMeshes} · gardé ${renderStats.cachedGeometryTiles}")
	append(" · textures GPU ${renderStats.cachedTextures}")
	append(" · attente ${renderStats.queuedTextureUploads + status.textureQueue}")
	if (renderStats.geometryBytes > 0L) append(" · géométrie ${formatDataSize(renderStats.geometryBytes)}")
	if (renderStats.textureBytes > 0L) append(" · textures ${formatDataSize(renderStats.textureBytes)}")
	val actualGpuBytes = renderStats.geometryBytes + renderStats.textureBytes
	if (status.estimatedVisibleGpuBytes > actualGpuBytes) {
		append(" · cible ${formatDataSize(status.estimatedVisibleGpuBytes)}")
	}
}

private fun terrainTierSummary(status: FlightTerrainStatus): String =
	"A${status.overviewTextureTiles} S${status.standardTextureTiles} H${status.highTextureTiles} " +
		"U${status.ultraTextureTiles} U+${status.ultraPlusTextureTiles}"

private fun terrainTierDetails(status: FlightTerrainStatus): String =
	"Aperçu ${status.overviewTextureTiles} · Standard ${status.standardTextureTiles} · " +
		"Haute ${status.highTextureTiles} · Ultra ${status.ultraTextureTiles} · Ultra+ ${status.ultraPlusTextureTiles}"

private fun formatDataSize(bytes: Long): String = when {
	bytes >= 1_048_576L -> "%.1f Mio".format(bytes / 1_048_576.0)
	bytes >= 1_024L -> "%.0f Kio".format(bytes / 1_024.0)
	else -> "$bytes o"
}

private fun formatClock(timestampMillis: Long): String =
	SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))

private fun formatDateTime(timestampMillis: Long): String =
	SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))

private fun formatVirtualPoint(zeroBasedPosition: Double): String =
	String.format(Locale.US, "%.2f", zeroBasedPosition + 1.0)

private fun formatDuration(durationMillis: Long): String {
	val totalSeconds = durationMillis / 1_000L
	val hours = totalSeconds / 3_600L
	val minutes = totalSeconds % 3_600L / 60L
	val seconds = totalSeconds % 60L
	return when {
		hours > 0L -> "%d h %02d min".format(hours, minutes)
		minutes > 0L -> "%d min %02d s".format(minutes, seconds)
		else -> "$seconds s"
	}
}

private fun normalizeDegrees(value: Float): Float {
	val normalized = value % 360f
	return if (normalized < 0f) normalized + 360f else normalized
}

@Preview(name = "Flight prepare Pixel 8", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun PreviewPrepare() {
	FlightModePreview(FlightUiState())
}

@Preview(name = "Flight cockpit Pixel 8", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun PreviewCockpit() {
	FlightModePreview(
		FlightUiState(
			page = FlightPage.MAP,
			sessionMode = FlightSessionMode.LIVE,
			snapshot = FlightSnapshot(
				FlightSample(0, 0, System.currentTimeMillis(), 47.2, 12.1, 10_650.0, 248f, 128f, 7f, satellitesUsed = 18, satellitesFound = 31),
				0.38f
			)
		)
	)
}

@Preview(name = "Flight window Pixel 8", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun PreviewWindow() {
	FlightModePreview(
		FlightUiState(
			page = FlightPage.WINDOW,
			sessionMode = FlightSessionMode.LIVE,
			windowPlacement = FlightWindowPlacement(forwardOffsetMeters = 0.45f, verticalOffsetMeters = 0.12f)
		)
	)
}

@Composable
private fun FlightModePreview(state: FlightUiState) {
	FlightModeScreen(
		state = state,
		onClose = {}, onPageChange = {}, onImportTrip = {}, onSelectInternalTrack = {}, onStartLive = {},
		onUpdateStop = { _, _ -> }, onSelectCity = { _, _ -> }, onDismissCitySuggestions = {},
		onAddStop = {}, onRemoveStop = {}, onUpdatePlan = {},
		onSeekReplay = {}, onToggleReplay = {}, onAdvanceReplay = {}, onMapState = { _, _, _, _ -> },
		onSetWindowAltitudeOverride = {}, onMoveWindow = { _, _ -> }, onSaveWindowPlacement = {}, onSetWindowSide = {},
		onMoveWindowLook = { _, _ -> }, onRecenterWindowLook = {}, onSetWindowZoom = {}, onChangeWindowZoom = {}, onSetCabinTransparent = {}, onSetCabinHidden = {},
		onRetryTerrain = {}, onTerrainRendererError = {}, onTerrainRenderStats = {}, onSetMapFollowing = {}, onShowTrackPoints = {},
		onMarkFlightStart = {}, onMarkFlightEnd = {}, onCancelFlightStart = {}, onRemoveFlightSpan = {},
		onSetSatelliteQuality = {},
		onSetRecordingPolicy = {}, onSetPhotoSources = { _, _, _, _ -> },
		onPhotoAction = {}, onValidatePhotos = {}, onDiscardPhotos = {}, onSelectPhoto = {},
		onAssociatePhotoAutomatically = {}, onAssociatePhotoAtCurrentReplay = {},
		onClearPhotoAssociation = {}, onRotatePhoto = { _, _ -> }, onOpenPhotoOnMap = {}, onOpenPhotoInWindow = {},
		onSetWindowPhotoOpacity = {}, onSetWindowGestureTarget = {},
		onTransformWindowPhoto = { _, _, _ -> }, onResetWindowPhotoTransform = {}, onClearWindowPhotoOverlay = {},
		onUpdateJourneyName = {}, onSaveJourney = {}, onExportJourney = {}, onOpenJourney = {},
		onOpenDuplicateJourney = {}, onContinueDuplicateImport = {}, onDismissDuplicateImport = {}
	)
}
