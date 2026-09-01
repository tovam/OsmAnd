package net.osmand.plus.plugins.flightmode

import android.graphics.Paint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.osmand.plus.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

private val FlightBackground = Color(0xFF0A0F13)
private val FlightPanel = Color(0xF211181E)
private val FlightPanelStrong = Color(0xFF11181E)
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
	onStartLive: () -> Unit,
	onUpdateStop: (Int, String) -> Unit,
	onAddStop: () -> Unit,
	onRemoveStop: (Int) -> Unit,
	onUpdatePlan: (FlightPlan) -> Unit,
	onPreloadTerrain: () -> Unit,
	onSeekReplay: (Float) -> Unit,
	onToggleReplay: () -> Unit,
	onAdvanceReplay: (Long) -> Unit,
	onMapSample: (FlightSample) -> Unit,
	onMoveHead: (Float, Float, Float) -> Unit,
	onSetHeadDistance: (Float) -> Unit,
	onSetHeadCalibration: (Boolean) -> Unit,
	onSaveNeutralHead: () -> Unit,
	onRecenterHead: () -> Unit,
	onRetryTerrain: () -> Unit,
	onTerrainRendererError: (String) -> Unit,
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
		LaunchedEffect(state.replayPlaying, state.replaySpeed) {
			while (state.replayPlaying) {
				delay(100)
				onAdvanceReplay(100)
			}
		}
		val mapSample = state.snapshot?.sample
		LaunchedEffect(state.page, mapSample) {
			if (state.page == FlightPage.MAP && mapSample != null) onMapSample(mapSample)
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
				.windowInsetsPadding(WindowInsets.safeDrawing)
		) {
			when (state.page) {
				FlightPage.PREPARE -> PrepareScreen(
					state = state,
					onClose = onClose,
					onImportTrip = onImportTrip,
					onStartLive = onStartLive,
					onUpdateStop = onUpdateStop,
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
					onToggleReplay = onToggleReplay
				)
				FlightPage.WINDOW -> WindowScreen(
					state = state,
					onClose = onClose,
					onPageChange = onPageChange,
					onMoveHead = onMoveHead,
					onSetHeadDistance = onSetHeadDistance,
					onSetCalibration = onSetHeadCalibration,
					onSaveNeutral = onSaveNeutralHead,
					onRecenter = onRecenterHead,
					onRetryTerrain = onRetryTerrain,
					onTerrainRendererError = onTerrainRendererError
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
	onStartLive: () -> Unit,
	onUpdateStop: (Int, String) -> Unit,
	onAddStop: () -> Unit,
	onRemoveStop: (Int) -> Unit,
	onUpdatePlan: (FlightPlan) -> Unit,
	onPreloadTerrain: () -> Unit
) {
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_prepare), FlightSessionMode.PREPARE, onClose)
		LazyColumn(Modifier.weight(1f)) {
			item { SectionTitle(stringResource(R.string.flight_mode_route)) }
			itemsIndexed(state.plan.stops) { index, stop ->
				FlightStopRow(
					index = index,
					count = state.plan.stops.size,
					name = stop.name,
					onNameChange = { onUpdateStop(index, it) },
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
		Row(
			Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine).padding(10.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			FlatButton(
				text = stringResource(R.string.flight_mode_load_trip),
				modifier = Modifier.weight(1f),
				accent = false,
				onClick = onImportTrip
			)
			FlatButton(
				text = stringResource(R.string.flight_mode_start_live),
				modifier = Modifier.weight(1f),
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
	onToggleReplay: () -> Unit
) {
	val sample = state.snapshot?.sample
	Column(Modifier.fillMaxSize()) {
		FlightTopBar(routeTitle(state), state.sessionMode, onClose)
		InstrumentStrip(sample)
		Box(Modifier.weight(1f).fillMaxWidth()) {
			Column(
				modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(FlightPanel).border(1.dp, FlightLine)
			) {
				Text("SUIVI 3D", color = FlightOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
				Text("relief ±${state.plan.terrainCorridorKm} km", color = FlightMuted, fontSize = 11.sp,
					modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
			}
			if (state.snapshot?.dataGap == true) {
				Text(
					stringResource(R.string.flight_mode_gap),
					color = FlightWarning,
					fontSize = 12.sp,
					modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE31A1510)).padding(9.dp),
					textAlign = TextAlign.Center
				)
			}
		}
		FlightProfileView(
			profile = state.profile,
			progress = state.replayProgress.takeIf { state.sessionMode == FlightSessionMode.REPLAY },
			modifier = Modifier.fillMaxWidth().height(122.dp).background(FlightPanelStrong).padding(horizontal = 8.dp, vertical = 4.dp)
		)
		if (state.sessionMode == FlightSessionMode.REPLAY) {
			ReplayBar(state, onSeekReplay, onToggleReplay)
		}
		FlightBottomNavigation(FlightPage.MAP, onPageChange)
	}
}

@Composable
private fun WindowScreen(
	state: FlightUiState,
	onClose: () -> Unit,
	onPageChange: (FlightPage) -> Unit,
	onMoveHead: (Float, Float, Float) -> Unit,
	onSetHeadDistance: (Float) -> Unit,
	onSetCalibration: (Boolean) -> Unit,
	onSaveNeutral: () -> Unit,
	onRecenter: () -> Unit,
	onRetryTerrain: () -> Unit,
	onTerrainRendererError: (String) -> Unit
) {
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_window), state.sessionMode, onClose)
		Box(Modifier.weight(1f).fillMaxWidth()) {
			FlightWindowScene(
				pose = state.headPose,
				calibrating = state.calibratingHead,
				sample = state.snapshot?.sample,
				scene = state.terrainScene,
				terrainStatus = state.terrainStatus,
				shadingEnabled = state.plan.shadowsEnabled,
				onMoveHead = onMoveHead,
				onRetryTerrain = onRetryTerrain,
				onRendererError = onTerrainRendererError,
				modifier = Modifier.fillMaxSize()
			)
			Text(
				text = stringResource(R.string.flight_mode_head_help),
				color = FlightText,
				fontSize = 12.sp,
				modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xB20A0F13)).padding(8.dp),
				textAlign = TextAlign.Center
			)
		}
		HeadPositionControls(
			pose = state.headPose,
			calibrating = state.calibratingHead,
			onSetDistance = onSetHeadDistance,
			onSetCalibration = onSetCalibration,
			onSaveNeutral = onSaveNeutral,
			onRecenter = onRecenter
		)
		FlightBottomNavigation(FlightPage.WINDOW, onPageChange)
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
	val currentSpeed = sample?.speedMetersPerSecond ?: 250f
	val interval = state.recordingPolicy.intervalSeconds(currentSpeed)
	Column(Modifier.fillMaxSize().background(FlightBackground)) {
		FlightTopBar(stringResource(R.string.flight_mode_sensors), state.sessionMode, onClose)
		LazyColumn(Modifier.weight(1f)) {
			item { SectionTitle("POSITION") }
			item {
				SensorGrid(sample)
			}
			item { SectionTitle("ENVIRONNEMENT") }
			item {
				DenseSettingRow(stringResource(R.string.flight_mode_sound), sample?.soundDb?.let { "%.1f dB".format(it) } ?: stringResource(R.string.flight_mode_missing))
				DenseSettingRow(stringResource(R.string.flight_mode_spectrum), stringResource(R.string.flight_mode_missing))
				SpectrumStrip(sample?.soundDb)
				DenseSettingRow(stringResource(R.string.flight_mode_vibration), sample?.vibrationHz?.let { "%.1f Hz".format(it) } ?: stringResource(R.string.flight_mode_missing))
			}
			item { SectionTitle(stringResource(R.string.flight_mode_recording).uppercase()) }
			item {
				Text(
					text = stringResource(
						R.string.flight_mode_recording_now,
						interval,
						interval * currentSpeed
					),
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
private fun FlightTopBar(title: String, mode: FlightSessionMode, onClose: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().height(54.dp).background(FlightPanelStrong).border(1.dp, FlightLine),
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
private fun InstrumentStrip(sample: FlightSample?) {
	Row(
		modifier = Modifier.fillMaxWidth().height(66.dp).background(FlightPanel).border(1.dp, FlightLine),
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
private fun FlightBottomNavigation(selected: FlightPage, onSelected: (FlightPage) -> Unit) {
	val pages = listOf(
		FlightPage.MAP to stringResource(R.string.flight_mode_map),
		FlightPage.WINDOW to stringResource(R.string.flight_mode_window),
		FlightPage.SENSORS to stringResource(R.string.flight_mode_sensors),
		FlightPage.PHOTO to stringResource(R.string.flight_mode_photo)
	)
	Row(Modifier.fillMaxWidth().height(48.dp).background(FlightPanelStrong).border(1.dp, FlightLine)) {
		pages.forEach { (page, label) ->
			Box(
				modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelected(page) },
				contentAlignment = Alignment.Center
			) {
				if (selected == page) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(FlightOrange))
				Text(label, color = if (selected == page) FlightText else FlightMuted, fontSize = 11.sp, fontWeight = if (selected == page) FontWeight.Bold else FontWeight.Normal)
			}
		}
	}
}

@Composable
private fun FlightStopRow(index: Int, count: Int, name: String, onNameChange: (String) -> Unit, onRemove: () -> Unit) {
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
		BasicTextField(
			value = name,
			onValueChange = onNameChange,
			singleLine = true,
			textStyle = TextStyle(color = FlightText, fontSize = 16.sp),
			cursorBrush = Brush.verticalGradient(listOf(FlightOrange, FlightOrange)),
			modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 12.dp)
		)
		if (count > 2 && index > 0 && index < count - 1) {
			Text("×", color = FlightMuted, fontSize = 22.sp, modifier = Modifier.size(36.dp).clickable(onClick = onRemove), textAlign = TextAlign.Center)
		}
	}
	Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 52.dp).background(FlightLine))
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
	pose: FlightHeadPose,
	calibrating: Boolean,
	sample: FlightSample?,
	scene: FlightTerrainScene?,
	terrainStatus: FlightTerrainStatus,
	shadingEnabled: Boolean,
	onMoveHead: (Float, Float, Float) -> Unit,
	onRetryTerrain: () -> Unit,
	onRendererError: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	val animatedHorizontal by animateFloatAsState(
		targetValue = pose.horizontalMeters,
		animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f),
		label = "flightHeadHorizontal"
	)
	val animatedVertical by animateFloatAsState(
		targetValue = pose.verticalMeters,
		animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f),
		label = "flightHeadVertical"
	)
	Box(
		modifier = modifier.background(FlightBackground).pointerInput(Unit) {
			detectTransformGestures { _, pan, zoom, _ ->
				val width = size.width.coerceAtLeast(1)
				val height = size.height.coerceAtLeast(1)
				onMoveHead(pan.x / width, pan.y / height, zoom)
			}
		}
	) {
		val windowModifier = Modifier
			.fillMaxWidth(0.77f)
			.fillMaxHeight(0.78f)
			.align(Alignment.Center)
			.clip(RoundedCornerShape(46))
		FlightTerrainSurface(
			scene = scene,
			sample = sample,
			pose = pose,
			shadingEnabled = shadingEnabled,
			onRendererError = onRendererError,
			modifier = windowModifier
		)
		Box(
			windowModifier.border(7.dp, Color(0xFF707981), RoundedCornerShape(46))
		)
		TerrainStatusOverlay(
			status = terrainStatus,
			scene = scene,
			onRetry = onRetryTerrain,
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
		)
		if (calibrating) {
			Canvas(Modifier.fillMaxSize()) {
				val center = Offset(size.width / 2f, size.height / 2f)
				drawLine(FlightOrange, Offset(center.x - 18.dp.toPx(), center.y), Offset(center.x + 18.dp.toPx(), center.y), 1.dp.toPx())
				drawLine(FlightOrange, Offset(center.x, center.y - 18.dp.toPx()), Offset(center.x, center.y + 18.dp.toPx()), 1.dp.toPx())
				val headMarker = Offset(
					center.x + animatedHorizontal / FlightHeadPose.MAX_HORIZONTAL_METERS * size.width * 0.26f,
					center.y + animatedVertical / FlightHeadPose.MAX_VERTICAL_METERS * size.height * 0.26f
				)
				drawCircle(FlightBlue, 7.dp.toPx(), headMarker, style = Stroke(2.dp.toPx()))
			}
		}
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
private fun HeadPositionControls(
	pose: FlightHeadPose,
	calibrating: Boolean,
	onSetDistance: (Float) -> Unit,
	onSetCalibration: (Boolean) -> Unit,
	onSaveNeutral: () -> Unit,
	onRecenter: () -> Unit
) {
	Column(Modifier.fillMaxWidth().background(FlightPanelStrong).border(1.dp, FlightLine)) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.flight_mode_head_position).uppercase(), color = FlightOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
				Text(headPoseText(pose), color = FlightText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
			}
			Text("↺", color = FlightBlue, fontSize = 22.sp, modifier = Modifier.size(38.dp).clickable(onClick = onRecenter), textAlign = TextAlign.Center)
		}
		Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			Text("DISTANCE", color = FlightMuted, fontSize = 9.sp, modifier = Modifier.width(62.dp))
			Slider(value = pose.distanceMeters, onValueChange = onSetDistance, valueRange = FlightHeadPose.MIN_DISTANCE_METERS..FlightHeadPose.MAX_DISTANCE_METERS, modifier = Modifier.weight(1f))
			Text("${(pose.distanceMeters * 100).toInt()} cm", color = FlightText, fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
		}
		if (calibrating) {
			FlatButton(
				text = stringResource(R.string.flight_mode_save_head),
				modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
				accent = true,
				onClick = onSaveNeutral
			)
		} else {
			FlatTextAction(
				text = stringResource(R.string.flight_mode_calibrate_head),
				modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
				onClick = { onSetCalibration(true) }
			)
		}
	}
}

