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
    private var flashMode: AVCaptureDevice.FlashMode = .auto

    /// Callback for when photo capture completes.
    private var photoCaptureHandler: ((UIImage?, Error?) -> Void)?

    /// Reference to the blur overlay view that is shown when switching to the triple camera in order to have a smooth transition
    private var blurOverlayView: UIVisualEffectView?

    /// Reference to the webView that is used by the Capacitor plugin for the preview layer is shown on
    private var webView: UIView?

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
        configuration: CameraSessionConfiguration,
        webView: UIView,
        completion: @escaping (Error?) -> Void
    ) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
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
                    if error != nil { completion(error) }

                    // Complete already because the camera is ready to be used
                    // We might asynchronously upgrade to a triple camera in the background if available and configured
                    completion(nil)

                    if configuration.useTripleCameraIfAvailable {
                        Task {
                            await self.upgradeToTripleCameraIfAvailable()
                        }
                    }
                })
        }
    }

    /// Stops the current capture session.
    public func stopSession() {
        guard captureSession.isRunning else { return }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession.stopRunning()
        }

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

        photoOutput.capturePhoto(with: photoSettings, delegate: self)
        photoCaptureHandler = completion
    }

    public func setCameraById(_ cameraId: String) throws {
        let devices = getAvailableDevices()
        guard let device = devices.first(where: { $0.uniqueID == cameraId }) else {
            throw CameraError.cameraUnavailable
        }

        try setInput(with: device)
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
        guard photoOutput.supportedFlashModes.contains(mode)
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
            return photoOutput.supportedFlashModes
        }

        return [.off]
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

        // Set the session preset if supported
        if captureSession.canSetSessionPreset(configuration.preset) {
            captureSession.sessionPreset = configuration.preset
        }

        // Set the camera input
        try setInput(with: device)

        // Set up the photo output
        try setupPhotoOutput()

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

    /// Set up output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Throws: An error if the output cannot be set.
    private func setupPhotoOutput() throws {
        if (captureSession.outputs.contains { $0 is AVCapturePhotoOutput }) {
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

    /// Retrieve a list of a available camera devices
    ///
    /// - Returns:  a list of all  available camera devices.
    public func getAvailableDevices() -> [AVCaptureDevice] {
        return AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInWideAngleCamera,
                .builtInUltraWideCamera,
                .builtInTelephotoCamera,
                .builtInDualCamera,
                .builtInDualWideCamera,
                .builtInTripleCamera,
                .builtInTrueDepthCamera,
            ],
            mediaType: .video,
            position: .unspecified
        ).devices
    }

    /// Gets the  best match camera device for the specified position.
    ///
    /// - Parameters:
    ///   - position: The position of the camera device to get
    /// - Returns: The camera device for the specified position
    /// - Throws: An error if no camera device is found.
    private func getCameraDevice(for position: AVCaptureDevice.Position?) throws -> AVCaptureDevice
    {
        if let match = getAvailableDevices().first(where: { $0.position == position }) {
            return match
        }

        // Fallback to default video device
        guard let device = AVCaptureDevice.default(for: .video) else {
            throw CameraError.cameraUnavailable
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

    /// Delegate method called when a photo has been captured via `AVCapturePhotoCaptureDelegate`
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

    /// MARK: - UI Preview Layer

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
            view.scrollView.backgroundColor = UIColor.clear

            self.videoPreviewLayer.frame = view.bounds
            view.layer.insertSublayer(self.videoPreviewLayer, at: 0)

            self.updatePreviewOrientation()

            completion(nil)
        }
    }

    /// MARK: - Triple Camera

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
        view.insertSubview(blurOverlayView, aboveSubview: view.subviews.first ?? view)
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
        guard let connection = self.videoPreviewLayer.connection, connection.isVideoOrientationSupported else {
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
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.captureSession.stopRunning()
            }
        }
    }

    /// Handles the app coming back to foreground by resuming the camera session.
    @objc private func handleAppDidBecomeActive() {
        // Resume the session when app comes back to foreground
        if !captureSession.isRunning && webView != nil {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.captureSession.startRunning()
            }
        }
    }
}
