package net.osmand.plus.plugins.flightmode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FlightTerrainSurface(
	scene: FlightTerrainScene?,
	sample: FlightSample?,
	windowPlacement: FlightWindowPlacement,
	windowLook: FlightWindowLook,
	altitudeOverrideMeters: Float?,
	shadingEnabled: Boolean,
	shadowIntensity: Float,
	satelliteOpacity: Float,
	terrainOpacity: Float,
	nativeMapOpacity: Float,
	onRendererError: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	AndroidView(
		modifier = modifier,
		factory = { context -> FlightTerrainView(context) },
		update = { view ->
			view.updateScene(
				scene = scene,
				sample = sample,
				windowPlacement = windowPlacement,
				windowLook = windowLook,
				altitudeOverrideMeters = altitudeOverrideMeters,
				shadingEnabled = shadingEnabled,
				shadowIntensity = shadowIntensity,
				satelliteOpacity = satelliteOpacity,
				terrainOpacity = terrainOpacity,
				nativeMapOpacity = nativeMapOpacity,
				onRendererError = onRendererError
			)
		}
	)
}
