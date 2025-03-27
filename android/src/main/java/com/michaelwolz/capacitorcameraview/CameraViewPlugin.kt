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
import androidx.lifecycle.LifecycleOwner

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
        val webView = bridge.webView
        val context = webView.context

        if (context !is LifecycleOwner) {
            call.reject("WebView context must be a LifecycleOwner")
            return
        }

        implementation.startSession(
            config = sessionConfigFromPluginCall(call),
            webView = webView,
            lifecycleOwner = context,
            callback = { error ->
                if (error != null) {
                    call.reject("Failed to start camera preview", error)
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
                call.reject("Failed to stop camera preview", error)
            } else {
                call.resolve()
            }
        })
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        val result = JSObject()
        result.put("isRunning", implementation.isRunning())

        call.resolve(result)
    }

    @PluginMethod
    fun capture(call: PluginCall) {
        val quality = (call.getDouble("quality") ?: 90).toInt()

        if (quality < 0 || quality > 100) {
            call.reject("Quality must be between 0 and 100")
            return
        }

        implementation.capturePhoto(quality) { photo, error ->
            if (error != null) {
                call.reject("Failed to capture image", error)
                return@capturePhoto
            }

            if (photo == null) {
                call.reject("No image data")
                return@capturePhoto
            }

            val result = JSObject()
            result.put("photo", photo)

            call.resolve(result)
        }
    }

    @PluginMethod
    fun getAvailableDevices(call: PluginCall) {
        val devices = implementation.getAvailableDevices()
        val result = JSObject()
        val devicesArray = JSArray()

        for (device in devices) {
            val deviceInfo = JSObject()
            deviceInfo.put("id", device.id)
            deviceInfo.put("name", device.name)
            deviceInfo.put("position", device.position)
            devicesArray.put(deviceInfo)
        }

        result.put("devices", devicesArray)
        call.resolve(result)
    }

    @PluginMethod
    fun flipCamera(call: PluginCall) {
        implementation.flipCamera { error ->
            if (error != null) {
                call.reject("Failed to flip camera", error)
            } else {
                call.resolve()
            }
        }
    }

    @PluginMethod
    fun getZoom(call: PluginCall) {
        val zoom = implementation.getSupportedZoomFactors()
        val result = JSObject()
        result.put("min", zoom.min)
        result.put("max", zoom.max)
        result.put("current", zoom.current)
        call.resolve(result)
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
            call.reject("Failed to set zoom level", e)
        }
    }

    @PluginMethod
    fun getFlashMode(call: PluginCall) {
        val flashMode = implementation.getFlashMode()
        val result = JSObject()
        result.put("flashMode", flashMode)

        call.resolve(result)
    }

    @PluginMethod
    fun getSupportedFlashModes(call: PluginCall) {
        val supportedFlashModes = implementation.getSupportedFlashModes()
        val result = JSObject()
        val modesArray = JSArray()

        for (mode in supportedFlashModes) {
            modesArray.put(mode)
        }

        result.put("flashModes", modesArray)
        call.resolve(result)
    }

    @PluginMethod
    fun setFlashMode(call: PluginCall) {
        val mode = call.getString("mode")
        if (mode == null) {
            call.reject("Flash mode must be provided")
            return
        }

        if (!listOf("off", "on", "auto").contains(mode)) {
            call.reject("Invalid flash mode")
            return
        }

        try {
            implementation.setFlashMode(mode)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Failed to set flash mode", e)
        }
    }

    override fun handleOnDestroy() {
        implementation.cleanup()
        super.handleOnDestroy()
    }
}
