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
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.CameraController
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
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
import com.michaelwolz.capacitorcameraview.model.CameraResult
import com.michaelwolz.capacitorcameraview.model.CameraSessionConfiguration
import com.michaelwolz.capacitorcameraview.model.ZoomFactors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Throttle time for barcode detection in milliseconds. */
const val BARCODE_DETECTION_THROTTLE_MS = 100L

class CameraView(plugin: Plugin) {
    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Thread-safe camera controller reference
    private val cameraControllerRef = AtomicReference<LifecycleCameraController?>(null)

    // Camera components (using atomic reference for thread safety)
    private var cameraController: LifecycleCameraController?
        get() = cameraControllerRef.get()
        set(value) { cameraControllerRef.set(value) }

    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var previewView: PreviewView? = null

    // Camera state
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF

    // Active video recording
    private var activeRecording: Recording? = null

    /**
     * Holds the pending stop-recording continuation result handler.
     * Needed because CameraX delivers the final recording outcome asynchronously via Finalize.
     */
    private var pendingStopCallback: ((CameraResult<JSObject>) -> Unit)? = null

    // Track the output file for the current recording
    private var currentRecordingFile: File? = null

    // Plugin context
    private var lifecycleOwner: LifecycleOwner? = null
    private var pluginDelegate: Plugin = plugin
    private var webView: WebView = plugin.bridge.webView
    private var context: Context = webView.context

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Thread-safe barcode throttle timestamp
    private val lastBarcodeDetectionTime = AtomicLong(0L)

    // Flow for reactive barcode events
    private val _barcodeEvents = MutableSharedFlow<BarcodeDetectionResult>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val barcodeEvents: SharedFlow<BarcodeDetectionResult> = _barcodeEvents.asSharedFlow()

    /** Starts a camera session with the provided configuration. */
    suspend fun startSessionAsync(config: CameraSessionConfiguration): CameraResult<Unit> =
        withContext(Dispatchers.Main) {
            val lifecycleOwner = context as? LifecycleOwner
                ?: return@withContext CameraResult.Error(CameraError.LifecycleOwnerMissing())

            this@CameraView.lifecycleOwner = lifecycleOwner

            try {
                initializeCamera(context, lifecycleOwner, config)
                CameraResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error in camera setup", e)
                CameraResult.Error(e)
            }
        }

    /** Starts a camera session with the provided configuration (callback version for backward compatibility). */
    fun startSession(config: CameraSessionConfiguration, callback: (Exception?) -> Unit) {
        scope.launch {
            startSessionAsync(config).fold(
                onSuccess = { callback(null) },
                onError = { callback(it) }
            )
        }
    }

    /** Stop the camera session and release resources. */
    suspend fun stopSessionAsync(): CameraResult<Unit> = withContext(Dispatchers.Main) {
        try {
            // Stop any active recording before unbinding
            activeRecording?.stop()
            activeRecording = null
            pendingStopCallback?.invoke(
                CameraResult.Error(Exception("Recording was interrupted because the camera session stopped"))
            )
            pendingStopCallback = null
            currentRecordingFile = null

            cameraController?.unbind()

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
            CameraResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera session", e)
            CameraResult.Error(e)
        }
    }

    /** Stop the camera session and release resources (callback version for backward compatibility). */
    fun stopSession(callback: ((Exception?) -> Unit)? = null) {
        scope.launch {
            stopSessionAsync().fold(
                onSuccess = { callback?.invoke(null) },
                onError = { callback?.invoke(it) }
            )
        }
    }

    /** Checks if the camera session is running */
    fun isRunning(): Boolean {
        return cameraController != null
    }

