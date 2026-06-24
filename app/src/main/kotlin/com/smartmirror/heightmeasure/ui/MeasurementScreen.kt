package com.smartmirror.heightmeasure.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.smartmirror.heightmeasure.camera.PoseAnalyzer
import com.smartmirror.heightmeasure.measurement.MeasurementStatus
import com.smartmirror.heightmeasure.measurement.MeasurementViewModel
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val ANALYSIS_WIDTH = 1280

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun MeasurementScreen(
    viewModel: MeasurementViewModel,
    onNavigateToSettings: () -> Unit
) {
    val uiState      by viewModel.uiState.collectAsState()
    val cameraHeight by viewModel.cameraHeightCm.collectAsState()
    val lowLightMode by viewModel.lowLightMode.collectAsState()
    val isBackCamera by viewModel.isBackCamera.collectAsState()
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember { PreviewView(context) }

    // Max-priority thread: the analyzer runs ahead of UI work so ML Kit gets
    // CPU time as soon as a frame arrives, reducing end-to-end latency.
    val executor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pose-analyzer").apply { priority = Thread.MAX_PRIORITY }
        }
    }

    // Re-bind whenever permission, low-light, or active camera changes
    DisposableEffect(lifecycleOwner, hasCameraPermission, lowLightMode, isBackCamera) {
        if (!hasCameraPermission) return@DisposableEffect onDispose {}

        val analyzer = PoseAnalyzer { result -> viewModel.onPoseResult(result) }
        var cameraProvider: ProcessCameraProvider? = null

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(ANALYSIS_WIDTH, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }

            val cameraSelector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA
                                 else              CameraSelector.DEFAULT_FRONT_CAMERA

            runCatching {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, analysis
                ) ?: return@runCatching

                // Derive focal length in pixels from Camera2 sensor intrinsics.
                // Only meaningful for back camera (accurate intrinsic data);
                // front camera uses the default fallback.
                val info   = Camera2CameraInfo.from(camera.cameraInfo)
                val fMm    = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val sensorW = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                if (fMm != null && sensorW != null && sensorW > 0f)
                    viewModel.focalLengthPx = fMm * ANALYSIS_WIDTH / sensorW

                // Boost camera exposure in low-light mode
                if (lowLightMode) {
                    val expState = camera.cameraInfo.exposureState
                    if (expState.isExposureCompensationSupported)
                        camera.cameraControl.setExposureCompensationIndex(
                            expState.exposureCompensationRange.upper
                        )
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            analyzer.close()
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    val borderColor by animateColorAsState(
        targetValue = when (uiState.status) {
            MeasurementStatus.NO_PERSON, MeasurementStatus.OUT_OF_ZONE -> Color(0xFFE53935)
            MeasurementStatus.IN_ZONE   -> Color(0xFF43A047)
            MeasurementStatus.MEASURING -> Color(0xFFFF8C00)
            MeasurementStatus.COMPLETE  -> Color.White
        },
        animationSpec = tween(durationMillis = 200),
        label = "border_color"
    )

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Coloured status border
        Box(modifier = Modifier.fillMaxSize().border(8.dp, borderColor))

        // Top bar: [camera toggle]  ...  [settings]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { viewModel.toggleCamera() }) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = if (isBackCamera) "Switch to front camera"
                                         else             "Switch to back camera",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Bottom status area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (uiState.status) {
                MeasurementStatus.COMPLETE -> {
                    Text(
                        "${uiState.measuredHeightCm?.roundToInt()} cm",
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { viewModel.reset() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) { Text("Measure Again", color = Color.Black) }
                }
                MeasurementStatus.MEASURING -> {
                    Text("Measuring…", fontSize = 18.sp, color = Color(0xFFFF8C00))
                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier.fillMaxWidth(0.6f),
                        color = Color(0xFFFF8C00),
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
                MeasurementStatus.IN_ZONE ->
                    Text("Hold still…", fontSize = 18.sp, color = Color(0xFF43A047))
                MeasurementStatus.OUT_OF_ZONE ->
                    Text(
                        "Move back until your full body is visible",
                        fontSize = 16.sp, color = Color(0xFFE53935),
                        textAlign = TextAlign.Center
                    )
                MeasurementStatus.NO_PERSON ->
                    Text(
                        "Stand in front of the mirror",
                        fontSize = 16.sp, color = Color.White,
                        textAlign = TextAlign.Center
                    )
            }
        }

        // First-run overlay: camera height not configured
        if (cameraHeight == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .padding(32.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Camera height not set",
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White, textAlign = TextAlign.Center
                    )
                    Text(
                        "Open Settings and enter the camera height and tilt angle.",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) { Text("Open Settings", color = Color.Black) }
                }
            }
        }

        // Permission overlay
        if (!hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text("Camera permission required", fontSize = 18.sp, color = Color.White)
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) { Text("Grant Permission", color = Color.Black) }
                }
            }
        }
    }
}
