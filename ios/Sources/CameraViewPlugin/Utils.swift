import AVFoundation

/// Converts string camera type identifiers from JavaScript to native AVCaptureDevice.DeviceType values
/// - Parameter stringType: The string camera type from JavaScript (matches CameraDeviceType enum values)
/// - Returns: The corresponding AVCaptureDevice.DeviceType or nil if no match
public func convertToNativeCameraType(_ stringType: String) -> AVCaptureDevice.DeviceType? {
    switch stringType {
    case "wideAngle":
        return .builtInWideAngleCamera
    case "ultraWide":
        return .builtInUltraWideCamera
    case "telephoto":
        return .builtInTelephotoCamera
    case "dual":
        return .builtInDualCamera
    case "dualWide":
        return .builtInDualWideCamera
    case "triple":
        return .builtInTripleCamera
    case "trueDepth":
        return .builtInTrueDepthCamera
    default:
        return nil
    }
}

/// Converts an array of string camera type identifiers to native AVCaptureDevice.DeviceType values
/// - Parameter stringTypes: Array of string camera types from JavaScript
/// - Returns: Array of AVCaptureDevice.DeviceType values (invalid types are filtered out)
public func convertToNativeCameraTypes(_ stringTypes: [String]) -> [AVCaptureDevice.DeviceType] {
    return stringTypes.compactMap { convertToNativeCameraType($0) }
}

