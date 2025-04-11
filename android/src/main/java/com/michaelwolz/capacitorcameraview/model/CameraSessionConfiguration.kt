package com.michaelwolz.capacitorcameraview.model

/** Configuration for a camera session. */
data class CameraSessionConfiguration(
    val deviceId: String? = null,
    val enableBarcodeDetection: Boolean = false,
    val position: String = "back",
    val zoomFactor: Float = 1.0f
)

