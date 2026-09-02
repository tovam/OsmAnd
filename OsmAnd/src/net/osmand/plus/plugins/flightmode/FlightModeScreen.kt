package net.osmand.plus.plugins.flightmode

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
import net.osmand.plus.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
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

@Composable
fun FlightModeScreen(
	state: FlightUiState,
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
	onPreloadTerrain: () -> Unit,
	onSeekReplay: (Float) -> Unit,
	onToggleReplay: () -> Unit,
	onAdvanceReplay: (Long) -> Unit,
	onMapSample: (FlightSample) -> Unit,
	onSetWindowAltitudeOverride: (Float?) -> Unit,
	onMoveWindow: (Float, Float) -> Unit,
	onSaveWindowPlacement: () -> Unit,
	onSetWindowSide: (FlightCabinSide) -> Unit,
	onSetWindowZoom: (Float) -> Unit,
	onChangeWindowZoom: (Float) -> Unit,
	onSetCabinTransparent: (Boolean) -> Unit,
	onRetryTerrain: () -> Unit,
	onTerrainRendererError: (String) -> Unit,
	onSetMapFollowing: (Boolean) -> Unit,
	onShowTrackPoints: (Boolean) -> Unit,
	onSetRecordingPolicy: (FlightRecordingPolicy) -> Unit,
	onSetPhotoSources: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit
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
		LaunchedEffect(state.page, mapSample, state.mapFollowing) {
			if (state.page == FlightPage.MAP && state.mapFollowing && mapSample != null) onMapSample(mapSample)
		}
		LaunchedEffect(state.page, state.terrainScene, state.terrainStatus.phase) {
			if (state.page == FlightPage.WINDOW && state.terrainScene == null &&
				(state.terrainStatus.phase == FlightTerrainPhase.IDLE || state.terrainStatus.phase == FlightTerrainPhase.READY)
			) {
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
					onPreloadTerrain = onPreloadTerrain
				)
				FlightPage.MAP -> MapScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onSeekReplay = onSeekReplay,
					onToggleReplay = onToggleReplay,
					onSetMapFollowing = onSetMapFollowing
				)
				FlightPage.WINDOW -> WindowScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onSetAltitudeOverride = onSetWindowAltitudeOverride,
					onChangeZoom = onChangeWindowZoom,
					onSetZoom = onSetWindowZoom,
					onSetCabinTransparent = onSetCabinTransparent,
					onRetryTerrain = onRetryTerrain,
					onTerrainRendererError = onTerrainRendererError
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
					onSetSources = onSetPhotoSources
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
	onPreloadTerrain: () -> Unit
) {
	val focusManager = LocalFocusManager.current
	val focusRequesters = remember(state.plan.stops.size) {
		List(state.plan.stops.size) { FocusRequester() }
	}
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_prepare), FlightSessionMode.PREPARE, onClose)
		LazyColumn(Modifier.weight(1f)) {
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
				TerrainPreloadControl(state.terrainStatus, onPreloadTerrain)
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
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onSeekReplay: (Float) -> Unit,
	onToggleReplay: () -> Unit,
	onSetMapFollowing: (Boolean) -> Unit
) {
	val sample = state.snapshot?.sample
	Box(Modifier.fillMaxSize()) {
		Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
			FlightTopBar(routeTitle(state), state.sessionMode, onClose, overlay = true)
			InstrumentStrip(sample, overlay = true)
		}

		Column(
			modifier = Modifier
				.align(Alignment.CenterEnd)
				.padding(10.dp)
				.background(FlightHudPanel)
				.border(1.dp, if (state.mapFollowing) FlightGreen else FlightLine)
				.clickable { onSetMapFollowing(true) }
		) {
			Text(
				if (state.mapFollowing) stringResource(R.string.flight_mode_map_following) else stringResource(R.string.flight_mode_map_free),
				color = if (state.mapFollowing) FlightGreen else FlightOrange,
				fontSize = 10.sp,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
			Text(
				if (state.mapFollowing) stringResource(R.string.flight_mode_map_drag_hint)
				else stringResource(R.string.flight_mode_map_recenter),
				color = FlightMuted,
				fontSize = 11.sp,
				modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
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
				modifier = Modifier.fillMaxWidth().height(122.dp).background(FlightHudPanel).padding(horizontal = 8.dp, vertical = 4.dp)
			)
			if (state.sessionMode == FlightSessionMode.REPLAY) {
				ReplayBar(state, onSeekReplay, onToggleReplay)
			}
			FlightBottomNavigation(FlightPage.MAP, onPageChange, overlay = true)
		}
	}
}

