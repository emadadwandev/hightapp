package com.smartmirror.heightmeasure.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartmirror.heightmeasure.measurement.MeasurementViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MeasurementViewModel,
    onBack: () -> Unit
) {
    val cameraHeight        by viewModel.cameraHeightCm.collectAsState()
    val tiltAngle           by viewModel.tiltAngleDeg.collectAsState()
    val lowLight            by viewModel.lowLightMode.collectAsState()
    val tiltCalibrating     by viewModel.tiltCalibrating.collectAsState()
    val calibrationProgress by viewModel.calibrationProgress.collectAsState()
    val detectedTiltDeg     by viewModel.detectedTiltDeg.collectAsState()

    var heightText  by remember { mutableStateOf("") }
    var tiltText    by remember { mutableStateOf("") }
    var heightError by remember { mutableStateOf(false) }
    var tiltError   by remember { mutableStateOf(false) }

    // Pre-fill from saved settings on first load
    LaunchedEffect(cameraHeight) {
        if (heightText.isEmpty() && cameraHeight != null)
            heightText = cameraHeight!!.roundToInt().toString()
    }
    LaunchedEffect(tiltAngle) {
        if (tiltText.isEmpty())
            tiltText = tiltAngle.roundToInt().toString()
    }
    // Auto-fill tilt field when sensor calibration completes
    LaunchedEffect(detectedTiltDeg) {
        detectedTiltDeg?.let { tiltText = it.roundToInt().toString() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {

            // ── Camera height ─────────────────────────────────────────────────
            SettingLabel(
                title = "Camera height",
                description = "Distance from the floor to the camera lens."
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = heightText,
                onValueChange = {
                    heightText = it.filter { c -> c.isDigit() || c == '.' }
                    heightError = false
                },
                label = { Text("Height") },
                suffix = { Text("cm") },
                isError = heightError,
                supportingText = if (heightError) { { Text("Enter 50 – 300 cm") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Spacer(Modifier.height(24.dp))

            // ── Camera tilt angle ─────────────────────────────────────────────
            SettingLabel(
                title = "Camera tilt angle",
                description = "Degrees the camera is angled downward from horizontal. " +
                    "Use the sensor button to measure automatically, or enter a value manually."
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = tiltText,
                onValueChange = {
                    tiltText = it.filter { c -> c.isDigit() || c == '.' }
                    tiltError = false
                },
                label = { Text("Tilt angle") },
                suffix = { Text("°") },
                isError = tiltError,
                supportingText = if (tiltError) { { Text("Enter 0 – 60 °") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (viewModel.hasTiltSensor) {
                if (tiltCalibrating) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Keep the mirror perfectly still…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { calibrationProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.startTiltCalibration() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Straighten,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Detect tilt using sensor  (3 s)")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Spacer(Modifier.height(24.dp))

            // ── Low light mode ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Low light mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Boosts camera exposure for dark environments. May overexpose in bright light.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = lowLight,
                    onCheckedChange = { viewModel.saveLowLightMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color.White
                    )
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── Save ──────────────────────────────────────────────────────────
            Button(
                onClick = {
                    val h = heightText.toFloatOrNull()
                    val t = tiltText.toFloatOrNull()
                    heightError = h == null || h !in 50f..300f
                    tiltError   = t == null || t !in 0f..60f
                    if (!heightError && !tiltError) {
                        viewModel.saveCameraHeight(h!!)
                        viewModel.saveTiltAngle(t!!)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("Save", color = Color.Black)
            }
        }
    }
}

@Composable
private fun SettingLabel(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}
