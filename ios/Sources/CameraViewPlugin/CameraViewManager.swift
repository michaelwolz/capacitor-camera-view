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
    private let sessionQueue = DispatchQueue(
        label: "com.michaelwolz.capacitorcameraview.session",
        qos: .userInitiated
    )

    /// Reusable queue for sample buffer processing during snapshot capture.
    /// Creating a new queue per snapshot causes memory allocation churn.
    private let sampleBufferQueue = DispatchQueue(
        label: "com.michaelwolz.capacitorcameraview.sampleBuffer",
        qos: .userInitiated
    )

    /// The currently active camera device.
    private var currentCameraDevice: AVCaptureDevice?

    /// List of preferred camera devices, this overrides the SUPPORTED_CAMERA_DEVICE_TYPES for the capture session
    private var preferredCameraDeviceTypes = SUPPORTED_CAMERA_DEVICE_TYPES

    /// Currently selected flash mode.
    private var flashMode: AVCaptureDevice.FlashMode = .auto

    /// Reference to the blur overlay view that is shown when switching to the triple camera in order to have a smooth transition
    private var blurOverlayView: UIVisualEffectView?

    /// Reference to the webView that is used by the Capacitor plugin for the preview layer is shown on
    private var webView: UIView?

    /// Callback for when photo capture completes (legacy UIImage-based API).
    internal var photoCaptureHandler: ((UIImage?, Error?) -> Void)?

    /// Callback for when photo capture completes with raw Data (optimized API).
    /// This avoids double JPEG encoding by returning the camera's JPEG data directly.
    internal var photoDataCaptureHandler: ((Data?, Error?) -> Void)?

    /// Callback for when snapshot capture completes.
    internal var snapshotCompletionHandler: ((UIImage?, Error?) -> Void)?

    /// Emits typed camera events to the delegate and NotificationCenter.
    internal let eventEmitter = CameraEventEmitter()

    override public init() {
        super.init()
        setupOrientationObserver()
        setupAppLifecycleObservers()
    }

    deinit {
        stopSession()
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Plugin API

    /// Starts capture session for the specified camera position.
    /// This will reuse the existing capture session if it is already running.
    ///
    /// - Parameters:
    ///   - position: The position of the camera to start the session for.
    ///   - completion: A closure called when the session setup completes with an optional error.
    public func startSession(
        configuration: CameraSessionConfiguration,
        webView: UIView,
        completion: @escaping (Error?) -> Void
    ) {
        if let preferredCameraDeviceTypes = configuration.preferredCameraDeviceTypes {
            self.preferredCameraDeviceTypes = convertToNativeCameraTypes(preferredCameraDeviceTypes)
        }

        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            do {
                try self.initiateCaptureSession(configuration: configuration)
            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
                return
            }

            // Start the capture session
            self.captureSession.startRunning()

            // Display the camera preview on the provided webview
            self.displayPreview(
                on: webView,
                completion: { error in
                    if error != nil { completion(error); return }

                    // Handle barcode detection after session is running
                    if configuration.enableBarcodeDetection {
                        do {
                            try self.enableBarcodeDetection(barcodeTypes: configuration.barcodeTypes)
                        } catch {
                            completion(error)
                            return
                        }
                    }

                    // Complete already because the camera is ready to be used
                    completion(nil)

                    // We might asynchronously upgrade to a triple camera in the background if available and configured
                    if configuration.useTripleCameraIfAvailable {
                        Task {
                            await self.upgradeToTripleCameraIfAvailable()
                        }
                    }
                })
        }
    }

    /// Stops the current capture session and cleans up temporary files.
    public func stopSession() {
        guard captureSession.isRunning else { return }

        sessionQueue.async { [weak self] in
            self?.captureSession.stopRunning()
        }

        // Clean up temporary files created during this session
        TempFileManager.shared.cleanupSessionFiles()

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.videoPreviewLayer.removeFromSuperlayer()
            self.webView?.isOpaque = true
            self.webView?.backgroundColor = nil
            self.webView = nil

            if let blurOverlayView = self.blurOverlayView {
                blurOverlayView.removeFromSuperview()
                self.blurOverlayView = nil
            }
        }
    }

    /// Checks if the capture session is currently running.
    public func isRunning() -> Bool {
        return captureSession.isRunning
    }

    /// Captures a photo with the current camera settings.
    /// - Returns: The picture as UIImage via `AVCapturePhotoCaptureDelegate`
    public func capturePhoto(completion: @escaping (UIImage?, Error?) -> Void) {
        guard let cameraDevice = currentCameraDevice else {
            completion(nil, CameraError.cameraUnavailable)
            return
        }

        guard captureSession.isRunning else {
            completion(nil, CameraError.sessionNotRunning)
            return
        }

        let photoSettings = AVCapturePhotoSettings()
        if cameraDevice.hasFlash {
            photoSettings.flashMode = flashMode
        } else {
            photoSettings.flashMode = .off
        }

        // Ensure proper orientation
        if let photoConnection = avPhotoOutput.connection(with: .video),
           let previewConnection = videoPreviewLayer.connection {
            if photoConnection.isVideoOrientationSupported {
                photoConnection.videoOrientation = previewConnection.videoOrientation
            }
        }

        avPhotoOutput.capturePhoto(with: photoSettings, delegate: self)
        photoCaptureHandler = completion
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

        let photoSettings = AVCapturePhotoSettings()
        if cameraDevice.hasFlash {
            photoSettings.flashMode = flashMode
        } else {
            photoSettings.flashMode = .off
        }

        // Ensure proper orientation
        if let photoConnection = avPhotoOutput.connection(with: .video),
           let previewConnection = videoPreviewLayer.connection {
            if photoConnection.isVideoOrientationSupported {
                photoConnection.videoOrientation = previewConnection.videoOrientation
            }
        }

        avPhotoOutput.capturePhoto(with: photoSettings, delegate: self)
        photoDataCaptureHandler = completion
    }

    /// Capture a snapshot of the current camera view. This is faster than actually processing a
    /// photo via capturePhoto
    /// - Parameter completion: called with the captured UIImage or an error.
    public func captureSnapshot(completion: @escaping (UIImage?, Error?) -> Void) {
        guard currentCameraDevice != nil else {
            completion(nil, CameraError.cameraUnavailable)
            return
        }

        guard captureSession.isRunning else {
            completion(nil, CameraError.sessionNotRunning)
            return
        }

        // Ensure proper orientation
        if let videoConnection = avVideoDataOutput.connection(with: .video),
           let previewConnection = videoPreviewLayer.connection {
            if videoConnection.isVideoOrientationSupported {
                videoConnection.videoOrientation = previewConnection.videoOrientation
            }
        }

        // Set the delegate for a single frame capture using the reusable queue
        snapshotCompletionHandler = completion
        avVideoDataOutput.setSampleBufferDelegate(self, queue: sampleBufferQueue)
    }

    /// Flips the camera to the opposite position (front to back or back to front).
    public func flipCamera() throws {
        let currentPosition: AVCaptureDevice.Position = currentCameraDevice?.position ?? .back
        let newPosition: AVCaptureDevice.Position = currentPosition == .back ? .front : .back

        let newCamera = try getCameraDevice(for: newPosition)
        try setInput(with: newCamera)
    }

    /// Sets the flash mode for the currently active camera device.
    ///
    /// - Parameter mode: The desired flash mode (.on, .of, or .auto).
    /// - Throws: An error if the flash mode cannot be set or is not supported.
    public func setFlashMode(_ mode: AVCaptureDevice.FlashMode) throws {
        guard let camera = currentCameraDevice else { throw CameraError.cameraUnavailable }
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
        guard let camera = currentCameraDevice else { throw CameraError.cameraUnavailable }
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

    /// Gets the minimum, maximum, and current zoom factors supported by the current camera device.
    /// The maximum zoom factor is limited to a reasonable value of 10x to prevent excessive zooming
    /// because some devices report very high zoom factors that aren't useful.
    ///
    /// - Returns: A tuple containing the minimum, maximum, and current zoom factors.
    public func getSupportedZoomFactors() -> (min: CGFloat, max: CGFloat, current: CGFloat) {
        guard let currentDevice = currentCameraDevice else {
            return (
                min: 1.0,
                max: 1.0,
                current: 1.0
            )
        }

        let minZoomFactor = currentDevice.minAvailableVideoZoomFactor
        let maxZoomFactor = min(currentDevice.activeFormat.videoMaxZoomFactor, 10.0)
        let currentZoomFactor = currentDevice.videoZoomFactor

        return (
            min: minZoomFactor,
            max: maxZoomFactor,
            current: currentZoomFactor
        )
    }

    /// Sets the zoom factor for the current camera device.
    ///
    /// - Parameters:
    ///   - factor: The zoom factor to set.
    ///   - ramp: If enabled the zoom will be applied via ramp
    /// - Throws: An error if the zoom factor cannot be set.
    public func setZoomFactor(_ factor: CGFloat, ramp: Bool = true) throws {
        guard let device = currentCameraDevice else { throw CameraError.cameraUnavailable }

        let supportedZoomFactors = getSupportedZoomFactors()
        guard factor >= supportedZoomFactors.min && factor <= supportedZoomFactors.max else {
            throw CameraError.zoomFactorOutOfRange
        }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }

            if ramp {
                device.ramp(toVideoZoomFactor: factor, withRate: 6.0)
            } else {
                device.videoZoomFactor = factor
            }
        } catch {
            throw CameraError.configurationFailed(error)
        }
    }

    /// Initiates the capture session with the specified camera device.
    ///
    /// - Parameters:
    ///   - configuration: The configuration object for the camera session.
    private func initiateCaptureSession(configuration: CameraSessionConfiguration) throws {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        // Configure the camera device
        let device: AVCaptureDevice
        if let deviceId = configuration.deviceId {
            device = try getCameraDeviceById(deviceId)
        } else {
            device = try getCameraDevice(for: configuration.position)
        }

        // Set the session preset to photo if supported (which should be the case for all devices)
        if captureSession.canSetSessionPreset(.photo) {
            captureSession.sessionPreset = .photo
        }

        // Set the camera input
        try setInput(with: device)

        // Set up the photo output
        try setupPhotoOutput()

        // Set up the video data output for snapshots
        try setupVideoDataOutput()

        if !configuration.enableBarcodeDetection {
            // Remove the metadata output in case it already existed for the
            // capture session
            removeMetadataOutput()
        }

        // Set the initial zoom factor if specified
        if let zoomFactor = configuration.zoomFactor {
            try setZoomFactor(zoomFactor, ramp: false)
        }
    }

    /// Sets the input for the capture session.
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Parameter device: The camera device to use as input.
    /// - Throws: An error if the input cannot be set.
    private func setInput(with device: AVCaptureDevice) throws {
        guard currentCameraDevice?.uniqueID != device.uniqueID else {
            // Nothing todo, input is already configured for the desired device
            return
        }

        // Remove any existing inputs
        captureSession.inputs.forEach { captureSession.removeInput($0) }

        do {
            let input = try AVCaptureDeviceInput(device: device)
            if !captureSession.canAddInput(input) {
                throw CameraError.inputAdditionFailed
            }

            captureSession.addInput(input)
            currentCameraDevice = device
        } catch {
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
    private func getCameraDevice(for position: AVCaptureDevice.Position?) throws -> AVCaptureDevice {
        let preferredDevices = getPreferredCameraDevices()

        // First try to get the best match based on the users preferred camera device types
        if let match = preferredDevices.first(where: { $0.position == position }) {
            return match
        }

        // If we haven't found one we try to get a best match for the position by iterating all supported device types
        // Only doing this when preferredCameraDeviceTypes size differs from SUPPORTED_CAMERA_DEVICE_TYPES, otherwise
        // we don't have to initialize a new discovery session
        if preferredCameraDeviceTypes.count < SUPPORTED_CAMERA_DEVICE_TYPES.count,
           let match = getAvailableDevices().first(where: { $0.position == position }) {
            return match
        }

        // Otherwise, fallback to default video device or throw an error
        guard let device = AVCaptureDevice.default(for: .video) else {
            throw CameraError.cameraUnavailable
        }

        // Log when we're falling back to a device with different position than requested
        if let requestedPosition = position, device.position != requestedPosition {
            print("Warning: Falling back to camera at position \(device.position) when \(requestedPosition) was requested")
        }

        return device
    }

    /// Gets the best camera device for the specified position.
    ///
    /// - Parameters:
    ///   - deviceId: The unique identifier of the camera device to get
    /// - Returns: The camera device for the specified position
    /// - Throws: An error if no camera device is found.
    private func getCameraDeviceById(_ deviceId: String) throws -> AVCaptureDevice {
        guard let device = getAvailableDevices().first(where: { $0.uniqueID == deviceId }) else {
            throw CameraError.cameraUnavailable
        }
        return device
    }

    // MARK: - UI Preview Layer

    /// Sets up the preview layer for the capture session which will
    /// display the camera feed in the view.
    ///
    /// - Parameters:
    ///   - view: The view that will display the camera preview.
    ///   - completion: The completion handler after successfully adding the previewLayer to the provided view
    /// - Throws: An error if the preview layer cannot be set up.
    private func displayPreview(on view: UIView, completion: @escaping (Error?) -> Void) {
        guard captureSession.isRunning else {
            completion(CameraError.sessionNotRunning)
            return
        }

        self.webView = view

        videoPreviewLayer.session = captureSession
        videoPreviewLayer.videoGravity = .resizeAspectFill

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            view.isOpaque = false
            view.backgroundColor = UIColor.clear
            (view as? WKWebView)?.scrollView.backgroundColor = UIColor.clear

            self.videoPreviewLayer.frame = view.bounds
            view.layer.insertSublayer(self.videoPreviewLayer, at: 0)

            self.updatePreviewOrientation()

            completion(nil)
        }
    }

    // MARK: - Triple Camera

    /// Upgrades the camera to the triple camera if available.
    /// Initializing the triple camera is an expensive operation and takes some time.
    /// This is why by default the regular physical camera is used and then later upgraded to the triple camera if available (Pro models only).
    private func upgradeToTripleCameraIfAvailable() async {
        guard captureSession.isRunning else { return }

        // Check if a triple camera is available (only on newer Pro models)
        let devices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInTripleCamera],
            mediaType: .video,
            position: .back
        ).devices

        // If we don't have a triple camera, exit early
        guard let tripleCamera = devices.first else { return }

        // Don't do anything if we're already using the triple camera
        if currentCameraDevice?.uniqueID == tripleCamera.uniqueID {
            return
        }

        // Add a blur overlay to the webview to have a smooth transition when switching to the triple camera
        await addBlurOverlay()

        await Task.detached(priority: .userInitiated) {
            self.captureSession.beginConfiguration()

            do {
                try self.setInput(with: tripleCamera)
                // TODO: Consider configured zoom factor from the initial camera???
                try self.setZoomFactor(2.0, ramp: false)
            } catch {
                // Fail silently if we can't upgrade to the triple camera
                print("Failed to upgrade to triple camera: \(error.localizedDescription)")
            }

            self.captureSession.commitConfiguration()
        }.value

        // Small delay to let camera stabilize
        try? await Task.sleep(nanoseconds: 300_000_000)  // 0.3 seconds

        await removeBlurOverlayWithAnimation()
    }

    /// Adds a blur overlay to the webview to have a smooth transition when switching to the triple camera
    @MainActor
    private func addBlurOverlay() async {
        guard let view = self.webView else { return }

        let blurEffect = UIBlurEffect(style: .light)
        let blurOverlayView = UIVisualEffectView(effect: blurEffect)
        self.blurOverlayView = blurOverlayView

        blurOverlayView.frame = view.bounds
        blurOverlayView.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        // Add the blurEffect layer to the view hierarchy just above the preview layer
        // but below the web content
        view.insertSubview(blurOverlayView, at: 1)
    }

    /// Removes the blur overlay with a fade out animation to have a smooth transition
    /// - Parameter duration: The duration of the fade out animation
    @MainActor
    private func removeBlurOverlayWithAnimation(duration: TimeInterval = 0.3) async {
        guard let blurEffectView = blurOverlayView else { return }

        await withCheckedContinuation { continuation in
            UIView.animate(
                withDuration: duration,
                animations: {
                    blurEffectView.alpha = 0
                },
                completion: { _ in
                    blurEffectView.removeFromSuperview()
                    self.blurOverlayView = nil
                    continuation.resume()
                })
        }
    }

    // MARK: - Orientation Observer

    /// Sets up an observer for device orientation changes to update the preview layer orientation.
    private func setupOrientationObserver() {
        NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updatePreviewOrientation()
        }
    }

    /// Updates the preview layer orientation based on the current device orientation.
    private func updatePreviewOrientation() {
        guard let connection = self.videoPreviewLayer.connection, connection.isVideoOrientationSupported else {
            return
        }

        let interfaceOrientation = UIApplication.shared.windows.first?.windowScene?.interfaceOrientation ?? .portrait
        let videoOrientation: AVCaptureVideoOrientation

        switch interfaceOrientation {
        case .portrait:
            videoOrientation = .portrait
        case .landscapeLeft:
            videoOrientation = .landscapeLeft
        case .landscapeRight:
            videoOrientation = .landscapeRight
        case .portraitUpsideDown:
            videoOrientation = .portraitUpsideDown
        default:
            videoOrientation = .portrait
        }

        connection.videoOrientation = videoOrientation

        // Update the frame of the preview layer to match the new bounds
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let view = self.webView else { return }
            self.videoPreviewLayer.frame = view.bounds
        }
    }

    // MARK: - App Lifecycle Observers

    /// Sets up observers for app lifecycle events to pause and resume the camera session.
    private func setupAppLifecycleObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil)
    }

    /// Handles the app going to background by pausing the camera session.
    @objc private func handleAppWillResignActive() {
        // Pause the session when app goes to background to save resources
        if captureSession.isRunning {
            sessionQueue.async { [weak self] in
                self?.captureSession.stopRunning()
            }
        }
    }

    /// Handles the app coming back to foreground by resuming the camera session.
    @objc private func handleAppDidBecomeActive() {
        // Resume the session when app comes back to foreground
        if !captureSession.isRunning && webView != nil {
            sessionQueue.async { [weak self] in
                self?.captureSession.startRunning()
            }
        }
    }
}
