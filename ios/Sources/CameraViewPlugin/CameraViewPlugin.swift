import AVFoundation
import Capacitor
import Foundation

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitorjs.com/docs/plugins/ios
@objc(CameraViewPlugin)
public class CameraViewPlugin: CAPPlugin, CAPBridgedPlugin, CameraEventDelegate {
    public let identifier = "CameraViewPlugin"
    public let jsName = "CameraView"
    
    /// Maps string flash mode values to AVCaptureDevice.FlashMode enum values.
    private let strToFlashModeMap: [String: AVCaptureDevice.FlashMode] = [
        "off": .off,
        "on": .on,
        "auto": .auto
    ]
    
    /// Maps AVCaptureDevice.FlashMode enum values to string values.
    private let flashModeToStrMap: [AVCaptureDevice.FlashMode: String] = [
        .off: "off",
        .on: "on",
        .auto: "auto"
    ]
    
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "capture", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureSample", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAvailableDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "flipCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFocusPoint", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedFlashModes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isTorchAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getTorchMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setTorchMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise)
    ]
    
    private let implementation = CameraViewManager()
    
    override public func load() {
        implementation.eventEmitter.delegate = self
    }
    
    public func cameraDidDetectBarcode(_ event: BarcodeDetectedEvent) {
        notifyListeners("barcodeDetected", data: event.toDictionary())
    }

    public func cameraWasInterrupted(reason: String) {
        notifyListeners("cameraInterrupted", data: ["reason": reason])
    }

    public func cameraInterruptionEnded() {
        notifyListeners("cameraResumed", data: [:])
    }

    public func cameraRuntimeError(message: String, code: Int?) {
        var data: [String: Any] = ["message": message]
        if let code = code {
            data["code"] = code
        }
        notifyListeners("cameraRuntimeError", data: data)
    }
    
    @objc func start(_ call: CAPPluginCall) {
        guard let webView = self.webView else {
            call.reject("Cannot find web view", CameraError.missingWebView.code)
            return
        }

        maybeRequestCameraAccess { [weak self] granted in
            guard granted else {
                call.reject("Camera access denied", CameraError.permissionDenied.code)
                return
            }

            self?.implementation.startSession(
                configuration: sessionConfigFromPluginCall(call),
                webView: webView,
                completion: { (error) in
                    if let error = error {
                        call.reject("Failed to start camera preview", error.cameraErrorCode, error)
                        return
                    }
                    call.resolve()
                })
        }
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        implementation.stopSession {
            call.resolve()
        }
    }
    
    @objc func isRunning(_ call: CAPPluginCall) {
        call.resolve([
            "isRunning": implementation.isRunning()
        ])
    }
    
    @objc func capture(_ call: CAPPluginCall) {
        let quality = call.getDouble("quality", 90.0)
        let saveToFile = call.getBool("saveToFile", false)
        
        guard quality >= 0.0 && quality <= 100.0 else {
            call.reject("Quality must be between 0 and 100", CameraError.invalidArgument.code)
            return
        }

        // Use optimized Data-based capture to avoid double JPEG encoding
        implementation.capturePhotoData(completion: { [weak self] (data, error) in
            if let error = error {
                call.reject("Failed to capture image", error.cameraErrorCode, error)
                return
            }

            guard let originalData = data else {
                call.reject("No image data", CameraError.captureOutputMissing.code)
                return
            }

            // Determine final image data based on quality setting
            // For quality >= 90%, use original camera JPEG data to avoid re-encoding
            // For lower quality, re-encode to reduce file size
            let imageData: Data
            if quality >= 90.0 {
                // Use original JPEG data from camera (avoids quality loss and CPU overhead)
                imageData = originalData
            } else {
                // Re-encode at lower quality for smaller file size. Uses ImageIO
                // directly (rather than UIImage.jpegData) so EXIF/GPS metadata
                // from the original camera JPEG survives the re-encode.
                guard let compressedData = reencodeJPEG(data: originalData, compressionQuality: quality / 100.0) else {
                    call.reject("Failed to compress image", CameraError.imageCompressionFailed.code)
                    return
                }
                imageData = compressedData
            }

            if saveToFile {
                // Use TempFileManager for tracked temp files with automatic cleanup
                let tempFileURL = TempFileManager.shared.createTempImageFile()
                do {
                    try imageData.write(to: tempFileURL)

                    // Convert file URL to webView-accessible path using Capacitor bridge
                    guard let webPath = self?.bridge?.portablePath(fromLocalURL: tempFileURL)?.absoluteString else {
                        call.reject("Failed to create web-accessible path", CameraError.pathConversionFailed.code)
                        return
                    }

                    call.resolve([
                        "path": tempFileURL.absoluteString,
                        "webPath": webPath,
                    ])
                } catch {
                    call.reject("Failed to save image to file", CameraError.fileWriteFailed.code, error)
                }
            } else {
                // Return as base64
                call.resolve([
                    "photo": imageData.base64EncodedString()
                ])
            }
        })
    }
    
    @objc func captureSample(_ call: CAPPluginCall) {
        let quality = call.getDouble("quality", 90.0)
        let saveToFile = call.getBool("saveToFile", false)
        
        guard quality >= 0.0 && quality <= 100.0 else {
            call.reject("Quality must be between 0 and 100", CameraError.invalidArgument.code)
            return
        }

        implementation.captureSnapshot { [weak self] (image, error) in
            if let error = error {
                call.reject("Failed to capture frame", error.cameraErrorCode, error)
                return
            }

            guard let image = image else {
                call.reject("No frame data", CameraError.captureOutputMissing.code)
                return
            }

            guard let imageData = image.jpegData(compressionQuality: quality / 100.0) else {
                call.reject("Failed to compress image", CameraError.imageCompressionFailed.code)
                return
            }

            if saveToFile {
                // Use TempFileManager for tracked temp files with automatic cleanup
                let tempFileURL = TempFileManager.shared.createTempImageFile()
                do {
                    try imageData.write(to: tempFileURL)

                    // Convert file URL to webView-accessible path using Capacitor bridge
                    guard let webPath = self?.bridge?.portablePath(fromLocalURL: tempFileURL)?.absoluteString else {
                        call.reject("Failed to create web-accessible path", CameraError.pathConversionFailed.code)
                        return
                    }

                    call.resolve([
                        "path": tempFileURL.absoluteString,
                        "webPath": webPath,
                    ])
                } catch {
                    call.reject("Failed to save sample to file", CameraError.fileWriteFailed.code, error)
                }
            } else {
                // Return as base64
                call.resolve([
                    "photo": imageData.base64EncodedString()
                ])
            }
        }
    }
    
    @objc func startRecording(_ call: CAPPluginCall) {
        let enableAudio = call.getBool("enableAudio") ?? false
        let videoQuality = call.getString("videoQuality") ?? "highest"

        guard let parsedVideoQuality = VideoRecordingQuality(rawValue: videoQuality) else {
            call.reject("Invalid videoQuality. Use one of: lowest, sd, hd, fhd, uhd, highest", CameraError.invalidArgument.code)
            return
        }

        if enableAudio {
            maybeRequestMicrophoneAccess { [weak self] granted in
                guard granted else {
                    call.reject("Microphone access denied", CameraError.permissionDenied.code)
                    return
                }
                self?.doStartRecording(call: call, enableAudio: true, videoQuality: parsedVideoQuality)
            }
        } else {
            doStartRecording(call: call, enableAudio: false, videoQuality: parsedVideoQuality)
        }
    }

    private func doStartRecording(
        call: CAPPluginCall,
        enableAudio: Bool,
        videoQuality: VideoRecordingQuality
    ) {
        implementation.startRecording(enableAudio: enableAudio, videoQuality: videoQuality) { error in
            if let error = error {
                call.reject("Failed to start recording", error.cameraErrorCode, error)
                return
            }
            call.resolve()
        }
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        implementation.stopRecording { [weak self] (outputURL, error) in
            if let error = error {
                call.reject("Failed to stop recording", error.cameraErrorCode, error)
                return
            }

            guard let outputURL = outputURL else {
                call.reject("No output file URL", CameraError.captureOutputMissing.code)
                return
            }

            guard let webPath = self?.bridge?.portablePath(fromLocalURL: outputURL)?.absoluteString else {
                call.reject("Failed to create web-accessible path", CameraError.pathConversionFailed.code)
                return
            }

            call.resolve([
                "path": outputURL.absoluteString,
                "webPath": webPath,
            ])
        }
    }

    @objc func getAvailableDevices(_ call: CAPPluginCall) {
        let devices = implementation.getAvailableDevices()
        
        var result = JSArray()
        for device in devices {
            var deviceInfo = JSObject()
            deviceInfo["id"] = device.uniqueID
            deviceInfo["name"] = device.localizedName
            deviceInfo["position"] = device.position == .front ? "front" : "back"
            deviceInfo["deviceType"] = convertToStringCameraType(device.deviceType)
            result.append(deviceInfo)
        }
        
        call.resolve([
            "devices": result
        ])
    }
    
    @objc func flipCamera(_ call: CAPPluginCall) {
        implementation.flipCamera { error in
            if let error = error {
                call.reject("Failed to switch camera", error.cameraErrorCode, error)
                return
            }
            call.resolve()
        }
    }
    
    @objc func getZoom(_ call: CAPPluginCall) {
        let zoom = implementation.getSupportedZoomFactors()
        
        call.resolve([
            "min": zoom.min,
            "max": zoom.max,
            "current": zoom.current
        ])
    }
    
    @objc func setZoom(_ call: CAPPluginCall) {
        guard let level = call.getDouble("level") else {
            call.reject("Zoom level must be provided", CameraError.invalidArgument.code)
            return
        }

        let ramp = call.getBool("ramp") ?? false

        do {
            try implementation.setZoomFactor(level, ramp: ramp)
            call.resolve()
        } catch {
            call.reject("Failed to set zoom level", error.cameraErrorCode, error)
            return
        }
    }
    
    @objc func setFocusPoint(_ call: CAPPluginCall) {
        guard let pointX = call.getDouble("x"), let pointY = call.getDouble("y") else {
            call.reject("Focus point x and y must be provided", CameraError.invalidArgument.code)
            return
        }

        implementation.setFocusPoint(x: CGFloat(pointX), y: CGFloat(pointY)) { error in
            if let error = error {
                call.reject("Failed to set focus point", error.cameraErrorCode, error)
                return
            }
            call.resolve()
        }
    }

    @objc func getFlashMode(_ call: CAPPluginCall) {
        let flashMode = implementation.getFlashMode()
        
        call.resolve([
            "flashMode": flashModeToStrMap[flashMode] ?? "off"
        ])
    }
    
    @objc func getSupportedFlashModes(_ call: CAPPluginCall) {
        let supportedFlashModes = implementation.getSupportedFlashModes()
        let supportedFlashModeStrArr = supportedFlashModes.compactMap { flashModeToStrMap[$0] }
        
        call.resolve([
            "flashModes": supportedFlashModeStrArr
        ])
    }
    
    @objc func setFlashMode(_ call: CAPPluginCall) {
        guard let mode = call.getString("mode") else {
            call.reject("Flash mode must be provided", CameraError.invalidArgument.code)
            return
        }

        guard let flashMode = strToFlashModeMap[mode] else {
            call.reject("Invalid flash mode", CameraError.invalidArgument.code)
            return
        }

        do {
            try implementation.setFlashMode(flashMode)
            call.resolve()
        } catch {
            call.reject("Failed to set flash mode", error.cameraErrorCode, error)
        }
    }
    
    @objc func isTorchAvailable(_ call: CAPPluginCall) {
        let available = implementation.isTorchAvailable()
        call.resolve([
            "available": available
        ])
    }
    
    @objc func getTorchMode(_ call: CAPPluginCall) {
        let torchState = implementation.getTorchMode()
        call.resolve([
            "enabled": torchState.enabled,
            "level": torchState.level
        ])
    }
    
    @objc func setTorchMode(_ call: CAPPluginCall) {
        guard let enabled = call.getBool("enabled") else {
            call.reject("Enabled parameter is required", CameraError.invalidArgument.code)
            return
        }

        let level = call.getFloat("level") ?? 1.0

        guard level >= 0.0 && level <= 1.0 else {
            call.reject("Level must be between 0.0 and 1.0", CameraError.invalidArgument.code)
            return
        }

        do {
            try implementation.setTorchMode(enabled: enabled, level: level)
            call.resolve()
        } catch {
            call.reject("Failed to set torch mode", error.cameraErrorCode, error)
        }
    }
    
    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        call.resolve([
            "camera": authorizationStateString(for: .video),
            "microphone": authorizationStateString(for: .audio)
        ])
    }
    
    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        let permissionsList = call.getArray("permissions", String.self) ?? ["camera"]

        let requestCamera = permissionsList.contains("camera")
        let requestMicrophone = permissionsList.contains("microphone")
        
        let completionHandler: () -> Void = { [weak self] in
            self?.checkPermissions(call)
        }
        
        if requestCamera {
            AVCaptureDevice.requestAccess(for: .video) { _ in
                if requestMicrophone {
                    AVCaptureDevice.requestAccess(for: .audio) { _ in
                        completionHandler()
                    }
                } else {
                    completionHandler()
                }
            }
        } else if requestMicrophone {
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                completionHandler()
            }
        } else {
            completionHandler()
        }
    }
    
    /// Maps AVFoundation authorization status to the Capacitor permission state string.
    private func authorizationStateString(for mediaType: AVMediaType) -> String {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .notDetermined:
            return "prompt"
        case .restricted, .denied:
            return "denied"
        case .authorized:
            return "granted"
        @unknown default:
            return "prompt"
        }
    }
    
    private func maybeRequestCameraAccess(completion: @escaping (Bool) -> Void) {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        if status == .authorized {
            completion(true)
        } else if status == .notDetermined {
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    completion(granted)
                }
            }
        } else {
            completion(false)
        }
    }
    
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
}