@Composable
private fun WindowScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onSetAltitudeOverride: (Float?) -> Unit,
	onChangeZoom: (Float) -> Unit,
	onSetZoom: (Float) -> Unit,
	onSetCabinTransparent: (Boolean) -> Unit,
	onRetryTerrain: () -> Unit,
	onTerrainRendererError: (String) -> Unit
) {
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_window), state.sessionMode, onClose)
		Box(Modifier.weight(1f).fillMaxWidth()) {
			FlightWindowScene(
				placement = state.windowPlacement,
				sample = state.snapshot?.sample,
				scene = state.terrainScene,
				terrainStatus = state.terrainStatus,
				altitudeOverrideMeters = state.windowAltitudeOverrideMeters,
				shadingEnabled = state.plan.shadowsEnabled,
				onChangeZoom = onChangeZoom,
				onRetryTerrain = onRetryTerrain,
				onRendererError = onTerrainRendererError,
				modifier = Modifier.fillMaxSize()
			)
		}
		AltitudeOverrideControls(
			reportedAltitudeMeters = state.snapshot?.sample?.altitudeMeters?.toFloat(),
			overrideAltitudeMeters = state.windowAltitudeOverrideMeters,
			onSetOverride = onSetAltitudeOverride
		)
		WindowViewControls(
			placement = state.windowPlacement,
			onOpenSetup = { onPageChange(FlightPage.WINDOW_SETUP) },
			onSetZoom = onSetZoom,
			onSetCabinTransparent = onSetCabinTransparent
		)
		FlightBottomNavigation(FlightPage.WINDOW, onPageChange)
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
	val refreshKey = "${state.terrainStatus.phase}:${state.terrainStatus.satelliteTiles}:${state.terrainStatus.availableTiles}"
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_satellite), state.sessionMode, onClose)
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
					.border(1.dp, FlightLine).padding(horizontal = 12.dp, vertical = 7.dp)
			) {
				Text(
					text = when {
						cacheInfo.loading -> stringResource(R.string.flight_mode_satellite_loading)
						cacheInfo.zoom != null -> stringResource(
							R.string.flight_mode_satellite_count,
							cacheInfo.tileCount,
							cacheInfo.zoom ?: 0
						)
						else -> stringResource(R.string.flight_mode_satellite_empty)
					},
					color = if (cacheInfo.tileCount > 0) FlightGreen else FlightMuted,
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold
				)
				Text(stringResource(R.string.flight_mode_satellite_help), color = FlightMuted, fontSize = 10.sp)
			}
			Text(
				text = stringResource(R.string.flight_mode_satellite_attribution_short),
				color = FlightMuted,
				fontSize = 8.sp,
				modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(FlightHudPanel)
					.padding(horizontal = 8.dp, vertical = 4.dp)
			)
		}
		FlightBottomNavigation(FlightPage.SATELLITE, onPageChange)
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
				SensorGrid(sample)
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
			item { SectionTitle(stringResource(R.string.flight_mode_recording).uppercase()) }
			item {
				Text(
					text = if (interval != null && currentSpeed != null) {
						stringResource(R.string.flight_mode_recording_now, interval, interval * currentSpeed)
					} else {
						stringResource(R.string.flight_mode_recording_waiting)
					},
					color = FlightGreen,
					fontSize = 13.sp,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
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
				SwitchSettingRow(
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
	onSetSources: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit
) {
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_photo), state.sessionMode, onClose)
		SectionTitle(stringResource(R.string.flight_mode_photo_composition).uppercase())
		SourceToggle(stringResource(R.string.flight_mode_main_camera), state.photoMainCamera) { onSetSources(it, null, null, null) }
		SourceToggle(stringResource(R.string.flight_mode_selfie), state.photoSelfie) { onSetSources(null, it, null, null) }
		SourceToggle(stringResource(R.string.flight_mode_current_map), state.photoMap) { onSetSources(null, null, it, null) }
		SourceToggle(stringResource(R.string.flight_mode_current_3d), state.photoScene3d) { onSetSources(null, null, null, it) }
		Box(
			Modifier.weight(1f).fillMaxWidth().padding(16.dp).border(1.dp, FlightLine),
			contentAlignment = Alignment.Center
		) {
			Text("APERÇU HORIZONTAL\nphoto · selfie · carte · 3D", color = FlightMuted, textAlign = TextAlign.Center)
		}
		FlatButton(
			text = stringResource(R.string.flight_mode_take_photo),
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			accent = true,
			onClick = { }
		)
		FlightBottomNavigation(FlightPage.PHOTO, onPageChange)
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
		modifier = Modifier.fillMaxWidth().height(58.dp).background(FlightPanelStrong).border(1.dp, FlightLine).padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(
			Modifier.size(38.dp).background(FlightOrange).clickable(onClick = onToggle),
			contentAlignment = Alignment.Center
		) {
			Text(if (state.replayPlaying) "Ⅱ" else "▶", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
		}
		Slider(
			value = state.replayProgress,
			onValueChange = onSeek,
			modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
		)
		Column(horizontalAlignment = Alignment.End) {
			Text(sample?.timestampMillis?.takeIf { it > 0 }?.let(::formatClock) ?: "point ${sample?.index?.plus(1) ?: 0}", color = FlightText, fontSize = 12.sp)
			Text("${(state.replayProgress * 100).toInt()} %", color = FlightBlue, fontSize = 10.sp)
		}
	}
}

