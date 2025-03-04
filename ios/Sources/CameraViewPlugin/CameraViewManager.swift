import AVFoundation
import Foundation

/// A camera implementation that handles camera session management and photo capture.
@objc public class CameraViewManager: NSObject, AVCapturePhotoCaptureDelegate {
    private let captureSession = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoPreviewLayer = AVCaptureVideoPreviewLayer()
    
    // Get available camera devices prioritized by best fit
    private let discoverySession = AVCaptureDevice.DiscoverySession(deviceTypes: [
        .builtInTripleCamera,
        .builtInDualCamera,
        .builtInUltraWideCamera,
        .builtInWideAngleCamera
    ], mediaType: .video, position: .unspecified)
    
    /// The currently active camera device.
    private var currentCameraDevice: AVCaptureDevice?
    
    /// Callback for when photo capture completes.
    private var photoCaptureHandler: ((UIImage?, Error?) -> Void)?
    
    /// Currently selected flash mode.
    private var flashMode: AVCaptureDevice.FlashMode = .off
    
    /// Reference of the webView the preview layer is shown on
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
        if captureSession.canSetSessionPreset(.photo) {
            captureSession.sessionPreset = .photo
        }
        
        guard let camera = getCameraDevice(for: position) else {
            completion(CameraError.cameraUnavailable)
            return
        }
        
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try self.setInput(device: camera)
                try self.setupOutput()
            } catch {
                completion(error)
                return
            }
            
            self.captureSession.startRunning()
            
            do {
                try self.displayPreview(on: webView)
                completion(nil)
            } catch {
                completion(error)
            }
        }
    }
    
    /// Stops the current capture session.
    public func stopSession() {
        guard captureSession.isRunning else { return }
        
        DispatchQueue.main.async {
            self.webView?.isOpaque = true
        }
        
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.stopRunning()
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
    
    /// Switches the camera to the opposite position (front to back or back to front).
    ///
    /// - Throws: `CameraError.cameraUnavailable` if no camera is available.
    public func switchCamera() throws {
        let currentPosition: AVCaptureDevice.Position = currentCameraDevice?.position ?? .back
        let newPosition: AVCaptureDevice.Position = currentPosition == .back ? .front : .back
        
        guard let newCamera = getCameraDevice(for: newPosition) else {
            return
        }
        
        try setInput(device: newCamera)
    }
    
    /// Sets the flash mode for the currently active camera device.
    ///
    /// - Parameter mode: The desired flash mode ("on", "off", or "auto").
    /// - Throws: An error if the flash mode cannot be set or is not supported.
    public func setFlashMode(_ mode: String) throws {
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
            
            currentDevice.videoZoomFactor = factor
        } catch {
            throw CameraError.configurationFailed(error)
        }
    }
    
    /// Sets the input for the capture session.
    ///
    /// - Parameter device: The camera device to use as input.
    /// - Throws: An error if the input cannot be set.
    private func setInput(device: AVCaptureDevice) throws {
        if let curr = currentCameraDevice, curr == device {
            // Nothing todo
            return
        }
        
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }
        
        if let currentInput = captureSession.inputs.first {
            captureSession.removeInput(currentInput)
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if captureSession.canAddInput(input) {
                captureSession.addInput(input)
                currentCameraDevice = device
            } else {
                throw CameraError.inputAdditionFailed
            }
        } catch {
            throw CameraError.inputAdditionFailed
        }
    }
    
    /// Gets the camera device for the specified position.
    ///
    /// - Parameter position: The position of the camera device to get.
    /// - Returns: The camera device for the specified position, or `nil` if no device is found.
    private func getCameraDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        let devices = self.discoverySession.devices
        guard !devices.isEmpty else { fatalError("Missing capture devices.")}
        
        
        return devices.first(where: { device in device.position == position })!
    }
    
    /// Set up ouptut for the capture session in case it's not configured yet
    private func setupOutput() throws {
        if self.captureSession.outputs.first != nil {
            // Nothing todo
            return
        }
        
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }
        
        if captureSession.canAddOutput(photoOutput) {
            captureSession.addOutput(photoOutput)
        } else {
            throw CameraError.outputAdditionFailed
        }
    }
    
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
        
        DispatchQueue.main.sync {
            // Make the webview transparent
            view.isOpaque = false
            view.backgroundColor = UIColor.clear
            view.scrollView.backgroundColor = UIColor.clear
            
            self.videoPreviewLayer.frame = view.bounds
            view.layer.insertSublayer(self.videoPreviewLayer, at: 0)
        }
    }
}
