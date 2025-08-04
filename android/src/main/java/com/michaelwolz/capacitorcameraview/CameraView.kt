package com.michaelwolz.capacitorcameraview

import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.getcapacitor.FileUtils
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.michaelwolz.capacitorcameraview.model.BarcodeDetectionResult
import com.michaelwolz.capacitorcameraview.model.CameraDevice
import com.michaelwolz.capacitorcameraview.model.CameraSessionConfiguration
import com.michaelwolz.capacitorcameraview.model.ZoomFactors
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Throttle time for barcode detection in milliseconds. */
const val BARCODE_DETECTION_THROTTLE_MS = 100

class CameraView(plugin: Plugin) {
    // Camera components
    private var cameraController: LifecycleCameraController? = null
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var previewView: PreviewView? = null

    // Camera state
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var isTorchEnabled: Boolean = false

    // Plugin context
    private var lifecycleOwner: LifecycleOwner? = null
    private var pluginDelegate: Plugin = plugin
    private var webView: WebView = plugin.bridge.webView
    private var context: Context = webView.context

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private var lastBarcodeDetectionTime = 0L

    /** Starts a camera session with the provided configuration. */
    fun startSession(config: CameraSessionConfiguration, callback: (Exception?) -> Unit) {
        val lifecycleOwner =
            context as? LifecycleOwner
                ?: run {
                    callback(CameraError.LifecycleOwnerMissing())
                    return
                }

        // Store references for later use
        this.lifecycleOwner = lifecycleOwner

        mainHandler.post {
            try {
                initializeCamera(context, lifecycleOwner, config)
                callback(null)
            } catch (e: Exception) {
                Log.e(TAG, "Error in camera setup", e)
                callback(e)
            }
        }
    }

    /** Stop the camera session and release resources */
    fun stopSession(callback: ((Exception?) -> Unit)? = null) {
        mainHandler.post {
            cameraController?.unbind()

            try {
                previewView?.let { view ->
                    try {
                        (webView.parent as? ViewGroup)?.removeView(view)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing preview view", e)
                    } finally {
                        previewView = null
                    }
                }

                webView.setLayerType(WebView.LAYER_TYPE_NONE, null)
                webView.setBackgroundColor(android.graphics.Color.WHITE)

                Log.d(TAG, "Camera session stopped successfully")
                callback?.invoke(null)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping camera session", e)
                callback?.invoke(e)
            }
        }
    }

    /** Checks if the camera session is running */
    fun isRunning(): Boolean {
        return cameraController != null
    }