    /** Capture a photo with the current camera configuration. */
    suspend fun capturePhotoAsync(
        quality: Int,
        saveToFile: Boolean = false
    ): CameraResult<JSObject> = suspendCancellableCoroutine { continuation ->
        val startTime = System.currentTimeMillis()

        val controller = cameraController
        if (controller == null) {
            continuation.resume(CameraResult.Error(CameraError.CameraNotInitialized()))
            return@suspendCancellableCoroutine
        }

        val preview = previewView
        if (preview == null) {
            continuation.resume(CameraResult.Error(CameraError.PreviewNotInitialized()))
            return@suspendCancellableCoroutine
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
                                continuation.resume(CameraResult.Success(result))
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Error saving image to file", exception)
                                continuation.resume(CameraResult.Error(exception))
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
                                try {
                                    val base64String = imageProxyToBase64(image, quality, imageRotationDegrees)
                                    val result = JSObject().apply {
                                        put("photo", base64String)
                                    }
                                    Log.d(TAG, "Image processed to Base64 in ${System.currentTimeMillis() - startTime}ms")
                                    continuation.resume(CameraResult.Success(result))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing captured image", e)
                                    continuation.resume(CameraResult.Error(e))
                                } finally {
                                    image.close()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Error capturing image", exception)
                                continuation.resume(CameraResult.Error(exception))
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up image capture", e)
                continuation.resume(CameraResult.Error(e))
            }
        }
    }

    /** Capture a photo with the current camera configuration (callback version for backward compatibility). */
    fun capturePhoto(
        quality: Int,
        saveToFile: Boolean = false,
        callback: (JSObject?, Exception?) -> Unit
    ) {
        scope.launch {
            capturePhotoAsync(quality, saveToFile).fold(
                onSuccess = { callback(it, null) },
                onError = { callback(null, it) }
            )
        }
    }

