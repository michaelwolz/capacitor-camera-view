package com.michaelwolz.capacitorcameraview

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
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
import com.michaelwolz.capacitorcameraview.model.TorchModeState
import com.michaelwolz.capacitorcameraview.model.VideoRecordingQuality
import com.michaelwolz.capacitorcameraview.model.ZoomFactors
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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

/** The JPEG quality [ImageCapture] is built with, matching the plugin's own cross-platform default. */
const val DEFAULT_JPEG_QUALITY = 90

class CameraView(plugin: Plugin) {
    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The process-wide camera provider, fetched on demand and cached once obtained. Populated
    // independently of whether a session is running. Created, mutated and read on the main
    // thread only.
    private var cameraProvider: ProcessCameraProvider? = null

    // CameraX session. Created, mutated and read on the main thread only.
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var sessionLifecycleOwner: SessionLifecycleOwner? = null
    private var sessionConfig: CameraSessionConfiguration? = null
    private var startGate: SessionStartGate? = null

    // Whether a session is running. Read from any thread via isRunning().
    @Volatile
    private var sessionActive = false

    // Viewport-derived aspect ratio the currently held use cases were built for, so a layout
    // change only recreates them when the resolution selection would actually change.
    private var boundAspectRatio: Int? = null

    // The Recorder currently held, and the quality it was built for.
    private var recorder: Recorder? = null
    private var boundVideoQuality: VideoRecordingQuality? = null
    private var videoRecordingQuality = VideoRecordingQuality.HIGHEST

    // Swaps ImageAnalysis out for VideoCapture while a recording is in progress.
    private var recordingUseCasesActive = false

    // Target rotation applied to every non-preview use case. Preview is left alone: the
    // PreviewView owns its own transform.
    private var targetRotation = Surface.ROTATION_0

    private val pendingZoomFactor = PendingValue<Float>()
    private val pendingTorchRequest = PendingValue<TorchRequest>()
    private val pendingFlashMode = PendingValue<Unit>()

