import AVFoundation
import CoreImage
import Foundation
import Metal
import UIKit
import WebKit

/// Supported camera device types for the capture session.
internal let SUPPORTED_CAMERA_DEVICE_TYPES: [AVCaptureDevice.DeviceType] = [
    .builtInWideAngleCamera,
    .builtInUltraWideCamera,
    .builtInTelephotoCamera,
    .builtInDualCamera,
    .builtInDualWideCamera,
    .builtInTripleCamera,
    .builtInTrueDepthCamera
]

/// A camera implementation that handles camera session management and photo capture.
@objc public class CameraViewManager: NSObject {
    // MARK: - Shared Resources
    
    /// Metal-backed CIContext singleton for efficient image processing.
    /// Creating CIContext per frame is extremely expensive (80%+ CPU waste).
    /// This shared instance uses Metal for GPU acceleration when available.
    internal static let sharedCIContext: CIContext = {
        if let metalDevice = MTLCreateSystemDefaultDevice() {
            return CIContext(mtlDevice: metalDevice, options: [.cacheIntermediates: false])
        }
        return CIContext(options: [.useSoftwareRenderer: true])
    }()
    
    // MARK: - Capture Session Components
    
    internal let captureSession = AVCaptureSession()
    internal let avPhotoOutput = AVCapturePhotoOutput()
    internal let avVideoDataOutput = AVCaptureVideoDataOutput()
    internal let videoPreviewLayer = AVCaptureVideoPreviewLayer()
    
    /// Dedicated queue for all capture session operations.
    /// Using a consistent queue prevents race conditions and ensures thread safety.
    internal let sessionQueue = DispatchQueue(
        label: "com.michaelwolz.capacitorcameraview.session",
        qos: .userInitiated
    )
    
    /// Reusable queue for sample buffer processing during snapshot capture.
    /// Creating a new queue per snapshot causes memory allocation churn.
    internal let sampleBufferQueue = DispatchQueue(
        label: "com.michaelwolz.capacitorcameraview.sampleBuffer",
        qos: .userInitiated
    )
    
    /// The currently active camera device.
    internal var currentCameraDevice: AVCaptureDevice?
    
    /// List of preferred camera devices, this overrides the SUPPORTED_CAMERA_DEVICE_TYPES for the capture session
    private var preferredCameraDeviceTypes = SUPPORTED_CAMERA_DEVICE_TYPES
    
    /// Currently selected flash mode.
    private var flashMode: AVCaptureDevice.FlashMode = .auto
    
    /// Whether the current session opted into the virtual triple camera, kept so
    /// a camera flip back to the rear position resolves it again rather than
    /// falling back to a physical lens. Confined to `sessionQueue`.
    private var useTripleCameraIfAvailable = false

    /// Reference to the webView that the Capacitor plugin's preview layer is
    /// shown on. Confined to the main queue: written in `attachPreview`'s
    /// main-queue block and cleared in `stopSession`'s, and read only from
    /// main-queue contexts (`revealPreview`, rotation frame updates). Keeping
    /// every access on one queue avoids racing the
    /// session-queue callers that drive session setup/teardown.
    internal var webView: UIView?

    /// Whether the session was explicitly stopped via `stopSession`, as opposed
    /// to being paused by backgrounding or an interruption. Confined to
    /// `sessionQueue` (set at the top of `startSession`'s and `stopSession`'s
    /// queue blocks) so lifecycle restart paths (which run on `sessionQueue`)
    /// can check it directly instead of reaching for the main-queue-confined
    /// `webView` reference from the wrong queue.
    internal var isSessionStoppedByUser = true

    /// Serializes assignment and consumption of the capture completion handlers
    /// below. The handlers are written on the Capacitor call thread and read /
    /// cleared on AVFoundation delegate queues, so all access must go through
    /// this lock to avoid one capture silently clobbering another's handler.
    internal let captureHandlerLock = NSLock()

    /// Callback for when photo capture completes with raw Data (optimized API).
    /// This avoids double JPEG encoding by returning the camera's JPEG data directly.
    /// Access only while holding `captureHandlerLock`.
    internal var photoDataCaptureHandler: ((Data?, Error?) -> Void)?

