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
    return CameraSessionConfiguration(
        deviceId = call.getString("deviceId"),
        enableBarcodeDetection = call.getBoolean("enableBarcodeDetection") ?: false,
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

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    } finally {
        // Ensure bitmap is always recycled
        bitmap.recycle()
    }
}