    // The surface provider currently installed on [preview]. Tracked so a rebind that reuses
    // the same Preview does not reset its surface.
    private var boundSurfaceProvider: Preview.SurfaceProvider? = null

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val isSizeChanged =
                right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (isSizeChanged) {
                bindSession()
            }
            pushViewTransformToAnalyzer()
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (previewView?.display?.displayId != displayId) return
            updateTargetRotation()
            pushViewTransformToAnalyzer()
        }
    }

    private val previewStreamStateObserver = Observer<PreviewView.StreamState> { state ->
        if (state == PreviewView.StreamState.STREAMING) {
            pushViewTransformToAnalyzer()
        }
    }

    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var previewView: PreviewView? = null

    // The ML Kit barcode scanner currently attached to the image analysis
    // analyzer, if barcode detection is enabled. Tracked here so it can be
    // closed (and the analyzer cleared) on session stop/cleanup - the
    // MlKitAnalyzer that wraps it does not own or close it for us.
    private var barcodeScanner: BarcodeScanner? = null

    // The analyzer wrapping [barcodeScanner]. Held so the sensor-to-view matrix can be
    // pushed into it.
    private var barcodeAnalyzer: ViewReferencedAnalyzer? = null

    // Camera state
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF

    /** A buffered [setTorchMode] request, replayed once the camera is attached. */
    private data class TorchRequest(val enabled: Boolean, val level: Float?)

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

            val gate = SessionStartGate()
            startGate = gate

            try {
                initializeCamera(context, lifecycleOwner, config)
                gate.awaitFirstBind()
                CameraResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in camera setup", e)
                if (startGate === gate) {
                    releaseSessionResources()
                }
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
     * Tears the camera session down: unbinds and releases the use cases it owns, releases the
     * preview view and restores the WebView. The process-wide [cameraProvider] and the
     * [sessionLifecycleOwner] outlive a session; only [cleanup] disposes of those.
     *
     * Must be called on the main thread. Used by [stopSessionAsync] and [cleanup], and to tear
     * down partially initialized state when [startSessionAsync] fails.
     */
    private fun releaseSessionResources() {
        sessionActive = false
        startGate?.onFailure(CameraError.CameraNotInitialized())
        closeBarcodeScanner()

        sessionLifecycleOwner?.setActive(false)
        preview?.surfaceProvider = null
        cameraProvider?.unbind(*allUseCases())

        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        videoCapture = null
        recorder = null
        boundVideoQuality = null
        boundAspectRatio = null
        boundSurfaceProvider = null

        val notBound = CameraError.CameraNotInitialized()
        pendingZoomFactor.clear(notBound)
        pendingTorchRequest.clear(notBound)
        pendingFlashMode.clear(notBound)

        unregisterDisplayListener()

        // Reset barcode dedupe state so a restarted session emits immediately
        recentBarcodeEmitTimes.clear()

        previewView?.let { view ->
            view.removeOnLayoutChangeListener(layoutChangeListener)
            view.previewStreamState.removeObserver(previewStreamStateObserver)
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
     * Clears the image-analysis analyzer (if any) and closes the ML Kit barcode scanner,
     * releasing its native resources. The [MlKitAnalyzer]
     * wrapping the scanner does not own or close it, so this must be done explicitly
     * whenever a session with barcode detection enabled is torn down.
     */
    private fun closeBarcodeScanner() {
        if (barcodeScanner == null) return

        imageAnalysis?.clearAnalyzer()
        barcodeAnalyzer = null
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
        return sessionActive
    }

    /** Capture a photo with the current camera configuration. */
    suspend fun capturePhotoAsync(
        quality: Int,
        saveToFile: Boolean = false
    ): CameraResult<JSObject> = suspendCancellableCoroutine { continuation ->
        val startTime = System.currentTimeMillis()

        mainHandler.post {
            val imageCapture = this.imageCapture
            if (imageCapture == null || camera == null) {
                continuation.resumeIfActive(CameraResult.Error(CameraError.CameraNotInitialized()))
                return@post
            }

            if (previewView == null) {
                continuation.resumeIfActive(CameraResult.Error(CameraError.PreviewNotInitialized()))
                return@post
            }

            try {
                if (saveToFile) {
                    // Direct file capture - much more efficient!
                    val tempFile =
                        File.createTempFile("camera_capture_photo", ".jpg", context.cacheDir)
                    val outputFileOptions = buildOutputFileOptions(tempFile)

                    takePictureToFile(imageCapture, outputFileOptions, tempFile, startTime, continuation)
                } else {
                    // Base64 capture using ImageProxy
                    imageCapture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                Log.d(
                                    TAG,
                                    "Image captured successfully in ${System.currentTimeMillis() - startTime}ms"
                                )
                                try {
                                    val base64String = imageProxyToBase64(image, quality)
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
     * Builds the output options for an on-disk capture, mirroring it when the front camera is
     * active so the file matches what the preview showed.
     */
    private fun buildOutputFileOptions(tempFile: File): ImageCapture.OutputFileOptions {
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = isLensFacingFront(camera?.cameraInfo?.lensFacing)
        }

        return ImageCapture.OutputFileOptions.Builder(tempFile)
            .setMetadata(metadata)
            .build()
    }

    /**
     * Takes a picture into [tempFile] and resumes [continuation] with the file paths.
     * Must be called on the main thread. Exactly one resume is guaranteed: either via the
     * capture callbacks or via the synchronous catch below (in which case no callback will
     * fire, since `takePicture` failed before being registered).
     */
    private fun takePictureToFile(
        imageCapture: ImageCapture,
        outputFileOptions: ImageCapture.OutputFileOptions,
        tempFile: File,
        startTime: Long,
        continuation: CancellableContinuation<CameraResult<JSObject>>
    ) {
        try {
            imageCapture.takePicture(
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

                            put("path", Uri.fromFile(tempFile).toString())
                            put("webPath", capacitorFilePath)
                        }
                        continuation.resumeIfActive(CameraResult.Success(result))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Error saving image to file", exception)
                        continuation.resumeIfActive(CameraResult.Error(exception))
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up image capture", e)
            continuation.resumeIfActive(CameraResult.Error(e))
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
        if (!validateRecordingPreconditions(continuation)) return

        try {
            videoRecordingQuality = videoQuality
            recordingUseCasesActive = true
            configureSession()

            val videoCapture = this.videoCapture
            if (videoCapture == null || camera == null) {
                restoreUseCasesAfterRecording()
                continuation.resumeIfActive(CameraResult.Error(CameraError.CameraNotInitialized()))
                return
            }

            val outputOptions = createRecordingOutputOptions()
            val audioEnabled = resolveAudioEnabled(enableAudio, continuation) ?: run {
                // Recording never actually started - the just-created output file is
                // empty and unused, so it must be removed here rather than left for
                // the next session's stale-file sweep to find.
                discardPendingRecordingFile()
                restoreUseCasesAfterRecording()
                return
            }

            startCameraRecording(videoCapture, outputOptions, audioEnabled, continuation)
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
    ): Boolean {
        if (camera == null) {
            continuation.resumeIfActive(CameraResult.Error(CameraError.CameraNotInitialized()))
            return false
        }

        if (activeRecording != null) {
            continuation.resumeIfActive(CameraResult.Error(CameraError.RecordingAlreadyInProgress()))
            return false
        }

        return true
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

    /**
     * Resolves whether the recording should capture audio, or `null` if it must not start at
     * all because the microphone permission is missing - in which case [continuation] has
     * already been resumed with the failure.
     */
    private fun resolveAudioEnabled(
        enableAudio: Boolean,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ): Boolean? {
        if (!enableAudio) {
            return false
        }

        if (hasMicrophonePermission()) {
            return true
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

    @SuppressLint("MissingPermission")
    private fun startCameraRecording(
        videoCapture: VideoCapture<Recorder>,
        outputOptions: FileOutputOptions,
        audioEnabled: Boolean,
        continuation: CancellableContinuation<CameraResult<Unit>>
    ) {
        val startResumed = AtomicBoolean(false)
        var pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)
        if (audioEnabled) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(cameraExecutor) { event ->
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

    private fun restoreUseCasesAfterRecording() {
        if (!recordingUseCasesActive) return
        recordingUseCasesActive = false
        bindSession()
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

    /** Returns the [Recorder] for [videoRecordingQuality], building a new one only if needed. */
    private fun resolveRecorder(): Recorder {
        recorder?.takeIf { boundVideoQuality == videoRecordingQuality }?.let { return it }

        boundVideoQuality = videoRecordingQuality
        return Recorder.Builder()
            .setQualitySelector(videoRecordingQuality.toQualitySelector())
            .build()
            .also { recorder = it }
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
     * Rejects while a recording is active: switching the camera rebinds every use case
     * (including the active `VideoCapture`), which interrupts the in-progress recording. The
     * check runs on [mainHandler], the only thread [activeRecording] is mutated on, so it
     * can't race a concurrent recording.
     */
    fun flipCamera(callback: (Exception?) -> Unit) {
        mainHandler.post {
            val camera = this.camera
                ?: run {
                    callback(CameraError.CameraNotInitialized())
                    return@post
                }

            if (activeRecording != null) {
                callback(CameraError.RecordingAlreadyInProgress())
                return@post
            }

            // Derive the current facing from the bound camera rather than the stored selector.
            // A deviceId-based selector never equals DEFAULT_FRONT/BACK_CAMERA, so toggling on
            // the selector would flip any deviceId camera to front regardless of which way it
            // physically points. Flipping abandons the deviceId in favour of the default
            // camera of the physically opposite facing.
            val currentlyFront = isLensFacingFront(camera.cameraInfo.lensFacing)
            val previousSelector = currentCameraSelector
            currentCameraSelector = if (currentlyFront) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            when (val result = configureSession()) {
                SessionBindResult.NotReady -> {
                    currentCameraSelector = previousSelector
                    callback(CameraError.CameraNotInitialized())
                }

                is SessionBindResult.Failed -> {
                    currentCameraSelector = previousSelector
                    if (configureSession() !is SessionBindResult.Bound) {
                        releaseSessionResources()
                    }
                    callback(result.cause)
                }

                is SessionBindResult.Bound -> callback(null)
            }
        }
    }

    /** Get the min, max, and current zoom values */
    fun getSupportedZoomFactors(callback: (ZoomFactors) -> Unit) {
        mainHandler.post { callback(getZoomFactorsInternal()) }
    }

    /**
     * Set the zoom factor for the camera. Buffered via [pendingZoomFactor] and replayed by
     * [applyPostBindState] if no camera is bound yet, rather than dropped.
     */
    fun setZoomFactor(zoomFactor: Float, callback: (((Exception?) -> Unit)?) = null) {
        mainHandler.post {
            if (!sessionActive) {
                callback?.invoke(CameraError.CameraNotInitialized())
                return@post
            }
            if (camera == null) {
                pendingZoomFactor.set(zoomFactor) { error -> callback?.invoke(error) }
                return@post
            }
            applyZoomFactor(zoomFactor, callback)
        }
    }

    private fun applyZoomFactor(zoomFactor: Float, callback: ((Exception?) -> Unit)?) {
        val cameraControl = camera?.cameraControl
            ?: run {
                callback?.invoke(CameraError.CameraNotInitialized())
                return
            }

        val availableZoomFactors = getZoomFactorsInternal()

        if (zoomFactor !in availableZoomFactors.min..availableZoomFactors.max) {
            callback?.invoke(CameraError.ZoomFactorOutOfRange())
            return
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
            val camera = this.camera
                ?: run {
                    callback?.invoke(CameraError.CameraNotInitialized())
                    return@post
                }

            val preview = previewView
                ?: run {
                    callback?.invoke(CameraError.PreviewNotInitialized())
                    return@post
                }

            val cameraControl = camera.cameraControl
            val cameraInfo = camera.cameraInfo

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
            val cameraInfo = camera?.cameraInfo
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
     * has actually been applied to [ImageCapture] on the main handler, rather than
     * immediately after posting the change - and with the typed [CameraError.CameraNotInitialized]
     * rather than a raw `Exception` when there is no active session.
     */
    fun setFlashMode(mode: String, callback: (Exception?) -> Unit) {
        val resolvedFlashMode = when (mode) {
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        mainHandler.post {
            if (!sessionActive) {
                callback(CameraError.CameraNotInitialized())
                return@post
            }

            currentFlashMode = resolvedFlashMode

            val imageCapture = this.imageCapture
            if (imageCapture == null) {
                pendingFlashMode.set(Unit) { error -> callback(error) }
                return@post
            }

            imageCapture.flashMode = resolvedFlashMode
            callback(null)
        }
    }

    /** Check if torch is available */
    fun isTorchAvailable(callback: (Boolean) -> Unit) {
        mainHandler.post {
            val cameraInfo = camera?.cameraInfo
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
            val cameraInfo = camera?.cameraInfo
                ?: run {
                    callback(TorchModeState(enabled = false, level = 0.0f))
                    return@post
                }

            val enabled = cameraInfo.torchState.value == TorchState.ON
            val maxStrengthLevel = cameraInfo.maxTorchStrengthLevel
            val level = normalizedTorchLevel(
                enabled,
                maxStrengthLevel,
                cameraInfo.torchStrengthLevel.value ?: maxStrengthLevel
            )
            callback(TorchModeState(enabled = enabled, level = level))
        }
    }

    /**
     * Sets the torch mode and, on hardware that supports it, its intensity.
     *
     * [level] is normalized 0.0-1.0 and mapped onto the device's
     * `CameraInfo.getMaxTorchStrengthLevel()` range via `CameraControl.setTorchStrengthLevel()`.
     *
     * On hardware that doesn't support configurable strength
     * ([androidx.camera.core.CameraInfo.isTorchStrengthSupported] is `false`), [level] is
     * ignored and the torch is simply switched on/off via
     * [androidx.camera.core.CameraControl.enableTorch].
     *
     * Buffered via [pendingTorchRequest] and replayed by [applyPostBindState] if no camera is
     * bound yet, rather than dropped.
     */
    fun setTorchMode(enabled: Boolean, level: Float? = null, callback: ((Exception?) -> Unit)? = null) {
        mainHandler.post {
            if (!sessionActive) {
                callback?.invoke(CameraError.CameraNotInitialized())
                return@post
            }
            if (camera == null) {
                pendingTorchRequest.set(TorchRequest(enabled, level)) { error -> callback?.invoke(error) }
                return@post
            }
            applyTorchMode(enabled, level, callback)
        }
    }

    private fun applyTorchMode(enabled: Boolean, level: Float?, callback: ((Exception?) -> Unit)?) {
        try {
            val camera = this.camera
                ?: run {
                    callback?.invoke(CameraError.CameraNotInitialized())
                    return
                }
            val cameraInfo = camera.cameraInfo

            if (!cameraInfo.hasFlashUnit()) {
                callback?.invoke(CameraError.TorchUnavailable())
                return
            }

            camera.cameraControl.enableTorch(enabled)

            val maxStrengthLevel = cameraInfo.maxTorchStrengthLevel
            if (!enabled || !cameraInfo.isTorchStrengthSupported() || maxStrengthLevel <= 0) {
                callback?.invoke(null)
                return
            }

            val strengthLevel = resolveTorchStrengthLevel(level ?: 1.0f, maxStrengthLevel)
            val future = camera.cameraControl.setTorchStrengthLevel(strengthLevel)

            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to apply torch strength level; keeping the previous strength",
                        e
                    )
                }
                callback?.invoke(null)
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            callback?.invoke(e)
        }
    }

    /**
     * Gets a list of available camera devices. Callable with no session running - it obtains
     * the [ProcessCameraProvider] on demand rather than requiring a bound camera.
     */
    suspend fun getAvailableDevicesAsync(): CameraResult<List<CameraDevice>> = withContext(Dispatchers.Main) {
        try {
            val provider = suspendCancellableCoroutine { continuation: CancellableContinuation<ProcessCameraProvider?> ->
                ensureCameraProvider { continuation.resumeIfActive(it) }
            } ?: return@withContext CameraResult.Success(emptyList())

            CameraResult.Success(provider.availableCameraInfos.mapNotNull(::buildCameraDevice))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera devices", e)
            CameraResult.Error(e)
        }
    }

    /**
     * Builds a [CameraDevice] from CameraX's [CameraInfo], or `null` when the camera's lens
     * facing can't be resolved.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildCameraDevice(info: CameraInfo): CameraDevice? {
        val lensFacing = try {
            info.lensFacing
        } catch (e: IllegalArgumentException) {
            return null
        }

        val position = when (lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "front"
            CameraSelector.LENS_FACING_BACK -> "back"
            else -> "external"
        }

        // A physical sub-camera's CameraInfo throws here; report it as unclassified.
        val deviceType = try {
            classifyLensDeviceType(info.intrinsicZoomRatio)
        } catch (e: UnsupportedOperationException) {
            null
        }

        return CameraDevice(
            id = Camera2CameraInfo.from(info).cameraId,
            name = buildDeviceName(position, deviceType),
            position = position,
            deviceType = deviceType
        )
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
        scope.cancel()

        mainHandler.post {
            try {
                activeRecording?.stop()
                activeRecording = null
                failPendingRecording("Recording was interrupted because camera resources were cleaned up")

                releaseSessionResources()

                sessionLifecycleOwner?.release()
                sessionLifecycleOwner = null
                cameraProvider = null
                sessionConfig = null
                lifecycleOwner = null

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

    private fun initializeCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        config: CameraSessionConfiguration,
    ) {
        setupPreviewView(context, config.previewScaleMode)

        val notBound = CameraError.CameraNotInitialized()
        pendingZoomFactor.clear(notBound)
        pendingTorchRequest.clear(notBound)
        pendingFlashMode.clear(notBound)

        sessionConfig = config
        currentCameraSelector = resolveCameraSelector(config)
        recordingUseCasesActive = false
        videoRecordingQuality = VideoRecordingQuality.HIGHEST
        targetRotation = previewView?.display?.rotation ?: Surface.ROTATION_0
        pendingZoomFactor.set(config.zoomFactor) { error ->
            if (error != null) {
                Log.e(TAG, "Failed to apply initial zoom factor ${config.zoomFactor}", error)
            }
        }

        if (config.enableBarcodeDetection) {
            barcodeScanner = createBarcodeScanner(config.barcodeTypes)
        }

        val owner = sessionLifecycleOwner
        if (owner == null || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            sessionLifecycleOwner = SessionLifecycleOwner(lifecycleOwner)
        }

        previewView?.addOnLayoutChangeListener(layoutChangeListener)
        previewView?.previewStreamState?.observeForever(previewStreamStateObserver)
        registerDisplayListener()
        sessionActive = true

        if (cameraProvider != null) {
            bindSession()
            return
        }

        ensureCameraProvider { asyncProvider ->
            if (asyncProvider == null) {
                abortSession(CameraError.CameraNotInitialized())
                return@ensureCameraProvider
            }

            bindSession()
        }
    }

    /**
     * The [configureSession] entry point for triggers that belong to the session itself,
     * rather than to a caller with its own error channel such as [flipCamera].
     */
    private fun bindSession() {
        val result = try {
            configureSession()
        } catch (e: Exception) {
            SessionBindResult.Failed(e)
        }

        when (result) {
            SessionBindResult.NotReady -> Unit
            is SessionBindResult.Bound -> startGate?.onBound()
            is SessionBindResult.Failed -> abortSession(result.cause)
        }
    }

    private fun abortSession(cause: Exception) {
        if (startGate?.onFailure(cause) != true) {
            Log.e(TAG, "Tearing down the camera session after a failed rebind", cause)
        }
        releaseSessionResources()
    }

    /**
     * Supplies the process-wide [ProcessCameraProvider] to [onReady], fetching it via
     * [ProcessCameraProvider.getInstance] if it hasn't been obtained yet. `null` is passed on
     * failure. Callable with no session running.
     */
    private fun ensureCameraProvider(onReady: (ProcessCameraProvider?) -> Unit) {
        val provider = cameraProvider
        if (provider != null) {
            onReady(provider)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val resolvedProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to obtain the camera provider", e)
                null
            }
            cameraProvider = resolvedProvider
            onReady(resolvedProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun resolveCameraSelector(config: CameraSessionConfiguration): CameraSelector {
        val deviceId = config.deviceId
        if (deviceId != null) {
            // Prefer specific device id over position
            return CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info -> Camera2CameraInfo.from(info).cameraId == deviceId }
                }
                .build()
        }

        return if (config.position == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private sealed interface SessionBindResult {
        /** A precondition for binding is still missing; nothing was unbound or rebound. */
        data object NotReady : SessionBindResult

        data class Failed(val cause: Exception) : SessionBindResult

        data class Bound(val camera: Camera) : SessionBindResult
    }

    /**
     * Rebuilds and rebinds the camera session for the current configuration, camera selector,
     * viewport and display rotation.
     *
     * This is the single entry point for every change that requires a rebind. It reports
     * [SessionBindResult.NotReady] until the provider is ready and the preview view has been
     * laid out; the layout change listener calls it again once a viewport is available.
     */
    private fun configureSession(): SessionBindResult {
        val provider = cameraProvider ?: return SessionBindResult.NotReady
        val previewView = this.previewView ?: return SessionBindResult.NotReady
        val lifecycleOwner = sessionLifecycleOwner ?: return SessionBindResult.NotReady
        val config = sessionConfig ?: return SessionBindResult.NotReady
        val viewPort = previewView.viewPort ?: return SessionBindResult.NotReady

        val aspectRatio = resolveViewportAspectRatio(provider, viewPort)

        // A partial rebind skews CameraX's resolution selection for the use cases that stay
        // bound, so every use case is unbound before the group is rebuilt.
        provider.unbind(*allUseCases())

        if (preview == null || aspectRatio != boundAspectRatio) {
            boundAspectRatio = aspectRatio
            rebuildImagingUseCases(config, aspectRatio)
        }

        videoCapture = if (recordingUseCasesActive) {
            VideoCapture.Builder(resolveRecorder())
                .setTargetRotation(targetRotation)
                .build()
        } else {
            null
        }

        val surfaceProvider = previewView.surfaceProvider
        if (boundSurfaceProvider !== surfaceProvider) {
            boundSurfaceProvider = surfaceProvider
            preview?.surfaceProvider = surfaceProvider
        }

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .apply { activeUseCases().forEach { addUseCase(it) } }
            .build()

        val boundCamera = try {
            provider.bindToLifecycle(lifecycleOwner, currentCameraSelector, useCaseGroup)
        } catch (e: Exception) {
            camera = null
            lifecycleOwner.setActive(false)
            return SessionBindResult.Failed(e)
        }

        camera = boundCamera
        lifecycleOwner.setActive(true)
        applyPostBindState()

        return SessionBindResult.Bound(boundCamera)
    }

    /**
     * Recreates Preview, ImageCapture and ImageAnalysis. A resolution selector and an
     * analyzer are only read at build time, so neither can be changed by rebinding.
     */
    private fun rebuildImagingUseCases(config: CameraSessionConfiguration, aspectRatio: Int) {
        val viewportSelector = aspectRatio
            .takeIf { it != AspectRatio.RATIO_DEFAULT }
            ?.let {
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(it, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                    )
                    .build()
            }

        boundSurfaceProvider = null
        preview = Preview.Builder()
            .apply {
                (previewResolutionSelector(config) ?: viewportSelector)
                    ?.let { setResolutionSelector(it) }
            }
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(imageCaptureResolutionSelector(config))
            .setTargetRotation(targetRotation)
            .setJpegQuality(DEFAULT_JPEG_QUALITY)
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .apply { viewportSelector?.let { setResolutionSelector(it) } }
            .setTargetRotation(targetRotation)
            .build()
            .also { analysis ->
                barcodeScanner?.let { attachBarcodeAnalyzer(analysis, it) }
            }
    }

    /** Every use case currently held, bound or not, for unbinding. */
    private fun allUseCases(): Array<UseCase> =
        listOfNotNull(preview, imageCapture, imageAnalysis, videoCapture).toTypedArray()

    /**
     * The use cases that belong in the next [UseCaseGroup]. ImageAnalysis gives way to
     * VideoCapture while recording, keeping the session at three concurrent use cases.
     */
    private fun activeUseCases(): List<UseCase> = buildList {
        preview?.let { add(it) }
        imageCapture?.let { add(it) }
        if (recordingUseCasesActive) {
            videoCapture?.let { add(it) }
        } else {
            imageAnalysis?.let { add(it) }
        }
    }

    private fun applyPostBindState() {
        updateTargetRotation()
        pushViewTransformToAnalyzer()

        imageCapture?.let { capture ->
            capture.flashMode = currentFlashMode
            pendingFlashMode.propagateIfPresent { _, callback -> callback(null) }
        }

        if (camera == null) return
        pendingZoomFactor.propagateIfPresent { zoomFactor, callback -> applyZoomFactor(zoomFactor, callback) }
        pendingTorchRequest.propagateIfPresent { request, callback ->
            applyTorchMode(request.enabled, request.level, callback)
        }
    }

    /**
     * The aspect ratio the viewport implies once expressed in the active camera's sensor
     * coordinate space, applied to every use case without an explicit resolution selector.
     */
    private fun resolveViewportAspectRatio(
        provider: ProcessCameraProvider,
        viewPort: ViewPort
    ): Int {
        val cameraInfo = try {
            provider.getCameraInfo(currentCameraSelector)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to resolve camera info for the current selector", e)
            return AspectRatio.RATIO_DEFAULT
        }

        return viewportAspectRatio(
            viewPort.aspectRatio.numerator,
            viewPort.aspectRatio.denominator,
            surfaceRotationToDegrees(viewPort.rotation),
            cameraInfo.sensorRotationDegrees,
            cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
        )
    }

    private fun configuredAspectRatioStrategy(
        config: CameraSessionConfiguration
    ): AspectRatioStrategy? = when (config.aspectRatio) {
        "4:3" -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        "16:9" -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else -> null
    }

    private fun previewResolutionSelector(
        config: CameraSessionConfiguration
    ): ResolutionSelector? = configuredAspectRatioStrategy(config)?.let { strategy ->
        ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()
    }

    /**
     * Resolves the still-capture resolution selector from the configured aspect ratio and
     * capture-resolution hint. With both options omitted this is the long-standing default:
     * 16:9 with automatic fallback.
     */
    private fun imageCaptureResolutionSelector(
        config: CameraSessionConfiguration
    ): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                configuredAspectRatioStrategy(config)
                    ?: AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )

        config.captureMaxDimension?.let { maxDimension ->
            // The bound size is expressed in the sensor's landscape-oriented coordinate
            // space: the longer edge is the width and the shorter edge derives from the
            // configured aspect ratio (16:9 by default). CLOSEST_LOWER_THEN_HIGHER picks the
            // largest supported resolution that does not exceed the bound, falling back to
            // the closest higher one - mirroring the iOS maxPhotoDimensions selection.
            val shorterEdge =
                if (config.aspectRatio == "4:3") maxDimension * 3 / 4
                else maxDimension * 9 / 16
            builder.setResolutionStrategy(
                ResolutionStrategy(
                    Size(maxDimension, shorterEdge),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
        }

        return builder.build()
    }

    /**
     * Points the non-preview use cases at the current display rotation. `Preview` is left
     * alone, since [PreviewView] owns its own transform.
     */
    private fun updateTargetRotation() {
        val rotation = previewView?.display?.rotation ?: return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    private fun displayManager(): DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private fun registerDisplayListener() {
        displayManager()?.registerDisplayListener(displayListener, mainHandler)
    }

    private fun unregisterDisplayListener() {
        displayManager()?.unregisterDisplayListener(displayListener)
    }

    /**
     * Builds an ML Kit barcode scanner for the specified formats.
     *
     * @param barcodeTypes Optional list of specific barcode format codes to detect.
     *                     If null, all supported formats are detected (backwards compatible).
     */
    private fun createBarcodeScanner(barcodeTypes: List<Int>? = null): BarcodeScanner {
        val options = if (!barcodeTypes.isNullOrEmpty()) {
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

        return BarcodeScanning.getClient(options)
    }

    /** Attaches [barcodeScanner] to [imageAnalysis]. Must happen before the use case is bound. */
    private fun attachBarcodeAnalyzer(imageAnalysis: ImageAnalysis, barcodeScanner: BarcodeScanner) {
        val previewView = this.previewView ?: return
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
        val analyzer = ViewReferencedAnalyzer(
            MlKitAnalyzer(
                listOf(barcodeScanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExecutor
            ) { result: MlKitAnalyzer.Result? ->
                processBarcodeResults(result, barcodeScanner, previewView, topOffset)
            }
        )

        barcodeAnalyzer = analyzer
        imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
    }

    /**
     * Supplies the barcode analyzer with the sensor-to-[PreviewView] matrix that its
     * `COORDINATE_SYSTEM_VIEW_REFERENCED` results are expressed in.
     */
    @MainThread
    private fun pushViewTransformToAnalyzer() {
        val analyzer = barcodeAnalyzer ?: return
        val previewView = this.previewView ?: return

        analyzer.updateTransform(previewView.sensorToViewTransform)
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
        val zoomState = camera?.cameraInfo?.zoomState?.value
            ?: return ZoomFactors(1.0f, 1.0f, 1.0f)

        return ZoomFactors(
            min = zoomState.minZoomRatio,
            max = zoomState.maxZoomRatio,
            current = zoomState.zoomRatio
        )
    }

    companion object {
        private const val TAG = "CameraView"
    }
}
