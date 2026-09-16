package net.osmand.plus.plugins.flightmode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    showSatelliteQualityOverlay: Boolean,
    terrainOpacity: Float,
    nativeMapOpacity: Float,
    spatialPhoto: FlightSpatialPhotoOverlay?,
    onRendererError: (String) -> Unit,
    onRenderStats: (FlightTerrainRenderStats) -> Unit,
    modifier: Modifier = Modifier,
    inspection: FlightPhotoInspection? = null,
    rendererRevision: Int = 0,
) {
    key(rendererRevision) {
        val context = LocalContext.current
        val terrainView = remember(context) { FlightTerrainView(context) }
        FlightVisibilityEffect(terrainView) { visible ->
            if (visible) terrainView.onResume() else terrainView.onPause()
        }
        AndroidView(
            modifier = modifier,
            factory = { terrainView },
            onReset = null,
            onRelease = { it.release() },
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
                    showSatelliteQualityOverlay = showSatelliteQualityOverlay,
                    terrainOpacity = terrainOpacity,
                    nativeMapOpacity = nativeMapOpacity,
                    spatialPhoto = spatialPhoto,
                    onRendererError = onRendererError,
                    onRenderStats = onRenderStats,
                    inspection = inspection,
                )
            },
        )
    }
}
