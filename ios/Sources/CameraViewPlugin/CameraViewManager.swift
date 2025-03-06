import AVFoundation
import Foundation

/// A camera implementation that handles camera session management and photo capture.
@objc public class CameraViewManager: NSObject, AVCapturePhotoCaptureDelegate {
    private let captureSession = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoPreviewLayer = AVCaptureVideoPreviewLayer()
    private var isPrewarmed = false

    /// The currently active camera device.
    private var currentCameraDevice: AVCaptureDevice?

    /// Currently selected flash mode.
    private var flashMode: AVCaptureDevice.FlashMode = .off

    /// Callback for when photo capture completes.
    private var photoCaptureHandler: ((UIImage?, Error?) -> Void)?

    /// Reference to the webView that is used by the Capacitor plugin for the preview layer is shown on
    private var webView: UIView?

    /// Maps string flash mode values to AVCaptureDevice.FlashMode enum values.
    private let flashModeMap: [String: AVCaptureDevice.FlashMode] = [
        "off": .off,
        "on": .on,
        "auto": .auto,
    ]

    /// Maps AVCaptureDevice.FlashMode enum values to string values.
    private let flashModeReverseMap: [AVCaptureDevice.FlashMode: String] = [
        .off: "off",
        .on: "on",
        .auto: "auto",
    ]

    public override init() {
        super.init()
        setupOrientationObserver()
        setupAppLifecycleObservers()
    }

    deinit {
        stopSession()
        NotificationCenter.default.removeObserver(self)
    }

    /// MARK: - Plugin API

