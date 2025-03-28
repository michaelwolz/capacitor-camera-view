package com.michaelwolz.capacitorcameraview

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Barcode detection result containing the value, format type, and normalized bounding rectangle.
 */
data class BarcodeDetectionResult(
    val value: String,
    val type: String,
    val boundingRect: BoundingRect
)

/**
 * Normalized rectangle for barcode bounds.
 */
data class BoundingRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Image analyzer for detecting barcodes in camera frames.
 * Optimized for minimal resource usage and quick detection.
 *
 * @param callback Function called when a barcode is detected
 */
class BarcodeAnalyzer(private val callback: (BarcodeDetectionResult) -> Unit) :
    ImageAnalysis.Analyzer {
    private val barcodeScanner = BarcodeScanning.getClient()
    private val isProcessing = AtomicBoolean(false)
    private var lastProcessedTimestamp = 0L
    private var throttleMs = 500L // Limit processing to max twice per second

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        // Skip processing if already processing another frame or if throttled
        val currentTime = System.currentTimeMillis()
        if (isProcessing.get() || currentTime - lastProcessedTimestamp < throttleMs) {
            imageProxy.close()
            return
        }

        try {
            // Set processing flag
            isProcessing.set(true)

            // Get image from proxy
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            // Create input image with correct rotation
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            // Process the image for barcode detection
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        // Take first valid barcode
                        val barcode = barcodes.firstOrNull { it.rawValue != null } ?: run {
                            imageProxy.close()
                            isProcessing.set(false)
                            return@addOnSuccessListener
                        }

                        // Convert barcode format to string
                        val format = getBarcodeFormatString(barcode.format)

                        // Map the barcode boundingBox to normalized coordinates
                        val boundingRect = normalizeRect(
                            rect = barcode.boundingBox ?: Rect(0, 0, 0, 0),
                            imageWidth = imageProxy.width,
                            imageHeight = imageProxy.height
                        )

                        // Create result object and notify
                        callback(
                            BarcodeDetectionResult(
                                value = barcode.rawValue ?: "",
                                type = format,
                                boundingRect = boundingRect
                            )
                        )

                        // Update timestamp to apply throttling
                        lastProcessedTimestamp = currentTime
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Barcode scanning failed", exception)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    isProcessing.set(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image for barcodes", e)
            imageProxy.close()
            isProcessing.set(false)
        }
    }

    /**
     * Converts a barcode format code to a readable string.
     */
    private fun getBarcodeFormatString(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "qr"
            Barcode.FORMAT_AZTEC -> "aztec"
            Barcode.FORMAT_CODABAR -> "codabar"
            Barcode.FORMAT_CODE_39 -> "code39"
            Barcode.FORMAT_CODE_93 -> "code93"
            Barcode.FORMAT_CODE_128 -> "code128"
            Barcode.FORMAT_DATA_MATRIX -> "dataMatrix"
            Barcode.FORMAT_EAN_8 -> "ean8"
            Barcode.FORMAT_EAN_13 -> "ean13"
            Barcode.FORMAT_ITF -> "itf"
            Barcode.FORMAT_PDF417 -> "pdf417"
            Barcode.FORMAT_UPC_A -> "upcA"
            Barcode.FORMAT_UPC_E -> "upcE"
            else -> "unknown"
        }
    }

    /**
     * Normalize a rectangle coordinates to be in range [0,1]
     * for consistent representation across platforms.
     */
    private fun normalizeRect(rect: Rect, imageWidth: Int, imageHeight: Int): BoundingRect {
        // Ensure we don't divide by zero
        val width = max(1, imageWidth)
        val height = max(1, imageHeight)

        return BoundingRect(
            x = rect.left.toFloat() / width,
            y = rect.top.toFloat() / height,
            width = rect.width().toFloat() / width,
            height = rect.height().toFloat() / height
        )
    }

    companion object {
        private const val TAG = "BarcodeAnalyzer"
    }
}