package com.michaelwolz.capacitorcameraview

import com.getcapacitor.PluginCall

/** Configuration for a camera session. */
data class CameraSessionConfiguration(
    val deviceId: String? = null,
    val enableBarcodeDetection: Boolean = false,
    val position: String = "back",
    val zoomFactor: Double = 1.0
)

/** Maps a Capacitor plugin call to a [CameraSessionConfiguration]. */
fun sessionConfigFromPluginCall(call: PluginCall): CameraSessionConfiguration {
    return CameraSessionConfiguration(
        deviceId = call.getString("deviceId"),
        enableBarcodeDetection = call.getBoolean("enableBarcodeDetection") ?: false,
        position = call.getString("position") ?: "back",
        zoomFactor = call.getDouble("zoomFactor") ?: 1.0
    )
}
