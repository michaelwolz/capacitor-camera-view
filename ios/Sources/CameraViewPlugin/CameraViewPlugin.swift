import AVFoundation
import Capacitor
import Foundation

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitorjs.com/docs/plugins/ios
@objc(CameraViewPlugin)
public class CameraViewPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CameraViewPlugin"
    public let jsName = "CameraView"
    
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "capture", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "switchCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedFlashModes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
    ]
    
    private let implementation = CameraViewManager()
    
    @objc func start(_ call: CAPPluginCall) {
        let position: AVCaptureDevice.Position =
        call.getString("cameraPosition") == "front" ? .front : .back
        
        guard let webView = self.webView else {
            call.reject("Cannot find web view")
            return
        }
        
        maybeRequestCameraAcceess { [weak self] granted in
            guard granted else {
                call.reject("Camera access denied")
                return
            }
            
            self?.implementation.startSession(
                for: position,
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
    }
    
    @objc func isRunning(_ call: CAPPluginCall) {
        call.resolve([
            "value": implementation.isRunning()
        ])
    }
    
    @objc func capture(_ call: CAPPluginCall) {
        let quality = call.getDouble("quality", 80.0)
        
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
                "value": imageData.base64EncodedString()
            ])
        })
    }
    
    @objc func switchCamera(_ call: CAPPluginCall) {
        do {
            try implementation.switchCamera()
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
        
        do {
            try implementation.setZoomFactor(level)
            call.resolve()
        } catch {
            call.reject("Failed to set zoom level", nil, error)
            return
        }
    }
    
    @objc func getFlashMode(_ call: CAPPluginCall) {
        let flashMode = implementation.getFlashMode()
        
        call.resolve([
            "value": flashMode
        ])
    }
    
    @objc func getSupportedFlashModes(_ call: CAPPluginCall) {
        do {
            let supportedFlashModes = try implementation.getSupportedFlashModes()
            call.resolve([
                "value": supportedFlashModes
            ])
        } catch {
            call.reject("Failed to get supported flash modes", nil, error)
        }
    }
    
    @objc func setFlashMode(_ call: CAPPluginCall) {
        guard let mode = call.getString("mode") else {
            call.reject("Flash mode must be provided")
            return
        }
        
        do {
            try implementation.setFlashMode(mode)
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
        
        call.resolve(["camera": cameraState])
    }
    
    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] _ in
            self?.checkPermissions(call)
        }
    }
    
    private func maybeRequestCameraAcceess(completion: @escaping (Bool) -> Void) {
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
