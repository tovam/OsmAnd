package net.osmand.plus.plugins.flightmode

import android.annotation.SuppressLint
import android.os.Build
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.plus.R

/** The modal owns only its camera use cases. Dismissal never tears down flight recording. */
@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
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
    FlightVisibilityEffect(sensors) { visible ->
        if (visible) sensors.start() else sensors.stop()
    }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameras by remember { mutableStateOf<List<FlightCameraLens>>(emptyList()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var manualFocus by remember { mutableStateOf(false) }
    var focusStatus by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        var active = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (active)
                    scope.launch {
                        try {
                            val ready = future.get()
                            val lenses =
                                withContext(Dispatchers.IO) {
                                    flightCameraLenses(context, ready.availableCameraInfos)
                                }
                            if (active) {
                                cameras = lenses
                                selected = defaultFlightLens(lenses)
                                provider = ready
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (active) error = e.message
                        }
                    }
            },
            executor,
        )
        onDispose { active = false }
    }
    DisposableEffect(provider, cameras, selected, owner) {
        val p = provider
        val lens = cameras.getOrNull(selected)
        val previewBuilder = Preview.Builder()
        val imageBuilder =
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        if (Build.VERSION.SDK_INT >= 28)
            lens?.physicalId?.let { id ->
                // Pin BOTH streams. Pinning preview alone would still allow a different capture
                // lens.
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(id)
                Camera2Interop.Extender(imageBuilder).setPhysicalCameraId(id)
            }
        val preview =
            previewBuilder.build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val image = imageBuilder.build()
        var bound: Camera? = null
        val zoomObserver =
            Observer<ZoomState> { z ->
                if (z != null) {
                    zoom = z.zoomRatio
                    minZoom =
                        if (lens?.locked == true) maxOf(1f, z.minZoomRatio) else z.minZoomRatio
                    maxZoom =
                        if (lens?.locked == true)
                            minOf(
                                    z.maxZoomRatio,
                                    lens.characteristics[
                                            android.hardware.camera2.CameraCharacteristics
                                                .SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]
                                        ?: z.maxZoomRatio,
                                )
                                .coerceAtLeast(minZoom)
                        else z.maxZoomRatio
                }
            }
        val stateObserver =
            Observer<CameraState> { state ->
                if (state.error != null) {
                    error =
                        context.getString(R.string.flight_camera_lens_failed, lens?.title ?: "?") +
                            " (${state.error?.code})"
                    capture = null
                } else if (state.type == CameraState.Type.OPEN) capture = image
            }
        try {
            if (p != null && cameras.isNotEmpty()) {
                val info = cameras[selected.coerceIn(cameras.indices)].parent
                val selector =
                    CameraSelector.Builder()
                        .addCameraFilter { infos -> infos.filter { it == info } }
                        .build()
                bound = p.bindToLifecycle(owner, selector, preview, image)
                camera = bound
                capture = image
                error = null
                focusStatus = null
                manualFocus = false
                bound.cameraInfo.zoomState.observe(owner, zoomObserver)
                bound.cameraInfo.cameraState.observe(owner, stateObserver)
                bound.cameraControl.setZoomRatio(1f)
            }
        } catch (e: Exception) {
            error =
                context.getString(R.string.flight_camera_lens_failed, lens?.title ?: "?") +
                    "\n" +
                    e.message
            camera = null
            capture = null
        }
        onDispose {
            bound?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
            bound?.cameraInfo?.cameraState?.removeObserver(stateObserver)
            p?.unbind(preview, image)
            camera = null
            capture = null
        }
    }
    fun changeZoom(value: Float) {
        camera?.cameraControl?.setZoomRatio(value.coerceIn(minZoom, maxZoom))
        zoom = value.coerceIn(minZoom, maxZoom)
    }
    val currentZoom by rememberUpdatedState(zoom)
    val currentCamera by rememberUpdatedState(camera)
    val currentManualFocus by rememberUpdatedState(manualFocus)
    val currentBusy by rememberUpdatedState(busy)
    val zoomAction by rememberUpdatedState<(Float) -> Unit>({ changeZoom(it) })
    DisposableEffect(previewView) {
        var usedMultiplePointers = false
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
                        if (currentBusy || pinch.isInProgress || usedMultiplePointers) return true
                        if (currentManualFocus) {
                            focusStatus =
                                context.getString(R.string.flight_camera_focus_manual_hint)
                            return true
                        }
                        val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                        val requestedCamera = currentCamera ?: return true
                        focusStatus = context.getString(R.string.flight_camera_focusing)
                        val result =
                            requestedCamera.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(point)
                                    .setAutoCancelDuration(5, TimeUnit.SECONDS)
                                    .build()
                            )
                        result.addListener(
                            {
                                if (currentCamera === requestedCamera) {
                                    val ok =
                                        runCatching { result.get().isFocusSuccessful }
                                            .getOrDefault(false)
                                    focusStatus =
                                        context.getString(
                                            if (ok) R.string.flight_camera_focus_ok
                                            else R.string.flight_camera_focus_failed
                                        )
                                }
                            },
                            executor,
                        )
                        return true
                    }
                },
            )
        previewView.setOnTouchListener { _, event ->
            if (!currentBusy) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) usedMultiplePointers = false
                if (event.pointerCount > 1) usedMultiplePointers = true
                pinch.onTouchEvent(event)
                if (event.pointerCount == 1 && !pinch.isInProgress) taps.onTouchEvent(event)
            }
            true
        }
        onDispose { previewView.setOnTouchListener(null) }
    }
    fun takePicture() {
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

                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
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
    }
    val currentTakePicture by rememberUpdatedState<(()->Unit)>({ takePicture() })
    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !busy,
                dismissOnClickOutside = false,
            ),
    ) {
        val window =
            (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val original = window?.callback
            if (window == null || original == null) {
                onDispose {}
            } else {
                val pressed = mutableSetOf<Int>()
                val callback =
                    object : android.view.Window.Callback by original {
                        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                            val decision =
                                FlightCameraKeyPolicy.action(
                                    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                        event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN,
                                    event.action == KeyEvent.ACTION_DOWN,
                                    event.repeatCount > 0,
                                    event.keyCode in pressed,
                                    busy,
                                    capture != null,
                                )
                            when (event.action) {
                                KeyEvent.ACTION_DOWN -> pressed.add(event.keyCode)
                                KeyEvent.ACTION_UP -> pressed.remove(event.keyCode)
                            }
                            return when (decision) {
                                FlightCameraKeyPolicy.Action.CAPTURE -> {
                                    currentTakePicture()
                                    true
                                }
                                FlightCameraKeyPolicy.Action.CONSUME -> true
                                FlightCameraKeyPolicy.Action.IGNORE -> original.dispatchKeyEvent(event)
                            }
                        }
                    }
                window.callback = callback
                onDispose {
                    if (window.callback === callback) window.callback = original
                }
            }
        }
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
            // Bound the control panel: extra manual controls must never squeeze preview/shutter
            // away.
            Column(
                Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState())
            ) {
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    cameras.indices.forEach { index ->
                        Box(
                            Modifier.weight(1f)
                                .heightIn(min = 52.dp)
                                .padding(2.dp)
                                .background(
                                    if (selected == index) Color(0xFF344A50) else Color.Black
                                )
                                .border(
                                    1.dp,
                                    if (selected == index) Color(0xFF80CBC4) else Color.DarkGray,
                                )
                                .selectable(
                                    selected == index,
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                    onClick = { selected = index },
                                )
                                .padding(horizontal = 3.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                cameras[index].title,
                                color = Color.White,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                Text(
                    stringResource(
                        if (cameras.getOrNull(selected)?.locked == true)
                            R.string.flight_camera_lens_locked
                        else R.string.flight_camera_lens_unlocked
                    ),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        stringResource(R.string.flight_camera_zoom_label, zoom),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.width(105.dp),
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
                camera?.let { active ->
                    cameras.getOrNull(selected)?.let { lens ->
                        key(selected) {
                            FlightCameraControls(
                                active,
                                lens,
                                !busy,
                                { manualFocus = it },
                                { error = it },
                            )
                        }
                    }
                }
                focusStatus?.let {
                    Text(
                        it,
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            error?.let {
                Text(
                    it,
                    color = Color(0xFFFFBD39),
                    fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 76.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (saved > 0)
                Text(
                    stringResource(R.string.flight_camera_saved, saved),
                    color = Color(0xFF2CDBBE),
                    fontSize = 12.sp,
                )
            Button(
                onClick = { takePicture() },
                enabled = capture != null && !busy,
                modifier =
                    Modifier.align(Alignment.CenterHorizontally)
                        .height(52.dp)
                        .padding(vertical = 3.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
            ) {
                Text(stringResource(R.string.flight_camera_shutter))
            }
        }
    }
}
