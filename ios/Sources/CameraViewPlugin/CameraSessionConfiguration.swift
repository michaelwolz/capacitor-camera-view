import AVFoundation
import Capacitor

public struct CameraSessionConfiguration {
    let deviceId: String?
    let position: AVCaptureDevice.Position
    let preset: AVCaptureSession.Preset
    let useTripleCameraIfAvailable: Bool
    let zoomFactor: CFloat?
}

/// Maps a Capacitor plugin call to a CameraSessionConfiguration struct.
///
/// - Parameter call: The Capacitor plugin call.
public func sessionConfigFromPluginCall(_ call: CAPPluginCall) -> CameraSessionConfiguration {
    let deviceId = call.getString("deviceId")
    let position: AVCaptureDevice.Position = call.getString("cameraPosition") == "front" ? .front : .back
    let preset: AVCaptureSession.Preset = call.getString("preset").map { AVCaptureSession.Preset(rawValue: $0) } ?? .photo
    let useTripleCameraIfAvailable = call.getBool("useTripleCameraIfAvailable",  false)
    let zoomFactor = call.getDouble("zoomFactor").map { CFloat($0) }

    return CameraSessionConfiguration(
        deviceId: deviceId,
        position: position,
        preset: preset,
        useTripleCameraIfAvailable: useTripleCameraIfAvailable,
        zoomFactor: 3.0
    )
}
