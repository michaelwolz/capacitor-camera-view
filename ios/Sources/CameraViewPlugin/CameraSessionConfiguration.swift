import AVFoundation
import Capacitor

public struct CameraSessionConfiguration {
    let deviceId: String?
    let enableBarcodeDetection: Bool
    let position: AVCaptureDevice.Position
    let useTripleCameraIfAvailable: Bool
    let zoomFactor: CGFloat?
}

/// Maps a Capacitor plugin call to a CameraSessionConfiguration struct.
///
/// - Parameter call: The Capacitor plugin call.
public func sessionConfigFromPluginCall(_ call: CAPPluginCall) -> CameraSessionConfiguration {
    let deviceId = call.getString("deviceId")
    let enableBarcodeDetection = call.getBool("enableBarcodeDetection", false)
    let position: AVCaptureDevice.Position = call.getString("position") == "front" ? .front : .back
    let useTripleCameraIfAvailable = call.getBool("useTripleCameraIfAvailable",  false)
    let zoomFactor = call.getDouble("zoomFactor").map { CGFloat($0) }

    return CameraSessionConfiguration(
        deviceId: deviceId,
        enableBarcodeDetection: enableBarcodeDetection,
        position: position,
        useTripleCameraIfAvailable: useTripleCameraIfAvailable,
        zoomFactor: zoomFactor
    )
}
