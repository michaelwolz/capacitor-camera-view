import AVFoundation
import Capacitor
import Foundation

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitorjs.com/docs/plugins/ios
@objc(CameraViewPlugin)
public class CameraViewPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CameraViewPlugin"
    public let jsName = "CameraView"

    /// Maps string flash mode values to AVCaptureDevice.FlashMode enum values.
    private let strToFlashModeMap: [String: AVCaptureDevice.FlashMode] = [
        "off": .off,
        "on": .on,
        "auto": .auto,
    ]

    /// Maps AVCaptureDevice.FlashMode enum values to string values.
    private let flashModeToStrMap: [AVCaptureDevice.FlashMode: String] = [
        .off: "off",
        .on: "on",
        .auto: "auto",
    ]

    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "capture", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureSample", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAvailableDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "flipCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedFlashModes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
    ]

    private let implementation = CameraViewManager()
    private var notificationObserver: NSObjectProtocol?

    override public func load() {
        // Add observer for barcode detection events
        notificationObserver = NotificationCenter.default.addObserver(
            forName: Notification.Name("barcodeDetected"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let barcodeData = notification.userInfo as? [String: Any] else {
                return
            }

            // Emit event to JS
            self.notifyListeners("barcodeDetected", data: barcodeData)
        }
    }

    deinit {
        if let observer = notificationObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    @objc func start(_ call: CAPPluginCall) {
        guard let webView = self.webView else {
            call.reject("Cannot find web view")
            return
        }

        maybeRequestCameraAccess { [weak self] granted in
            guard granted else {
                call.reject("Camera access denied")
                return
            }

            self?.implementation.startSession(
                configuration: sessionConfigFromPluginCall(call),
                webView: webView,
                completion: { (error) in
                    if let error = error {
                        call.reject("Failed to start camera preview", nil, error)
                        return
                    }
                    call.resolve()
                })
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        implementation.stopSession()
        call.resolve()
    }

    @objc func isRunning(_ call: CAPPluginCall) {
        call.resolve([
            "isRunning": implementation.isRunning()
        ])
    }

    @objc func capture(_ call: CAPPluginCall) {
        let quality = call.getDouble("quality", 90.0)

        guard quality >= 0.0 && quality <= 100.0 else {
            call.reject("Quality must be between 0 and 100")
            return
        }

        implementation.capturePhoto(completion: { (image, error) in
            if let error = error {
                call.reject("Failed to capture image", nil, error)
                return
            }

            guard let image = image else {
                call.reject("No image data", nil, nil)
                return
            }

            guard let imageData = image.jpegData(compressionQuality: quality / 100.0) else {
                call.reject("Failed to compress image", nil, nil)
                return
            }

            call.resolve([
                "photo": imageData.base64EncodedString()
            ])
        })
    }

    @objc func captureSample(_ call: CAPPluginCall) {
        let quality = call.getDouble("quality", 90.0)

        guard quality >= 0.0 && quality <= 100.0 else {
            call.reject("Quality must be between 0 and 100")
            return
        }

        implementation.captureSnapshot() { (image, error) in
            if let error = error {
                call.reject("Failed to capture frame", nil, error)
                return
            }

            guard let image = image else {
                call.reject("No frame data", nil, nil)
                return
            }

            guard let imageData = image.jpegData(compressionQuality: quality / 100.0) else {
                call.reject("Failed to compress image", nil, nil)
                return
            }

            call.resolve([
                "photo": imageData.base64EncodedString()
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
            result.append(deviceInfo)
        }

        call.resolve([
            "devices": result
        ])
    }

    @objc func flipCamera(_ call: CAPPluginCall) {
        do {
            try implementation.flipCamera()
            call.resolve()
        } catch {
            call.reject("Failed to switch camera", nil, error)
            return
        }
    }

    @objc func getZoom(_ call: CAPPluginCall) {
        let zoom = implementation.getSupportedZoomFactors()

        call.resolve([
            "min": zoom.min,
            "max": zoom.max,
            "current": zoom.current,
        ])
    }

    @objc func setZoom(_ call: CAPPluginCall) {
        guard let level = call.getDouble("level") else {
            call.reject("Zoom level must be provided")
            return
        }

        let ramp = call.getBool("ramp") ?? false

        do {
            try implementation.setZoomFactor(level, ramp: ramp)
            call.resolve()
        } catch {
            call.reject("Failed to set zoom level", nil, error)
            return
        }
    }

    @objc func getFlashMode(_ call: CAPPluginCall) {
        let flashMode = implementation.getFlashMode()

        call.resolve([
            "flashMode": flashMode
        ])
    }

    @objc func getSupportedFlashModes(_ call: CAPPluginCall) {
        let supportedFlashModes = implementation.getSupportedFlashModes()
        let supportedFlashModeStrArr = supportedFlashModes.map { flashModeToStrMap[$0] }

        call.resolve([
            "flashModes": supportedFlashModeStrArr
        ])
    }

    @objc func setFlashMode(_ call: CAPPluginCall) {
        guard let mode = call.getString("mode") else {
            call.reject("Flash mode must be provided")
            return
        }

        guard let flashMode = strToFlashModeMap[mode] else {
            call.reject("Invalid flash mode")
            return
        }

        do {
            try implementation.setFlashMode(flashMode)
            call.resolve()
        } catch {
            call.reject("Failed to set flash mode", nil, error)
        }
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        let cameraState: String

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined:
            cameraState = "prompt"
        case .restricted, .denied:
            cameraState = "denied"
        case .authorized:
            cameraState = "granted"
        @unknown default:
            cameraState = "prompt"
        }

        call.resolve([
            "camera": cameraState
        ])
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] _ in
            self?.checkPermissions(call)
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
}