    /** Capture a photo with the current camera configuration */
    fun capturePhoto(
        quality: Int,
        saveToFile: Boolean = false,
        callback: (JSObject?, Exception?) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        val controller = cameraController
            ?: run {
                callback(null, CameraError.CameraNotInitialized())
                return
            }

        val preview = previewView
            ?: run {
                callback(null, CameraError.PreviewNotInitialized())
                return
            }

        mainHandler.post {
            val cameraInfo = controller.cameraInfo
            val isFrontFacing = controller.cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
            val sensorRotationDegrees = cameraInfo?.sensorRotationDegrees ?: 0
            val displayRotationDegrees = preview.display?.rotation ?: Surface.ROTATION_0
            val imageRotationDegrees = calculateImageRotationBasedOnDisplayRotation(
                displayRotationDegrees,
                sensorRotationDegrees,
                isFrontFacing
            )

            try {
                if (saveToFile) {
                    // Direct file capture - much more efficient!
                    val tempFile =
                        File.createTempFile("camera_capture_photo", ".jpg", context.cacheDir)
                    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                    controller.takePicture(
                        outputFileOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val processingTime = System.currentTimeMillis() - startTime
                                Log.d(TAG, "Image saved directly to file in ${processingTime}ms")

                                val result = JSObject().apply {
                                    val capacitorFilePath = FileUtils.getPortablePath(
                                        context,
                                        pluginDelegate.bridge.localUrl,
                                        Uri.fromFile(tempFile)
                                    )

                                    put("webPath", capacitorFilePath)
                                }
                                callback(result, null)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Error saving image to file", exception)
                                callback(null, exception)
                            }
                        }
                    )
                } else {
                    // Base64 capture using ImageProxy
                    controller.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                Log.d(
                                    TAG,
                                    "Image captured successfully in ${System.currentTimeMillis() - startTime}ms"
                                )
                                handleCaptureSuccess(image, quality, imageRotationDegrees, callback)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Error capturing image", exception)
                                callback(null, exception)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up image capture", e)
                callback(null, e)
            }
        }
    }

    /**
     * Handles the successful capture of an image for base64 conversion
     */
    fun handleCaptureSuccess(
        image: ImageProxy,
        quality: Int,
        rotationDegrees: Int,
        callback: (JSObject?, Exception?) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        try {
            val base64String = imageProxyToBase64(image, quality, rotationDegrees)
            val result = JSObject().apply {
                put("photo", base64String)
            }
            Log.d(TAG, "Image processed to Base64 in ${System.currentTimeMillis() - startTime}ms")
            callback(result, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing captured image", e)
            callback(null, e)
        } finally {
            image.close()
        }
    }

    /**
     * Capture a frame directly from the preview without using the full photo pipeline which is
     * faster but has lower quality.
     */
    fun captureSampleFromPreview(
        quality: Int,
        saveToFile: Boolean = false,
        callback: (JSObject?, Exception?) -> Unit
    ) {
        val previewView =
            this.previewView
                ?: run {
                    callback(null, CameraError.PreviewNotInitialized())
                    return
                }

        mainHandler.post {
            try {
                val bitmap =
                    previewView.bitmap
                        ?: run {
                            callback(null, Exception("Preview bitmap not available"))
                            return@post
                        }

                val result = JSObject()

                if (saveToFile) {
                    val tempFile =
                        File.createTempFile("camera_capture_sample", ".jpg", context.cacheDir)

                    FileOutputStream(tempFile).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    }

                    val capacitorFilePath = FileUtils.getPortablePath(
                        context,
                        pluginDelegate.bridge.localUrl,
                        Uri.fromFile(tempFile)
                    )

                    result.put("webPath", capacitorFilePath)
                } else {
                    // Convert bitmap to Base64
                    val outputStream = ByteArrayOutputStream()
                    outputStream.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                        val byteArray = stream.toByteArray()
                        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                        result.put("photo", base64String)
                    }
                }

                callback(result, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing preview frame", e)
                callback(null, e)
            }
        }
    }

    /** Flip between front and back cameras */
    fun flipCamera(callback: (Exception?) -> Unit) {
        currentCameraSelector = when (currentCameraSelector) {
            CameraSelector.DEFAULT_FRONT_CAMERA -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val controller = cameraController
            ?: run {
                callback(CameraError.CameraNotInitialized())
                return
            }

        mainHandler.post {
            controller.cameraSelector = currentCameraSelector
            callback(null)
        }
    }

    /** Get the min, max, and current zoom values */
    fun getSupportedZoomFactors(callback: (ZoomFactors) -> Unit) {
        mainHandler.post { callback(getZoomFactorsInternal()) }
    }

    /** Set the zoom factor for the camera */
    fun setZoomFactor(zoomFactor: Float, callback: (((Exception?) -> Unit)?) = null) {
        mainHandler.post {
            val cameraControl = cameraController?.cameraControl
                ?: run {
                    callback?.invoke(CameraError.CameraNotInitialized())
                    return@post
                }

            val availableZoomFactors = getZoomFactorsInternal()

            if (zoomFactor !in availableZoomFactors.min..availableZoomFactors.max) {
                callback?.invoke(CameraError.ZoomFactorOutOfRange())
                return@post
            }

            Log.d(TAG, "Setting zoom factor to $zoomFactor")
            val zoomFuture = cameraControl.setZoomRatio(zoomFactor)

            zoomFuture.addListener(
                {
                    try {
                        zoomFuture.get()
                        Log.d(TAG, "Zoom factor set successfully to $zoomFactor")
                        callback?.invoke(null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set zoom factor", e)
                        callback?.invoke(Exception(e.message))
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    /** Get the current flash mode */
    fun getFlashMode(): String {
        return when (currentFlashMode) {
            ImageCapture.FLASH_MODE_ON -> "on"
            ImageCapture.FLASH_MODE_AUTO -> "auto"
            else -> "off"
        }
    }

    /** Get supported flash modes */
    fun getSupportedFlashModes(callback: (supportedFlashModes: List<String>) -> Unit) {
        mainHandler.post {
            val cameraInfo = cameraController?.cameraInfo
                ?: run {
                    callback(listOf("off"))
                    return@post
                }

            callback(
                if (cameraInfo.hasFlashUnit()) {
                    listOf("off", "on", "auto")
                } else {
                    listOf("off")
                }
            )
        }
    }

    /** Set the flash mode */
    fun setFlashMode(mode: String) {
        val controller =
            this.cameraController
                ?: run { throw Exception("Camera controller not initialized") }

        currentFlashMode =
            when (mode) {
                "on" -> ImageCapture.FLASH_MODE_ON
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

        mainHandler.post { controller.imageCaptureFlashMode = currentFlashMode }
    }

    /** Check if torch is available */
    fun isTorchAvailable(callback: (Boolean) -> Unit) {
        mainHandler.post {
            val cameraInfo = cameraController?.cameraInfo
                ?: run {
                    callback(false)
                    return@post
                }

            callback(cameraInfo.hasFlashUnit())
        }
    }

    /** Get the current torch mode */
    fun getTorchMode(): Boolean {
        return isTorchEnabled
    }

    /** Set the torch mode */
    fun setTorchMode(enabled: Boolean, callback: ((Exception?) -> Unit)? = null) {
        mainHandler.post {
            try {
                val controller = cameraController
                    ?: run {
                        callback?.invoke(CameraError.CameraNotInitialized())
                        return@post
                    }

                val cameraInfo = controller.cameraInfo
                if (cameraInfo?.hasFlashUnit() != true) {
                    callback?.invoke(CameraError.TorchUnavailable())
                    return@post
                }

                controller.cameraControl?.enableTorch(enabled)
                isTorchEnabled = enabled
                callback?.invoke(null)
            } catch (e: Exception) {
                callback?.invoke(e)
            }
        }
    }

    /** Get a list of available camera devices */
    fun getAvailableDevices(): List<CameraDevice> {
        try {
            val cameraManager =
                context.getSystemService(CAMERA_SERVICE) as? CameraManager ?: return emptyList()

            return cameraManager.cameraIdList.mapNotNull { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing =
                    characteristics.get(CameraCharacteristics.LENS_FACING)
                        ?: return@mapNotNull null

                val position =
                    when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "external"
                    }

                CameraDevice(id = cameraId, name = cameraId, position = position)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera devices", e)
            return emptyList()
        }
    }

    /** Clean up resources when the plugin is being destroyed */
    fun cleanup() {
        mainHandler.post {
            try {
                // Stop camera session
                cameraController?.unbind()
                cameraController = null

                // Remove preview view
                previewView?.let { view ->
                    (webView.parent as? ViewGroup)?.removeView(view)
                    previewView = null
                }

                // Reset WebView properties
                webView.setLayerType(WebView.LAYER_TYPE_NONE, null)
                webView.setBackgroundColor(android.graphics.Color.WHITE)

                // Clear references
                lifecycleOwner = null

                // Shutdown executor
                if (!cameraExecutor.isShutdown) {
                    cameraExecutor.shutdown()
                }

                Log.d(TAG, "Camera resources cleaned up successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    private fun setupPreviewView(context: Context) {
        // Make WebView transparent
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        previewView =
            PreviewView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

        (webView.parent as? ViewGroup)?.addView(previewView, 0)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun initializeCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        config: CameraSessionConfiguration,
    ) {
        // Setup preview view
        setupPreviewView(context)

        currentCameraSelector = if (config.position == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (config.deviceId != null) {
            // Prefer specific device id over position
            currentCameraSelector = CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        val cameraId = Camera2CameraInfo.from(info).cameraId
                        cameraId == config.deviceId
                    }
                }
                .build()
        }

        // Initialize camera controller
        val controller =
            LifecycleCameraController(context).apply {
                cameraSelector = currentCameraSelector
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                imageCaptureResolutionSelector =
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        )
                        .build()
            }

        cameraController = controller
        previewView?.controller = controller

        // Setup barcode scanning if needed
        if (config.enableBarcodeDetection) {
            setupBarcodeScanner(controller)
        }

        // Bind to lifecycle
        controller.bindToLifecycle(lifecycleOwner)

        // Set initial zoom factor
        this.setZoomFactor(config.zoomFactor, null)
    }

    private fun setupBarcodeScanner(controller: LifecycleCameraController) {
        val previewView = this.previewView ?: return

        val options =
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()

        val barcodeScanner = BarcodeScanning.getClient(options)
        val mainExecutor = ContextCompat.getMainExecutor(previewView.context)

        // Calculate a possible top offset of the webView which is not applied to the previewView
        // and might break the positioning of the bounding box of the barcode in relation to the
        // webView. This is due to capacitors required hack around the edge-to-edge behavior of web
        // views on android
        val topOffset = calculateTopOffset(webView)

        controller.setImageAnalysisAnalyzer(
            mainExecutor,
            MlKitAnalyzer(
                listOf(barcodeScanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExecutor
            ) { result: MlKitAnalyzer.Result? ->
                processBarcodeResults(result, barcodeScanner, previewView, topOffset)
            }
        )
    }

    private fun processBarcodeResults(
        result: MlKitAnalyzer.Result?,
        barcodeScanner: BarcodeScanner,
        previewView: PreviewView,
        topOffset: Int
    ) {
        val now = System.currentTimeMillis()
        if (now - lastBarcodeDetectionTime < BARCODE_DETECTION_THROTTLE_MS) {
            return // Skip this frame
        }

        val barcodes = result?.getValue(barcodeScanner) ?: return
        if (barcodes.isEmpty()) return

        val barcode = barcodes.firstOrNull() ?: return

        // Adjust bounding box to webView coordinates
        val webBoundingRect =
            boundingBoxToWebBoundingRect(previewView, barcode.boundingBox, topOffset)

        val barcodeResult =
            BarcodeDetectionResult(
                value = barcode.rawValue ?: "",
                displayValue = barcode.displayValue ?: "",
                type = getBarcodeFormatString(barcode.format),
                boundingRect = webBoundingRect
            )

        notifyBarcodeDetected(barcodeResult)
        lastBarcodeDetectionTime = now
    }

    private fun notifyBarcodeDetected(result: BarcodeDetectionResult) {
        pluginDelegate.let { plugin ->
            if (plugin is CameraViewPlugin) {
                plugin.notifyBarcodeDetected(result)
            }
        }
    }

    /** Get the current zoom factors */
    private fun getZoomFactorsInternal(): ZoomFactors {
        cameraController?.let { controller ->
            val zoomState = controller.zoomState
            val zoomFactors =
                ZoomFactors(
                    min = zoomState.value?.minZoomRatio ?: 1.0f,
                    max = zoomState.value?.maxZoomRatio ?: 1.0f,
                    current = zoomState.value?.zoomRatio ?: 1.0f
                )

            return zoomFactors
        }

        return ZoomFactors(1.0f, 1.0f, 1.0f)
    }

    companion object {
        private const val TAG = "CameraView"
    }
}