    /// Callback for when snapshot capture completes.
    /// Access only while holding `captureHandlerLock`.
    internal var snapshotCompletionHandler: ((UIImage?, Error?) -> Void)?

    /// Timeout that fails an in-flight `captureSnapshot` if no frame is delivered,
    /// so its JS promise can never hang when the session stalls (backgrounding,
    /// interruption). Access only while holding `captureHandlerLock`.
    internal var snapshotTimeoutWorkItem: DispatchWorkItem?

    /// How long to wait for a video frame before failing a snapshot capture.
    private let snapshotTimeout: TimeInterval = 2.0

    /// Work item that restores continuous auto focus/exposure after a one-shot
    /// tap-to-focus (see `CameraViewManager+Focus`). Cancelled and replaced on
    /// each new focus point so rapid taps don't reset a later focus. Confined to
    /// `sessionQueue`.
    internal var focusResetWorkItem: DispatchWorkItem?
    
    /// Emits typed camera events to the delegate.
    internal let eventEmitter = CameraEventEmitter()

    /// Dedicated serial queue for barcode metadata delivery so the metadata
    /// delegate does not run on (and stall) the main queue.
    internal let barcodeMetadataQueue = DispatchQueue(
        label: "com.michaelwolz.capacitorcameraview.barcodeMetadata",
        qos: .userInitiated
    )

    /// Timestamps (seconds) of recently emitted barcodes keyed by their dedupe
    /// key (value + type), tracked per key so multiple codes in frame can't
    /// alternate and defeat the suppression window. Accessed only on the serial
    /// `barcodeMetadataQueue`.
    internal var recentBarcodeEmitTimes: [String: TimeInterval] = [:]
    
    /// Movie file output for video recording.
    internal let avMovieOutput = AVCaptureMovieFileOutput()
    
    /// Callback invoked when video recording completes with the output URL or an error.
    internal var videoRecordingCompletionHandler: ((URL?, Error?) -> Void)?
    
    /// Whether audio was added to the session for the current recording.
    internal var recordingWithAudio = false

    /// Session preset used before starting recording, restored when recording ends.
    internal var sessionPresetBeforeRecording: AVCaptureSession.Preset?

    /// Capture-resolution hint of the current session (longer edge, pixels).
    /// Re-applied to the photo output whenever the active format changes; see
    /// `applyConfiguredMaxPhotoDimensions()`. Confined to `sessionQueue`.
    internal var configuredCaptureMaxDimension: Int?

    /// Aspect ratio ("4:3"/"16:9") of the current session, kept so the session
    /// preset can be re-resolved against the new device on a camera flip.
    /// Confined to `sessionQueue`.
    internal var configuredAspectRatio: String?

    /// Backing storage for the iOS 17+ rotation coordinator. Stored as `Any?`
    /// because `AVCaptureDevice.RotationCoordinator` is iOS 17+ while this class
    /// targets iOS 16; cast under `#available` via the `rotationCoordinator`
    /// accessor before use. Access only while holding `rotationCoordinatorLock`.
    internal var rotationCoordinatorStorage: Any?

    /// Serializes access to `rotationCoordinatorStorage`. The coordinator is
    /// written on the main queue (device changes) but read from the Capacitor
    /// call thread (photo/snapshot capture) and the session queue (recording);
    /// an unsynchronized ARC reassignment racing a read is undefined behavior,
    /// so all access goes through the `rotationCoordinator` accessor which
    /// takes this lock.
    internal let rotationCoordinatorLock = NSLock()

    /// KVO observation of the rotation coordinator's preview angle, used to keep
    /// the preview level with the horizon as the device rotates. Invalidated and
    /// replaced whenever the active device changes.
    internal var rotationObservation: NSKeyValueObservation?

