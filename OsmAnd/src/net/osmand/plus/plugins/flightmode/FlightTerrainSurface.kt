package net.osmand.plus.plugins.flightmode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FlightTerrainSurface(
	scene: FlightTerrainScene?,
	sample: FlightSample?,
	pose: FlightHeadPose,
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
				pose = pose,
				shadingEnabled = shadingEnabled,
				onRendererError = onRendererError
			)
		}
	)
}
