package com.michaelwolz.capacitorcameraview

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
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
    val displayValue: String,
    val type: String,
    val boundingRect: BoundingRect
)

/** Normalized rectangle for barcode bounds. */
data class BoundingRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Image analyzer for detecting barcodes in camera frames. Optimized for minimal resource usage and
 * quick detection.
 *
 * @param callback Function called when a barcode is detected
 * @param previewView Optional preview view to transform coordinates to screen space
 */
class BarcodeAnalyzer(
    private val callback: (BarcodeDetectionResult) -> Unit,
    private val previewView: PreviewView? = null
) : ImageAnalysis.Analyzer {
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

            Log.d(
                TAG,
                "Analyzing image with dimensions: ${mediaImage.width} x ${mediaImage.height}"
            )
            Log.d(
                TAG,
                "Analyzing image with dimensions: ${imageProxy.width} x ${imageProxy.height}"
            )

            // Create input image with correct rotation
            val inputImage =
                InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Process the image for barcode detection
            barcodeScanner
                .process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isEmpty()) {
                        imageProxy.close()
                        isProcessing.set(false)
                        return@addOnSuccessListener
                    }

                    // Take first valid barcode
                    val barcode = barcodes.firstOrNull { it.rawValue != null }
                        ?: run {
                            imageProxy.close()
                            isProcessing.set(false)
                            return@addOnSuccessListener
                        }

                    // Convert barcode format to string
                    val format = getBarcodeFormatString(barcode.format)

                    // Get barcode bounding box
                    val barcodeRect = barcode.boundingBox ?: Rect(0, 0, 0, 0)

                    Log.d(TAG, "Rotation degrees: ${imageProxy.imageInfo.rotationDegrees}")

                    // Map the barcode boundingBox based on the preview view if available
                    val boundingRect = transformToViewCoordinates(barcodeRect, imageProxy)

                    callback(
                        BarcodeDetectionResult(
                            value = barcode.rawValue ?: "",
                            displayValue = barcode.displayValue ?: "",
                            type = format,
                            boundingRect = boundingRect
                        )
                    )

                    // Update timestamp to apply throttling
                    lastProcessedTimestamp = currentTime
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

    /** Converts a barcode format code to a readable string. */
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

    /** Normalize a rectangle coordinates to be in range [0,1]. */
    private fun normalizeRect(rect: Rect, imageWidth: Int, imageHeight: Int): BoundingRect {
        val width = max(1, imageWidth)
        val height = max(1, imageHeight)

        return BoundingRect(
            x = rect.left.toFloat() / width,
            y = rect.top.toFloat() / height,
            width = rect.width().toFloat() / width,
            height = rect.height().toFloat() / height
        )
    }

    private fun transformToViewCoordinates(
        rect: Rect,
        imageProxy: ImageProxy
    ): BoundingRect {
        val previewView = this.previewView ?: run {
            return normalizeRect(rect, imageProxy.width, imageProxy.height)
        }

        // Get display orientation and dimensions
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        
        // Get web dimensions (accounting for device pixel ratio)
        val devicePixelRatio = previewView.context.resources.displayMetrics.density
        val webWidth = previewView.width.toFloat() / devicePixelRatio
        val webHeight = previewView.height.toFloat() / devicePixelRatio
        
        // Get proper dimensions based on rotation
        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height
        
        // Normalize coordinates based on rotation
        val normalizedRect = when (rotationDegrees) {
            90 -> { // Most common case for portrait mode
                // Swap X/Y and invert X
                BoundingRect(
                    x = rect.top.toFloat() / imageHeight,
                    y = (imageWidth - rect.right).toFloat() / imageWidth,
                    width = rect.height().toFloat() / imageHeight,
                    height = rect.width().toFloat() / imageWidth
                )
            }
            270 -> {
                // Swap X/Y and invert Y
                BoundingRect(
                    x = (imageHeight - rect.bottom).toFloat() / imageHeight,
                    y = rect.left.toFloat() / imageWidth,
                    width = rect.height().toFloat() / imageHeight,
                    height = rect.width().toFloat() / imageWidth
                )
            }
            180 -> {
                // Invert both X and Y
                BoundingRect(
                    x = (imageWidth - rect.right).toFloat() / imageWidth,
                    y = (imageHeight - rect.bottom).toFloat() / imageHeight,
                    width = rect.width().toFloat() / imageWidth,
                    height = rect.height().toFloat() / imageHeight
                )
            }
            else -> { // 0 degrees
                // Standard normalization
                normalizeRect(rect, imageWidth, imageHeight)
            }
        }
        
        // Convert to web pixel coordinates
        val transformedRect = BoundingRect(
            x = normalizedRect.x * webWidth,
            y = normalizedRect.y * webHeight,
            width = normalizedRect.width * webWidth,
            height = normalizedRect.height * webHeight
        )
        
        // Log for debugging
        Log.d(TAG, "Image size: $imageWidth x $imageHeight")
        Log.d(TAG, "Rotation: $rotationDegrees")
        Log.d(TAG, "PreviewView size: ${previewView.width} x ${previewView.height}")
        Log.d(TAG, "Web dimensions: $webWidth x $webHeight")
        Log.d(TAG, "Normalized rect: $normalizedRect")
        Log.d(TAG, "Transformed rect: $transformedRect")
        
        return transformedRect
    }

    companion object {
        private const val TAG = "BarcodeAnalyzer"
    }
}
