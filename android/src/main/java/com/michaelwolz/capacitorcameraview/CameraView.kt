package com.michaelwolz.capacitorcameraview

import android.Manifest
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.getcapacitor.FileUtils
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.michaelwolz.capacitorcameraview.model.BarcodeDetectionResult
import com.michaelwolz.capacitorcameraview.model.CameraDevice
import com.michaelwolz.capacitorcameraview.model.CameraResult
import com.michaelwolz.capacitorcameraview.model.CameraSessionConfiguration
import com.michaelwolz.capacitorcameraview.model.TorchModeState
import com.michaelwolz.capacitorcameraview.model.VideoRecordingQuality
import com.michaelwolz.capacitorcameraview.model.ZoomFactors
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan
import kotlin.math.roundToInt

/** Throttle time for barcode detection in milliseconds. */
const val BARCODE_DETECTION_THROTTLE_MS = 100L

/**
 * Suppression window in milliseconds during which a repeat of the same barcode
 * (identical value + type) is not re-emitted. A genuinely new code still emits
 * immediately. Kept consistent with the iOS and web implementations.
 */
const val BARCODE_SUPPRESSION_WINDOW_MS = 500L

/**
 * Once the per-key dedupe map grows past this size, expired entries are pruned
 * so a long session scanning many different codes stays bounded. Kept
 * consistent with the iOS and web implementations.
 */
const val BARCODE_DEDUPE_MAP_PRUNE_THRESHOLD = 64

/**
 * Age threshold, in milliseconds, past which a leftover `camera_capture_*`/
 * `camera_recording_*` file in the cache directory is considered stale and swept on the
 * next session start. Kept consistent with the iOS implementation's `TempFileManager`.
 */
const val STALE_TEMP_FILE_THRESHOLD_MS = 30 * 60 * 1000L

/**
 * Horizontal field-of-view threshold (degrees) at or above which a lens is classified as
 * `"ultraWide"` in [CameraView.classifyLensDeviceType]. Common ultra-wide modules sit around
 * 100-120°; standard wide modules are typically well under 94°.
 */
const val ULTRA_WIDE_FOV_THRESHOLD_DEGREES = 94.0

/**
 * Horizontal field-of-view threshold (degrees) at or below which a lens is classified as
 * `"telephoto"` in [CameraView.classifyLensDeviceType]. Common telephoto modules are well
 * under 60°; standard wide modules are typically well above it.
 */
const val TELEPHOTO_FOV_THRESHOLD_DEGREES = 60.0

class CameraView(plugin: Plugin) {
    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Thread-safe camera controller reference
    private val cameraControllerRef = AtomicReference<LifecycleCameraController?>(null)

    // Camera components (using atomic reference for thread safety)
    private var cameraController: LifecycleCameraController?
        get() = cameraControllerRef.get()
        set(value) {
            cameraControllerRef.set(value)
        }

    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var previewView: PreviewView? = null

    // The ML Kit barcode scanner currently attached to the image analysis
    // analyzer, if barcode detection is enabled. Tracked here so it can be
    // closed (and the analyzer cleared) on session stop/cleanup - the
    // MlKitAnalyzer that wraps it does not own or close it for us.
    private var barcodeScanner: BarcodeScanner? = null

    // Camera state
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF

    /**
     * Normalized (0.0-1.0) torch intensity last successfully applied via [setTorchMode],
     * reported back by [getTorchMode]. CameraX/Camera2 interop expose no getter for the
     * currently active `FLASH_STRENGTH_LEVEL` capture-request option, so the level this
     * plugin itself applied is tracked here instead. Reset to 0.0 whenever the torch is off.
     */
    private var currentTorchLevel: Float = 0.0f

    /**
     * The torch-strength Camera2 interop capture-request option currently applied on the
     * bound camera, or `null` when the torch is off or strength isn't controllable.
     *
     * Persisted so [clearJpegQualityCaptureOption] - which clears ALL interop options -
     * can re-apply it instead of silently resetting an active torch to default strength.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private var torchStrengthCaptureRequestOptions: CaptureRequestOptions? = null

    // Active video recording
    private var activeRecording: Recording? = null

    /**
     * Holds the pending stop-recording continuation result handler.
     * Needed because CameraX delivers the final recording outcome asynchronously via Finalize.
     */
    private var pendingStopCallback: ((CameraResult<JSObject>) -> Unit)? = null

    // Track the output file for the current recording
    private var currentRecordingFile: File? = null

    // Enabled CameraX use cases before video recording temporarily changes them.
    private var enabledUseCasesBeforeRecording: Int? = null

    // Plugin context
    private var lifecycleOwner: LifecycleOwner? = null
    private var pluginDelegate: Plugin = plugin
    private var webView: WebView = plugin.bridge.webView
    private var context: Context = webView.context

    // The WebView's appearance before the first camera session made it
    // transparent. Captured once and never overwritten, so teardown restores
    // the app's real appearance instead of hard-coding white/LAYER_TYPE_NONE.
    //
    // A ColorDrawable must be captured as its color int, not as a Drawable
    // reference: setBackgroundColor() mutates an existing ColorDrawable in
    // place, so a reference would alias the drawable we turn transparent.
    private var originalWebViewBackgroundColor: Int? = null
    private var originalWebViewBackground: Drawable? = null
    private var originalWebViewLayerType: Int? = null
    private var hasCapturedOriginalWebViewAppearance = false

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Thread-safe barcode throttle timestamp
    private val lastBarcodeDetectionTime = AtomicLong(0L)

    // Dedupe state: timestamps of recently emitted barcodes keyed by value +
    // type. A per-key map (rather than a single "last" slot) is required so
    // multiple codes in frame can't alternate and defeat the suppression
    // window. Accessed only on the main executor (MlKitAnalyzer callback) and
    // the main thread (session teardown), so no locking is needed.
    private val recentBarcodeEmitTimes = mutableMapOf<String, Long>()

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
            if (isRunning()) {
                return@withContext CameraResult.Error(CameraError.SessionAlreadyRunning())
            }

