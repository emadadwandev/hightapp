package com.smartmirror.heightmeasure.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.smartmirror.heightmeasure.measurement.PoseResult

class PoseAnalyzer(
    private val onResult: (PoseResult?) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            // STREAM_MODE gives ML Kit temporal context across frames, which is the
            // primary accuracy lever — it tracks body parts between frames rather
            // than starting fresh each time, so detection is both faster and more
            // stable than SINGLE_IMAGE_MODE at video frame rates.
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
    )

    // alpha = 0.6: favours the new measurement (fast response) while still
    // dampening single-frame noise.  Lower values → smoother but laggier.
    private val headEma = EmaFilter(alpha = 0.6f)
    private val feetEma = EmaFilter(alpha = 0.6f)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                val raw = runCatching {
                    extractResult(pose, imageProxy.width, imageProxy.height)
                }.getOrNull()

                if (raw == null) {
                    headEma.reset()
                    feetEma.reset()
                    onResult(null)
                } else {
                    onResult(
                        raw.copy(
                            headTopY = headEma.update(raw.headTopY),
                            feetY    = feetEma.update(raw.feetY)
                        )
                    )
                }
            }
            .addOnFailureListener {
                headEma.reset()
                feetEma.reset()
                onResult(null)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun extractResult(
        pose: com.google.mlkit.vision.pose.Pose,
        imageWidth: Int,
        imageHeight: Int
    ): PoseResult? {
        // ── Head top ──────────────────────────────────────────────────────────
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE) ?: return null
        if (nose.inFrameLikelihood < 0.4f) return null

        // Eyes are more reliably detected than ears at distance / low light
        val leftEye  = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)
        val rightEye = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)

        val eyeCenterY = listOfNotNull(leftEye, rightEye)
            .filter { it.inFrameLikelihood >= 0.4f }
            .map { it.position.y }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toFloat()
            ?: (nose.position.y - imageHeight * 0.04f)

        // Anatomical proportion: top-of-head to eye ≈ 3.3× the eye-to-nose vertical distance.
        // This estimate reaches the actual crown of the head.
        val eyeToNose = (nose.position.y - eyeCenterY).coerceAtLeast(imageHeight * 0.015f)
        val headTopY  = eyeCenterY - eyeToNose * 3.3f

        // ── Feet ──────────────────────────────────────────────────────────────
        // Prefer heel/toe landmarks (ground contact) over ankle; take all that
        // are visible and use the one farthest down in the image (largest Y).
        val feetCandidates = listOfNotNull(
            pose.getPoseLandmark(PoseLandmark.LEFT_HEEL),
            pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL),
            pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX),
            pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX),
            pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE),
            pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        // Use 0.25f — when the person fills the frame vertically, feet sit at the
        // very bottom edge and ML Kit reports low inFrameLikelihood even though
        // it has a valid extrapolated position.
        ).filter { it.inFrameLikelihood >= 0.25f }

        if (feetCandidates.isEmpty()) return null

        val feetY = feetCandidates.maxOf { it.position.y }

        val avgConfidence = (listOf(nose) + feetCandidates)
            .map { it.inFrameLikelihood }
            .average()
            .toFloat()

        return PoseResult(
            headTopY    = headTopY,
            feetY       = feetY,
            imageHeight = imageHeight,
            imageWidth  = imageWidth,
            confidence  = avgConfidence
        )
    }

    fun close() = detector.close()

    private class EmaFilter(private val alpha: Float) {
        private var value: Float? = null
        fun update(new: Float): Float =
            (alpha * new + (1f - alpha) * (value ?: new)).also { value = it }
        fun reset() { value = null }
    }
}
