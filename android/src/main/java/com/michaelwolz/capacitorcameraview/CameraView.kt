package com.michaelwolz.capacitorcameraview

import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraView {
    // Camera components
    private var cameraController: LifecycleCameraController? = null
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var previewView: PreviewView? = null

    // Camera state
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var enableBarcodeDetection = false
    private var zoomFactor = 1.0f

    // Camera use cases
    private var imageCapture: ImageCapture? = null

    // Plugin context
    private var lifecycleOwner: LifecycleOwner? = null
    private var pluginDelegate: Plugin? = null
    private var webView: WebView? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Starts a camera session with the provided configuration. */
    fun startSession(
        config: CameraSessionConfiguration,
        plugin: Plugin,
        callback: (Exception?) -> Unit
    ) {
        val webView = plugin.bridge.webView
        val context = webView.context
        val lifecycleOwner = context as? LifecycleOwner ?: run {
            callback(Exception("WebView context must be a LifecycleOwner"))
            return
        }

        // Store references for later use
        this.webView = webView
        this.lifecycleOwner = lifecycleOwner
        this.pluginDelegate = plugin

        // Apply base configuration
        this.enableBarcodeDetection = config.enableBarcodeDetection
        this.currentCameraSelector = when (config.position) {
            "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        this.zoomFactor = config.zoomFactor.toFloat()

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
                imageCapture = null

                previewView?.let { view ->
                    try {
                        (webView?.parent as? ViewGroup)?.removeView(view)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing preview view", e)
                    } finally {
                        previewView = null
                    }
                }

                webView?.setLayerType(WebView.LAYER_TYPE_NONE, null)
                webView?.setBackgroundColor(android.graphics.Color.WHITE)

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
    fun capturePhoto(quality: Int?, callback: (String?, Exception?) -> Unit) {
        val controller = this.cameraController ?: run {
            callback(null, Exception("Camera controller not initialized"))
            return
        }

        mainHandler.post {
            try {
                controller.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val base64String = imageProxyToBase64(image, quality)
                                callback(base64String, null)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing captured image", e)
                                callback(null, e)
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Error capturing image", exception)
                            callback(null, exception)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up image capture", e)
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

        val controller = this.cameraController ?: run {
            callback(Exception("Camera controller not initialized"))
            return
        }

        mainHandler.post {
            controller.cameraSelector = currentCameraSelector
        }
    }

    /** Get the min, max, and current zoom values */
    fun getSupportedZoomFactors(callback: (ZoomFactors) -> Unit) {
        mainHandler.post {
            callback(getZoomFactorsInternal())
        }
    }

    /** Set the zoom factor for the camera */
    fun setZoomFactor(factor: Double) {
        mainHandler.post {
            val cameraControl = cameraController?.cameraControl ?: run {
                throw Exception("Camera controller not initialized")
            }

            val availableZoomFactors = getZoomFactorsInternal()
            val zoomFactor =
                factor.toFloat().coerceIn(availableZoomFactors.min, availableZoomFactors.max)

            cameraControl.setZoomRatio(zoomFactor)
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
            val cameraInfo = cameraController?.cameraInfo ?: run {
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
        val controller = this.cameraController ?: run {
            throw Exception("Camera controller not initialized")
        }

        currentFlashMode =
            when (mode) {
                "on" -> ImageCapture.FLASH_MODE_ON
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

        mainHandler.post {
            controller.imageCaptureFlashMode = currentFlashMode
        }
    }

    /** Get a list of available camera devices */
    fun getAvailableDevices(): List<CameraDevice> {
        val context = webView?.context ?: return emptyList()

        try {
            val cameraManager = context.getSystemService(CAMERA_SERVICE) as? CameraManager
                ?: return emptyList()

            return cameraManager.cameraIdList.mapNotNull { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing =
                    characteristics.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null

                val position = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }

                CameraDevice(
                    id = cameraId,
                    name = "Camera $cameraId ($position)",
                    position = position
                )
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
                    (webView?.parent as? ViewGroup)?.removeView(view)
                    previewView = null
                }

                // Reset WebView properties
                webView?.setLayerType(WebView.LAYER_TYPE_NONE, null)
                webView?.setBackgroundColor(android.graphics.Color.WHITE)

                // Clear references
                webView = null
                lifecycleOwner = null
                imageCapture = null

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
        webView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView?.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        previewView =
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

        (webView?.parent as? ViewGroup)?.addView(previewView, 0)
    }

    private fun initializeCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        config: CameraSessionConfiguration
    ) {
        // Setup preview view
        setupPreviewView(context)

        // Initialize camera controller
        val controller = LifecycleCameraController(context).apply {
            cameraSelector = if (config.position == "front") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Image capture configuration
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

            // Set the initial zoom
            if (config.zoomFactor != 1.0) {
                cameraControl?.setZoomRatio(config.zoomFactor.toFloat())
            }
        }

        cameraController = controller
        previewView?.controller = controller

        // Setup barcode scanning if needed
        if (config.enableBarcodeDetection) {
            setupBarcodeScanner(controller)
        }

        // Bind to lifecycle
        controller.bindToLifecycle(lifecycleOwner)
    }

    private fun setupBarcodeScanner(controller: LifecycleCameraController) {
        val previewView = this.previewView ?: return
        val context = webView?.context ?: return

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()

        val barcodeScanner = BarcodeScanning.getClient(options)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        controller.setImageAnalysisAnalyzer(
            mainExecutor,
            MlKitAnalyzer(
                listOf(barcodeScanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExecutor
            ) { result: MlKitAnalyzer.Result? ->
                processBarcodeResults(result, barcodeScanner, previewView)
            }
        )
    }

    private fun processBarcodeResults(
        result: MlKitAnalyzer.Result?,
        barcodeScanner: BarcodeScanner,
        previewView: PreviewView
    ) {
        val barcodes = result?.getValue(barcodeScanner) ?: return
        if (barcodes.isEmpty()) return

        val barcode = barcodes.firstOrNull() ?: return

        val webBoundingRect = boundingBoxToWebBoundingRect(
            previewView,
            barcode.boundingBox
        )

        val barcodeResult = BarcodeDetectionResult(
            value = barcode.rawValue ?: "",
            displayValue = barcode.displayValue ?: "",
            type = getBarcodeFormatString(barcode.format),
            boundingRect = webBoundingRect
        )

        notifyBarcodeDetected(barcodeResult)
    }

    /** Converts an ImageProxy to a Base64 encoded string */
    private fun imageProxyToBase64(image: ImageProxy, quality: Int?): String {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Apply correct rotation
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality ?: 90, outputStream)
            val byteArray = outputStream.toByteArray()

            return Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun notifyBarcodeDetected(result: BarcodeDetectionResult) {
        pluginDelegate?.let { plugin ->
            if (plugin is CameraViewPlugin) {
                plugin.notifyBarcodeDetected(result)
            }
        }
    }

    /** Get the current zoom factors */
    private fun getZoomFactorsInternal(): ZoomFactors {
        cameraController?.let { controller ->
            val cameraInfo = controller.cameraInfo
            val zoomFactors = ZoomFactors(
                min = 1.0f,
                max = cameraInfo?.zoomState?.value?.maxZoomRatio ?: 5.0f,
                current = cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
            )
            return zoomFactors
        }

        return ZoomFactors(1.0f, 5.0f, 1.0f)
    }

    companion object {
        private const val TAG = "CameraView"
    }
}
