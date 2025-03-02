package com.michaelwolz.capacitorcameraview

import com.getcapacitor.JSObject
import com.getcapacitor.JSArray
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "CameraView")
class CameraViewPlugin : Plugin() {
    private val implementation = CameraView()

    @PluginMethod
    fun echo(call: PluginCall) {
        val value = call.getString("value")

        val ret = JSObject()
        ret.put("value", implementation.echo(value))
        call.resolve(ret)
    }

    @PluginMethod
    fun start(call: PluginCall) {
        val cameraPosition = call.getString("cameraPosition")
        if (cameraPosition == null) {
            call.reject("Camera position must be provided")
            return
        }

        // TODO: Implement camera start with position
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        // TODO: Implement camera stop
        call.resolve()
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        val ret = JSObject()
        // TODO: Check if camera is running
        ret.put("value", false)
        call.resolve(ret)
    }

    @PluginMethod
    fun capture(call: PluginCall) {
        val quality = call.getInt("quality")
        if (quality == null) {
            call.reject("Quality must be provided")
            return
        }

        val ret = JSObject()
        // TODO: Implement photo capture with quality
        ret.put("value", "base64_encoded_image_data")
        call.resolve(ret)
    }

    @PluginMethod
    fun switchCamera(call: PluginCall) {
        // TODO: Implement camera switching
        call.resolve()
    }

    @PluginMethod
    fun getZoom(call: PluginCall) {
        val ret = JSObject()
        // TODO: Get current zoom levels
        ret.put("min", 1.0)
        ret.put("max", 10.0)
        ret.put("current", 1.0)
        call.resolve(ret)
    }

    @PluginMethod
    fun setZoom(call: PluginCall) {
        val level = call.getDouble("level")
        if (level == null) {
            call.reject("Zoom level must be provided")
            return
        }

        // TODO: Implement zoom level setting
        call.resolve()
    }

    @PluginMethod
    fun getFlashMode(call: PluginCall) {
        val ret = JSObject()
        // TODO: Get current flash mode
        ret.put("value", "off")
        call.resolve(ret)
    }

    @PluginMethod
    fun getSupportedFlashModes(call: PluginCall) {
        val ret = JSObject()
        val flashModes = JSArray()
        flashModes.put("off")
        flashModes.put("on")
        flashModes.put("auto")
        flashModes.put("torch")

        // TODO: Get supported flash modes
        ret.put("value", flashModes)
        call.resolve(ret)
    }

    @PluginMethod
    fun setFlashMode(call: PluginCall) {
        val mode = call.getString("mode")
        if (mode == null) {
            call.reject("Flash mode must be provided")
            return
        }

        // TODO: Set flash mode
        call.resolve()
    }
}
