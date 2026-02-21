# Video Recording Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `startRecording()` and `stopRecording()` to the capacitor-camera-view plugin, returning the video file as a web-accessible temp file path on iOS, Android, and Web.

**Architecture:** Video recording piggybacks on the existing camera session (no re-init required). iOS uses `AVCaptureMovieFileOutput` added to the existing `AVCaptureSession`. Android uses CameraX `VideoCapture` use case via `LifecycleCameraController`. Web uses the browser `MediaRecorder` API on the existing `MediaStream`.

**Tech Stack:** TypeScript, Swift/AVFoundation, Kotlin/CameraX 1.4.2, MediaRecorder API

---

### Task 1: TypeScript API — Add types and interface methods

**Files:**
- Modify: `src/definitions.ts`

**Step 1: Add video recording types and methods to definitions.ts**

Add the following types after the `CaptureOptions` interface (around line 421):

```typescript
/**
 * Quality options for video recording.
 * @since 3.0.0
 */
export type VideoQuality = 'low' | 'medium' | 'high';

/**
 * Configuration options for video recording.
 * @since 3.0.0
 */
export interface VideoRecordingOptions {
  /**
   * Whether to record audio with the video.
   * Requires microphone permission.
   * @default false
   * @since 3.0.0
   */
  enableAudio?: boolean;

  /**
   * The quality preset for the recording.
   * @default 'medium'
   * @since 3.0.0
   */
  quality?: VideoQuality;
}

/**
 * Response from stopping a video recording.
 * @since 3.0.0
 */
export interface VideoRecordingResponse {
  /**
   * Web-accessible path to the recorded video file.
   * On web, this is a blob URL.
   * On iOS/Android, this is a path accessible via Capacitor's filesystem.
   * @since 3.0.0
   */
  webPath: string;
}
```

Add the following two methods to `CameraViewPlugin` interface (after `captureSample`, before `flipCamera`):

```typescript
  /**
   * Start recording video from the current camera.
   * Camera must be running. Throws if already recording.
   *
   * @param options - Optional recording configuration
   * @returns A promise that resolves when recording has started
   *
   * @since 3.0.0
   */
  startRecording(options?: VideoRecordingOptions): Promise<void>;

  /**
   * Stop the current video recording and return the result.
   * Throws if no recording is in progress.
   *
   * @returns A promise that resolves with the recorded video file path
   *
   * @since 3.0.0
   */
  stopRecording(): Promise<VideoRecordingResponse>;
```

**Step 2: Build TypeScript to verify types compile**

```bash
npm run build
```

