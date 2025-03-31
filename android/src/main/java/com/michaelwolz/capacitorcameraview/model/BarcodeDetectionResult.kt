package com.michaelwolz.capacitorcameraview.model

/**
 * Barcode detection result containing the value, format type, and normalized bounding rectangle.
 */
data class BarcodeDetectionResult(
    val value: String,
    val displayValue: String,
    val type: String,
    val boundingRect: WebBoundingRect
)
