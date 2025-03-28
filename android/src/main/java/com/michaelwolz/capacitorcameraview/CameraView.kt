package com.michaelwolz.capacitorcameraview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.getcapacitor.Plugin
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraView {
    // Camera components
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null

    // Camera state
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var isSessionRunning = AtomicBoolean(false)
    private var enableBarcodeDetection = false

    // Camera use cases
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Plugin context
    private var lifecycleOwner: LifecycleOwner? = null
    private var pluginDelegate: Plugin? = null
    private var webView: WebView? = null

    private var orientationEventListener: OrientationEventListener? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Starts a camera session with the provided configuration. */
    fun startSession(
        config: CameraSessionConfiguration,
        plugin: Plugin,
        callback: (Exception?) -> Unit
    ) {
        if (isSessionRunning.get()) {
            callback(null)
            return
        }

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
        // TODO: Set initial zoom level if provided in config

        // Create and configure UI on main thread
        mainHandler.post {
            try {
                setupPreviewView(context, webView)
                setupOrientationListener(context)
                startCameraInternal(context, lifecycleOwner, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error in camera setup", e)
                callback(e)
            }
        }
    }

    /** Stop the camera session and release resources */
    fun stopSession(callback: ((Exception?) -> Unit)? = null) {
        if (!isSessionRunning.getAndSet(false)) {
            callback?.invoke(null)
            return
        }

        mainHandler.post {
            try {
                camera = null
                cameraProvider?.unbindAll()
                cameraProvider = null
                imageCapture = null
                imageAnalysis = null

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

                // Disable orientation listener
                orientationEventListener?.disable()
                orientationEventListener = null

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
        return isSessionRunning.get()
    }

    /** Capture a photo with the current camera configuration */
    fun capturePhoto(quality: Int, callback: (String?, Exception?) -> Unit) {
        if (!isSessionRunning.get()) {
            callback(null, Exception("Camera session not running"))
            return
        }

        val imageCapture = imageCapture ?: run {
            callback(null, Exception("Image capture not initialized"))
            return
        }

        try {
            imageCapture.takePicture(
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

    /** Flip between front and back cameras */
    fun flipCamera(callback: (Exception?) -> Unit) {
        currentCameraSelector = when (currentCameraSelector) {
            CameraSelector.DEFAULT_FRONT_CAMERA -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val lifecycleOwner = this.lifecycleOwner
        val cameraProvider = this.cameraProvider

        if (!isSessionRunning.get() || lifecycleOwner == null || cameraProvider == null) {
            callback(Exception("Camera not properly initialized"))
            return
        }

        mainHandler.post {
            bindCameraUseCases(lifecycleOwner, cameraProvider, callback)
        }
    }

    /** Get the min, max, and current zoom values */
    fun getSupportedZoomFactors(): ZoomFactors {
        val camera = camera ?: return ZoomFactors(1.0f, 1.0f, 1.0f)

        return ZoomFactors(
            min = 1.0f,
            max = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 5.0f,
            current = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
        )
    }

    /** Set the zoom factor for the camera */
    fun setZoomFactor(factor: Double) {
        val camera = camera ?: throw Exception("Camera not initialized")

        val zoomFactors = getSupportedZoomFactors()
        val zoomFactor = factor.toFloat().coerceIn(zoomFactors.min, zoomFactors.max)

        camera.cameraControl.setZoomRatio(zoomFactor)
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
    fun getSupportedFlashModes(): List<String> {
        val camera = camera ?: return listOf("off")

        return if (camera.cameraInfo.hasFlashUnit()) {
            listOf("off", "on", "auto")
        } else {
            listOf("off")
        }
    }

    /** Set the flash mode */
    fun setFlashMode(mode: String) {
        val imageCapture = imageCapture ?: throw Exception("Camera not initialized")

        currentFlashMode =
            when (mode) {
                "on" -> ImageCapture.FLASH_MODE_ON
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

        imageCapture.flashMode = currentFlashMode
    }

    /** Get a list of available camera devices */
    fun getAvailableDevices(): List<CameraDevice> {
        return emptyList()
    }

    /** Clean up resources when the plugin is being destroyed */
    fun cleanup() {
        // First stop any running session
        if (isSessionRunning.get()) {
            stopSession()
        }

        // Disable orientation listener if still active
        orientationEventListener?.disable()
        orientationEventListener = null

        // Release camera use cases
        imageAnalysis = null
        imageCapture = null

        // Shutdown the executor service
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }

        // Clear references
        this.webView = null
        this.lifecycleOwner = null
    }

    private fun setupPreviewView(context: Context, webView: WebView) {
        // Make WebView transparent
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        previewView =
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

        (webView.parent as? ViewGroup)?.addView(previewView, 0)
    }

    private fun startCameraInternal(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        callback: (Exception?) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    this.cameraProvider = cameraProvider
                    bindCameraUseCases(lifecycleOwner, cameraProvider, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera provider error", e)
                    callback(e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        callback: (Exception?) -> Unit
    ) {
        try {
            // Unbind any existing use cases first
            cameraProvider.unbindAll()

            // Configure the preview use case
            val preview = Preview.Builder().build().also {
                previewView?.let { view -> it.setSurfaceProvider(view.surfaceProvider) }
            }

            // Configure image capture
            imageCapture = ImageCapture.Builder()
                .setFlashMode(currentFlashMode)
                .build()

            // Build the use case list
            val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
            imageCapture?.let { useCases.add(it) }

            // Add barcode scanning analyzer if enabled
            if (enableBarcodeDetection) {
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { result ->
                            notifyBarcodeDetected(result)
                        })
                    }
                imageAnalysis?.let { useCases.add(it) }
            }

            // Bind all use cases to lifecycle
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                this.currentCameraSelector,
                *useCases.toTypedArray()
            )

            isSessionRunning.set(true)
            callback(null)
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            callback(e)
        }
    }

    /** Converts an ImageProxy to a Base64 encoded string */
    private fun imageProxyToBase64(image: ImageProxy, quality: Int): String {
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

        val outputStream = ByteArrayOutputStream()

        // Apply compression
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /** Setup orientation listener to update image capture rotation */
    private fun setupOrientationListener(context: Context) {
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                // Convert orientation to rotation degrees
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
            }
        }

        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        } else {
            Log.w(TAG, "Cannot detect orientation changes")
            orientationEventListener = null
        }
    }

    private fun notifyBarcodeDetected(result: BarcodeDetectionResult) {
        pluginDelegate?.let { plugin ->
            if (plugin is CameraViewPlugin) {
                plugin.notifyBarcodeDetected(result)
            }
        }
    }

    companion object {
        private const val TAG = "CameraView"
    }
}
