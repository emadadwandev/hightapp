package com.smartmirror.heightmeasure.measurement

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object HeightCalculator {

    /**
     * Calculates person height for a camera mounted at [cameraHeightCm] tilted downward
     * by [tiltAngleDeg] degrees from horizontal.
     *
     * Derivation (perspective projection, horizontal camera rotated by θ downward):
     *
     *   Let δ_feet = y_feet − cy,  δ_head = y_head − cy  (image-space offsets from center)
     *
     *   Distance to person:
     *     D = H_c × (f·cosθ − δ_feet·sinθ) / (δ_feet·cosθ + f·sinθ)
     *
     *   Person height:
     *     H_p = H_c − D × (f·sinθ + δ_head·cosθ) / (f·cosθ − δ_head·sinθ)
     *
     * When θ = 0 this reduces to the classic:
     *     H_p = H_c × (y_feet − y_head) / (y_feet − cy)
     * and [focalLengthPx] is not required.
     *
     * Returns −1 if the geometry is degenerate (person behind camera, tilt too steep, etc.).
     */
    fun calculate(
        cameraHeightCm: Float,
        tiltAngleDeg: Float,
        focalLengthPx: Float,
        headPixelY: Float,
        feetPixelY: Float,
        imageCenterY: Float
    ): Float {
        val dFeet = feetPixelY - imageCenterY
        val dHead = headPixelY - imageCenterY

        // Use the simpler, f-independent formula for an effectively horizontal camera
        if (tiltAngleDeg < 0.5f) {
            if (dFeet <= 0f) return -1f
            return cameraHeightCm * (feetPixelY - headPixelY) / dFeet
        }

        val theta   = Math.toRadians(tiltAngleDeg.toDouble())
        val cosT    = cos(theta).toFloat()
        val sinT    = sin(theta).toFloat()
        val f       = focalLengthPx

        // Distance to subject
        val denomD = dFeet * cosT + f * sinT
        if (denomD <= 0f) return -1f
        val D = cameraHeightCm * (f * cosT - dFeet * sinT) / denomD
        if (D <= 0f) return -1f

        // Person height
        val denomH = f * cosT - dHead * sinT
        if (abs(denomH) < 1f) return -1f
        val Hp = cameraHeightCm - D * (f * sinT + dHead * cosT) / denomH

        return Hp
    }
}
