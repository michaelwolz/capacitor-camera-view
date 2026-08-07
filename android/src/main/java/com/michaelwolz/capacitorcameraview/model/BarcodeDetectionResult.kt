package com.michaelwolz.capacitorcameraview.model

/**
 * Barcode detection result containing the value, format type, and the bounding
 * rectangle in display/CSS pixels within the webview coordinate space.
 *
 * Overrides `equals`/`hashCode` explicitly because [rawBytes] is an array: the
 * data-class-generated versions would otherwise compare/hash it by reference identity
 * rather than content, silently breaking equality (and any hash-based collection use)
 * for two results with the same content but different array instances.
 */
data class BarcodeDetectionResult(
    val value: String,
    val rawBytes: ByteArray,
    val displayValue: String,
    val type: String,
    val boundingRect: WebBoundingRect
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BarcodeDetectionResult) return false

        return value == other.value &&
            rawBytes.contentEquals(other.rawBytes) &&
            displayValue == other.displayValue &&
            type == other.type &&
            boundingRect == other.boundingRect
    }

    override fun hashCode(): Int {
        var result = value.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + displayValue.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + boundingRect.hashCode()
        return result
    }
}