    /// Token for the block-based `UIDevice.orientationDidChangeNotification`
    /// observer registered in `setupOrientationObserver` (iOS 16 legacy path
    /// only). `NotificationCenter.removeObserver(self)` does not remove
    /// block-based observers, so this token must be removed explicitly via
    /// `removeOrientationObserver` to avoid leaking it for the process lifetime.
    internal var orientationObserverToken: NSObjectProtocol?

    override public init() {
        super.init()
        setupOrientationObserver()
        setupAppLifecycleObservers()
        setupInterruptionObservers()
    }
    
    deinit {
        // Stop synchronously and directly here rather than calling `stopSession()`,
        // which hops onto `sessionQueue` via `[weak self]`: by the time that block
        // runs, this instance has already finished deallocating and `self` reads
        // as nil, so the scheduled cleanup silently never executes.
        if captureSession.isRunning {
            captureSession.stopRunning()
        }
        rotationObservation?.invalidate()
        removeOrientationObserver()
        NotificationCenter.default.removeObserver(self)
    }
    
    // MARK: - Plugin API
    
    /// Starts the capture session and attaches the preview to the given view.
    ///
    /// The WebView is only made transparent once the session is running, so the
    /// app's own UI stays visible while the camera powers up.
    ///
    /// Rejects with `CameraError.sessionAlreadyRunning` if a session is already
    /// running — callers must `stop()` first. If setup fails *after*
    /// `startRunning()` has succeeded, the session and preview are torn down
    /// before the error is forwarded, so a rejected start never leaves a live
    /// session behind.
    ///
    /// - Parameters:
    ///   - configuration: The session configuration to apply.
    ///   - webView: The view the camera preview is inserted behind.
    ///   - completion: A closure called when the session setup completes with an optional error.
    public func startSession(
        configuration: CameraSessionConfiguration,
        webView: UIView,
        completion: @escaping (Error?) -> Void
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            // Checked on the session queue, not the caller thread, so a start()
            // still queued behind another can't slip past the guard.
            guard !self.captureSession.isRunning else {
                DispatchQueue.main.async {
                    completion(CameraError.sessionAlreadyRunning)
                }
                return
            }

            // Stored after the rejection guard so a rejected start() can't alter
            // which device a later flipCamera() picks.
            if let preferredCameraDeviceTypes = configuration.preferredCameraDeviceTypes {
                self.preferredCameraDeviceTypes = convertToNativeCameraTypes(
                    preferredCameraDeviceTypes
                )
            }

            do {
                try self.initiateCaptureSession(configuration: configuration)

                // Applied once the transaction has committed, since the supported
                // zoom range depends on the now-final active format.
                try self.applyInitialZoom(configuration.zoomFactor)
            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
                return
            }

            self.applyConfiguredMaxPhotoDimensions()

            // `fit` letterboxes the whole frame; the default `cover` center-crops it.
            let videoGravity: AVLayerVideoGravity =
                configuration.previewScaleMode == "fit" ? .resizeAspect : .resizeAspectFill
            self.attachPreview(to: webView, videoGravity: videoGravity) {
                // attachPreview completes on the main thread; hop back so all
                // session work stays serialized on the session queue.
                self.sessionQueue.async { [weak self] in
                    guard let self = self else { return }

                    self.captureSession.startRunning()

                    // Only now is the session user-started, so lifecycle restart
                    // paths know they may bring it back if it stops on its own.
                    self.isSessionStoppedByUser = false

                    self.revealPreview()

                    // Handle barcode detection after session is running
                    if configuration.enableBarcodeDetection {
                        do {
                            try self.enableBarcodeDetection(barcodeTypes: configuration.barcodeTypes)
                        } catch {
                            // `startRunning()` already succeeded, so forwarding
                            // the error as-is would strand a live session and a
                            // transparent WebView, and the guard above would then
                            // reject every retry. `stopSession` only enqueues onto
                            // `sessionQueue`, so calling it from here queues the
                            // teardown behind us instead of deadlocking.
                            self.stopSession { completion(error) }
                            return
                        }
                    }

                    // Complete already because the camera is ready to be used
                    DispatchQueue.main.async {
                        completion(nil)
                    }
                }
            }
        }
    }
    
    /// Stops the current capture session
    public func stopSession(completion: (() -> Void)? = nil) {
        sessionQueue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async {
                    completion?()
                }
                return
            }

            // Record the user's intent to stop before checking `isRunning` so
            // any lifecycle restart block that was already queued behind this
            // one (or gets queued after it) won't bring the session back up.
            self.isSessionStoppedByUser = true

            // Only the capture-session teardown is guarded on the running state.
            // The preview-layer / WebView / rotation-state teardown below must
            // ALWAYS run: while backgrounded the session is already stopped but
            // the WebView is still transparent with the preview layer attached.
            if self.captureSession.isRunning {
                if self.avMovieOutput.isRecording {
                    // Capture and clear the handler a concurrent `stopRecording()`
                    // is waiting on so its promise rejects instead of hanging —
                    // the recording delegate finds no handler once cleared here.
                    // `recordingWithAudio` is deliberately left alone; the
                    // finalize delegate reads it and owns clearing it.
                    let pendingRecordingHandler = self.videoRecordingCompletionHandler
                    self.avMovieOutput.stopRecording()
                    self.videoRecordingCompletionHandler = nil

                    if let pendingRecordingHandler = pendingRecordingHandler {
                        DispatchQueue.main.async {
                            pendingRecordingHandler(nil, CameraError.sessionNotRunning)
                        }
                    }
                }

                self.captureSession.stopRunning()

                // Reset barcode dedupe state so a restarted session emits immediately
                self.resetBarcodeDedupeState()
            }

            DispatchQueue.main.async { [weak self] in
                guard let self = self else {
                    completion?()
                    return
                }
                self.videoPreviewLayer.removeFromSuperlayer()
                self.webView?.isOpaque = true
                self.webView?.backgroundColor = nil
                self.webView = nil

                // Release rotation state so a stopped session doesn't keep the
                // coordinator (and its device reference) alive. Recreated by
                // `configureRotationHandling` on the next session start.
                self.rotationObservation?.invalidate()
                self.rotationObservation = nil
                if #available(iOS 17.0, *) {
                    self.rotationCoordinator = nil
                }

                completion?()
            }
        }
    }
    
    /// Checks if the capture session is currently running.
    public func isRunning() -> Bool {
        return captureSession.isRunning
    }
    
    /// Captures a photo and returns the raw JPEG data directly.
    /// This optimized method avoids double JPEG encoding by returning the camera's
    /// native JPEG data instead of converting through UIImage.
    ///
    /// - Parameter completion: Called with the captured JPEG data or an error.
    public func capturePhotoData(completion: @escaping (Data?, Error?) -> Void) {
        guard let cameraDevice = currentCameraDevice else {
            completion(nil, CameraError.cameraUnavailable)
            return
        }
        
        guard captureSession.isRunning else {
            completion(nil, CameraError.sessionNotRunning)
            return
        }

        // Reserve the photo capture slot before initiating the capture so a
        // concurrent capture cannot clobber this handler. Reject instead of
        // overwriting when one is already in flight.
        captureHandlerLock.lock()
        guard photoDataCaptureHandler == nil else {
            captureHandlerLock.unlock()
            completion(nil, CameraError.captureInProgress)
            return
        }
        photoDataCaptureHandler = completion
        captureHandlerLock.unlock()

        let photoSettings = AVCapturePhotoSettings()
        if cameraDevice.hasFlash {
            photoSettings.flashMode = flashMode
        } else {
            photoSettings.flashMode = .off
        }

        // Ensure proper orientation
        if let photoConnection = avPhotoOutput.connection(with: .video) {
            applyCaptureOrientation(to: photoConnection)
        }

        // Handler is assigned above, before the capture is initiated.
        avPhotoOutput.capturePhoto(with: photoSettings, delegate: self)
    }

    /// Capture a snapshot of the current camera view. This is faster than actually processing a
    /// photo via capturePhoto
    /// - Parameter completion: called with the captured UIImage or an error.
    public func captureSnapshot(
        completion: @escaping (UIImage?, Error?) -> Void
    ) {
        guard currentCameraDevice != nil else {
            completion(nil, CameraError.cameraUnavailable)
            return
        }
        
        guard captureSession.isRunning else {
            completion(nil, CameraError.sessionNotRunning)
            return
        }

        // Reserve the snapshot slot and arm the timeout before starting frame
        // delivery, so a concurrent snapshot cannot clobber this handler and the
        // promise can never hang if no frame arrives.
        let timeoutWorkItem = DispatchWorkItem { [weak self] in
            guard let self = self,
                  let handler = self.consumeSnapshotHandler() else { return }
            self.avVideoDataOutput.setSampleBufferDelegate(nil, queue: nil)
            handler(nil, CameraError.captureTimeout)
        }

        captureHandlerLock.lock()
        guard snapshotCompletionHandler == nil else {
            captureHandlerLock.unlock()
            completion(nil, CameraError.captureInProgress)
            return
        }
        snapshotCompletionHandler = completion
        snapshotTimeoutWorkItem = timeoutWorkItem
        captureHandlerLock.unlock()

        // Ensure proper orientation
        if let videoConnection = avVideoDataOutput.connection(with: .video) {
            applyCaptureOrientation(to: videoConnection)
        }

        sampleBufferQueue.asyncAfter(
            deadline: .now() + snapshotTimeout,
            execute: timeoutWorkItem
        )

        // Set the delegate last, once the handler and timeout are in place, to
        // begin single-frame capture on the reusable queue.
        avVideoDataOutput.setSampleBufferDelegate(
            self,
            queue: sampleBufferQueue
        )
    }

    /// Flips the camera to the opposite position (front to back or back to front).
    ///
    /// The input swap runs on the session queue inside a single configuration
    /// transaction, so the session is never left without an input.
    ///
    /// Rejects while a recording is active: `setInput` tears down all inputs
    /// (including the microphone) and only re-adds the video input, which would
    /// silently drop audio from an in-progress recording.
    ///
    /// - Parameter completion: Called on the main thread with an optional error.
    public func flipCamera(completion: @escaping (Error?) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            guard !self.avMovieOutput.isRecording else {
                DispatchQueue.main.async { completion(CameraError.recordingAlreadyInProgress) }
                return
            }

            let currentPosition: AVCaptureDevice.Position =
            self.currentCameraDevice?.position ?? .back
            let newPosition: AVCaptureDevice.Position =
            currentPosition == .back ? .front : .back

            self.captureSession.beginConfiguration()
            defer { self.captureSession.commitConfiguration() }

            do {
                let newCamera = try self.getCameraDevice(for: newPosition)

                // Drop to the universally supported .photo baseline before the
                // input swap: the session may run a 16:9 preset the new device
                // does not support, which would make `canAddInput` fail. The
                // configured aspect ratio is re-resolved right after, validated
                // against the new device.
                if self.captureSession.canSetSessionPreset(.photo) {
                    self.captureSession.sessionPreset = .photo
                }

                try self.setInput(with: newCamera)
                self.applySessionPreset(forAspectRatio: self.configuredAspectRatio)
            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
                return
            }

            // Both steps below depend on the new device's active format, which is
            // only final once the input swap has committed — hence the next
            // session-queue block. The call is resolved from there so the flip
            // isn't reported as done while the preview is still settling.
            self.sessionQueue.async { [weak self] in
                guard let self = self else { return }

                // The new device's format resets the photo output's
                // maxPhotoDimensions.
                self.applyConfiguredMaxPhotoDimensions()

                // Normalize the new device's zoom domain: flipping back to a
                // virtual rear camera would otherwise sit at its raw 1.0, which
                // is the ultra-wide field of view.
                do {
                    try self.applyInitialZoom(nil)
                } catch {
                    cameraViewLogger.error(
                        "Failed to normalize zoom after camera flip: \(error.localizedDescription, privacy: .public)"
                    )
                }

                DispatchQueue.main.async {
                    completion(nil)
                }
            }
        }
    }
    
    /// Sets the flash mode for the currently active camera device.
    ///
    /// - Parameter mode: The desired flash mode (.on, .of, or .auto).
    /// - Throws: An error if the flash mode cannot be set or is not supported.
    public func setFlashMode(_ mode: AVCaptureDevice.FlashMode) throws {
        guard let camera = currentCameraDevice else {
            throw CameraError.cameraUnavailable
        }
        guard camera.hasFlash else { throw CameraError.unsupportedFlashMode }
        guard avPhotoOutput.supportedFlashModes.contains(mode)
        else {
            throw CameraError.unsupportedFlashMode
        }
        
        flashMode = mode
    }
    
    /// Gets the current flash mode for the current camera device.
    public func getFlashMode() -> AVCaptureDevice.FlashMode {
        return flashMode
    }
    
    /// Gets the supported flash modes for the current camera device.
    ///
    /// - Returns: An array of supported flash modes, fallback is .off
    public func getSupportedFlashModes() -> [AVCaptureDevice.FlashMode] {
        if let camera = currentCameraDevice, camera.hasFlash {
            return avPhotoOutput.supportedFlashModes
        }
        
        return [.off]
    }
    
    /// Checks if torch is available on the current camera device.
    ///
    /// - Returns: True if torch is available, false otherwise
    public func isTorchAvailable() -> Bool {
        guard let camera = currentCameraDevice else { return false }
        return camera.hasTorch
    }
    
    /// Gets the current torch mode and level.
    ///
    /// - Returns: A tuple containing the torch enabled state and level
    public func getTorchMode() -> (enabled: Bool, level: Float) {
        guard let camera = currentCameraDevice else { return (false, 0.0) }
        
        let isEnabled = camera.torchMode == .on
        let level = camera.torchLevel
        
        return (isEnabled, level)
    }
    
    /// Sets the torch mode and level for the currently active camera device.
    ///
    /// - Parameters:
    ///   - enabled: Whether to enable or disable the torch
    ///   - level: The torch intensity level (0.0 to 1.0)
    /// - Throws: An error if the torch mode cannot be set or is not supported.
    public func setTorchMode(enabled: Bool, level: Float = 1.0) throws {
        guard let camera = currentCameraDevice else {
            throw CameraError.cameraUnavailable
        }
        guard camera.hasTorch else { throw CameraError.torchUnavailable }
        
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            
            if enabled && level > 0.0 {
                try camera.setTorchModeOn(level: level)
            } else {
                camera.torchMode = .off
            }
        } catch {
            throw CameraError.configurationFailed(error)
        }
    }
    
    /// Initiates the capture session with the specified camera device.
    ///
    /// - Parameters:
    ///   - configuration: The configuration object for the camera session.
    private func initiateCaptureSession(
        configuration: CameraSessionConfiguration
    ) throws {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        configureAutomaticDeferredStart()

        // Remember the triple-camera preference before resolving the device:
        // `getCameraDevice` reads it, and so does a later camera flip.
        useTripleCameraIfAvailable = configuration.useTripleCameraIfAvailable

        // Configure the camera device
        let device: AVCaptureDevice
        if let deviceId = configuration.deviceId {
            device = try getCameraDeviceById(deviceId)
        } else {
            device = try getCameraDevice(for: configuration.position)
        }

        // Reset to the universally supported .photo baseline first so a
        // lingering 16:9 preset from a previous session cannot make the new
        // input incompatible before it is added.
        if captureSession.canSetSessionPreset(.photo) {
            captureSession.sessionPreset = .photo
        }

        // Remember the resolution selection for later (re-)application on
        // camera flips and format changes.
        configuredAspectRatio = configuration.aspectRatio
        configuredCaptureMaxDimension = configuration.captureMaxDimension

        // Set the camera input
        try setInput(with: device)

        // Choose the session preset for the configured aspect ratio now that
        // the input is attached, so preset support is validated against the
        // actual device (e.g. front cameras without 4K).
        applySessionPreset(forAspectRatio: configuration.aspectRatio)

        // Set up the photo output
        try setupPhotoOutput(prioritizeQuality: configuration.prioritizeQuality)
        
        // Set up the video data output for snapshots
        try setupVideoDataOutput()
        
        if !configuration.enableBarcodeDetection {
            // Remove the metadata output in case it already existed for the
            // capture session
            removeMetadataOutput()
        }
        
        // The initial zoom factor is applied by the caller once this transaction
        // has committed, see `applyInitialZoom`.
    }
    
    /// Sets the input for the capture session.
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// If the new input cannot be added, the previously removed inputs are
    /// restored and `currentCameraDevice` is left untouched so the session
    /// never ends up without an input.
    ///
    /// - Parameter device: The camera device to use as input.
    /// - Throws: An error if the input cannot be set.
    internal func setInput(with device: AVCaptureDevice) throws {
        guard currentCameraDevice?.uniqueID != device.uniqueID else {
            // Nothing todo, input is already configured for the desired device
            return
        }

        // Remove any existing inputs, keeping a reference so they can be
        // restored if adding the new input fails
        let removedInputs = captureSession.inputs
        removedInputs.forEach { captureSession.removeInput($0) }

        do {
            let input = try AVCaptureDeviceInput(device: device)
            if !captureSession.canAddInput(input) {
                throw CameraError.inputAdditionFailed
            }

            captureSession.addInput(input)
            currentCameraDevice = device

            // Recreate the rotation coordinator for the new device so capture
            // and preview rotation track the physical camera in use (flip,
            // triple-camera upgrade). No-op until the preview is available.
            configureRotationHandling()
        } catch {
            // Restore the previous inputs so the session is not left without input
            removedInputs.forEach {
                if captureSession.canAddInput($0) {
                    captureSession.addInput($0)
                }
            }

            if let avError = error as? AVError {
                throw CameraError.configurationFailed(avError)
            } else {
                throw CameraError.inputAdditionFailed
            }
        }
    }
    
    /// Enables barcode detection by adding metadata output to the running session.
    /// Somehow adding the metadata output with the session not being started yet
    /// caused issues on some devices (iPad 7th Gen) where the session would just
    /// not start at all without an error being thrown. I haven't been able to
    /// figure out the root cause of this but it might generally be a good idea
    /// to only add the metadata output when the session is already running.
    ///
    /// - Parameter barcodeTypes: Optional array of barcode types to detect. If nil, all supported types are used.
    /// - Throws: An error if the metadata output cannot be added.
    private func enableBarcodeDetection(barcodeTypes: [AVMetadataObject.ObjectType]? = nil) throws {
        guard captureSession.isRunning else {
            throw CameraError.sessionNotRunning
        }
        
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }
        
        try setupMetadataOutput(barcodeTypes: barcodeTypes)
    }
    
    /// Retrieve a list of a available camera devices
    ///
    /// - Returns: a list of all  available camera devices.
    public func getAvailableDevices() -> [AVCaptureDevice] {
        return AVCaptureDevice.DiscoverySession(
            deviceTypes: SUPPORTED_CAMERA_DEVICE_TYPES,
            mediaType: .video,
            position: .unspecified
        ).devices
    }
    
    /// Returns a list of available camera devices based on the preferences by the user
    ///
    /// - Returns: a list of camera devices based on the preferredCameraDeviceTypes
    private func getPreferredCameraDevices() -> [AVCaptureDevice] {
        return AVCaptureDevice.DiscoverySession(
            deviceTypes: self.preferredCameraDeviceTypes,
            mediaType: .video,
            position: .unspecified
        ).devices
    }
    
    /// Gets the  best match camera device for the specified position.
    /// This method will consider preferredCameraDevices as possibly provided by the user allowing a best
    /// match to the users request.
    ///
    /// - Parameters:
    ///   - position: The position of the camera device to get
    /// - Returns: The camera device for the specified position
    /// - Throws: An error if no camera device is found.
    private func getCameraDevice(for position: AVCaptureDevice.Position?) throws
    -> AVCaptureDevice {
        // The opt-in virtual triple camera takes precedence for the rear
        // position, so the session starts on it directly instead of swapping the
        // input of a running session afterwards. It only exists on Pro models.
        if useTripleCameraIfAvailable, position == .back,
           let tripleCamera = tripleCameraDevice() {
            return tripleCamera
        }

        let preferredDevices = getPreferredCameraDevices()

        // First try to get the best match based on the users preferred camera device types
        if let match = preferredDevices.first(where: { $0.position == position }
        ) {
            return match
        }
        
        // If we haven't found one we try to get a best match for the position by iterating all supported device types
        // Only doing this when preferredCameraDeviceTypes size differs from SUPPORTED_CAMERA_DEVICE_TYPES, otherwise
        // we don't have to initialize a new discovery session
        if preferredCameraDeviceTypes.count < SUPPORTED_CAMERA_DEVICE_TYPES.count,
           let match = getAvailableDevices().first(where: {
               $0.position == position
           }) {
            return match
        }
        
        // Otherwise, fallback to default video device or throw an error
        guard let device = AVCaptureDevice.default(for: .video) else {
            throw CameraError.cameraUnavailable
        }
        
        // Log when we're falling back to a device with different position than requested
        if let requestedPosition = position, device.position != requestedPosition {
            cameraViewLogger.warning(
                "Falling back to camera at position \(device.position.rawValue, privacy: .public) when \(requestedPosition.rawValue, privacy: .public) was requested"
            )
        }
        
        return device
    }
    
    /// The virtual triple camera (Pro models), if this device has one.
    ///
    /// `AVCaptureDevice.default(_:for:position:)` resolves it directly instead of
    /// allocating an `AVCaptureDevice.DiscoverySession` just to read its first
    /// element.
    private func tripleCameraDevice() -> AVCaptureDevice? {
        return AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back)
    }

    /// Gets the best camera device for the specified position.
    ///
    /// - Parameters:
    ///   - deviceId: The unique identifier of the camera device to get
    /// - Returns: The camera device for the specified position
    /// - Throws: An error if no camera device is found.
    private func getCameraDeviceById(_ deviceId: String) throws
    -> AVCaptureDevice {
        guard
            let device = getAvailableDevices().first(where: {
                $0.uniqueID == deviceId
            })
        else {
            throw CameraError.cameraUnavailable
        }
        return device
    }
    
    // MARK: - UI Preview Layer
    
    /// Attaches the preview layer to the capture session and inserts it behind
    /// the given view, leaving the view opaque for now (see `revealPreview`).
    ///
    /// Called before `startRunning()` so the preview is the session's one
    /// non-deferred consumer.
    ///
    /// - Parameters:
    ///   - view: The view that will display the camera preview.
    ///   - videoGravity: How the preview layer scales the feed into the view.
    ///     `.resizeAspectFill` (cover, default) center-crops; `.resizeAspect`
    ///     (fit) letterboxes the whole frame.
    ///   - completion: Called on the main thread once the layer is attached.
    private func attachPreview(
        to view: UIView,
        videoGravity: AVLayerVideoGravity,
        completion: @escaping () -> Void
    ) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // Stored on the main queue so every access to `self.webView` is
            // confined to a single queue and can't race the session-queue caller.
            self.webView = view

            // AVCaptureVideoPreviewLayer is a CALayer; its properties must be
            // mutated on the main thread rather than the session queue.
            self.videoPreviewLayer.session = self.captureSession
            self.videoPreviewLayer.videoGravity = videoGravity
            self.videoPreviewLayer.frame = view.bounds
            view.layer.insertSublayer(self.videoPreviewLayer, at: 0)

            self.configureRotationHandling()

            completion()
        }
    }

    /// Makes the WebView transparent so the attached preview layer becomes
    /// visible. Called once the session is running, so the app's own UI stays
    /// visible while the camera powers up.
    private func revealPreview() {
        DispatchQueue.main.async { [weak self] in
            guard let view = self?.webView else { return }

            view.isOpaque = false
            view.backgroundColor = UIColor.clear
            (view as? WKWebView)?.scrollView.backgroundColor = UIColor.clear
        }
    }

}
