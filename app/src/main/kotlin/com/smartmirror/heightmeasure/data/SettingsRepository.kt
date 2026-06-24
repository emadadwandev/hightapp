package com.smartmirror.heightmeasure.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "height_measure_settings"
)

class SettingsRepository(private val context: Context) {

    companion object {
        private val CAMERA_HEIGHT_KEY  = floatPreferencesKey("camera_height_cm")
        private val TILT_ANGLE_KEY     = floatPreferencesKey("tilt_angle_deg")
        private val LOW_LIGHT_KEY      = booleanPreferencesKey("low_light_mode")
        private val IS_BACK_CAMERA_KEY = booleanPreferencesKey("is_back_camera")
    }

    val cameraHeightFlow: Flow<Float?> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[CAMERA_HEIGHT_KEY] }

    val tiltAngleFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[TILT_ANGLE_KEY] ?: 0f }

    val lowLightModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[LOW_LIGHT_KEY] ?: false }

    val isBackCameraFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[IS_BACK_CAMERA_KEY] ?: true }

    suspend fun setCameraHeight(heightCm: Float) {
        context.dataStore.edit { it[CAMERA_HEIGHT_KEY] = heightCm }
    }

    suspend fun setTiltAngle(degrees: Float) {
        context.dataStore.edit { it[TILT_ANGLE_KEY] = degrees }
    }

    suspend fun setLowLightMode(enabled: Boolean) {
        context.dataStore.edit { it[LOW_LIGHT_KEY] = enabled }
    }

    suspend fun setIsBackCamera(isBack: Boolean) {
        context.dataStore.edit { it[IS_BACK_CAMERA_KEY] = isBack }
    }
}
