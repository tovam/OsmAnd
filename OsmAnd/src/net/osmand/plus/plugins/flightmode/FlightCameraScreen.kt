package net.osmand.plus.plugins.flightmode

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.io.File
import java.util.concurrent.TimeUnit
import net.osmand.plus.R

/** The modal owns only its camera use cases. Dismissal never tears down flight recording. */
@SuppressLint("ClickableViewAccessibility")
@Composable
internal fun FlightCameraScreen(
    owner: LifecycleOwner,
    fix: FlightSample?,
    onClose: () -> Unit,
    onPrepareFile: () -> File,
    onCaptured: (Boolean) -> Unit,
    onShutter: (FlightPhotoCapture) -> Unit,
) {
    val context = LocalContext.current
    val sensors = remember(context) { FlightCaptureSensors(context) }
    val latestFix by rememberUpdatedState(fix)
    val latestShutter by rememberUpdatedState(onShutter)
    DisposableEffect(sensors) {
        sensors.start()
        onDispose { sensors.stop() }
    }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameras by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableIntStateOf(0) }
    var manualExposure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        var active = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (active)
                    try {
                        provider = future.get()
                        cameras = provider!!.availableCameraInfos
                    } catch (e: Exception) {
                        error = e.message
                    }
            },
            executor,
        )
        onDispose { active = false }
    }
    DisposableEffect(provider, cameras, selected, owner) {
        val p = provider
        val preview =
            Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val image =
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        var bound: Camera? = null
        val zoomObserver =
            Observer<ZoomState> { z ->
                if (z != null) {
                    zoom = z.zoomRatio
                    minZoom = z.minZoomRatio
                    maxZoom = z.maxZoomRatio
                }
            }
        try {
            if (p != null && cameras.isNotEmpty()) {
                val info = cameras[selected.coerceIn(cameras.indices)]
                val selector =
                    CameraSelector.Builder()
                        .addCameraFilter { infos -> infos.filter { it == info } }
                        .build()
                bound = p.bindToLifecycle(owner, selector, preview, image)
                camera = bound
                capture = image
                error = null
                exposure = bound.cameraInfo.exposureState.exposureCompensationIndex
                bound.cameraInfo.zoomState.observe(owner, zoomObserver)
            }
        } catch (e: Exception) {
            error = e.message
            camera = null
            capture = null
        }
        onDispose {
            bound?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
            p?.unbind(preview, image)
        }
    }
    fun changeZoom(value: Float) {
        camera?.cameraControl?.setZoomRatio(value.coerceIn(minZoom, maxZoom))
        zoom = value.coerceIn(minZoom, maxZoom)
    }
    val currentZoom by rememberUpdatedState(zoom)
    val currentCamera by rememberUpdatedState(camera)
    val zoomAction by rememberUpdatedState<(Float) -> Unit>({ changeZoom(it) })
    DisposableEffect(previewView) {
        val pinch =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        zoomAction(currentZoom * detector.scaleFactor)
                        return true
                    }
                },
            )
        val taps =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                        currentCamera
                            ?.cameraControl
                            ?.startFocusAndMetering(
                                FocusMeteringAction.Builder(point)
                                    .setAutoCancelDuration(5, TimeUnit.SECONDS)
                                    .build()
                            )
                        return true
                    }
                },
            )
        previewView.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            taps.onTouchEvent(event)
            true
        }
        onDispose { previewView.setOnTouchListener(null) }
    }
    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !busy,
                dismissOnClickOutside = false,
            ),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row {
                Text(
                    stringResource(R.string.flight_live_camera),
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f).padding(8.dp),
                )
                PlanAction(stringResource(R.string.shared_string_close), onClose, enabled = !busy)
            }
            AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
            Text(
                stringResource(R.string.flight_camera_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                cameras.indices.forEach { index ->
                    PlanAction(
                        stringResource(R.string.flight_camera_lens, index + 1),
                        { selected = index },
                        selected == index,
                        !busy,
                    )
                }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                Text(
                    "%.1f×".format(zoom),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.width(44.dp),
                )
                if (maxZoom > minZoom)
                    Slider(
                        zoom,
                        { changeZoom(it) },
                        valueRange = minZoom..maxZoom,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
            }
            camera?.let { FlightCameraControls(it,!busy,{manualExposure=it},{error=it}) }
            camera?.takeUnless { manualExposure }
                ?.cameraInfo
                ?.exposureState
                ?.takeIf { it.isExposureCompensationSupported }
                ?.let { e ->
                    Row {
                        PlanAction(
                            "− EV",
                            {
                                exposure =
                                    (exposure - 1).coerceAtLeast(e.exposureCompensationRange.lower)
                                camera?.cameraControl?.setExposureCompensationIndex(exposure)
                            },
                            enabled = !busy,
                        )
                        Text(
                            "%+.1f EV".format(exposure * e.exposureCompensationStep.toFloat()),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        PlanAction(
                            "+ EV",
                            {
                                exposure =
                                    (exposure + 1).coerceAtMost(e.exposureCompensationRange.upper)
                                camera?.cameraControl?.setExposureCompensationIndex(exposure)
                            },
                            enabled = !busy,
                        )
                    }
                }
            error?.let { Text(it, color = Color(0xFFFFBD39), fontSize = 12.sp) }
            if (saved > 0)
                Text(
                    stringResource(R.string.flight_camera_saved, saved),
                    color = Color(0xFF2CDBBE),
                    fontSize = 12.sp,
                )
            PlanAction(
                stringResource(R.string.flight_camera_shutter),
                {
                    val image = capture
                    if (image != null && !busy)
                        try {
                            busy = true
                            error = null
                            image.targetRotation =
                                previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                            val metadata = ImageCapture.Metadata()
                            fix?.takeIf {
                                    System.currentTimeMillis() - it.timestampMillis in 0..15_000
                                }
                                ?.let { f ->
                                    metadata.location =
                                        android.location.Location("gps").apply {
                                            latitude = f.latitude
                                            longitude = f.longitude
                                            time = f.timestampMillis
                                            f.altitudeMeters?.let { altitude = it }
                                            f.horizontalAccuracyMeters?.let { accuracy = it }
                                        }
                                }
                            val output =
                                ImageCapture.OutputFileOptions.Builder(onPrepareFile())
                                    .setMetadata(metadata)
                                    .build()
                            image.takePicture(
                                output,
                                executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onCaptureStarted() {
                                        latestShutter(sensors.snapshot(latestFix))
                                    }

                                    override fun onImageSaved(
                                        results: ImageCapture.OutputFileResults
                                    ) {
                                        busy = false
                                        saved++
                                        onCaptured(true)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        error = exception.message
                                        onCaptured(false)
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            busy = false
                            error = e.message
                            onCaptured(false)
                        }
                },
                enabled = capture != null && !busy,
            )
        }
    }
}
