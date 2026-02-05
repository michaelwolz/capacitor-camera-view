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
 */
data class CameraSessionConfiguration(
    val deviceId: String? = null,
    val enableBarcodeDetection: Boolean = false,
    val barcodeTypes: List<Int>? = null,
    val position: String = "back",
    val zoomFactor: Float = 1.0f
)

