import AVFoundation
import Foundation

@objc public class CameraView: NSObject {
    var captureSession: AVCaptureSession?
    var currentCameraPosition: AVCaptureDevice.Position = .back
    var flashMode = AVCaptureDevice.FlashMode.off
    var photoOutput: AVCapturePhotoOutput?
    var videoPreviewLayer: AVCaptureVideoLayer?

    // Devices
    var currentCamera: AVCaptureDevice?

    public func startSession(for: cameraPosition) {
        if captureSession == nil {
            captureSession = AVCaptureSession()
        }

        guard let session = captureSession else { throw CameraError.sessionCreationFailed }

        if session.canSetSessionPreset(.photo) {
            session.sessionPreset = .photo
        }

        guard let camera = getCameraDevice(for: cameraPosition) else {
            throw CameraError.cameraUnavailable
        }
        currentCameraPosition = cameraPosition

        // WIP
    }

    public func stopSession() {}

    public func capturePhoto() {}

    public func switchCamera() throws {
        guard let currentDevice = currentCamera else {
            throw CameraError.cameraUnavailable
        }

        guard let session = captureSession else {
            throw CameraError.sessionNotInitialized
        }

        let newCameraPosition = currentCameraPosition == .back ? .front : .back
        let newCamera = getCameraDevice(for: currentCameraPosition)
        guard let currentCamera = newCamera else {
            throw CameraError.cameraUnavailable
        }

        // If we found a camera for the new position, update the current camera position
        currentCameraPosition = newCameraPosition

        session.beginConfiguration()
        defer { session.commitConfiguration() }

        // Remove any (probably) existing input
        currentInput = session.inputs.first
        if let input = currentInput {
            session.removeInput(input)
        }

        do {
            let input = try AVCaptureDeviceInput(device: currentCamera)
            session.addInput(input)
        } catch {
            throw CameraError.inputAdditionFailed
        }
    }

    public func setFlashMode(_ mode: String) throws {
        guard let currentDevice = currentCamera else {
            throw CameraError.cameraUnavailable
        }

        guard let photoOutput = self.photoOutput else {
            throw CameraError.photoOutputNotConfigured
        }

        if !photoOutput.supportedFlashModes.contains(flashMode) {
            throw CameraError.unsupportedFlashMode
        }

        do {
            try currentDevice.lockForConfiguration()

            switch mode {
            case "off":
                currentDevice.flashMode = .off
                currentDevice.torchMode = .off
            case "on":
                currentDevice.flashMode = .on
                currentDevice.torchMode = .off
            case "auto":
                currentDevice.flashMode = .auto
                currentDevice.torchMode = .off
            case "torch":
                currentDevice.flashMode = .off
                currentDevice.torchMode = .on
            default:
                throw CameraError.unsupportedFlashMode
            }

            currentDevice.unlockForConfiguration()
        } catch {
            throw CameraError.configurationFailed(error)
        }
    }

    public func getSupportedFlashModes() throws -> [String] {
        guard let currentDevice = currentCamera else {
            throw CameraError.cameraUnavailable
        }

        guard let photoOutput = self.photoOutput else {
            throw CameraError.photoOutputNotConfigured
        }

        var supportedFlashModes: [String] = []

        if currentDevice.hasFlash {
            let modeMap: [AVCaptureDevice.FlashMode: String] = [
                .off: "off",
                .on: "on",
                .auto: "auto",
            ]

            for mode in photoOutput.supportedFlashModes {
                if let stringMode = modeMap[mode as AVCaptureDevice.FlashMode] {
                    supportedFlashModes.append(stringMode)
                }
            }
        }

        if currentDevice.hasTorch {
            supportedFlashModes.append("torch")
        }

        if supportedFlashModes.isEmpty {
            supportedFlashModes.append("off")
        }

        return supportedFlashModes
    }

    /**
    Get the minimum, maximum, and current zoom factors supported by the current camera device.
    The maximum zoom factor is limited to a reasonable value of 10x to prevent excessive zooming
    because some devices report very high zoom factors that aren't useful.

    - Returns: A tuple containing the minimum, maximum, and current zoom factors.
    */
    public func getSupportedZoomFactors() -> (min: CGFloat, max: CGFloat, current: CGFloat) {
        guard let currentDevice = currentCamera else {
            return (
                minimum: 1.0,
                maximum: 1.0,
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

    private func setupSession(cameraPosition: AVCaptureDevice.Position = .back) async throws {
        // Create a new capture session in case it is not already created
        if captureSession == nil {
            captureSession = AVCaptureSession()
        }

        guard let session = captureSession else { throw CameraError.sessionCreationFailed }

        if session.canSetSessionPreset(.photo) {
            session.sessionPreset = .photo
        }

    }

    private func setupInput() {}

    private func setupOutput() {}

    private func getCameraDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        if position == .back {
            let discoverySession = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInTripleCamera, .builtInDualCamera, .builtInWideAngleCamera],
                mediaType: .video,
                position: .back
            )

            return discoverySession.devices.first
        } else {
            let discoverySession = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInTrueDepthCamera, .builtInWideAngleCamera],
                mediaType: .video,
                position: .front
            )

            return discoverySession.devices.first
        }
    }
}