    /// Starts capture session for the specified camera position.
    /// This will reuse the existing capture session if it is already running.
    ///
    /// - Parameters:
    ///   - position: The position of the camera to start the session for.
    ///   - completion: A closure called when the session setup completes with an optional error.
    public func startSession(
        for position: AVCaptureDevice.Position,
        webView: UIView,
        completion: @escaping (Error?) -> Void
    ) {
        guard let camera = getBestCameraDevice(for: position) else {
            completion(CameraError.cameraUnavailable)
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            do {
                try self.configureSession(with: camera)
            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
                return
            }

            self.captureSession.startRunning()

            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                do {
                    try self.displayPreview(on: webView)
                    completion(nil)
                } catch {
                    completion(error)
                }
            }
        }
    }

    /// Stops the current capture session.
    public func stopSession() {
        guard captureSession.isRunning else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.videoPreviewLayer.removeFromSuperlayer()

            // Reset the webview
            self.webView?.isOpaque = true
            self.webView?.backgroundColor = nil
            self.webView = nil
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession.stopRunning()
        }
    }

    /// Checks if the capture session is currently running.
    public func isRunning() -> Bool {
        return captureSession.isRunning
    }

    /// Captures a photo with the current camera settings.
    /// Returns the picture as UIImage via completion handler
    public func capturePhoto(completion: @escaping (UIImage?, Error?) -> Void) {
        guard captureSession.isRunning else {
            completion(nil, CameraError.sessionNotRunning)
            return
        }

        let photoSettings = AVCapturePhotoSettings()
        photoSettings.flashMode = flashMode

        photoOutput.capturePhoto(with: photoSettings, delegate: self)
        photoCaptureHandler = completion
    }

    /// Switches the camera to the opposite position (front to back or back to front).
    public func switchCamera() throws {
        let currentPosition: AVCaptureDevice.Position = currentCameraDevice?.position ?? .back
        let newPosition: AVCaptureDevice.Position = currentPosition == .back ? .front : .back
        guard let newCamera = getBestCameraDevice(for: newPosition) else { return }
        try configureSession(with: newCamera)
    }

    /// Sets the flash mode for the currently active camera device.
    ///
    /// - Parameter mode: The desired flash mode ("on", "off", or "auto").
    /// - Throws: An error if the flash mode cannot be set or is not supported.
    public func setFlashMode(_ mode: String) throws {
        guard let camera = currentCameraDevice else { throw CameraError.cameraUnavailable }
        guard camera.hasFlash else { throw CameraError.unsupportedFlashMode }
        guard let newMode = flashModeMap[mode], photoOutput.supportedFlashModes.contains(newMode)
        else {
            throw CameraError.unsupportedFlashMode
        }

        flashMode = newMode
    }

    /// Gets the current flash mode for the current camera device.
    public func getFlashMode() -> String {
        return flashModeReverseMap[flashMode] ?? "off"
    }

    /// Gets the supported flash modes for the current camera device.
    ///
    /// - Returns: A string array of supported flash modes.
    /// - Throws: An error if the camera is unavailable.
    public func getSupportedFlashModes() throws -> [String] {
        guard let currentDevice = currentCameraDevice else { throw CameraError.cameraUnavailable }

        var supportedFlashModes: [String] = []

        if currentDevice.hasFlash {
            for mode in photoOutput.supportedFlashModes {
                if let stringMode = flashModeReverseMap[mode as AVCaptureDevice.FlashMode] {
                    supportedFlashModes.append(stringMode)
                }
            }
        }

        if supportedFlashModes.isEmpty {
            supportedFlashModes.append("off")
        }

        return supportedFlashModes
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
    /// - Parameter factor: The zoom factor to set.
    /// - Throws: An error if the zoom factor cannot be set.
    public func setZoomFactor(_ factor: CGFloat) throws {
        guard let currentDevice = currentCameraDevice else { throw CameraError.cameraUnavailable }

        let supportedZoomFactors = getSupportedZoomFactors()
        guard factor >= supportedZoomFactors.min && factor <= supportedZoomFactors.max else {
            throw CameraError.zoomFactorOutOfRange
        }

        do {
            try currentDevice.lockForConfiguration()
            defer { currentDevice.unlockForConfiguration() }

            currentDevice.ramp(toVideoZoomFactor: factor, withRate: 5.0)
        } catch {
            throw CameraError.configurationFailed(error)
        }
    }

    /// MARK: - Camera Session Management

    /// Prewarms camera session to reduce startup time
    public func prewarmSession(for position: AVCaptureDevice.Position = .back) {
        guard !isPrewarmed && !captureSession.isRunning else { return }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            if let camera = self.getBestCameraDevice(for: position) {
                try? self.configureSession(with: camera)
                self.isPrewarmed = true
            }
        }
    }

    /// Configures the capture session with the specified camera device.
    ///
    /// - Parameters:
    ///   - device: The camera device to use for the capture session.
    private func configureSession(with device: AVCaptureDevice) throws {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        try self.setInput(device: device)
        try self.setupOutput()

        if captureSession.canSetSessionPreset(.photo) {
            captureSession.sessionPreset = .photo
        }
    }

    /// Sets the input for the capture session.
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Parameter device: The camera device to use as input.
    /// - Throws: An error if the input cannot be set.
    private func setInput(device: AVCaptureDevice) throws {
        if let curr = captureSession.inputs.first, curr == device {
            // Nothing todo, input is already configured for the desired device
            return
        }

        do {
            // Remove any existing inputs
            captureSession.inputs.forEach { captureSession.removeInput($0) }

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

    /// Set up output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    private func setupOutput() throws {
        if captureSession.outputs.first != nil {
            // Nothing todo, we already have an output and since we only
            // use outputs for taking photos here we don't need a new one
            return
        }

        // Balanced should be a good choice for most use case
        photoOutput.maxPhotoQualityPrioritization = .balanced

        if !captureSession.canAddOutput(photoOutput) {
            throw CameraError.outputAdditionFailed
        }

        captureSession.addOutput(photoOutput)
    }

    /// Delegate method called when a photo has been captured.
    ///
    /// - Parameters:
    ///   - output: The photo output that captured the photo.
    ///   - photo: The captured photo.
    ///   - error: An error that occurred during photo capture.
    public func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error = error {
            photoCaptureHandler?(nil, error)
            return
        }

        guard let data = photo.fileDataRepresentation(), let image = UIImage(data: data) else {
            photoCaptureHandler?(nil, CameraError.photoOutputError)
            return
        }

        photoCaptureHandler?(image, nil)
    }

    /// Gets the best camera device for the specified position.
    /// This method prefers virtual devices over phsyical devices for improving UX, paying with a little bit of performance while initializing.
    ///
    /// - Parameter position: The position of the camera device to get.
    /// - Returns: The camera device for the specified position, or `nil` if no device is found.
    private func getBestCameraDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        // Get available camera devices prioritized by best fit
        let devices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInTripleCamera,
                .builtInDualCamera,
                .builtInUltraWideCamera,
                .builtInWideAngleCamera,
            ], mediaType: .video, position: .unspecified
        ).devices

        guard !devices.isEmpty else { return nil }

        // First try to find exact position match
        if let exactMatch = devices.first(where: { $0.position == position }) {
            return exactMatch
        }

        // Fallback to any available camera if the requested position isn't available
        return devices.first
    }

    /// MARK: - UI Preview Layer

    /// Sets up the preview layer for the capture session which will
    /// display the camera feed in the view.
    ///
    /// - Parameter view: The view that will display the camera preview.
    /// - Throws: An error if the preview layer cannot be set up.
    private func displayPreview(on view: UIView) throws {
        guard captureSession.isRunning else { throw CameraError.sessionNotRunning }

        self.webView = view

        videoPreviewLayer.session = captureSession
        videoPreviewLayer.videoGravity = .resizeAspectFill

        // Make the webview transparent
        view.isOpaque = false
        view.backgroundColor = UIColor.clear
        view.scrollView.backgroundColor = UIColor.clear

        self.videoPreviewLayer.frame = view.bounds
        view.layer.insertSublayer(self.videoPreviewLayer, at: 0)

        self.updatePreviewOrientation()
    }

    /// MARK: - Orientation Observers

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
        guard let connection = self.videoPreviewLayer.connection,
            connection.isVideoOrientationSupported
        else {
            return
        }

        let orientation = UIDevice.current.orientation
        let videoOrientation: AVCaptureVideoOrientation

        switch orientation {
        case .portrait:
            videoOrientation = .portrait
        case .landscapeLeft:
            videoOrientation = .landscapeRight
        case .landscapeRight:
            videoOrientation = .landscapeLeft
        case .portraitUpsideDown:
            videoOrientation = .portraitUpsideDown
        default:
            videoOrientation = .portrait
        }

        connection.videoOrientation = videoOrientation
    }

    /// MARK: - App Lifecycle Observers

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
            captureSession.stopRunning()
        }
    }

    /// Handles the app coming back to foreground by resuming the camera session.
    @objc private func handleAppDidBecomeActive() {
        // Resume the session when app comes back to foreground
        if !captureSession.isRunning && webView != nil {
            captureSession.startRunning()
        }
    }
}