Expected: Build succeeds (TypeScript compiles, web.ts will have unimplemented methods — that's OK for now)

**Step 3: Commit**

```bash
git add src/definitions.ts
git commit -m "feat(types): add VideoRecordingOptions, VideoRecordingResponse types and startRecording/stopRecording methods to plugin interface"
```

---

### Task 2: Web implementation — MediaRecorder API

**Files:**
- Modify: `src/web.ts`

**Step 1: Add recording state to the class**

In `CameraViewWeb`, after the existing private fields (around line 36), add:

```typescript
  // Recording state
  private mediaRecorder: MediaRecorder | null = null;
  private recordedChunks: Blob[] = [];
  private recordingResolve: ((response: VideoRecordingResponse) => void) | null = null;
  private recordingReject: ((error: Error) => void) | null = null;
```

**Step 2: Add `startRecording` method**

Add after the `captureSample` method:

```typescript
  /**
   * Start recording video using MediaRecorder API
   */
  async startRecording(options?: VideoRecordingOptions): Promise<void> {
    if (!this.#isRunning || !this.videoElement) {
      throw new Error('Camera is not running');
    }

    if (this.mediaRecorder) {
      throw new Error('Recording is already in progress');
    }

    try {
      let stream = this.stream;

      // If audio is requested, get a new stream with audio track
      if (options?.enableAudio && stream) {
        const audioStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        const audioTrack = audioStream.getAudioTracks()[0];
        const videoTracks = stream.getVideoTracks();
        stream = new MediaStream([...videoTracks, audioTrack]);
      }

      if (!stream) {
        throw new Error('No camera stream available');
      }

      this.recordedChunks = [];

      const mimeType = MediaRecorder.isTypeSupported('video/webm;codecs=vp9')
        ? 'video/webm;codecs=vp9'
        : 'video/webm';

      this.mediaRecorder = new MediaRecorder(stream, { mimeType });

      this.mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          this.recordedChunks.push(event.data);
        }
      };

      this.mediaRecorder.onstop = () => {
        const blob = new Blob(this.recordedChunks, { type: mimeType });
        const url = URL.createObjectURL(blob);
        this.recordedChunks = [];
        this.mediaRecorder = null;
        this.recordingResolve?.({ webPath: url });
        this.recordingResolve = null;
        this.recordingReject = null;
      };

      this.mediaRecorder.onerror = (event) => {
        this.mediaRecorder = null;
        this.recordedChunks = [];
        this.recordingReject?.(new Error('Recording error: ' + event));
        this.recordingResolve = null;
        this.recordingReject = null;
      };

      this.mediaRecorder.start(100); // Collect data in 100ms chunks
    } catch (err) {
      throw new Error(`Failed to start recording: ${this.formatError(err)}`);
    }
  }
```

**Step 3: Add `stopRecording` method**

Add after `startRecording`:

```typescript
  /**
   * Stop the current video recording
   */
  async stopRecording(): Promise<VideoRecordingResponse> {
    if (!this.mediaRecorder) {
      throw new Error('No recording is in progress');
    }

    return new Promise<VideoRecordingResponse>((resolve, reject) => {
      this.recordingResolve = resolve;
      this.recordingReject = reject;
      this.mediaRecorder?.stop();
    });
  }
```

**Step 4: Add cleanup for mediaRecorder in `stop()` and `handleOnDestroy()`**

In the `stop()` method, after stopping stream tracks, add:
```typescript
      // Stop any active recording
      if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
        this.mediaRecorder.stop();
        this.mediaRecorder = null;
      }
      this.recordedChunks = [];
```

**Step 5: Update imports in web.ts**

Add to the existing import statement:
```typescript
import type {
  // ... existing imports ...
  VideoRecordingOptions,
  VideoRecordingResponse,
} from './definitions';
```

**Step 6: Build to verify**

```bash
npm run build
```

Expected: No TypeScript errors.

**Step 7: Commit**

```bash
git add src/web.ts
git commit -m "feat(web): implement video recording using MediaRecorder API"
```

---

### Task 3: iOS — Extend TempFileManager with video file support

**Files:**
- Modify: `ios/Sources/CameraViewPlugin/TempFileManager.swift`

**Step 1: Add `createTempVideoFile()` method**

In `TempFileManager.swift`, after the `createTempImageFile()` method (around line 53), add:

```swift
/// Creates a temporary file URL for storing recorded videos and tracks it for cleanup.
///
/// - Returns: A URL pointing to the temporary video file location
public func createTempVideoFile() -> URL {
    let timestamp = Int(Date().timeIntervalSince1970 * 1000)
    let fileName = "\(tempFilePrefix)\(timestamp).mp4"
    let tempDir = FileManager.default.temporaryDirectory
    let fileURL = tempDir.appendingPathComponent(fileName)

    queue.sync {
        trackedFiles.insert(fileURL)
    }

    return fileURL
}
```

**Step 2: Commit**

```bash
git add ios/Sources/CameraViewPlugin/TempFileManager.swift
git commit -m "feat(ios): add createTempVideoFile() to TempFileManager"
```

---

### Task 4: iOS — CameraViewManager video recording extension

**Files:**
- Create: `ios/Sources/CameraViewPlugin/CameraViewManager+VideoRecording.swift`
- Modify: `ios/Sources/CameraViewPlugin/CameraViewManager.swift` (add stored properties)

**Step 1: Add video recording properties to CameraViewManager**

In `CameraViewManager.swift`, add after the `eventEmitter` property declaration (around line 80):

```swift
/// Movie file output for video recording.
internal let avMovieOutput = AVCaptureMovieFileOutput()

/// Callback invoked when video recording completes with the output URL or an error.
internal var videoRecordingCompletionHandler: ((URL?, Error?) -> Void)?

/// Whether audio was requested for the current recording.
internal var recordingWithAudio = false
```

**Step 2: Create the video recording extension file**

Create `ios/Sources/CameraViewPlugin/CameraViewManager+VideoRecording.swift`:

```swift
import AVFoundation
import Foundation

extension CameraViewManager: AVCaptureFileOutputRecordingDelegate {

    // MARK: - Public API

    /// Starts video recording to a temporary file.
    ///
    /// - Parameters:
    ///   - enableAudio: Whether to include audio in the recording
    ///   - quality: The desired recording quality preset
    ///   - completion: Called with the output file URL or an error
    public func startRecording(
        enableAudio: Bool,
        completion: @escaping (Error?) -> Void
    ) {
        guard captureSession.isRunning else {
            completion(CameraError.sessionNotRunning)
            return
        }

        guard !avMovieOutput.isRecording else {
            completion(CameraError.recordingAlreadyInProgress)
            return
        }

        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            self.captureSession.beginConfiguration()

            // Add movie output if not already added
            if !self.captureSession.outputs.contains(self.avMovieOutput) {
                guard self.captureSession.canAddOutput(self.avMovieOutput) else {
                    self.captureSession.commitConfiguration()
                    DispatchQueue.main.async {
                        completion(CameraError.outputAdditionFailed)
                    }
                    return
                }
                self.captureSession.addOutput(self.avMovieOutput)
            }

            // Add audio input if requested
            if enableAudio {
                self.addAudioInput()
            }

            self.captureSession.commitConfiguration()

            // Set orientation on the movie output connection
            if let connection = self.avMovieOutput.connection(with: .video),
               let previewConnection = self.videoPreviewLayer.connection {
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = previewConnection.videoOrientation
                }
                if connection.isVideoMirroringSupported {
                    connection.isVideoMirrored = self.currentCameraDevice?.position == .front
                }
            }

            // Create a temp file URL
            let outputURL = TempFileManager.shared.createTempVideoFile()

            // Start recording
            self.avMovieOutput.startRecording(to: outputURL, recordingDelegate: self)

            DispatchQueue.main.async {
                completion(nil)
            }
        }
    }

    /// Stops the current video recording.
    ///
    /// - Parameter completion: Called with the output file URL or an error
    public func stopRecording(completion: @escaping (URL?, Error?) -> Void) {
        guard avMovieOutput.isRecording else {
            completion(nil, CameraError.noRecordingInProgress)
            return
        }

        videoRecordingCompletionHandler = completion
        avMovieOutput.stopRecording()
    }

    // MARK: - AVCaptureFileOutputRecordingDelegate

    public func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        let handler = videoRecordingCompletionHandler
        videoRecordingCompletionHandler = nil

        // Remove audio input if it was added for this recording
        if recordingWithAudio {
            sessionQueue.async { [weak self] in
                guard let self = self else { return }
                self.captureSession.beginConfiguration()
                self.removeAudioInput()
                self.captureSession.commitConfiguration()
            }
            recordingWithAudio = false
        }

        if let error = error {
            DispatchQueue.main.async {
                handler?(nil, error)
            }
            return
        }

        DispatchQueue.main.async {
            handler?(outputFileURL, nil)
        }
    }

    // MARK: - Private Helpers

    /// Adds microphone input to the capture session.
    private func addAudioInput() {
        // Check if audio input already exists
        let hasAudioInput = captureSession.inputs.contains { input in
            (input as? AVCaptureDeviceInput)?.device.hasMediaType(.audio) == true
        }

        guard !hasAudioInput else { return }

        guard let microphone = AVCaptureDevice.default(for: .audio) else { return }

        do {
            let audioInput = try AVCaptureDeviceInput(device: microphone)
            if captureSession.canAddInput(audioInput) {
                captureSession.addInput(audioInput)
                recordingWithAudio = true
            }
        } catch {
            print("Failed to add audio input: \(error.localizedDescription)")
        }
    }

    /// Removes microphone input from the capture session.
    private func removeAudioInput() {
        captureSession.inputs
            .compactMap { $0 as? AVCaptureDeviceInput }
            .filter { $0.device.hasMediaType(.audio) }
            .forEach { captureSession.removeInput($0) }
    }
}
```

**Step 3: Add new error cases to CameraError**

Open `ios/Sources/CameraViewPlugin/CameraError.swift` and add the missing error cases. Check what's already there first, then add:

```swift
case recordingAlreadyInProgress
case noRecordingInProgress
```

**Step 4: Build iOS to verify**

```bash
npm run verify:ios
```

Expected: Build succeeds.

**Step 5: Commit**

```bash
git add ios/Sources/CameraViewPlugin/
git commit -m "feat(ios): implement video recording using AVCaptureMovieFileOutput"
```

---

### Task 5: iOS — Plugin bridge for video recording

**Files:**
- Modify: `ios/Sources/CameraViewPlugin/CameraViewPlugin.swift`

**Step 1: Add plugin method declarations**

In `CameraViewPlugin.swift`, add to the `pluginMethods` array (after `captureSample`, before `getAvailableDevices`):

```swift
CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise),
```

**Step 2: Add startRecording handler**

Add after the `captureSample` method:

```swift
@objc func startRecording(_ call: CAPPluginCall) {
    let enableAudio = call.getBool("enableAudio") ?? false

    if enableAudio {
        // Check microphone permission before starting
        maybeRequestMicrophoneAccess { [weak self] granted in
            guard granted else {
                call.reject("Microphone access denied")
                return
            }
            self?.doStartRecording(call: call, enableAudio: true)
        }
    } else {
        doStartRecording(call: call, enableAudio: false)
    }
}

private func doStartRecording(call: CAPPluginCall, enableAudio: Bool) {
    implementation.startRecording(enableAudio: enableAudio) { [weak self] error in
        if let error = error {
            call.reject("Failed to start recording", nil, error)
            return
        }
        call.resolve()
    }
}
```

**Step 3: Add stopRecording handler**

Add after `startRecording`:

```swift
@objc func stopRecording(_ call: CAPPluginCall) {
    implementation.stopRecording { [weak self] (outputURL, error) in
        if let error = error {
            call.reject("Failed to stop recording", nil, error)
            return
        }

        guard let outputURL = outputURL else {
            call.reject("No output file URL")
            return
        }

        guard let webPath = self?.bridge?.portablePath(fromLocalURL: outputURL)?.absoluteString else {
            call.reject("Failed to create web-accessible path")
            return
        }

        call.resolve(["webPath": webPath])
    }
}
```

**Step 4: Add microphone permission helper**

Add after the existing `maybeRequestCameraAccess` method:

```swift
private func maybeRequestMicrophoneAccess(completion: @escaping (Bool) -> Void) {
    let status = AVCaptureDevice.authorizationStatus(for: .audio)
    if status == .authorized {
        completion(true)
    } else if status == .notDetermined {
        AVCaptureDevice.requestAccess(for: .audio) { granted in
            DispatchQueue.main.async {
                completion(granted)
            }
        }
    } else {
        completion(false)
    }
}
```

**Step 5: Build iOS**

```bash
npm run verify:ios
```

Expected: Build succeeds.

**Step 6: Commit**

```bash
git add ios/Sources/CameraViewPlugin/CameraViewPlugin.swift
git commit -m "feat(ios): add startRecording/stopRecording plugin bridge methods"
```

---

### Task 6: Android — Add camera-video dependency

**Files:**
- Modify: `android/build.gradle`
- Modify: `android/src/main/AndroidManifest.xml`

**Step 1: Add camera-video to dependencies**

In `android/build.gradle`, in the CameraX dependencies section, add:

```gradle
implementation "androidx.camera:camera-video:${camerax_version}"
```

**Step 2: Add RECORD_AUDIO permission to AndroidManifest.xml**

Open `android/src/main/AndroidManifest.xml` and check what's there. Add:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**Step 3: Commit**

```bash
git add android/build.gradle android/src/main/AndroidManifest.xml
git commit -m "feat(android): add camera-video dependency and RECORD_AUDIO permission"
```

---

### Task 7: Android — Video recording in CameraView.kt

**Files:**
- Modify: `android/src/main/java/com/michaelwolz/capacitorcameraview/CameraView.kt`

**Step 1: Add new imports**

Add to the import section of `CameraView.kt`:

```kotlin
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.core.CameraController
import androidx.camera.view.video.AudioConfig
```

**Step 2: Add recording state property**

In `CameraView` class, after the existing state properties, add:

```kotlin
// Active video recording
private var activeRecording: Recording? = null
```

**Step 3: Add `startRecordingAsync` method**

Add after `captureSampleFromPreview` method:

```kotlin
/**
 * Starts video recording to a temporary file.
 * @param enableAudio Whether to record audio. Requires RECORD_AUDIO permission.
 * @param quality The recording quality preset.
 */
suspend fun startRecordingAsync(
    enableAudio: Boolean,
    quality: String = "medium"
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

            val outputOptions = FileOutputOptions.Builder(tempFile).build()
            val audioConfig = if (enableAudio) {
                AudioConfig.create(true)
            } else {
                AudioConfig.AUDIO_DISABLED
            }

            activeRecording = controller.startRecording(
                outputOptions,
                audioConfig,
                cameraExecutor
            ) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Video recording started")
                        if (continuation.isActive) {
                            continuation.resume(CameraResult.Success(Unit))
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording finalized with error: ${event.error}")
                        } else {
                            Log.d(TAG, "Recording finalized successfully: ${event.outputResults.outputUri}")
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            // Restore normal use cases on error
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            continuation.resume(CameraResult.Error(e))
        }
    }
}
```

**Step 4: Add `stopRecordingAsync` method**

Add after `startRecordingAsync`:

```kotlin
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

        val controller = cameraController

        // Override the finalize handler to capture the result
        activeRecording = null

        // We need a different approach - intercept the finalize event
        // Stop the recording and wait for the Finalize event
        val tempFile = File.createTempFile("camera_recording_stop_", ".mp4", context.cacheDir)

        // Since we already set the output file when starting, we need to
        // stop and capture the URI from the finalize event
        // The recording was started with a specific output file, so we need
        // to track that file. Let's use a different approach with a pending file.

        recording.stop()
        // Restore normal use cases
        controller?.setEnabledUseCases(CameraController.IMAGE_CAPTURE)

        // The finalize event will be delivered to the listener set in startRecordingAsync
        // We need a way to communicate the result back. Use a shared mechanism.
        // See implementation note in the stop recording handler.
        continuation.resume(CameraResult.Error(Exception("Use pendingStopRecordingContinuation pattern")))
    }
}
```

Wait — the CameraX recording event callback architecture requires a different approach. Let me restructure:

**Step 4 (revised): Restructure to properly handle async recording lifecycle**

Replace the above with a cleaner implementation. In `CameraView.kt`, add a continuation for stop recording:

```kotlin
// Continuation for stop recording result
private var pendingStopContinuation: ((CameraResult<JSObject>) -> Unit)? = null

// Track the output file for the current recording
private var currentRecordingFile: File? = null
```

Then update `startRecordingAsync`:

```kotlin
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
            controller.setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE
            )

            val tempFile = File.createTempFile("camera_recording_", ".mp4", context.cacheDir)
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
                        val pendingCallback = pendingStopContinuation
                        pendingStopContinuation = null

                        if (event.hasError()) {
                            Log.e(TAG, "Recording error: ${event.error}")
                            pendingCallback?.invoke(CameraResult.Error(Exception("Recording failed with error: ${event.error}")))
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
                                pendingCallback?.invoke(CameraResult.Success(result))
                            } else {
                                pendingCallback?.invoke(CameraResult.Error(Exception("Recording file not found")))
                            }
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            cameraController?.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            continuation.resume(CameraResult.Error(e))
        }
    }
}

suspend fun stopRecordingAsync(): CameraResult<JSObject> = suspendCancellableCoroutine { continuation ->
    mainHandler.post {
        val recording = activeRecording
        if (recording == null) {
            continuation.resume(CameraResult.Error(Exception("No recording is in progress")))
            return@post
        }

        pendingStopContinuation = { result ->
            continuation.resume(result)
        }

        activeRecording = null
        recording.stop()

        // Restore image capture use case after stopping
        cameraController?.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
    }
}
```

**Step 5: Build Android**

```bash
npm run verify:android
```

Expected: Build succeeds.

**Step 6: Commit**

```bash
git add android/src/main/java/com/michaelwolz/capacitorcameraview/CameraView.kt
git commit -m "feat(android): implement video recording using CameraX VideoCapture"
```

---

### Task 8: Android — Plugin bridge for video recording

**Files:**
- Modify: `android/src/main/java/com/michaelwolz/capacitorcameraview/CameraViewPlugin.kt`

**Step 1: Add microphone permission to plugin annotation**

Update the `@CapacitorPlugin` annotation:

```kotlin
@CapacitorPlugin(
    name = "CameraView",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microphone")
    ]
)
```

Add `Manifest.permission.RECORD_AUDIO` to imports if not already there:
```kotlin
import android.Manifest
```
(Already imported)

**Step 2: Add `startRecording` plugin method**

Add after the `captureSample` method:

```kotlin
@PluginMethod
fun startRecording(call: PluginCall) {
    val enableAudio = call.getBoolean("enableAudio") ?: false

    if (enableAudio) {
        // Check/request RECORD_AUDIO permission before starting
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "microphonePermsCallback")
            return
        }
    }

    doStartRecording(call, enableAudio)
}

@PermissionCallback
private fun microphonePermsCallback(call: PluginCall) {
    if (getPermissionState("microphone") == PermissionState.GRANTED) {
        val enableAudio = call.getBoolean("enableAudio") ?: false
        doStartRecording(call, enableAudio)
    } else {
        call.reject("Microphone permission is required for audio recording")
    }
}

private fun doStartRecording(call: PluginCall, enableAudio: Boolean) {
    pluginScope.launch {
        implementation.startRecordingAsync(enableAudio).fold(
            onSuccess = { call.resolve() },
            onError = { error ->
                call.reject("Failed to start recording: ${error.message}", error)
            }
        )
    }
}
```

**Step 3: Add `stopRecording` plugin method**

Add after `startRecording`:

```kotlin
@PluginMethod
fun stopRecording(call: PluginCall) {
    pluginScope.launch {
        implementation.stopRecordingAsync().fold(
            onSuccess = { result ->
                call.resolve(result)
            },
            onError = { error ->
                call.reject("Failed to stop recording: ${error.message}", error)
            }
        )
    }
}
```

**Step 4: Build Android**

```bash
npm run verify:android
```

Expected: Build succeeds.

**Step 5: Commit**

```bash
git add android/src/main/java/com/michaelwolz/capacitorcameraview/CameraViewPlugin.kt
git commit -m "feat(android): add startRecording/stopRecording plugin bridge methods with microphone permission"
```

---

### Task 9: Check CameraError.swift and add missing cases

**Files:**
- Modify: `ios/Sources/CameraViewPlugin/CameraError.swift`

**Step 1: Read the file first, then add any missing cases**

Open `ios/Sources/CameraViewPlugin/CameraError.swift` and check existing error cases. Add:
- `case recordingAlreadyInProgress` - if not present
- `case noRecordingInProgress` - if not present

Error descriptions to add:
```swift
case .recordingAlreadyInProgress:
    return "A video recording is already in progress"
case .noRecordingInProgress:
    return "No video recording is in progress"
```

**Step 2: Verify iOS build**

```bash
npm run verify:ios
```

**Step 3: Final build verification**

```bash
npm run build
```

Expected: All builds succeed.

**Step 4: Final commit**

```bash
git add .
git commit -m "feat: complete video recording implementation across iOS, Android, and Web"
```

---

## Notes

- **iOS Info.plist**: App developers must add `NSMicrophoneUsageDescription` to their app's `Info.plist` when using `enableAudio: true`. The plugin cannot do this automatically.
- **Web format**: Browser MediaRecorder uses WebM format. MP4 is not universally supported as an output format in browsers.
- **Android AudioConfig**: The `AudioConfig.create(true)` API enforces that the app has RECORD_AUDIO permission — without it, an exception will be thrown.
- **CameraX IMAGE_CAPTURE + VIDEO_CAPTURE**: Not all Android devices support both simultaneously. On unsupported devices, CameraX will automatically disable one. This is a known CameraX limitation.
