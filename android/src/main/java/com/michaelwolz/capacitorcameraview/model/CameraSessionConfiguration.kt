package com.michaelwolz.capacitorcameraview.model

/**
 * Configuration for a camera session.
 *
 * @property deviceId Specific device ID to use. Takes precedence over position.
 * @property enableBarcodeDetection Whether to enable barcode detection.
 * @property barcodeTypes Optional list of specific barcode format codes to detect.
 *                        If null, all supported formats are detected.
 * @property position Camera position to use ("front" or "back").
 * @property zoomFactor Initial zoom factor.
 * @property aspectRatio Desired sensor aspect ratio ("4:3" or "16:9") applied to both
 *                       the preview and photo capture. If null, the long-standing
 *                       defaults are kept (16:9-with-fallback for capture, automatic
 *                       preview resolution).
 * @property captureMaxDimension Optional upper bound, in pixels, for the longer edge
 *                               of captured photos. If null, the default capture
 *                               resolution is kept.
 * @property previewScaleMode How the preview is scaled into its container. "fit"
 *                            letterboxes the whole frame (FIT_CENTER); any other
 *                            value (including null) keeps the default cover
 *                            behavior (FILL_CENTER).
 */
data class CameraSessionConfiguration(
    val deviceId: String? = null,
    val enableBarcodeDetection: Boolean = false,
    val barcodeTypes: List<Int>? = null,
    val position: String = "back",
    val zoomFactor: Float = 1.0f,
    val aspectRatio: String? = null,
    val captureMaxDimension: Int? = null,
    val previewScaleMode: String? = null
)

