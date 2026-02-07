package com.michaelwolz.capacitorcameraview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import android.view.Surface
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.getcapacitor.PluginCall
import com.google.mlkit.vision.barcode.common.Barcode
import com.michaelwolz.capacitorcameraview.model.CameraSessionConfiguration
import com.michaelwolz.capacitorcameraview.model.WebBoundingRect
import java.io.ByteArrayOutputStream

/**
 * Memory-efficient Base64 encoding utilities.
 * Uses ThreadLocal ByteArrayOutputStream pool to reduce allocation churn.
 */
object StreamingBase64Encoder {
    // Reusable ByteArrayOutputStream to reduce allocation churn (per-thread)
    private val outputStreamPool = object : ThreadLocal<ByteArrayOutputStream>() {
        override fun initialValue(): ByteArrayOutputStream {
            return ByteArrayOutputStream(256 * 1024) // 256KB initial capacity
        }
    }

    /**
     * Encodes a bitmap to Base64 with memory optimization.
     * Reuses ByteArrayOutputStream to reduce allocations.
     *
     * @param bitmap The bitmap to encode
     * @param quality JPEG compression quality (0-100)
     * @param format Compression format (default JPEG)
     * @return Base64 encoded string
     */
    fun encodeToBase64(
        bitmap: Bitmap,
        quality: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    ): String {
        val outputStream = outputStreamPool.get()!!
        outputStream.reset() // Clear previous data

        bitmap.compress(format, quality, outputStream)
        val byteArray = outputStream.toByteArray()

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Encodes raw byte array to Base64.
     */
    fun encodeToBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

/** Converts a barcode format code to a readable string. */
fun getBarcodeFormatString(format: Int): String {
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
 * Converts a string barcode type from JavaScript to ML Kit Barcode format constant.
 *
 * @param stringType The string barcode type from JavaScript.
 * @return The corresponding ML Kit Barcode format constant, or null if not recognized.
 */
fun convertToNativeBarcodeFormat(stringType: String): Int? {
    return when (stringType) {
        "qr" -> Barcode.FORMAT_QR_CODE
        "aztec" -> Barcode.FORMAT_AZTEC
        "codabar" -> Barcode.FORMAT_CODABAR
        "code39" -> Barcode.FORMAT_CODE_39
        "code39Mod43" -> Barcode.FORMAT_CODE_39 // ML Kit doesn't distinguish Mod43
        "code93" -> Barcode.FORMAT_CODE_93
        "code128" -> Barcode.FORMAT_CODE_128
        "dataMatrix" -> Barcode.FORMAT_DATA_MATRIX
        "ean8" -> Barcode.FORMAT_EAN_8
        "ean13" -> Barcode.FORMAT_EAN_13
        "interleaved2of5" -> Barcode.FORMAT_ITF
        "itf14" -> Barcode.FORMAT_ITF
        "pdf417" -> Barcode.FORMAT_PDF417
        "upcA" -> Barcode.FORMAT_UPC_A
        "upce" -> Barcode.FORMAT_UPC_E
        else -> null
    }
}

/**
 * Converts an array of string barcode types to ML Kit Barcode format constants.
 *
 * @param stringTypes List of string barcode types from JavaScript.
 * @return List of ML Kit Barcode format constants (invalid types are filtered out).
 */
fun convertToNativeBarcodeFormats(stringTypes: List<String>): List<Int> {
    return stringTypes.mapNotNull { convertToNativeBarcodeFormat(it) }.distinct()
}

/**
 * Converts the bounding box of a barcode detection result to a [WebBoundingRect]
 * suitable for use in the web view via regular CSS pixels.
 *
 * @param previewView The [PreviewView] used for the camera preview.
 * @param boundingBox The bounding box of the barcode detection result.
 * @param topOffset The top offset to be subtracted from the bounding box's top coordinate.
 */
fun boundingBoxToWebBoundingRect(
    previewView: PreviewView,
    boundingBox: Rect?,
    topOffset: Int = 0
): WebBoundingRect {
    if (boundingBox == null) {
        return WebBoundingRect(0f, 0f, 0f, 0f)
    }

    val devicePixelRatio = previewView.context.resources.displayMetrics.density

    return WebBoundingRect(
        x = boundingBox.left.toFloat() / devicePixelRatio,
        y = (boundingBox.top.toFloat() - topOffset) / devicePixelRatio,
        width = boundingBox.width().toFloat() / devicePixelRatio,
        height = boundingBox.height().toFloat() / devicePixelRatio
    )
}

/**
 * Helper method for calculating the top margin of the capacitor web view for correctly
 * positioning the barcode detection rectangle due to weird android edge-to-edge behavior
 * with web views and capacitors hack around this:
 * https://github.com/ionic-team/capacitor/pull/7871
 *
 * Not subtracting the margins will lead to the barcode detection rectangle being
 * positioned incorrectly too low on the screen.
 */
fun calculateTopOffset(webView: View): Int {
    val layoutParams = webView.layoutParams

    if (layoutParams is MarginLayoutParams) {
        return layoutParams.topMargin
    }

    return 0
}

/** Maps a Capacitor plugin call to a [CameraSessionConfiguration]. */
fun sessionConfigFromPluginCall(call: PluginCall): CameraSessionConfiguration {
    // Parse barcode types if provided
    val barcodeTypes: List<Int>? = call.getArray("barcodeTypes")?.let { jsonArray ->
        val stringTypes = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            jsonArray.optString(i)?.let { stringTypes.add(it) }
        }
        val converted = convertToNativeBarcodeFormats(stringTypes)
        if (converted.isNotEmpty()) converted else null
    }

    return CameraSessionConfiguration(
        deviceId = call.getString("deviceId"),
        enableBarcodeDetection = call.getBoolean("enableBarcodeDetection") ?: false,
        barcodeTypes = barcodeTypes,
        position = call.getString("position") ?: "back",
        zoomFactor = call.getFloat("zoomFactor") ?: 1.0f
    )
}

/**
 * Calculates the image orientation based on the display rotation and sensor rotation degrees.
 *
 * This is because CameraController will set the image orientation based on the device's
 * motion sensor, which may not match the display rotation and in this case not what we actually
 * want.
 *
 * @param displayRotation The current display rotation (0, 1, 2, or 3).
 * @param sensorRotationDegrees The rotation of the camera sensor in degrees (0, 90, 180, or 270).
 * @param isFrontFacing Whether the camera is front-facing or back-facing.
 * @return The calculated image orientation in degrees.
 */
fun calculateImageRotationBasedOnDisplayRotation(
    displayRotation: Int,
    sensorRotationDegrees: Int,
    isFrontFacing: Boolean
): Int {
    val surfaceRotationDegrees = when (displayRotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    return if (isFrontFacing) {
        (sensorRotationDegrees + surfaceRotationDegrees) % 360
    } else {
        (sensorRotationDegrees - surfaceRotationDegrees + 360) % 360
    }
}

/**
 * Converts an ImageProxy to a Base64 encoded string and applies rotation if necessary.
 * Uses StreamingBase64Encoder for memory-efficient encoding.
 *
 * @param image The ImageProxy to convert.
 * @param quality The JPEG compression quality (0-100).
 * @param rotationDegrees The degrees to rotate the image (0, 90, 180, 270).
 */
fun imageProxyToBase64(image: ImageProxy, quality: Int, rotationDegrees: Int): String {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("Failed to decode image")

    try {
        // Apply rotation if needed
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }
            val rotatedBitmap =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Recycle the original bitmap to prevent memory leaks
            bitmap.recycle()
            bitmap = rotatedBitmap
        }

        // Use streaming encoder for memory efficiency
        return StreamingBase64Encoder.encodeToBase64(bitmap, quality)
    } finally {
        // Ensure bitmap is always recycled
        bitmap.recycle()
    }
}