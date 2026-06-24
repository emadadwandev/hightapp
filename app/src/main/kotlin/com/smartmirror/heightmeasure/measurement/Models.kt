package com.smartmirror.heightmeasure.measurement

enum class MeasurementStatus {
    NO_PERSON,
    OUT_OF_ZONE,
    IN_ZONE,
    MEASURING,
    COMPLETE
}

data class MeasurementUiState(
    val status: MeasurementStatus = MeasurementStatus.NO_PERSON,
    val measuredHeightCm: Float? = null,
    val progress: Float = 0f
)

data class PoseResult(
    val headTopY: Float,
    val feetY: Float,
    val imageHeight: Int,
    val imageWidth: Int,
    val confidence: Float
)
