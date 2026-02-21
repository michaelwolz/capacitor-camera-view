import AVFoundation
import Capacitor

/// Configuration for a camera capture session.
/// This struct is Sendable as all properties are value types.
public struct CameraSessionConfiguration: Sendable {
    /// Specific device ID to use. Takes precedence over position.
    let deviceId: String?
    
    /// Whether to enable barcode detection.
    let enableBarcodeDetection: Bool
    
    /// Optional array of specific barcode types to detect.
    /// If nil, all supported types are detected (for backwards compatibility).
    let barcodeTypes: [AVMetadataObject.ObjectType]?
    
    /// Camera position to use (front or back).
    let position: AVCaptureDevice.Position
    
    /// Preferred camera device types in order of preference.
    let preferredCameraDeviceTypes: [String]?
    
    /// Whether to upgrade to triple camera if available (Pro models).
    let useTripleCameraIfAvailable: Bool
    
    /// Initial zoom factor.
    let zoomFactor: CGFloat?
}

/// Maps a Capacitor plugin call to a CameraSessionConfiguration struct.
///
/// - Parameter call: The Capacitor plugin call.
public func sessionConfigFromPluginCall(_ call: CAPPluginCall) -> CameraSessionConfiguration {
    let deviceId = call.getString("deviceId")
    let enableBarcodeDetection = call.getBool("enableBarcodeDetection", false)
    let position: AVCaptureDevice.Position = call.getString("position") == "front" ? .front : .back
    let preferredCameraDeviceTypes = call.getArray("preferredCameraDeviceTypes") as? [String]
    let useTripleCameraIfAvailable = call.getBool("useTripleCameraIfAvailable", false)
    let zoomFactor = call.getDouble("zoomFactor").map { CGFloat($0) }
    
    // Parse barcode types if provided
    let barcodeTypes: [AVMetadataObject.ObjectType]?
    if let barcodeTypeStrings = call.getArray("barcodeTypes") as? [String] {
        let converted = convertToNativeBarcodeTypes(barcodeTypeStrings)
        barcodeTypes = converted.isEmpty ? nil : converted
    } else {
        barcodeTypes = nil
    }
    
    return CameraSessionConfiguration(
        deviceId: deviceId,
        enableBarcodeDetection: enableBarcodeDetection,
        barcodeTypes: barcodeTypes,
        position: position,
        preferredCameraDeviceTypes: preferredCameraDeviceTypes,
        useTripleCameraIfAvailable: useTripleCameraIfAvailable,
        zoomFactor: zoomFactor
    )
}
