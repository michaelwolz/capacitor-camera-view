package com.michaelwolz.capacitorcameraview

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Wraps an [ImageAnalysis.Analyzer] that reports results in
 * [ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED] coordinates, which requires the
 * sensor-to-view matrix supplied through [updateTransform].
 *
 * Until that matrix arrives such an analyzer discards every frame, so this wrapper turns a
 * matrix that never arrives into a log warning instead of silence.
 *
 * @param delegate The analyzer every call is forwarded to.
 */
class ViewReferencedAnalyzer(private val delegate: ImageAnalysis.Analyzer) :
    ImageAnalysis.Analyzer {

    private val missingTransformWarning = MissingViewTransformWarning()

    @Volatile
    private var hasTransform = false

    override fun analyze(image: ImageProxy) {
        if (!hasTransform) {
            missingTransformWarning
                .onFrameWithoutTransform(SystemClock.elapsedRealtime())
                ?.let { droppedFrames ->
                    Log.w(
                        TAG,
                        "No sensor-to-view transform available; " +
                            "$droppedFrames analysis frames dropped without detection."
                    )
                }
        }

        delegate.analyze(image)
    }

    override fun getDefaultTargetResolution(): Size? = delegate.defaultTargetResolution

    override fun getTargetCoordinateSystem(): Int = delegate.targetCoordinateSystem

    override fun updateTransform(matrix: Matrix?) {
        hasTransform = matrix != null
        if (matrix != null) {
            missingTransformWarning.onTransformAvailable()
        }
        delegate.updateTransform(matrix)
    }

    private companion object {
        const val TAG = "CameraView"
    }
}

/**
 * How long image analysis may run without a sensor-to-view matrix before the first warning,
 * covering the normal gap between binding a session and its first rendered preview frame.
 */
const val MISSING_VIEW_TRANSFORM_GRACE_MS = 1_000L

/** Minimum spacing between repeated "no sensor-to-view matrix" warnings. */
const val MISSING_VIEW_TRANSFORM_REPEAT_MS = 10_000L

/**
 * Decides when a run of frames analyzed without a sensor-to-view matrix is worth warning
 * about. Those frames are dropped before detection, so the condition is otherwise
 * indistinguishable from a device on which barcode scanning simply never works.
 *
 * Safe to drive from the analysis executor and the main thread at the same time.
 */
class MissingViewTransformWarning(
    private val graceMs: Long = MISSING_VIEW_TRANSFORM_GRACE_MS,
    private val repeatMs: Long = MISSING_VIEW_TRANSFORM_REPEAT_MS
) {
    private var firstDropAtMs: Long? = null
    private var lastWarnedAtMs: Long? = null
    private var droppedFrames = 0

    /**
     * Records a frame analyzed without a matrix.
     *
     * @param nowMs Monotonic timestamp of the frame.
     * @return The number of frames dropped since the previous warning when one is due now,
     *         or `null` when the run is still within the grace or repeat interval.
     */
    @Synchronized
    fun onFrameWithoutTransform(nowMs: Long): Int? {
        droppedFrames++

        val firstDropAt = firstDropAtMs ?: nowMs.also { firstDropAtMs = it }
        if (nowMs - firstDropAt < graceMs) return null

        val lastWarnedAt = lastWarnedAtMs
        if (lastWarnedAt != null && nowMs - lastWarnedAt < repeatMs) return null

        lastWarnedAtMs = nowMs
        return droppedFrames.also { droppedFrames = 0 }
    }

    /** Records that a matrix is available again, ending the current run. */
    @Synchronized
    fun onTransformAvailable() {
        firstDropAtMs = null
        lastWarnedAtMs = null
        droppedFrames = 0
    }
}
