import AVFoundation

/// Converts string camera type identifiers from JavaScript to native AVCaptureDevice.DeviceType values
/// - Parameter stringType: The string camera type from JavaScript
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

/// Converts AVCaptureDevice.DeviceType to JavaScript string value
/// - Parameter deviceType: The AVCaptureDevice.DeviceType
/// - Returns: The corresponding string or nil if no match
public func convertToStringCameraType(_ deviceType: AVCaptureDevice.DeviceType) -> String? {
    switch deviceType {
    case .builtInWideAngleCamera:
        return "wideAngle"
    case .builtInUltraWideCamera:
        return "ultraWide"
    case .builtInTelephotoCamera:
        return "telephoto"
    case .builtInDualCamera:
        return "dual"
    case .builtInDualWideCamera:
        return "dualWide"
    case .builtInTripleCamera:
        return "triple"
    case .builtInTrueDepthCamera:
        return "trueDepth"
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

/// Creates a temporary file URL for storing captured images
public func createTempImageFile() throws -> URL {
    let timestamp = Int(Date().timeIntervalSince1970 * 1000)
    let fileName = "camera_capture_\(timestamp).jpg"
    let tempDir = FileManager.default.temporaryDirectory
    return tempDir.appendingPathComponent(fileName)
}
