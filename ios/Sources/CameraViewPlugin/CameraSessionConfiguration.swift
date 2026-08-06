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

    /// Whether to prioritize photo quality over capture responsiveness.
    /// When `false` (default) the plugin opts into the iOS 17+ responsive-capture
    /// pipeline (zero-shutter-lag, responsive capture, fast capture prioritization)
    /// where supported. When `true` those optimizations are skipped so captures
    /// always prioritize quality.
    let prioritizeQuality: Bool

    /// Desired sensor aspect ratio ("4:3" or "16:9") for both the preview and
    /// photo capture. `nil` keeps the default `.photo` session preset (4:3).
    let aspectRatio: String?

    /// Optional upper bound, in pixels, for the longer edge of captured
    /// photos. `nil` keeps the photo output's default dimensions.
    let captureMaxDimension: Int?

    /// How the preview is scaled into its container. `"fit"` letterboxes the
    /// whole frame (`.resizeAspect`); any other value (including `nil`) keeps
    /// the default cover behavior (`.resizeAspectFill`).
    let previewScaleMode: String?
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
    let prioritizeQuality = call.getBool("prioritizeQuality", false)
    let aspectRatio = call.getString("aspectRatio")
    let captureMaxDimension = call.getInt("captureMaxDimension")
    let previewScaleMode = call.getString("previewScaleMode")

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
        zoomFactor: zoomFactor,
        prioritizeQuality: prioritizeQuality,
        aspectRatio: aspectRatio,
        captureMaxDimension: captureMaxDimension,
        previewScaleMode: previewScaleMode
    )
}
