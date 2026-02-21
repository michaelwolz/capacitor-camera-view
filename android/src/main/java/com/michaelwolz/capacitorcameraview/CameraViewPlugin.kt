package com.michaelwolz.capacitorcameraview

import android.Manifest
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@CapacitorPlugin(
    name = "CameraView",
    permissions = [Permission(strings = [Manifest.permission.CAMERA], alias = "camera")]
)
class CameraViewPlugin : Plugin() {
    // Coroutine scope for async operations
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Job for barcode event subscription
    private var barcodeJob: Job? = null

    private val implementation by lazy {
        CameraView(this)
    }

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
        val config = sessionConfigFromPluginCall(call)

        pluginScope.launch {
            implementation.startSessionAsync(config).fold(
                onSuccess = {
                    // Subscribe to barcode events if detection is enabled
                    if (config.enableBarcodeDetection) {
                        barcodeJob?.cancel()
                        barcodeJob = pluginScope.launch {
                            implementation.barcodeEvents.collect { result ->
                                notifyBarcodeDetected(result)
                            }
                        }
                    }
                    call.resolve()
                },
                onError = { error ->
                    call.reject("Failed to start camera preview: ${error.localizedMessage}", error)
                }
            )
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        // Cancel barcode subscription
        barcodeJob?.cancel()
        barcodeJob = null

        pluginScope.launch {
            implementation.stopSessionAsync().fold(
                onSuccess = { call.resolve() },
                onError = { error ->
                    call.reject("Failed to stop camera preview: ${error.localizedMessage}", error)
                }
            )
        }
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
        val timeStart = System.currentTimeMillis()
        val quality = call.getInt("quality") ?: 90
        val saveToFile = call.getBoolean("saveToFile") ?: false

        if (quality !in 0..100) {
            call.reject("Quality must be between 0 and 100")
            return
        }

        pluginScope.launch {
            implementation.capturePhotoAsync(quality, saveToFile).fold(
                onSuccess = { result ->
                    call.resolve(result)
                    Log.d(TAG, "capture took ${System.currentTimeMillis() - timeStart}ms")
                },
                onError = { error ->
                    call.reject("Failed to capture image: ${error.message}", error)
                    Log.d(TAG, "capture failed after ${System.currentTimeMillis() - timeStart}ms")
                }
            )
        }
    }

    @PluginMethod
    fun captureSample(call: PluginCall) {
        val timeStart = System.currentTimeMillis()
        val quality = call.getInt("quality") ?: 90
        val saveToFile = call.getBoolean("saveToFile") ?: false

        if (quality !in 0..100) {
            call.reject("Quality must be between 0 and 100")
            return
        }

        pluginScope.launch {
            implementation.captureSampleFromPreviewAsync(quality, saveToFile).fold(
                onSuccess = { result ->
                    call.resolve(result)
                    Log.d(TAG, "captureSample took ${System.currentTimeMillis() - timeStart}ms")
                },
                onError = { error ->
                    call.reject("Failed to capture frame: ${error.message}", error)
                    Log.d(TAG, "captureSample failed after ${System.currentTimeMillis() - timeStart}ms")
                }
            )
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
        val level = call.getFloat("level")
        if (level == null) {
            call.reject("Zoom level must be provided")
            return
        }

        implementation.setZoomFactor(level) { error ->
            if (error != null) {
                call.reject(error.localizedMessage)
            } else {
                call.resolve()
            }
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

    @PluginMethod
    fun isTorchAvailable(call: PluginCall) {
        implementation.isTorchAvailable { available ->
            call.resolve(JSObject().apply {
                put("available", available)
            })
        }
    }

    @PluginMethod
    fun getTorchMode(call: PluginCall) {
        implementation.getTorchMode { enabled ->
            call.resolve(JSObject().apply {
                put("enabled", enabled)
                put(
                    "level",
                    // Android always uses full intensity when enabled
                    if (enabled) 1.0f else 0.0f
                )
            })
        }
    }

    @PluginMethod
    fun setTorchMode(call: PluginCall) {
        val enabled = call.getBoolean("enabled")
        if (enabled == null) {
            call.reject("Enabled parameter is required")
            return
        }

        implementation.setTorchMode(enabled) { error ->
            if (error != null) {
                call.reject("Failed to set torch mode: ${error.localizedMessage}", error)
            } else {
                call.resolve()
            }
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
        // Cancel barcode subscription and plugin scope
        barcodeJob?.cancel()
        pluginScope.cancel()

        implementation.cleanup()
        super.handleOnDestroy()
    }

    companion object {
        private const val TAG = "CameraViewPlugin"
    }
}