    /**
     * Capture a frame directly from the preview without using the full photo pipeline.
     * Faster but has lower quality than full photo capture.
     */
    suspend fun captureSampleFromPreviewAsync(
        quality: Int,
        saveToFile: Boolean = false
    ): CameraResult<JSObject> = withContext(Dispatchers.Main) {
        val preview = previewView
            ?: return@withContext CameraResult.Error(CameraError.PreviewNotInitialized())

        try {
            val bitmap = preview.bitmap
                ?: return@withContext CameraResult.Error(Exception("Preview bitmap not available"))

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
                // Convert bitmap to Base64 using pooled stream
                val base64String = bitmapToBase64(bitmap, quality)
                result.put("photo", base64String)
            }

            CameraResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing preview frame", e)
            CameraResult.Error(e)
        }
    }

    /**
     * Capture a frame directly from the preview without using the full photo pipeline (callback version).
     * Faster but has lower quality than full photo capture.
     */
    fun captureSampleFromPreview(
        quality: Int,
        saveToFile: Boolean = false,
        callback: (JSObject?, Exception?) -> Unit
    ) {
        scope.launch {
            captureSampleFromPreviewAsync(quality, saveToFile).fold(
                onSuccess = { callback(it, null) },
                onError = { callback(null, it) }
            )
        }
    }

    /**
     * Starts video recording to a temporary file.
     */
    suspend fun startRecordingAsync(
        enableAudio: Boolean,
    ): CameraResult<Unit> = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            val controller = cameraController
            if (controller == null) {
                continuation.resume(CameraResult.Error(CameraError.CameraNotInitialized()))
                return@post
            }

            if (activeRecording != null) {
                continuation.resume(CameraResult.Error(Exception("Recording is already in progress")))
                return@post
            }

            try {
                // Enable VIDEO_CAPTURE use case alongside IMAGE_CAPTURE
                controller.setEnabledUseCases(
                    CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE
                )

                val tempFile = File.createTempFile(
                    "camera_recording_",
                    ".mp4",
                    context.cacheDir
                )
                currentRecordingFile = tempFile

                val outputOptions = FileOutputOptions.Builder(tempFile).build()
                val audioConfig = if (enableAudio) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED

                var startResumed = false

                activeRecording = controller.startRecording(
                    outputOptions,
                    audioConfig,
                    cameraExecutor
                ) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "Video recording started")
                            if (!startResumed && continuation.isActive) {
                                startResumed = true
                                continuation.resume(CameraResult.Success(Unit))
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            // If recording finalized before Start was emitted, resume the
                            // startRecording continuation with an error
                            if (!startResumed && continuation.isActive) {
                                startResumed = true
                                continuation.resume(CameraResult.Error(
                                    Exception("Recording failed to start: error code ${event.error}")
                                ))
                            }

                            mainHandler.post {
                                // CameraX requires use case changes on the main thread.
                                cameraController?.setEnabledUseCases(CameraController.IMAGE_CAPTURE)

                                val callback = pendingStopCallback
                                pendingStopCallback = null
                                // Always clean up recording state
                                activeRecording = null

                                if (event.hasError()) {
                                    Log.e(TAG, "Recording error: ${event.error}")
                                    val file = currentRecordingFile
                                    currentRecordingFile = null
                                    callback?.invoke(CameraResult.Error(Exception("Recording failed with error code: ${event.error}")))
                                } else {
                                    val file = currentRecordingFile
                                    currentRecordingFile = null
                                    if (file != null) {
                                        val capacitorFilePath = FileUtils.getPortablePath(
                                            context,
                                            pluginDelegate.bridge.localUrl,
                                            Uri.fromFile(file)
                                        )
                                        val result = JSObject().apply {
                                            put("webPath", capacitorFilePath)
                                        }
                                        callback?.invoke(CameraResult.Success(result))
                                    } else {
                                        callback?.invoke(CameraResult.Error(Exception("Recording file not found")))
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                // Restore normal use cases on error
                cameraController?.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                continuation.resume(CameraResult.Error(e))
            }
        }
    }

    /**
     * Stops the current video recording and returns the file path.
     */
    suspend fun stopRecordingAsync(): CameraResult<JSObject> = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            val recording = activeRecording
            if (recording == null) {
                continuation.resume(CameraResult.Error(Exception("No recording is in progress")))
                return@post
            }

            pendingStopCallback = { result ->
                continuation.resume(result)
            }

            activeRecording = null
            recording.stop()
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
    fun getTorchMode(callback: (Boolean) -> Unit) {
        mainHandler.post {
            val cameraInfo = cameraController?.cameraInfo
                ?: run {
                    callback(false)
                    return@post
                }

            callback(cameraInfo.torchState.value == TorchState.ON)
        }
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
        // Cancel all coroutines first
        scope.cancel()

        mainHandler.post {
            try {
                // Stop any active recording before cleanup
                activeRecording?.stop()
                activeRecording = null
                pendingStopCallback = null
                currentRecordingFile = null

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

    /** Converts a bitmap to Base64 with memory-efficient pooled ByteArrayOutputStream. */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream(256 * 1024) // 256KB initial capacity
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
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
            setupBarcodeScanner(controller, config.barcodeTypes)
        }

        // Bind to lifecycle
        controller.bindToLifecycle(lifecycleOwner)

        // Set initial zoom factor
        this.setZoomFactor(config.zoomFactor, null)
    }

    /**
     * Sets up the barcode scanner with the specified formats.
     *
     * @param controller The camera controller to attach the scanner to.
     * @param barcodeTypes Optional list of specific barcode format codes to detect.
     *                     If null, all supported formats are detected (backwards compatible).
     */
    private fun setupBarcodeScanner(
        controller: LifecycleCameraController,
        barcodeTypes: List<Int>? = null
    ) {
        val previewView = this.previewView ?: return

        // Build scanner options with specified formats or all formats
        val options = if (barcodeTypes != null && barcodeTypes.isNotEmpty()) {
            // Use specific formats - setBarcodeFormats takes first format + vararg rest
            val firstFormat = barcodeTypes.first()
            val restFormats = barcodeTypes.drop(1).toIntArray()
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(firstFormat, *restFormats)
                .build()
        } else {
            // Default to all formats for backwards compatibility
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        }

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
        val lastTime = lastBarcodeDetectionTime.get()

        // Thread-safe throttle check using atomic compare-and-set
        if (now - lastTime < BARCODE_DETECTION_THROTTLE_MS) {
            return // Skip this frame
        }

        // Atomically update the timestamp - if another thread beat us, skip
        if (!lastBarcodeDetectionTime.compareAndSet(lastTime, now)) {
            return
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

        // Emit to Flow for reactive subscribers
        scope.launch {
            _barcodeEvents.emit(barcodeResult)
        }

        // Also notify via callback for backward compatibility
        notifyBarcodeDetected(barcodeResult)
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
