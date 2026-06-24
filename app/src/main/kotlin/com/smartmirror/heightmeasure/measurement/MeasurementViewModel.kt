package com.smartmirror.heightmeasure.measurement

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartmirror.heightmeasure.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.sqrt

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo  = SettingsRepository(application)
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ── Measurement state ─────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(MeasurementUiState())
    val uiState: StateFlow<MeasurementUiState> = _uiState.asStateFlow()

    val cameraHeightCm: StateFlow<Float?> = settingsRepo.cameraHeightFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tiltAngleDeg: StateFlow<Float> = settingsRepo.tiltAngleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val lowLightMode: StateFlow<Boolean> = settingsRepo.lowLightModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isBackCamera: StateFlow<Boolean> = settingsRepo.isBackCameraFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Set by MeasurementScreen after camera binds, from Camera2 sensor intrinsics. */
    @Volatile var focalLengthPx: Float = 820f

    // Tuned for fast response: short stabilise window + fast reset on person leaving
    private val samples           = mutableListOf<Float>()
    private val requiredSamples   = 90   // ~3 s at 30 fps
    private val inZoneFrameTarget = 15   // ~0.5 s stabilise before measuring starts
    private var inZoneFrameCount  = 0
    private var missingFrameCount = 0
    private val missingFrameGrace = 5    // frames of grace before resetting

    // ── Camera toggle ────────────────────────────────────────────────────────

    fun toggleCamera() {
        viewModelScope.launch {
            settingsRepo.setIsBackCamera(!isBackCamera.value)
        }
        // Reset any in-progress measurement when the lens changes
        resetTo(MeasurementStatus.NO_PERSON)
    }

    // ── Tilt calibration via accelerometer ───────────────────────────────────

    /**
     * TYPE_GRAVITY (preferred) is already low-pass filtered by Android OS.
     * Falls back to raw TYPE_ACCELEROMETER if the device lacks a gravity sensor.
     * null → no accelerometer at all; show manual-only UI.
     */
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasTiltSensor: Boolean = gravitySensor != null

    private val _tiltCalibrating     = MutableStateFlow(false)
    val tiltCalibrating: StateFlow<Boolean> = _tiltCalibrating.asStateFlow()

    private val _calibrationProgress = MutableStateFlow(0f)
    val calibrationProgress: StateFlow<Float> = _calibrationProgress.asStateFlow()

    private val _detectedTiltDeg = MutableStateFlow<Float?>(null)
    val detectedTiltDeg: StateFlow<Float?> = _detectedTiltDeg.asStateFlow()

    private var tiltListener: SensorEventListener? = null

    /**
     * Reads the gravity sensor for 3 seconds and derives camera tilt from:
     *
     *   tilt = asin(gz / |g|)
     *
     * Physical basis (screen-facing-wall mount, back camera toward room):
     * when the top of the device tilts toward the room (camera looks down),
     * the gz component of gravity increases proportionally to sin(θ).
     *
     * For front-camera setups where the device orientation differs, enter
     * the angle manually or verify the detected value looks reasonable.
     *
     * No-op when [hasTiltSensor] is false.
     */
    fun startTiltCalibration() {
        val sensor = gravitySensor ?: return
        if (_tiltCalibrating.value) return

        _tiltCalibrating.value    = true
        _calibrationProgress.value = 0f
        _detectedTiltDeg.value    = null

        val samples = mutableListOf<Float>()

        tiltListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx  = event.values[0]
                val gy  = event.values[1]
                val gz  = event.values[2]
                val mag = sqrt(gx * gx + gy * gy + gz * gz)
                if (mag < 1f) return
                // gz / |g| = sin(tilt): positive when camera looks downward
                val tiltDeg = Math.toDegrees(asin((gz / mag).toDouble())).toFloat()
                samples.add(tiltDeg)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensorManager.registerListener(tiltListener, sensor, SensorManager.SENSOR_DELAY_GAME)

        viewModelScope.launch {
            val totalMs = 3_000L
            val stepMs  = 50L
            var elapsed = 0L
            while (elapsed < totalMs) {
                delay(stepMs)
                elapsed += stepMs
                _calibrationProgress.value = elapsed / totalMs.toFloat()
            }
            tiltListener?.let { sensorManager.unregisterListener(it) }
            tiltListener = null
            _tiltCalibrating.value    = false
            _calibrationProgress.value = 0f
            if (samples.isNotEmpty()) {
                val avg = samples.average().toFloat().coerceAtLeast(0f)
                _detectedTiltDeg.value = avg
                settingsRepo.setTiltAngle(avg)
            }
        }
    }

    // ── Settings saves ────────────────────────────────────────────────────────

    fun saveCameraHeight(heightCm: Float) {
        viewModelScope.launch { settingsRepo.setCameraHeight(heightCm) }
    }

    fun saveTiltAngle(degrees: Float) {
        viewModelScope.launch { settingsRepo.setTiltAngle(degrees) }
    }

    fun saveLowLightMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setLowLightMode(enabled) }
    }

    // ── Pose processing ───────────────────────────────────────────────────────

    fun onPoseResult(result: PoseResult?) {
        val currentStatus = _uiState.value.status
        if (currentStatus == MeasurementStatus.COMPLETE) return

        val cameraHeight = cameraHeightCm.value ?: return
        val isValid = result != null && isInZone(result)

        if (!isValid) {
            if (++missingFrameCount > missingFrameGrace)
                resetTo(if (result == null) MeasurementStatus.NO_PERSON else MeasurementStatus.OUT_OF_ZONE)
            return
        }

        missingFrameCount = 0
        result!!

        val height = HeightCalculator.calculate(
            cameraHeightCm = cameraHeight,
            tiltAngleDeg   = tiltAngleDeg.value,
            focalLengthPx  = focalLengthPx,
            headPixelY     = result.headTopY,
            feetPixelY     = result.feetY,
            imageCenterY   = result.imageHeight / 2f
        )

        if (height !in 50f..250f) { resetTo(MeasurementStatus.OUT_OF_ZONE); return }

        when (currentStatus) {
            MeasurementStatus.NO_PERSON, MeasurementStatus.OUT_OF_ZONE -> {
                inZoneFrameCount = 1
                _uiState.update { it.copy(status = MeasurementStatus.IN_ZONE) }
            }
            MeasurementStatus.IN_ZONE -> {
                if (++inZoneFrameCount >= inZoneFrameTarget)
                    _uiState.update { it.copy(status = MeasurementStatus.MEASURING, progress = 0f) }
            }
            MeasurementStatus.MEASURING -> {
                samples.add(height)
                val progress = samples.size / requiredSamples.toFloat()
                if (samples.size >= requiredSamples) {
                    _uiState.update {
                        it.copy(
                            status           = MeasurementStatus.COMPLETE,
                            measuredHeightCm = robustHeight(samples),
                            progress         = 1f
                        )
                    }
                } else {
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            MeasurementStatus.COMPLETE -> Unit
        }
    }

    fun reset() = resetTo(MeasurementStatus.NO_PERSON)

    private fun isInZone(result: PoseResult): Boolean {
        val h = result.imageHeight.toFloat()
        // Loose checks to handle real-world edge cases:
        //  • headTopY can be negative — the anatomical crown estimate extends above
        //    the frame when the person stands close and fills the image vertically.
        //  • feetY can be >= 0.98h — feet at the very bottom edge are valid; ML Kit
        //    reports inFrameLikelihood < 1 but still gives a position.
        //  • We only require head is above feet, each is in the correct half of the
        //    image, and minimum detection confidence is met.
        return result.headTopY < result.feetY          // basic sanity: head above feet
            && result.headTopY < h * 0.60f             // head in upper 60 % of frame
            && result.feetY    > h * 0.35f             // feet in lower 65 % of frame
            && result.confidence >= 0.30f              // relaxed — covers edge landmarks
    }

    private fun robustHeight(raw: List<Float>): Float {
        val sorted  = raw.sorted()
        val n       = sorted.size
        val q1      = sorted[n / 4]
        val q3      = sorted[3 * n / 4]
        val iqr     = q3 - q1
        val inliers = sorted.filter { it in (q1 - 1.5f * iqr)..(q3 + 1.5f * iqr) }
        return (if (inliers.isNotEmpty()) inliers else sorted).average().toFloat()
    }

    private fun resetTo(status: MeasurementStatus) {
        samples.clear()
        inZoneFrameCount  = 0
        missingFrameCount = 0
        _uiState.update { MeasurementUiState(status = status) }
    }

    override fun onCleared() {
        super.onCleared()
        tiltListener?.let { sensorManager.unregisterListener(it) }
    }
}