@Composable
private fun FlightBottomNavigation(selected: FlightPage, onSelected: (FlightPage) -> Unit, overlay: Boolean = false) {
	val pages = listOf(
		FlightPage.MAP to stringResource(R.string.flight_mode_map),
		FlightPage.WINDOW to stringResource(R.string.flight_mode_window),
		FlightPage.SATELLITE to stringResource(R.string.flight_mode_satellite),
		FlightPage.SENSORS to stringResource(R.string.flight_mode_sensors),
		FlightPage.PHOTO to stringResource(R.string.flight_mode_photo)
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
					fontSize = 10.sp,
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
private fun TerrainPreloadControl(status: FlightTerrainStatus, onPreload: () -> Unit) {
	val working = status.phase == FlightTerrainPhase.PLANNING ||
		status.phase == FlightTerrainPhase.DOWNLOADING ||
		status.phase == FlightTerrainPhase.BUILDING
	Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.flight_mode_terrain_source), color = FlightText, fontSize = 13.sp)
				Text(terrainStatusText(status), color = if (status.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightMuted, fontSize = 11.sp)
			}
			FlatButton(
				text = if (working) stringResource(R.string.flight_mode_terrain_loading) else stringResource(R.string.flight_mode_preload_terrain),
				modifier = Modifier.width(132.dp),
				accent = false,
				onClick = { if (!working) onPreload() }
			)
		}
		if (working && status.requestedTiles > 0) {
			LinearProgressIndicator(
				progress = { status.progress },
				modifier = Modifier.fillMaxWidth().height(3.dp),
				color = FlightOrange,
				trackColor = FlightLine
			)
		}
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
private fun FlightProfileView(profile: FlightProfile, progress: Float?, modifier: Modifier = Modifier) {
	val density = LocalDensity.current
	Canvas(modifier) {
		if (profile.points.isEmpty()) return@Canvas
		val left = 8.dp.toPx()
		val right = size.width - 8.dp.toPx()
		val top = 9.dp.toPx()
		val bottom = size.height - 25.dp.toPx()
		val chartHeight = bottom - top
		val maxAltitude = max(1f, profile.points.maxOf { it.altitudeMeters })

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
			drawPath(path, if (leg.index % 2 == 0) FlightOrange else FlightBlue, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
			if (leg.index < profile.legs.lastIndex) {
				val stopX = left + (right - left) * leg.endProgress
				drawLine(FlightWarning.copy(alpha = 0.65f), Offset(stopX, top), Offset(stopX, bottom), 1.dp.toPx())
				drawCircle(FlightWarning, radius = 3.dp.toPx(), center = Offset(stopX, bottom))
			}
		}
		progress?.let {
			val x = left + (right - left) * it.coerceIn(0f, 1f)
			drawLine(FlightGreen, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
		}

		val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.rgb(190, 201, 208)
			textSize = with(density) { 10.sp.toPx() }
		}
		drawIntoCanvas { canvas ->
			val labels = buildList {
				profile.legs.firstOrNull()?.let { add(it.startProgress to it.from.name) }
				profile.legs.forEach { add(it.endProgress to it.to.name) }
			}
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
	sample: FlightSample?,
	scene: FlightTerrainScene?,
	terrainStatus: FlightTerrainStatus,
	altitudeOverrideMeters: Float?,
	shadingEnabled: Boolean,
	onChangeZoom: (Float) -> Unit,
	onRetryTerrain: () -> Unit,
	onRendererError: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	Box(
		modifier = modifier.background(FlightBackground).pointerInput(Unit) {
			detectTransformGestures { _, _, zoom, _ ->
				if (abs(zoom - 1f) > 0.002f) onChangeZoom(zoom)
			}
		}
	) {
		FlightTerrainSurface(
			scene = scene,
			sample = sample,
			windowPlacement = placement,
			altitudeOverrideMeters = altitudeOverrideMeters,
			shadingEnabled = shadingEnabled,
			onRendererError = onRendererError,
			modifier = Modifier.fillMaxSize()
		)
		FlightCabinWindowOverlay(placement, Modifier.fillMaxSize())
		Text(
			text = windowPlacementText(placement),
			color = FlightText,
			fontSize = 10.sp,
			modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xA60A0F13))
				.padding(horizontal = 8.dp, vertical = 5.dp),
			textAlign = TextAlign.Center
		)
		TerrainStatusOverlay(
			status = terrainStatus,
			scene = scene,
			onRetry = onRetryTerrain,
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
		)
		if ((scene?.satelliteTiles ?: 0) > 0) {
			Text(
				text = stringResource(R.string.flight_mode_satellite_attribution_short),
				color = Color(0xFFC7D0D6),
				fontSize = 8.sp,
				modifier = Modifier
					.align(Alignment.BottomStart)
					.background(Color(0x990A0F13))
					.padding(horizontal = 5.dp, vertical = 3.dp)
			)
		}
	}
}