            val lifecycleOwner = context as? LifecycleOwner
                ?: return@withContext CameraResult.Error(CameraError.LifecycleOwnerMissing())

            this@CameraView.lifecycleOwner = lifecycleOwner

            // Sweep any stale capture/recording temp files left behind by a previous
            // session that crashed or was killed before it could clean up after itself.
            // Session start is the one guaranteed point where none of our own
            // captures/recordings can be in flight yet, so it's safe to delete anything
            // old here. Run off the main thread so this never delays session start.
            scope.launch(cameraExecutor.asCoroutineDispatcher()) {
                sweepStaleTempFiles()
            }

            try {
                initializeCamera(context, lifecycleOwner, config)
                CameraResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error in camera setup", e)
                // Tear down any partially initialized state so a failed start
                // doesn't leave the session guard tripped or a preview attached
                releaseSessionResources()
                CameraResult.Error(e)
            }
        }

    /** Stop the camera session and release resources. */
    suspend fun stopSessionAsync(): CameraResult<Unit> = withContext(Dispatchers.Main) {
        try {
            // Stop any active recording before unbinding
            activeRecording?.stop()
            activeRecording = null
            failPendingRecording("Recording was interrupted because the camera session stopped")

            releaseSessionResources()

            Log.d(TAG, "Camera session stopped successfully")
            CameraResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera session", e)
            CameraResult.Error(e)
        }
    }

    /**
     * Releases the camera controller and preview view and restores the WebView.
     *
     * Must be called on the main thread. Used both for a regular [stopSessionAsync]
     * and to tear down partially initialized state when [startSessionAsync] fails.
     */
    private fun releaseSessionResources() {
        closeBarcodeScanner()

        cameraController?.unbind()
        cameraController = null

        // Reset barcode dedupe state so a restarted session emits immediately
        recentBarcodeEmitTimes.clear()

        previewView?.let { view ->
            try {
                (webView.parent as? ViewGroup)?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing preview view", e)
            } finally {
                previewView = null
            }
        }

        restoreWebViewAppearance()
    }

    /**
     * Restores the WebView's background and layer type to what they were before
     * [setupPreviewView] first made it transparent. No-op if a session was never
     * started, since nothing was ever changed.
     */
    private fun restoreWebViewAppearance() {
        if (!hasCapturedOriginalWebViewAppearance) return

        webView.setLayerType(originalWebViewLayerType ?: WebView.LAYER_TYPE_NONE, null)

        val originalColor = originalWebViewBackgroundColor
        if (originalColor != null) {
            webView.setBackgroundColor(originalColor)
        } else {
            webView.background = originalWebViewBackground
        }
    }

    /**
     * Clears the image-analysis analyzer from the camera controller (if any) and closes
     * the ML Kit barcode scanner, releasing its native resources. The [MlKitAnalyzer]
     * wrapping the scanner does not own or close it, so this must be done explicitly
     * whenever a session with barcode detection enabled is torn down.
     */
    private fun closeBarcodeScanner() {
        if (barcodeScanner == null) return

        cameraController?.clearImageAnalysisAnalyzer()
        barcodeScanner?.close()
        barcodeScanner = null
    }

    /**
     * Fails any pending stop-recording callback with [message] and deletes the
     * partially-written recording file (if any), so an interrupted recording neither
     * leaves its caller awaiting forever nor leaks an orphaned temp file.
     *
     * Used both by [stopSessionAsync] (session stopped mid-recording) and [cleanup]
     * (plugin torn down mid-recording).
     */
    private fun failPendingRecording(message: String) {
        pendingStopCallback?.invoke(CameraResult.Error(Exception(message)))
        pendingStopCallback = null

        currentRecordingFile?.let { deleteFileQuietly(it) }
        currentRecordingFile = null
    }

    /** Deletes [file] if it exists, logging (but never throwing) on failure. */
    private fun deleteFileQuietly(file: File) {
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete temp file: ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting temp file: ${file.name}", e)
        }
    }

    /**
     * Deletes stale `camera_capture_*`/`camera_recording_*` files left in [Context.cacheDir]
     * by a previous session that crashed or was killed before it could clean up after
     * itself. Only files older than [STALE_TEMP_FILE_THRESHOLD_MS] are removed, so a file
     * from a capture/recording that is genuinely still in flight is never touched.
     *
     * Performs file I/O and must not be called on the main thread.
     */
    private fun sweepStaleTempFiles() {
        try {
            val cutoff = System.currentTimeMillis() - STALE_TEMP_FILE_THRESHOLD_MS
            context.cacheDir?.listFiles()?.forEach { file ->
                val isCameraTempFile = file.isFile &&
                    (file.name.startsWith("camera_capture_") || file.name.startsWith("camera_recording_"))
                if (isCameraTempFile && file.lastModified() < cutoff) {
                    deleteFileQuietly(file)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sweeping stale temp files", e)
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
            continuation.resumeIfActive(CameraResult.Error(CameraError.CameraNotInitialized()))
            return@suspendCancellableCoroutine
        }

        val preview = previewView
        if (preview == null) {
            continuation.resumeIfActive(CameraResult.Error(CameraError.PreviewNotInitialized()))
            return@suspendCancellableCoroutine
        }

        mainHandler.post {
            // Derive facing from the bound camera's lens facing rather than comparing the
            // selector against DEFAULT_FRONT_CAMERA, which is always false for a selector
            // built from a deviceId even when it resolves to the front camera.
            val cameraInfo = controller.cameraInfo
            val isFrontFacing = isLensFacingFront(cameraInfo?.lensFacing)
            val sensorRotationDegrees = cameraInfo?.sensorRotationDegrees ?: 0
            val displayRotation = preview.display?.rotation ?: Surface.ROTATION_0
            val imageRotationDegrees = calculateImageRotation(
                sensorRotationDegrees,
                displayRotation,
                isFrontFacing
            )

            try {
                if (saveToFile) {
                    // Direct file capture - much more efficient!
                    val tempFile =
                        File.createTempFile("camera_capture_photo", ".jpg", context.cacheDir)
                    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                    // CameraController has no JPEG-quality setter, so quality is applied via
                    // Camera2 interop on the capture request. Applying it is asynchronous, so
                    // the capture is chained on the returned future. If interop isn't usable,
                    // fall back to re-compressing the saved file.
                    val qualityOptionFuture = tryApplyJpegQualityCaptureOption(controller, quality)

                    if (qualityOptionFuture == null) {
                        // Interop unavailable - capture now, re-compress the file afterwards.
                        takePictureToFile(
                            controller,
                            outputFileOptions,
                            tempFile,
                            quality,
                            recompressAtQuality = true,
                            startTime,
                            continuation
                        )
                    } else {
                        qualityOptionFuture.addListener({
                            val interopApplied = try {
                                qualityOptionFuture.get()
                                true
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "JPEG quality capture option was not applied; " +
                                        "falling back to re-compression",
                                    e
                                )
                                false
                            }
                            takePictureToFile(
                                controller,
                                outputFileOptions,
                                tempFile,
                                quality,
                                recompressAtQuality = !interopApplied,
                                startTime,
                                continuation
                            )
                        }, ContextCompat.getMainExecutor(context))
                    }
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
                                    val base64String =
                                        imageProxyToBase64(image, quality, imageRotationDegrees)
                                    val result = JSObject().apply {
                                        put("photo", base64String)
                                    }
                                    Log.d(
                                        TAG,
                                        "Image processed to Base64 in ${System.currentTimeMillis() - startTime}ms"
                                    )
                                    continuation.resumeIfActive(CameraResult.Success(result))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing captured image", e)
                                    continuation.resumeIfActive(CameraResult.Error(e))
                                } finally {
                                    image.close()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Error capturing image", exception)
                                continuation.resumeIfActive(CameraResult.Error(exception))
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up image capture", e)
                continuation.resumeIfActive(CameraResult.Error(e))
            }
        }
    }

    /**
     * Attempts to apply the requested JPEG quality to the still-capture pipeline via the
     * Camera2 `JPEG_QUALITY` capture-request option. Returns the future that completes once
     * the option has been submitted to the capture session, or `null` if interop isn't
     * usable at all (e.g. the camera control has no Camera2 implementation).
     *
     * Options set this way are submitted with every capture request for this camera, so
     * callers must clear them again via [clearJpegQualityCaptureOption] once the capture
     * completes, and must await the returned future before triggering the capture.
     *
     * Uses `addCaptureRequestOptions()` rather than `setCaptureRequestOptions()` so an
     * active [torchStrengthCaptureRequestOptions] is preserved.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun tryApplyJpegQualityCaptureOption(
        controller: LifecycleCameraController,
        quality: Int
    ): ListenableFuture<Void>? {
        return try {
            val cameraControl = controller.cameraControl ?: return null
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, quality.coerceIn(1, 100).toByte())
                .build()
            Camera2CameraControl.from(cameraControl).addCaptureRequestOptions(options)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 interop unavailable for JPEG quality; falling back to re-compression", e)
            null
        }
    }

    /**
     * Clears any JPEG-quality Camera2 interop option previously set by
     * [tryApplyJpegQualityCaptureOption].
     *
     * `clearCaptureRequestOptions()` clears ALL interop options on this camera, including an
     * active [torchStrengthCaptureRequestOptions], so that one is re-applied right after.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun clearJpegQualityCaptureOption(controller: LifecycleCameraController) {
        try {
            val cameraControl = controller.cameraControl ?: return
            val camera2CameraControl = Camera2CameraControl.from(cameraControl)
            camera2CameraControl.clearCaptureRequestOptions()

            torchStrengthCaptureRequestOptions?.let { options ->
                camera2CameraControl.addCaptureRequestOptions(options)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear JPEG quality Camera2 interop option", e)
        }
    }

    /**
     * Takes a picture into [tempFile] and resumes [continuation] with the file paths.
     * Must be called on the main thread.
     *
     * If [recompressAtQuality] is true (the Camera2 interop JPEG-quality option could not
     * be applied), the saved file is re-compressed in place at [quality] before resuming,
     * so the requested quality is honored either way. Exactly one resume is guaranteed:
     * either via the capture callbacks or via the synchronous catch below (in which case
     * no callback will fire, since `takePicture` failed before being registered).
     */
    private fun takePictureToFile(
        controller: LifecycleCameraController,
        outputFileOptions: ImageCapture.OutputFileOptions,
        tempFile: File,
        quality: Int,
        recompressAtQuality: Boolean,
        startTime: Long,
        continuation: CancellableContinuation<CameraResult<JSObject>>
    ) {
        try {
            controller.takePicture(
                outputFileOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        clearJpegQualityCaptureOption(controller)
                        val processingTime = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Image saved directly to file in ${processingTime}ms")

                        try {
                            if (recompressAtQuality) {
                                recompressFileInPlace(tempFile, quality)
                            }

                            val result = JSObject().apply {
                                val capacitorFilePath = FileUtils.getPortablePath(
                                    context,
                                    pluginDelegate.bridge.localUrl,
                                    Uri.fromFile(tempFile)
                                )

                                put("path", Uri.fromFile(tempFile).toString())
                                put("webPath", capacitorFilePath)
                            }
                            continuation.resumeIfActive(CameraResult.Success(result))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error re-compressing captured file", e)
                            continuation.resumeIfActive(CameraResult.Error(e))
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        clearJpegQualityCaptureOption(controller)
                        Log.e(TAG, "Error saving image to file", exception)
                        continuation.resumeIfActive(CameraResult.Error(exception))
                    }
                }
            )
        } catch (e: Exception) {
            clearJpegQualityCaptureOption(controller)
            Log.e(TAG, "Error setting up image capture", e)
            continuation.resumeIfActive(CameraResult.Error(e))
        }
    }

    /**
     * Re-compresses a JPEG file in place at the given quality. Used as a fallback for the
     * file-based capture path when Camera2 interop isn't available to honor `quality` at
     * capture time.
     */
    private fun recompressFileInPlace(file: File, quality: Int) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Failed to decode captured file for re-compression")
        try {
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Capture a frame directly from the preview without using the full photo pipeline.
     * Faster but has lower quality than full photo capture.
     */
    suspend fun captureSampleFromPreviewAsync(
        quality: Int,
        saveToFile: Boolean = false
    ): CameraResult<JSObject> {
        val bitmapResult: CameraResult<Bitmap> = withContext(Dispatchers.Main) {
            val preview = previewView
                ?: return@withContext CameraResult.Error(CameraError.PreviewNotInitialized())
            preview.bitmap
                ?.let { CameraResult.Success(it) }
                ?: CameraResult.Error(Exception("Preview bitmap not available"))
        }

        val bitmap = when (bitmapResult) {
            is CameraResult.Error -> return bitmapResult
            is CameraResult.Success -> bitmapResult.value
        }

        // Compression, encoding and file IO are CPU/IO-bound and must not block the main thread.
        return withContext(cameraExecutor.asCoroutineDispatcher()) {
            try {
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

                    result.put("path", Uri.fromFile(tempFile).toString())
                    result.put("webPath", capacitorFilePath)
                } else {
                    // Convert bitmap to Base64 using the shared pooled streaming encoder
                    // (also used by imageProxyToBase64) rather than a separate,
                    // non-pooled duplicate.
                    val base64String = StreamingBase64Encoder.encodeToBase64(bitmap, quality)
                    result.put("photo", base64String)
                }

                CameraResult.Success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing preview frame", e)
                CameraResult.Error(e)
            }
        }
    }

    /**
     * Starts video recording to a temporary file.
     */
    suspend fun startRecordingAsync(
        enableAudio: Boolean,
        videoQuality: VideoRecordingQuality,
    ): CameraResult<Unit> = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            startRecordingOnMainThread(enableAudio, videoQuality, continuation)
        }
    }

    private fun startRecordingOnMainThread(
        enableAudio: Boolean,
        videoQuality: VideoRecordingQuality,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ) {
        val controller = validateRecordingPreconditions(continuation) ?: return

        try {
            controller.videoCaptureQualitySelector = videoQuality.toQualitySelector()

            // Enable VIDEO_CAPTURE use case alongside IMAGE_CAPTURE
            enabledUseCasesBeforeRecording = controller.currentEnabledUseCases()
            controller.setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE
            )

            val outputOptions = createRecordingOutputOptions()
            val audioConfig = resolveAudioConfig(enableAudio, continuation) ?: run {
                // Recording never actually started - the just-created output file is
                // empty and unused, so it must be removed here rather than left for
                // the next session's stale-file sweep to find.
                discardPendingRecordingFile()
                return
            }

            startCameraRecording(controller, outputOptions, audioConfig, continuation)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when starting recording. Missing permission?", e)
            restoreUseCasesAfterRecording()
            discardPendingRecordingFile()
            continuation.resumeIfActive(CameraResult.Error(e))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            restoreUseCasesAfterRecording()
            discardPendingRecordingFile()
            continuation.resumeIfActive(CameraResult.Error(e))
        }
    }

    private fun validateRecordingPreconditions(
        continuation: CancellableContinuation<CameraResult<Unit>>
    ): LifecycleCameraController? {
        val controller = cameraController
        if (controller == null) {
            continuation.resumeIfActive(CameraResult.Error(CameraError.CameraNotInitialized()))
            return null
        }

        if (activeRecording != null) {
            continuation.resumeIfActive(CameraResult.Error(CameraError.RecordingAlreadyInProgress()))
            return null
        }

        return controller
    }

    private fun createRecordingOutputOptions(): FileOutputOptions {
        val tempFile = File.createTempFile(
            "camera_recording_",
            ".mp4",
            context.cacheDir
        )
        currentRecordingFile = tempFile
        return FileOutputOptions.Builder(tempFile).build()
    }

    /**
     * Deletes the just-created (but not yet recorded-into) output file tracked by
     * [currentRecordingFile] and clears the reference. Used when recording fails to start
     * after [createRecordingOutputOptions] has already created the file.
     */
    private fun discardPendingRecordingFile() {
        currentRecordingFile?.let { deleteFileQuietly(it) }
        currentRecordingFile = null
    }

    private fun resolveAudioConfig(
        enableAudio: Boolean,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ): AudioConfig? {
        if (!enableAudio) {
            return AudioConfig.AUDIO_DISABLED
        }

        if (hasMicrophonePermission()) {
            return try {
                AudioConfig.create(true)
            } catch (e: SecurityException) {
                continuation.resumeIfActive(CameraResult.Error(e))
                null
            }
        }

        continuation.resumeIfActive(
            CameraResult.Error(
                SecurityException("Microphone permission is required for audio recording")
            )
        )
        return null
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraRecording(
        controller: LifecycleCameraController,
        outputOptions: FileOutputOptions,
        audioConfig: AudioConfig,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ) {
        val startResumed = AtomicBoolean(false)
        activeRecording = controller.startRecording(
            outputOptions,
            audioConfig,
            cameraExecutor
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> handleRecordingStartEvent(startResumed, continuation)
                is VideoRecordEvent.Finalize -> {
                    handleRecordingFinalizeEvent(event, startResumed, continuation)
                }

                else -> Unit
            }
        }
    }

    private fun handleRecordingStartEvent(
        startResumed: AtomicBoolean,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ) {
        Log.d(TAG, "Video recording started")
        if (startResumed.compareAndSet(false, true)) {
            continuation.resumeIfActive(CameraResult.Success(Unit))
        }
    }

    private fun handleRecordingFinalizeEvent(
        event: VideoRecordEvent.Finalize,
        startResumed: AtomicBoolean,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ) {
        // If recording finalized before Start was emitted, resume the
        // startRecording continuation with an error
        if (startResumed.compareAndSet(false, true)) {
            continuation.resumeIfActive(
                CameraResult.Error(
                    Exception("Recording failed to start: error code ${event.error}")
                )
            )
        }

        finalizeRecordingAndNotifyStopCallback(event)
    }

    private fun finalizeRecordingAndNotifyStopCallback(event: VideoRecordEvent.Finalize) {
        mainHandler.post {
            // CameraX requires use case changes on the main thread.
            restoreUseCasesAfterRecording()

            val callback = pendingStopCallback
            pendingStopCallback = null
            // Always clean up recording state
            activeRecording = null

            if (event.hasError()) {
                Log.e(TAG, "Recording error: ${event.error}")
                // The recording failed partway through, so the file on disk is
                // incomplete/corrupt - delete it rather than leaving it for the next
                // session's stale-file sweep to find.
                currentRecordingFile?.let { deleteFileQuietly(it) }
                currentRecordingFile = null
                callback?.invoke(CameraResult.Error(Exception("Recording failed with error code: ${event.error}")))
                return@post
            }

            val file = currentRecordingFile
            currentRecordingFile = null
            if (file == null) {
                callback?.invoke(CameraResult.Error(Exception("Recording file not found")))
                return@post
            }

            val capacitorFilePath = FileUtils.getPortablePath(
                context,
                pluginDelegate.bridge.localUrl,
                Uri.fromFile(file)
            )
            val result = JSObject().apply {
                put("path", Uri.fromFile(file).toString())
                put("webPath", capacitorFilePath)
            }
            callback?.invoke(CameraResult.Success(result))
        }
    }

    private fun LifecycleCameraController.currentEnabledUseCases(): Int {
        var enabledUseCases = 0
        if (isImageCaptureEnabled) {
            enabledUseCases = enabledUseCases or CameraController.IMAGE_CAPTURE
        }
        if (isImageAnalysisEnabled) {
            enabledUseCases = enabledUseCases or CameraController.IMAGE_ANALYSIS
        }
        if (isVideoCaptureEnabled) {
            enabledUseCases = enabledUseCases or CameraController.VIDEO_CAPTURE
        }
        return enabledUseCases
    }

    private fun restoreUseCasesAfterRecording() {
        val useCases = enabledUseCasesBeforeRecording ?: CameraController.IMAGE_CAPTURE
        enabledUseCasesBeforeRecording = null
        cameraController?.setEnabledUseCases(useCases)
    }

    private fun VideoRecordingQuality.toQualitySelector(): QualitySelector {
        return when (this) {
            VideoRecordingQuality.LOWEST -> QualitySelector.from(Quality.LOWEST)
            VideoRecordingQuality.SD -> QualitySelector.from(
                Quality.SD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            VideoRecordingQuality.HD -> QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )

            VideoRecordingQuality.FHD -> QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )

            VideoRecordingQuality.UHD -> QualitySelector.from(
                Quality.UHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.UHD)
            )

            VideoRecordingQuality.HIGHEST -> QualitySelector.from(Quality.HIGHEST)
        }
    }

    /**
     * Stops the current video recording and returns the file path.
     */
    suspend fun stopRecordingAsync(): CameraResult<JSObject> =
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                val recording = activeRecording
                if (recording == null) {
                    continuation.resumeIfActive(CameraResult.Error(Exception("No recording is in progress")))
                    return@post
                }

                // Delivered later from Finalize (finalizeRecordingAndNotifyStopCallback) or,
                // if the session/plugin tears down first, from failPendingRecording. Guard
                // with resumeIfActive: if this coroutine was itself cancelled in the
                // meantime, the recording still needs to run to completion (it isn't
                // stoppable from here), but nobody is left to observe the outcome.
                pendingStopCallback = { result ->
                    continuation.resumeIfActive(result)
                }

                activeRecording = null
                recording.stop()
            }
        }

    /**
     * Flip between front and back cameras.
     *
     * Rejects while a recording is active: reassigning the controller's `cameraSelector`
     * unbinds and rebinds every use case (including the active `VideoCapture`), which
     * interrupts the in-progress recording. The check runs on [mainHandler], the only
     * thread [activeRecording] is mutated on, so it can't race a concurrent recording.
     */
    fun flipCamera(callback: (Exception?) -> Unit) {
        // Validate controller state before mutating any stored selector state, so a failed
        // flip (no active session) leaves currentCameraSelector untouched for the next start.
        val controller = cameraController
            ?: run {
                callback(CameraError.CameraNotInitialized())
                return
            }

        mainHandler.post {
            if (activeRecording != null) {
                callback(CameraError.RecordingAlreadyInProgress())
                return@post
            }

            // Derive the current facing from the bound camera rather than the stored selector.
            // A deviceId-based selector never equals DEFAULT_FRONT/BACK_CAMERA, so toggling on
            // the selector would flip any deviceId camera to front regardless of which way it
            // physically points. Flipping abandons the deviceId in favour of the default
            // camera of the physically opposite facing.
            val currentlyFront = isLensFacingFront(controller.cameraInfo?.lensFacing)
            val newSelector = if (currentlyFront) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            controller.cameraSelector = newSelector
            currentCameraSelector = newSelector
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

    /**
     * Focus and meter the camera at a point given in CSS/viewport pixels
     * (tap-to-focus).
     *
     * The coordinates are in the same space the plugin emits for barcode
     * `boundingRect`, so that mapping is inverted here to build a metering point
     * in the PreviewView's coordinate system.
     *
     * A combined AF + AE [FocusMeteringAction] is used with CameraX's default
     * auto-cancel, so continuous auto-focus resumes automatically. Fixed-focus
     * cameras degrade to exposure-only metering; if neither is supported the
     * callback receives [CameraError.FocusNotSupported].
     */
    fun setFocusPoint(x: Float, y: Float, callback: ((Exception?) -> Unit)? = null) {
        mainHandler.post {
            val controller = cameraController
                ?: run {
                    callback?.invoke(CameraError.CameraNotInitialized())
                    return@post
                }

            val preview = previewView
                ?: run {
                    callback?.invoke(CameraError.PreviewNotInitialized())
                    return@post
                }

            val cameraControl = controller.cameraControl
                ?: run {
                    callback?.invoke(CameraError.CameraNotInitialized())
                    return@post
                }

            val cameraInfo = controller.cameraInfo
                ?: run {
                    callback?.invoke(CameraError.CameraNotInitialized())
                    return@post
                }

            // Invert the barcode boundingRect mapping: CSS px -> PreviewView px.
            val density = preview.context.resources.displayMetrics.density
            val topOffset = calculateTopOffset(webView)
            val previewX = x * density
            val previewY = y * density + topOffset

            val meteringPoint = preview.meteringPointFactory.createPoint(previewX, previewY)

            // Prefer combined focus + exposure metering; degrade to
            // exposure-only on fixed-focus cameras.
            var action = FocusMeteringAction.Builder(
                meteringPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).build()

            if (!cameraInfo.isFocusMeteringSupported(action)) {
                action = FocusMeteringAction.Builder(
                    meteringPoint,
                    FocusMeteringAction.FLAG_AE
                ).build()

                if (!cameraInfo.isFocusMeteringSupported(action)) {
                    callback?.invoke(CameraError.FocusNotSupported())
                    return@post
                }
            }

            val focusFuture = cameraControl.startFocusAndMetering(action)
            focusFuture.addListener(
                {
                    try {
                        focusFuture.get()
                        callback?.invoke(null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set focus point", e)
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

    /**
     * Set the flash mode. [callback] is invoked (with `null` on success) only after the mode
     * has actually been applied to the controller on the main handler, rather than
     * immediately after posting the change - and with the typed [CameraError.CameraNotInitialized]
     * rather than a raw `Exception` when there is no active session.
     */
    fun setFlashMode(mode: String, callback: (Exception?) -> Unit) {
        val controller = this.cameraController
            ?: run {
                callback(CameraError.CameraNotInitialized())
                return
            }

        currentFlashMode =
            when (mode) {
                "on" -> ImageCapture.FLASH_MODE_ON
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

        mainHandler.post {
            controller.imageCaptureFlashMode = currentFlashMode
            callback(null)
        }
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

    /** Get the current torch mode and intensity level. */
    fun getTorchMode(callback: (TorchModeState) -> Unit) {
        mainHandler.post {
            val cameraInfo = cameraController?.cameraInfo
                ?: run {
                    callback(TorchModeState(enabled = false, level = 0.0f))
                    return@post
                }

            val enabled = cameraInfo.torchState.value == TorchState.ON
            callback(TorchModeState(enabled = enabled, level = if (enabled) currentTorchLevel else 0.0f))
        }
    }

    /**
     * Sets the torch mode and, on API 33+ multi-level hardware, its intensity.
     *
     * [level] is normalized 0.0-1.0 and mapped onto the device's
     * [CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL] range via the Camera2 interop
     * `CaptureRequest.FLASH_STRENGTH_LEVEL` option, applied to the active CameraX session.
     * `CameraManager.turnOnTorchWithStrengthLevel` is intentionally not used: it throws
     * `CAMERA_IN_USE` while a CameraX session has the camera device open, which is always
     * the case here.
     *
     * Below API 33 or on single-level hardware [level] is ignored and the torch is simply
     * switched on/off via [androidx.camera.core.CameraControl.enableTorch].
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun setTorchMode(enabled: Boolean, level: Float? = null, callback: ((Exception?) -> Unit)? = null) {
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

                if (!enabled) {
                    clearTorchStrengthCaptureOption(controller)
                    currentTorchLevel = 0.0f
                    callback?.invoke(null)
                    return@post
                }

                val requestedLevel = (level ?: 1.0f).coerceIn(0.0f, 1.0f)
                val maxStrengthLevel = getMaxTorchStrengthLevel(controller)

                if (maxStrengthLevel == null) {
                    // Below API 33, or single-level hardware: strength isn't controllable, so
                    // the torch is simply on at the device's (only) strength - report 1.0.
                    clearTorchStrengthCaptureOption(controller)
                    currentTorchLevel = 1.0f
                    callback?.invoke(null)
                    return@post
                }

                applyTorchStrengthLevel(controller, requestedLevel, maxStrengthLevel, callback)
            } catch (e: Exception) {
                callback?.invoke(e)
            }
        }
    }

    /**
     * Returns the maximum torch-strength level supported by [controller]'s bound camera
     * ([CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL]), or `null` if strength
     * control isn't available - either below API 33, where the characteristic doesn't exist
     * yet, or on flash units supporting only a single binary on/off level.
     */
    private fun getMaxTorchStrengthLevel(controller: LifecycleCameraController): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        return try {
            val cameraInfo = controller.cameraInfo ?: return null
            val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
            val cameraManager =
                context.getSystemService(CAMERA_SERVICE) as? CameraManager ?: return null
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                ?.takeIf { it > 1 }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read torch strength characteristics", e)
            null
        }
    }

    /**
     * Applies [requestedLevel] (normalized 0.0-1.0) as a `FLASH_STRENGTH_LEVEL` Camera2 interop
     * capture-request option, mapped onto `1..maxStrengthLevel` (levels are 1-indexed; "off" is
     * handled separately by the caller via `enableTorch(false)`, so 0.0 here still maps to the
     * dimmest available level rather than off).
     *
     * If applying the option fails the torch remains on at the device's default strength and
     * [callback] is still invoked with `null` rather than failing the whole call.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyTorchStrengthLevel(
        controller: LifecycleCameraController,
        requestedLevel: Float,
        maxStrengthLevel: Int,
        callback: ((Exception?) -> Unit)?
    ) {
        val cameraControl = controller.cameraControl
        if (cameraControl == null) {
            callback?.invoke(CameraError.CameraNotInitialized())
            return
        }

        val strengthLevel = (requestedLevel * maxStrengthLevel).roundToInt().coerceIn(1, maxStrengthLevel)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.FLASH_STRENGTH_LEVEL, strengthLevel)
            .build()

        val camera2CameraControl = Camera2CameraControl.from(cameraControl)
        val future = camera2CameraControl.addCaptureRequestOptions(options)

        future.addListener({
            try {
                future.get()
                torchStrengthCaptureRequestOptions = options
                currentTorchLevel = strengthLevel.toFloat() / maxStrengthLevel
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed to apply torch strength level; torch remains on at default strength",
                    e
                )
                torchStrengthCaptureRequestOptions = null
                currentTorchLevel = 1.0f
            }
            callback?.invoke(null)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Clears any torch-strength Camera2 interop option previously set by
     * [applyTorchStrengthLevel], and forgets it so [clearJpegQualityCaptureOption] no longer
     * re-applies it after a capture.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun clearTorchStrengthCaptureOption(controller: LifecycleCameraController) {
        if (torchStrengthCaptureRequestOptions == null) return
        torchStrengthCaptureRequestOptions = null

        try {
            controller.cameraControl?.let { Camera2CameraControl.from(it).clearCaptureRequestOptions() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear torch strength Camera2 interop option", e)
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

                val deviceType = classifyLensDeviceType(characteristics)

                CameraDevice(
                    id = cameraId,
                    name = buildDeviceName(position, deviceType),
                    position = position,
                    deviceType = deviceType
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera devices", e)
            return emptyList()
        }
    }

    /**
     * Classifies a camera's lens as `"wideAngle"`, `"ultraWide"`, or `"telephoto"` based on
     * its horizontal field of view, derived from [CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
     * and [CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE].
     *
     * Unlike iOS, Camera2 has no direct lens-type API per physical camera ID, and it doesn't
     * expose multi-camera composition either, so the `dual`/`triple`/`trueDepth` types are
     * never produced here. Returns `null` when focal length or sensor size isn't reported.
     */
    private fun classifyLensDeviceType(characteristics: CameraCharacteristics): String? {
        val focalLength =
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
        val sensorWidth =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width

        if (focalLength == null || focalLength <= 0f || sensorWidth == null || sensorWidth <= 0f) {
            return null
        }

        val horizontalFovDegrees =
            Math.toDegrees(2.0 * atan(sensorWidth / (2.0 * focalLength)))

        return when {
            horizontalFovDegrees >= ULTRA_WIDE_FOV_THRESHOLD_DEGREES -> "ultraWide"
            horizontalFovDegrees <= TELEPHOTO_FOV_THRESHOLD_DEGREES -> "telephoto"
            else -> "wideAngle"
        }
    }

    /** Builds a human-readable device name from its position and (optional) lens type. */
    private fun buildDeviceName(position: String, deviceType: String?): String {
        val positionLabel = when (position) {
            "front" -> "Front"
            "back" -> "Back"
            else -> "External"
        }
        val typeLabel = when (deviceType) {
            "ultraWide" -> "Ultra Wide Camera"
            "telephoto" -> "Telephoto Camera"
            "wideAngle" -> "Wide Camera"
            else -> "Camera"
        }
        return "$positionLabel $typeLabel"
    }

    /** Clean up resources when the plugin is being destroyed */
    fun cleanup() {
        // Cancel all coroutines first
        scope.cancel()

        mainHandler.post {
            try {
                // Stop any active recording before cleanup. Resumes (rather than drops)
                // any pending stopRecording() caller and deletes the partially-written
                // recording file, mirroring stopSessionAsync's handling of the same case.
                activeRecording?.stop()
                activeRecording = null
                failPendingRecording("Recording was interrupted because camera resources were cleaned up")

                // Clear the image-analysis analyzer and close the ML Kit scanner (if any)
                closeBarcodeScanner()

                // Stop camera session
                cameraController?.unbind()
                cameraController = null

                // Remove preview view
                previewView?.let { view ->
                    (webView.parent as? ViewGroup)?.removeView(view)
                    previewView = null
                }

                // Reset WebView properties
                restoreWebViewAppearance()

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

    private fun setupPreviewView(context: Context, previewScaleMode: String? = null) {
        // Capture the WebView's original background/layer type exactly once,
        // before it's ever made transparent, so restoreWebViewAppearance()
        // can restore the app's real appearance later instead of a
        // hard-coded value.
        if (!hasCapturedOriginalWebViewAppearance) {
            val background = webView.background
            if (background is ColorDrawable) {
                // Store the color, not the drawable: setBackgroundColor()
                // below mutates this ColorDrawable instance in place, so a
                // captured reference would already be transparent on restore.
                originalWebViewBackgroundColor = background.color
                originalWebViewBackground = null
            } else {
                originalWebViewBackgroundColor = null
                originalWebViewBackground = background
            }
            originalWebViewLayerType = webView.layerType
            hasCapturedOriginalWebViewAppearance = true
        }

        // Make WebView transparent
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        // "fit" letterboxes the whole frame (FIT_CENTER); any other value keeps
        // the default cover behavior (FILL_CENTER). The PreviewView stays
        // fullscreen (MATCH_PARENT) in both cases, so the barcode
        // COORDINATE_SYSTEM_VIEW_REFERENCED coordinates and the metering-point
        // factory - both scaleType-aware - remain correct; only the fitted
        // region within the view changes.
        val previewScaleType =
            if (previewScaleMode == "fit") {
                PreviewView.ScaleType.FIT_CENTER
            } else {
                PreviewView.ScaleType.FILL_CENTER
            }

        previewView =
            PreviewView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                scaleType = previewScaleType
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
        setupPreviewView(context, config.previewScaleMode)

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

        // Resolve the resolution selectors from the configured aspect ratio and
        // capture-resolution hint. With both options omitted this is exactly the
        // long-standing default: 16:9-with-automatic-fallback for capture and an
        // automatically chosen preview resolution.
        val aspectRatioStrategy = when (config.aspectRatio) {
            "4:3" -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            "16:9" -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else -> null
        }

        val imageCaptureSelectorBuilder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                aspectRatioStrategy ?: AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )

        config.captureMaxDimension?.let { maxDimension ->
            // The bound size is expressed in the sensor's landscape-oriented
            // coordinate space: the longer edge is the width and the shorter
            // edge derives from the configured aspect ratio (16:9 by default).
            // CLOSEST_LOWER_THEN_HIGHER picks the largest supported resolution
            // that does not exceed the bound, falling back to the closest
            // higher one - mirroring the iOS maxPhotoDimensions selection.
            val shorterEdge =
                if (config.aspectRatio == "4:3") maxDimension * 3 / 4
                else maxDimension * 9 / 16
            imageCaptureSelectorBuilder.setResolutionStrategy(
                ResolutionStrategy(
                    Size(maxDimension, shorterEdge),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
        }

        // Initialize camera controller
        val controller =
            LifecycleCameraController(context).apply {
                cameraSelector = currentCameraSelector
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                imageCaptureResolutionSelector = imageCaptureSelectorBuilder.build()

                // Drive the preview stream with the same aspect ratio so the
                // on-screen framing matches the captured photo. Left untouched
                // when no aspectRatio was configured (default behavior).
                aspectRatioStrategy?.let { strategy ->
                    previewResolutionSelector =
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(strategy)
                            .build()
                }
            }

        cameraController = controller
        previewView?.controller = controller

        // Setup barcode scanning if needed
        if (config.enableBarcodeDetection) {
            setupBarcodeScanner(controller, config.barcodeTypes)
        }

        // Bind to lifecycle
        controller.bindToLifecycle(lifecycleOwner)

        // Apply the initial zoom factor once the camera has actually opened.
        // Immediately after bindToLifecycle the controller's zoomState is still
        // null, so the supported range falls back to 1.0..1.0 and would reject
        // any non-default zoomFactor.
        val zoomState = controller.zoomState
        zoomState.observe(
            lifecycleOwner,
            object : Observer<ZoomState> {
                override fun onChanged(value: ZoomState) {
                    zoomState.removeObserver(this)

                    // Guard against a stale observation from a controller whose
                    // session was already replaced.
                    if (cameraController !== controller) return

                    setZoomFactor(config.zoomFactor) { error ->
                        if (error != null) {
                            Log.e(
                                TAG,
                                "Failed to apply initial zoom factor ${config.zoomFactor}",
                                error
                            )
                        }
                    }
                }
            }
        )
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
        this.barcodeScanner = barcodeScanner
        val mainExecutor = ContextCompat.getMainExecutor(previewView.context)

        // Calculate a possible top offset of the webView which is not applied to the previewView
        // and might break the positioning of the bounding box of the barcode in relation to the
        // webView. This is due to capacitors required hack around the edge-to-edge behavior of web
        // views on android
        val topOffset = calculateTopOffset(webView)

        // The analyzer itself (frame acquisition + ML Kit detection) runs on the camera
        // executor so it never competes with UI work on the main thread; only the final
        // result callback is delivered on mainExecutor, since processBarcodeResults reads
        // previewView (a UI-thread-only object) to map the bounding box to webview
        // coordinates.
        controller.setImageAnalysisAnalyzer(
            cameraExecutor,
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
        // Monotonic clock (ms since boot): a backward wall-clock jump must not
        // extend the throttle or suppression windows arbitrarily.
        val now = SystemClock.elapsedRealtime()
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

        val value = barcode.rawValue ?: ""
        val type = getBarcodeFormatString(barcode.format)

        // Suppress re-emission of the same code within the suppression window so
        // a static barcode in view produces a bounded event rate. Timestamps are
        // tracked per key so multiple codes in frame can't defeat the window by
        // alternating.
        val barcodeKey = "$type\u0000$value"
        val lastEmit = recentBarcodeEmitTimes[barcodeKey]
        if (lastEmit != null && now - lastEmit < BARCODE_SUPPRESSION_WINDOW_MS) {
            return
        }

        // Prune expired entries once the map grows, keeping it bounded during
        // long sessions that scan many different codes.
        if (recentBarcodeEmitTimes.size > BARCODE_DEDUPE_MAP_PRUNE_THRESHOLD) {
            recentBarcodeEmitTimes.entries.removeAll { now - it.value >= BARCODE_SUPPRESSION_WINDOW_MS }
        }
        recentBarcodeEmitTimes[barcodeKey] = now

        // Adjust bounding box to webView coordinates
        val webBoundingRect =
            boundingBoxToWebBoundingRect(previewView, barcode.boundingBox, topOffset)

        val barcodeResult =
            BarcodeDetectionResult(
                value = value,
                rawBytes = barcode.rawBytes ?: ByteArray(0),
                displayValue = barcode.displayValue ?: "",
                type = type,
                boundingRect = webBoundingRect
            )

        // Emit to Flow; the plugin collects this and notifies JS listeners
        scope.launch {
            _barcodeEvents.emit(barcodeResult)
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
