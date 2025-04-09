package com.michaelwolz.capacitorcameraview

import android.Manifest
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.michaelwolz.capacitorcameraview.model.BarcodeDetectionResult

@CapacitorPlugin(
    name = "CameraView",
    permissions = [Permission(strings = [Manifest.permission.CAMERA], alias = "camera")]
)
class CameraViewPlugin : Plugin() {
    private val implementation = CameraView()

    @PluginMethod
    fun start(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            startCamera(call)
        } else {
            requestPermissionForAlias("camera", call, "cameraPermsCallback")
        }
    }

    @PermissionCallback
    private fun cameraPermsCallback(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            startCamera(call)
        } else {
            call.reject("Permission is required to take a picture")
        }
    }

    private fun startCamera(call: PluginCall) {
        implementation.startSession(
            config = sessionConfigFromPluginCall(call),
            plugin = this,
            callback = { error ->
                if (error != null) {
                    call.reject("Failed to start camera preview: ${error.localizedMessage}", error)
                } else {
                    call.resolve()
                }
            }
        )
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        implementation.stopSession({ error ->
            if (error != null) {
                call.reject("Failed to stop camera preview: ${error.localizedMessage}", error)
            } else {
                call.resolve()
            }
        })
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        val result = JSObject().apply {
            put("isRunning", implementation.isRunning())
        }
        call.resolve(result)
    }

    @PluginMethod
    fun capture(call: PluginCall) {
        val quality = call.getInt("quality", 90)

        if (quality !in 0..100) {
            call.reject("Quality must be between 0 and 100")
            return
        }

        implementation.capturePhoto(quality) { photo, error ->
            when {
                error != null -> call.reject("Failed to capture image: ${error.message}", error)
                photo == null -> call.reject("No image data")
                else -> call.resolve(JSObject().apply { put("photo", photo) })
            }
        }
    }

    @PluginMethod
    fun captureSample(call: PluginCall) {
        val quality = call.getInt("quality", 90)

        if (quality !in 0..100) {
            call.reject("Quality must be between 0 and 100")
            return
        }

        implementation.captureSampleFromPreview(quality) { photo, error ->
            when {
                error != null -> call.reject("Failed to capture frame: ${error.message}", error)
                photo == null -> call.reject("No frame data")
                else -> call.resolve(JSObject().apply { put("photo", photo) })
            }
        }
    }

    @PluginMethod
    fun getAvailableDevices(call: PluginCall) {
        val devices = implementation.getAvailableDevices()
        val devicesArray = JSArray().apply {
            devices.forEach { device ->
                put(JSObject().apply {
                    put("id", device.id)
                    put("name", device.name)
                    put("position", device.position)
                })
            }
        }

        call.resolve(JSObject().apply { put("devices", devicesArray) })
    }

    @PluginMethod
    fun flipCamera(call: PluginCall) {
        implementation.flipCamera { error ->
            if (error != null) {
                call.reject("Failed to flip camera: ${error.localizedMessage}", error)
            } else {
                call.resolve()
            }
        }
    }

    @PluginMethod
    fun getZoom(call: PluginCall) {
        implementation.getSupportedZoomFactors { zoomFactors ->
            call.resolve(JSObject().apply {
                put("min", zoomFactors.min)
                put("max", zoomFactors.max)
                put("current", zoomFactors.current)
            })
        }
    }

    @PluginMethod
    fun setZoom(call: PluginCall) {
        val level = call.getDouble("level")
        if (level == null) {
            call.reject("Zoom level must be provided")
            return
        }

        try {
            implementation.setZoomFactor(level)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Failed to set zoom level: ${e.localizedMessage}", e)
        }
    }

    @PluginMethod
    fun getFlashMode(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("flashMode", implementation.getFlashMode())
        })
    }

    @PluginMethod
    fun getSupportedFlashModes(call: PluginCall) {
        implementation.getSupportedFlashModes { supportedFlashModes ->
            val modesArray = JSArray().apply {
                supportedFlashModes.forEach { put(it) }
            }

            call.resolve(JSObject().apply { put("flashModes", modesArray) })
        }
    }

    @PluginMethod
    fun setFlashMode(call: PluginCall) {
        val mode = call.getString("mode")
        if (mode == null) {
            call.reject("Flash mode must be provided")
            return
        }

        val validModes = listOf("off", "on", "auto")
        if (!validModes.contains(mode)) {
            call.reject("Invalid flash mode. Must be one of: ${validModes.joinToString(", ")}")
            return
        }

        try {
            implementation.setFlashMode(mode)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Failed to set flash mode: ${e.localizedMessage}", e)
        }
    }

    /**
     * Called by the CameraView when a barcode is detected.
     */
    fun notifyBarcodeDetected(result: BarcodeDetectionResult) {
        val jsObject = JSObject().apply {
            put("value", result.value)
            put("type", result.type)
            put("boundingRect", JSObject().apply {
                put("x", result.boundingRect.x)
                put("y", result.boundingRect.y)
                put("width", result.boundingRect.width)
                put("height", result.boundingRect.height)
            })
        }

        notifyListeners("barcodeDetected", jsObject)
    }

    override fun handleOnDestroy() {
        implementation.cleanup()
        super.handleOnDestroy()
    }
}
