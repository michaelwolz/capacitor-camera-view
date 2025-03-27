package com.michaelwolz.capacitorcameraview

import com.getcapacitor.PluginCall

/** Configuration for a camera session. */
data class CameraSessionConfiguration(
    val deviceId: String?,
    val enableBarcodeDetection: Boolean,
    val position: String,
    val preset: String,
    val zoomFactor: Double
)

/** Maps a Capacitor plugin call to a [CameraSessionConfiguration]. */
fun sessionConfigFromPluginCall(call: PluginCall): CameraSessionConfiguration {
    val deviceId = call.getString("deviceId")
    val enableBarcodeDetection = call.getBoolean("enableBarcodeDetection") ?: false
    val position = call.getString("position") ?: "back"
    val preset = call.getString("preset") ?: "high"
    val zoomFactor = call.getDouble("zoomFactor") ?: 1.0

    return CameraSessionConfiguration(
        deviceId = deviceId,
        enableBarcodeDetection = enableBarcodeDetection,
        position = position,
        preset = preset,
        zoomFactor = zoomFactor
    )
}