@Composable
private fun SensorGrid(sample: FlightSample?) {
	Column {
		Row(Modifier.fillMaxWidth()) {
			SensorCell(stringResource(R.string.flight_mode_satellites), if (sample?.satellitesUsed != null) "${sample.satellitesUsed} / ${sample.satellitesFound ?: 0}" else "—", Modifier.weight(1f))
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
	}
}

@Composable
private fun SensorCell(label: String, value: String, modifier: Modifier) {
	Column(modifier.height(54.dp).border(0.5.dp, FlightLine).padding(horizontal = 12.dp, vertical = 7.dp)) {
		Text(label.uppercase(), color = FlightMuted, fontSize = 9.sp)
		Text(value, color = FlightText, fontSize = 16.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
	}
}

@Composable
private fun SpectrumStrip(level: Float?) {
	Canvas(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp, vertical = 8.dp).border(1.dp, FlightLine)) {
		val count = 32
		val intensity = ((level ?: 45f) / 100f).coerceIn(0.15f, 1f)
		for (i in 0 until count) {
			val barWidth = size.width / count
			val wave = (0.25f + abs(sin(i * 0.63f)).toFloat() * 0.75f) * intensity
			drawRect(
				color = if (i < count * 0.68f) FlightBlue else FlightOrange,
				topLeft = Offset(i * barWidth + 1f, size.height * (1f - wave)),
				size = Size((barWidth - 2f).coerceAtLeast(1f), size.height * wave)
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

private fun headPoseText(pose: FlightHeadPose): String {
	val horizontal = if (pose.horizontalMeters >= 0) "+%.0f cm D".format(pose.horizontalMeters * 100) else "%.0f cm G".format(-pose.horizontalMeters * 100)
	val vertical = if (pose.verticalMeters >= 0) "+%.0f cm bas".format(pose.verticalMeters * 100) else "%.0f cm haut".format(-pose.verticalMeters * 100)
	return "$horizontal  ·  $vertical  ·  ${(pose.distanceMeters * 100).toInt()} cm"
}

private fun terrainStatusText(status: FlightTerrainStatus): String = when (status.phase) {
	FlightTerrainPhase.IDLE -> "Pas encore préchargé"
	FlightTerrainPhase.PLANNING -> "Calcul des tuiles nécessaires…"
	FlightTerrainPhase.DOWNLOADING -> buildString {
		append("${status.availableTiles}/${status.requestedTiles} tuiles")
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
	FlightModePreview(FlightUiState(page = FlightPage.WINDOW, sessionMode = FlightSessionMode.LIVE, calibratingHead = true))
}

@Composable
private fun FlightModePreview(state: FlightUiState) {
	FlightModeScreen(
		state = state,
		onClose = {}, onPageChange = {}, onImportTrip = {}, onStartLive = {},
		onUpdateStop = { _, _ -> }, onAddStop = {}, onRemoveStop = {}, onUpdatePlan = {}, onPreloadTerrain = {},
		onSeekReplay = {}, onToggleReplay = {}, onAdvanceReplay = {}, onMapSample = {},
		onMoveHead = { _, _, _ -> }, onSetHeadDistance = {}, onSetHeadCalibration = {},
		onSaveNeutralHead = {}, onRecenterHead = {}, onRetryTerrain = {}, onTerrainRendererError = {}, onShowTrackPoints = {},
		onSetRecordingPolicy = {}, onSetPhotoSources = { _, _, _, _ -> }
	)
}