@Composable
private fun FlightCabinWindowOverlay(placement: FlightWindowPlacement, modifier: Modifier = Modifier) {
	Canvas(modifier) {
		val geometry = placement.geometry()
		val distance = geometry.eyeToWindowDistanceMeters.coerceAtLeast(0.2f)
		val fieldOfViewDegrees = (FlightWindowPlacement.DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES / placement.zoom)
			.coerceIn(14f, 82f)
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
		val center = Offset(size.width / 2f, size.height / 2f)
		val windowPath = Path()
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
		val cabinMask = Path().apply {
			fillType = PathFillType.EvenOdd
			addRect(Rect(0f, 0f, size.width, size.height))
			addPath(windowPath)
		}
		drawPath(
			path = cabinMask,
			color = Color(0xFF151C22).copy(alpha = if (placement.cabinTransparent) 0.50f else 0.97f)
		)
		drawPath(windowPath, Color(0xFF505B63), style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round))
		drawPath(windowPath, Color(0xFFAAB4BA), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
		drawPath(windowPath, Color(0x66000000), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
	}
}

@Composable
private fun TerrainStatusOverlay(
	status: FlightTerrainStatus,
	scene: FlightTerrainScene?,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier
) {
	val working = status.phase == FlightTerrainPhase.PLANNING ||
		status.phase == FlightTerrainPhase.DOWNLOADING ||
		status.phase == FlightTerrainPhase.BUILDING
	if (!working && status.phase != FlightTerrainPhase.ERROR && scene != null) return
	Row(
		modifier = modifier
			.background(Color(0xD911181E))
			.border(1.dp, if (status.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightLine)
			.clickable(enabled = status.phase == FlightTerrainPhase.ERROR, onClick = onRetry)
			.padding(horizontal = 10.dp, vertical = 7.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (working) CircularProgressIndicator(color = FlightOrange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
		Text(
			text = if (status.phase == FlightTerrainPhase.ERROR) "${terrainStatusText(status)} · toucher pour réessayer" else terrainStatusText(status),
			color = if (status.phase == FlightTerrainPhase.ERROR) FlightWarning else FlightText,
			fontSize = 11.sp
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
			Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
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
					fontSize = 11.sp,
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
				Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
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
private fun WindowViewControls(
	placement: FlightWindowPlacement,
	onOpenSetup: () -> Unit,
	onSetZoom: (Float) -> Unit,
	onSetCabinTransparent: (Boolean) -> Unit
) {
	Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)) {
		Row(
			Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onOpenSetup).padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.flight_mode_window_position).uppercase(), color = FlightOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
				Text(windowPlacementText(placement), color = FlightText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
			}
			Text(stringResource(R.string.flight_mode_adjust).uppercase(), color = FlightBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 12.dp).background(FlightLine))
		Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.flight_mode_zoom).uppercase(), color = FlightMuted, fontSize = 9.sp, modifier = Modifier.width(48.dp))
			Slider(
				value = placement.zoom,
				onValueChange = onSetZoom,
				valueRange = FlightWindowPlacement.MIN_ZOOM..FlightWindowPlacement.MAX_ZOOM,
				modifier = Modifier.weight(1f)
			)
			Text("×%.1f".format(placement.zoom), color = FlightText, fontSize = 11.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
		}
		Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.flight_mode_transparent_cabin), color = FlightText, fontSize = 12.sp, modifier = Modifier.weight(1f))
			Switch(checked = placement.cabinTransparent, onCheckedChange = onSetCabinTransparent)
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
private fun SensorGrid(sample: FlightSample?) {
	Column {
		Row(Modifier.fillMaxWidth()) {
			SensorCell(stringResource(R.string.flight_mode_satellites_short), if (sample?.satellitesUsed != null) "${sample.satellitesUsed}/${sample.satellitesFound ?: 0}" else "—", Modifier.weight(1f))
			SensorCell(stringResource(R.string.flight_mode_accuracy), sample?.horizontalAccuracyMeters?.let { "±%.0f m".format(it) } ?: "—", Modifier.weight(1f))
			SensorCell("HDOP", sample?.hdop?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
		}
		Row(Modifier.fillMaxWidth()) {
			SensorCell("LAT", sample?.let { "%.5f".format(it.latitude) } ?: "—", Modifier.weight(1f))
			SensorCell("LON", sample?.let { "%.5f".format(it.longitude) } ?: "—", Modifier.weight(1f))
		}
		Row(Modifier.fillMaxWidth()) {
			SensorCell(stringResource(R.string.flight_mode_altitude), sample?.altitudeMeters?.let { "%.0f m".format(it) } ?: "—", Modifier.weight(1f))
			SensorCell(stringResource(R.string.flight_mode_speed), sample?.speedMetersPerSecond?.let { "%.0f km/h".format(it * 3.6f) } ?: "—", Modifier.weight(1f))
		}
		Row(Modifier.fillMaxWidth()) {
			SensorCell(stringResource(R.string.flight_mode_heading), sample?.bearingDegrees?.let { "%03.0f°".format(it) } ?: "—", Modifier.weight(1f))
			SensorCell(stringResource(R.string.flight_mode_timestamp), sample?.timestampMillis?.takeIf { it > 0L }?.let(::formatClock) ?: "—", Modifier.weight(1f))
		}
	}
}

@Composable
private fun SensorCell(label: String, value: String, modifier: Modifier) {
	Column(modifier.height(50.dp).border(0.5.dp, FlightLine).padding(horizontal = 9.dp, vertical = 6.dp)) {
		Text(label.uppercase(), color = FlightMuted, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(
			value,
			color = if (value == "—") FlightMuted else FlightText,
			fontSize = if (value.length > 11) 11.sp else 13.sp,
			fontFamily = FontFamily.Monospace,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
private fun EnvironmentSensorRow(title: String, value: String?) {
	Row(
		Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 14.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(Modifier.size(7.dp).background(if (value == null) FlightLine else FlightGreen, RoundedCornerShape(50)))
		Text(title, color = FlightText, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 9.dp))
		Text(value ?: stringResource(R.string.flight_mode_missing), color = if (value == null) FlightMuted else FlightText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 14.dp).background(FlightLine))
}

@Composable
private fun SpectrumStrip(levels: List<Float>?) {
	if (levels.isNullOrEmpty()) {
		Box(
			Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp, vertical = 5.dp).border(1.dp, FlightLine),
			contentAlignment = Alignment.CenterStart
		) {
			Text(
				stringResource(R.string.flight_mode_spectrum_missing),
				color = FlightMuted,
				fontSize = 10.sp,
				modifier = Modifier.padding(horizontal = 9.dp)
			)
		}
		return
	}
	Canvas(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp, vertical = 7.dp).border(1.dp, FlightLine)) {
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
	Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
		Row {
			Text(title, color = FlightText, fontSize = 13.sp, modifier = Modifier.weight(1f))
			Text(valueText, color = FlightBlue, fontSize = 11.sp)
		}
		Slider(value = value, onValueChange = onValue, valueRange = range, modifier = Modifier.fillMaxWidth().height(30.dp))
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp).background(FlightLine))
}

@Composable
private fun SourceToggle(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
	SwitchSettingRow(title, checked, onChecked)
}

private fun routeTitle(state: FlightUiState): String {
	return state.trip?.name ?: state.plan.stops.joinToString(" → ") { it.name }
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
		append("${status.availableTiles}/${status.requestedTiles} tuiles")
		if (status.satelliteTiles > 0) append(" · satellite ${status.satelliteTiles}")
		if (status.satelliteFailedTiles > 0) append(" · ${status.satelliteFailedTiles} satellite manquantes")
		status.zoom?.let { append(" · z$it") }
		if (status.bytesDownloaded > 0L) append(" · ${formatDataSize(status.bytesDownloaded)}")
	}
	FlightTerrainPhase.BUILDING -> "Construction du maillage GPU…"
	FlightTerrainPhase.READY -> status.message ?: "Relief disponible hors ligne"
	FlightTerrainPhase.ERROR -> status.message ?: "Relief indisponible"
}

private fun formatDataSize(bytes: Long): String = when {
	bytes >= 1_048_576L -> "%.1f Mio".format(bytes / 1_048_576.0)
	bytes >= 1_024L -> "%.0f Kio".format(bytes / 1_024.0)
	else -> "$bytes o"
}

private fun formatClock(timestampMillis: Long): String =
	SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))

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
		onAddStop = {}, onRemoveStop = {}, onUpdatePlan = {}, onPreloadTerrain = {},
		onSeekReplay = {}, onToggleReplay = {}, onAdvanceReplay = {}, onMapSample = {},
		onSetWindowAltitudeOverride = {}, onMoveWindow = { _, _ -> }, onSaveWindowPlacement = {}, onSetWindowSide = {},
		onSetWindowZoom = {}, onChangeWindowZoom = {}, onSetCabinTransparent = {},
		onRetryTerrain = {}, onTerrainRendererError = {}, onSetMapFollowing = {}, onShowTrackPoints = {},
		onSetRecordingPolicy = {}, onSetPhotoSources = { _, _, _, _ -> }
	)
}
