import AVFoundation
import Capacitor

private let strToPreset: [String: AVCaptureSession.Preset] = [
    "low": .low,
    "medium": .medium,
    "hight": .high,
    "photo": .photo
]

public struct CameraSessionConfiguration {
    let deviceId: String?
    let enableQrCodeScanning: Bool
    let position: AVCaptureDevice.Position
    let preset: AVCaptureSession.Preset
    let useTripleCameraIfAvailable: Bool
    let zoomFactor: CGFloat?
}

/// Maps a Capacitor plugin call to a CameraSessionConfiguration struct.
///
/// - Parameter call: The Capacitor plugin call.
public func sessionConfigFromPluginCall(_ call: CAPPluginCall) -> CameraSessionConfiguration {
    let deviceId = call.getString("deviceId")
    let enableBarcodeScanning = call.getBool("enableQrCodeScanning", false)
    let position: AVCaptureDevice.Position = call.getString("position") == "front" ? .front : .back
    let preset: AVCaptureSession.Preset = strToPreset[call.getString("preset", "photo")] ?? .photo
    let useTripleCameraIfAvailable = call.getBool("useTripleCameraIfAvailable",  false)
    let zoomFactor = call.getDouble("zoomFactor").map { CGFloat($0) }

    return CameraSessionConfiguration(
        deviceId: deviceId,
        enableQrCodeScanning: enableBarcodeScanning,
        position: position,
        preset: preset,
        useTripleCameraIfAvailable: useTripleCameraIfAvailable,
        zoomFactor: zoomFactor
    )
}
