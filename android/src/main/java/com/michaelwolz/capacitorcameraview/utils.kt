package com.michaelwolz.capacitorcameraview

import android.graphics.Rect
import androidx.camera.view.PreviewView
import com.getcapacitor.PluginCall
import com.google.mlkit.vision.barcode.common.Barcode
import com.michaelwolz.capacitorcameraview.model.CameraSessionConfiguration
import com.michaelwolz.capacitorcameraview.model.WebBoundingRect

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

fun boundingBoxToWebBoundingRect(previewView: PreviewView, boundingBox: Rect?): WebBoundingRect {
    if (boundingBox == null) {
        return WebBoundingRect(0f, 0f, 0f, 0f)
    }

    val devicePixelRatio = previewView.context.resources.displayMetrics.density

    return WebBoundingRect(
        x = boundingBox.left.toFloat() / devicePixelRatio,
        y = boundingBox.top.toFloat() / devicePixelRatio,
        width = boundingBox.width().toFloat() / devicePixelRatio,
        height = boundingBox.height().toFloat() / devicePixelRatio
    )
}

/** Maps a Capacitor plugin call to a [CameraSessionConfiguration]. */
fun sessionConfigFromPluginCall(call: PluginCall): CameraSessionConfiguration {
    return CameraSessionConfiguration(
        deviceId = call.getString("deviceId"),
        enableBarcodeDetection = call.getBoolean("enableBarcodeDetection") ?: false,
        position = call.getString("position") ?: "back",
        zoomFactor = call.getDouble("zoomFactor") ?: 1.0
    )
}
