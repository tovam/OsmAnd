package net.osmand.plus.plugins.flightmode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FlightTerrainSurface(
	scene: FlightTerrainScene?,
	sample: FlightSample?,
	windowPlacement: FlightWindowPlacement,
	altitudeOverrideMeters: Float?,
	shadingEnabled: Boolean,
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
				altitudeOverrideMeters = altitudeOverrideMeters,
				shadingEnabled = shadingEnabled,
				onRendererError = onRendererError
			)
		}
	)
}
